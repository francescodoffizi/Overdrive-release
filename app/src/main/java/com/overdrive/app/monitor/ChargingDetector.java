package com.overdrive.app.monitor;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;

/**
 * Fused charging-state detector.
 *
 * Purpose: replace the old polling-only inference path (which produced the
 * "very inconsistent" detection) with an event-driven layered model that
 * fuses three independent BYD HAL signals plus broadcast-receiver edges.
 *
 * Layers, evaluated in order:
 *
 *   L1. BMS state edge (chargingState == 1 CHARGING) — pushed by the
 *       BYDAutoChargingDevice typed listener (onBatteryManagementDeviceStateChanged).
 *       Authoritative when present. The known firmware bug: some PHEV builds
 *       leave this stuck at 15 IDLE while AC charging, so it's not sufficient
 *       on its own.
 *
 *   L2. BYDAutoPowerDevice.isCharging() — independent ground truth from the
 *       power MCU. Polled once per collect cycle. Used as the primary
 *       cross-check that catches the L1 firmware lie.
 *
 *   L3. Power-flow inference — only fires when L1 AND L2 disagree for
 *       {@link #INFERENCE_DISAGREEMENT_MIN_MS}. Requires the gear-in-park
 *       guard, a positive AC/DC gun assertion (NOT a !=disconnected guard,
 *       which lets UNAVAILABLE through), and {@link #HYSTERESIS_SAMPLES}
 *       consecutive observations. enginePowerKw is invalidated on ACC OFF,
 *       so a stale value from yesterday's drive cannot retrigger this layer.
 *
 *   Edge inputs: ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED
 *       transitions are pushed in directly. CONNECTED nudges fusion toward
 *       charging (sets a "plug recently inserted" flag, accelerating L3
 *       hysteresis); DISCONNECTED forces immediate transition to NOT_CHARGING
 *       and clears all sticky power values.
 *
 * Threading: all mutations via synchronized methods; reads return immutable
 * ChargingStateData snapshots.
 */
public final class ChargingDetector {

    private static final String TAG = "ChargingDetector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // ===== Tuning =====

    /**
     * How long L1 (BMS) and L2 (Power.isCharging) must disagree before we
     * fall through to L3 (power-flow inference). Both layers can take a few
     * seconds to settle after plug-in, so we tolerate a brief window.
     */
    private static final long INFERENCE_DISAGREEMENT_MIN_MS = 10_000L;

    /**
     * Samples required to flip the L3 inferred state. Each call to
     * {@link #updatePollEvidence} is one sample. With the 5-second
     * collect cadence, 3 samples = ~15 seconds of consistent evidence
     * before L3 declares charging — long enough to ride out a CAN-bus glitch.
     */
    private static final int HYSTERESIS_SAMPLES = 3;

    /**
     * After ACTION_POWER_CONNECTED, we know the user just plugged in. This
     * window biases fusion toward charging — useful because the BMS can
     * take 5-10s to start reporting state and we don't want to flap to
     * "not charging" between plug-in and the first BMS event.
     */
    private static final long PLUG_BIAS_WINDOW_MS = 30_000L;

    /**
     * After ACTION_POWER_DISCONNECTED, we know charging is over regardless
     * of what the BMS says. We snap to NOT_CHARGING and ignore stale BMS
     * for this long (handles the case where the BMS still says 1 CHARGING
     * for a few seconds after unplug).
     */
    private static final long UNPLUG_OVERRIDE_MS = 15_000L;

    /**
     * Power-evidence thresholds (kW). enginePowerKw negative = current flowing
     * into pack. -0.3 kW is the deadband below which sensor noise dominates.
     */
    private static final double ENGINE_POWER_DEADBAND = 0.3;
    private static final double EXTERNAL_POWER_THRESHOLD = 0.15;
    private static final double DEVICE_POWER_THRESHOLD = 0.15;

    /**
     * Maximum age for enginePowerKw to be trusted as live evidence. Beyond
     * this, the value is stale and {@link #invalidateAccDependentSignals}
     * will have already cleared it on ACC OFF anyway.
     */
    private static final long ENGINE_POWER_FRESHNESS_MS = 15_000L;

    /**
     * Output-edge debounce. The session's "charging" verdict is frequently
     * held by a single layer (e.g. L2 Power.isCharging() on a PHEV whose BMS
     * is stuck at 15 IDLE while AC charging). When that layer's reflective
     * call momentarily fails or returns a sentinel, its input goes null for
     * one cycle and {@link #recompute} would briefly resolve to NOT_CHARGING,
     * firing a spurious stopped+started pair — the "charging started keeps
     * re-triggering" symptom. We require an ON->OFF verdict to persist this
     * long before committing it. At the ~5s collect cadence this rides out a
     * 1-2 cycle dropout. A genuine physical unplug (edge-unplug) bypasses the
     * debounce and stops immediately.
     */
    private static final long OFF_EDGE_DEBOUNCE_MS = 12_000L;

    // ===== State =====

    private static final ChargingDetector INSTANCE = new ChargingDetector();
    public static ChargingDetector getInstance() { return INSTANCE; }

    /**
     * Listener for fused-state edges. Fires only on actual transitions
     * (true→false or false→true), not on every input. Use this when you
     * want session-level events rather than the raw BMS edge stream
     * (which misses PHEV-stuck-at-IDLE charging sessions entirely).
     */
    public interface FusedStateListener {
        void onFusedChargingChanged(boolean isCharging, String source);
    }

    private final java.util.concurrent.CopyOnWriteArrayList<FusedStateListener> fusedListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addFusedStateListener(FusedStateListener l) {
        if (l != null) fusedListeners.addIfAbsent(l);
    }
    public void removeFusedStateListener(FusedStateListener l) {
        if (l != null) fusedListeners.remove(l);
    }

    private final Object lock = new Object();

    // L1
    private int bmsState = BydVehicleData.UNAVAILABLE;
    private long bmsStateAtMs = 0L;

    // L2
    /** Tri-state: TRUE/FALSE/null (unavailable). */
    private Boolean powerIsChargingTri = null;
    private long powerIsChargingAtMs = 0L;

    // L3 inputs (snapshot pushed in by the collector each cycle)
    private double enginePowerKw = Double.NaN;
    private long enginePowerAtMs = 0L;
    private double externalChargingPowerKw = Double.NaN;
    private double chargingPowerKw = Double.NaN;
    private int chargingGunState = BydVehicleData.UNAVAILABLE;
    private boolean inPark = false;

    // L3 hysteresis counter (positive = consecutive "charging" samples,
    // negative = consecutive "not charging" samples).
    private int inferenceHysteresis = 0;
    private boolean l3Latched = false;

    // ACC awareness
    private boolean accIsOn = true;

    // Edge events
    private long lastPlugConnectedMs = 0L;
    private long lastPlugDisconnectedMs = 0L;

    // Fused output (what callers see)
    private boolean fusedCharging = false;
    private long fusedAtMs = 0L;
    /** Which layer last decided the fused state. For diagnostic logging only. */
    private String fusedSource = "init";

    /**
     * When a recompute first resolves ON->OFF for a non-unplug reason, we
     * record the timestamp here instead of committing the flip immediately.
     * The OFF only commits once it has persisted {@link #OFF_EDGE_DEBOUNCE_MS}
     * (see recompute). 0 = no pending OFF. Reset the moment any layer resolves
     * back to charging, so a transient dropout never fires a stopped edge.
     */
    private long pendingOffSinceMs = 0L;

    private ChargingDetector() {}

    // ===== Inputs =====

    /**
     * BMS state edge. Called by the typed charging listener on
     * onBatteryManagementDeviceStateChanged AND by the collector after
     * polling getBatteryManagementDeviceState() / chargingState feature ID.
     */
    public void updateBmsState(int newState) {
        FusedTransition transition;
        synchronized (lock) {
            if (newState == bmsState) return;
            bmsState = newState;
            bmsStateAtMs = System.currentTimeMillis();
            transition = recompute("bms-edge");
        }
        dispatchFusedTransition(transition);
    }

    /**
     * BYDAutoPowerDevice.isCharging() result. May be null if the call
     * failed or returned a sentinel — null means "unavailable, do not use".
     */
    public void updatePowerIsCharging(Boolean tri) {
        FusedTransition transition;
        synchronized (lock) {
            powerIsChargingTri = tri;
            powerIsChargingAtMs = System.currentTimeMillis();
            transition = recompute("power-isCharging");
        }
        dispatchFusedTransition(transition);
    }

    /**
     * Push the latest poll snapshot into the detector. Called once per
     * collect cycle by the collector. Used for L3 inference + log
     * diagnostics.
     *
     * @param vd may be null — treated as "no fresh evidence this cycle"
     */
    public void updatePollEvidence(BydVehicleData vd, int gearMode, int gearP) {
        if (vd == null) return;
        FusedTransition transition;
        synchronized (lock) {
            // enginePower freshness — only trust the value if it was
            // populated by an ACC-on collect. invalidateAccDependentSignals()
            // resets enginePowerKw to NaN on ACC OFF, so a stale value
            // from yesterday's drive cannot retrigger inference.
            if (!Double.isNaN(vd.enginePowerKw)) {
                enginePowerKw = vd.enginePowerKw;
                // Use the age CARRIED WITH the value, not our own clock. Stamping now() made a
                // carried-forward reading of arbitrary age look 0 ms old — and because
                // updatePollEvidence stamps and then recompute()s microseconds later,
                // ENGINE_POWER_FRESHNESS_MS was unconditionally satisfied from the poll path, so
                // the freshness window never expired on the staleness it exists to catch. The
                // snapshot stamps this at the moment of a LIVE read (BydVehicleData.Builder
                // .enginePowerKw), so the window now measures real data age. Fall back to now()
                // only if the snapshot predates the field (0), preserving old behaviour there.
                enginePowerAtMs = (vd.enginePowerAtMs > 0)
                        ? vd.enginePowerAtMs : System.currentTimeMillis();
            }
            externalChargingPowerKw = vd.externalChargingPowerKw;
            chargingPowerKw = vd.chargingPowerKw;
            chargingGunState = vd.chargingGunState;
            inPark = (gearMode == gearP);

            // BMS state seen via poll path (the typed listener edge handler
            // already calls updateBmsState; this catches the case where the
            // listener never fires and the value comes from the polled
            // getBatteryManagementDeviceState() call instead).
            if (vd.chargingState != BydVehicleData.UNAVAILABLE
                    && vd.chargingState != bmsState) {
                bmsState = vd.chargingState;
                bmsStateAtMs = System.currentTimeMillis();
            }

            transition = recompute("poll");
        }
        dispatchFusedTransition(transition);
    }

    /** Called when ACC transitions on/off. */
    public void updateAccState(boolean isOn) {
        FusedTransition transition;
        synchronized (lock) {
            this.accIsOn = isOn;
            if (!isOn) {
                // ACC just went OFF. enginePowerKw stops being refreshed,
                // so any value already in this object is the last live
                // reading from while ACC was on. We invalidate to prevent
                // a stale negative reading from yesterday's regen from
                // looking like "current flowing into pack" while parked.
                invalidateAccDependentSignals();
            }
            transition = recompute("acc-" + (isOn ? "on" : "off"));
        }
        dispatchFusedTransition(transition);
    }

    /**
     * Invalidate signals that go stale when ACC is off. Called on ACC OFF
     * AND on ACTION_POWER_DISCONNECTED.
     */
    private void invalidateAccDependentSignals() {
        enginePowerKw = Double.NaN;
        enginePowerAtMs = 0L;
    }

    /** ACTION_POWER_CONNECTED received. */
    public void onPowerConnected() {
        FusedTransition transition;
        synchronized (lock) {
            lastPlugConnectedMs = System.currentTimeMillis();
            // Any prior "unplugged" override is now stale.
            lastPlugDisconnectedMs = 0L;
            logger.info("Plug edge: CONNECTED");
            transition = recompute("plug-connected");
        }
        dispatchFusedTransition(transition);
    }

    /** ACTION_POWER_DISCONNECTED received. */
    public void onPowerDisconnected() {
        FusedTransition transition;
        synchronized (lock) {
            lastPlugDisconnectedMs = System.currentTimeMillis();
            lastPlugConnectedMs = 0L;
            // Wipe sticky power values so a 1-cycle straggler from the
            // BMS doesn't keep us in "charging" after unplug.
            chargingPowerKw = Double.NaN;
            externalChargingPowerKw = Double.NaN;
            invalidateAccDependentSignals();
            inferenceHysteresis = 0;
            l3Latched = false;
            logger.info("Plug edge: DISCONNECTED — clearing power evidence");
            transition = recompute("plug-disconnected");
        }
        dispatchFusedTransition(transition);
    }

    // ===== Outputs =====

    /** True if the fused detector currently believes the vehicle is charging. */
    public boolean isCharging() {
        synchronized (lock) { return fusedCharging; }
    }

    /** Diagnostic: which layer/event last decided the fused state. */
    public String lastSource() {
        synchronized (lock) { return fusedSource; }
    }

    // ===== Fusion =====

    /**
     * Carrier for a flip the synchronized recompute saw, so the public
     * caller can dispatch listeners AFTER releasing the lock. Avoids the
     * usual deadlock hazard where a listener calls back into the detector.
     */
    private static final class FusedTransition {
        final boolean fired;
        final boolean isCharging;
        final String source;
        FusedTransition(boolean fired, boolean isCharging, String source) {
            this.fired = fired; this.isCharging = isCharging; this.source = source;
        }
        static final FusedTransition NONE = new FusedTransition(false, false, "");
    }

    private void dispatchFusedTransition(FusedTransition t) {
        if (t == null || !t.fired) return;
        for (FusedStateListener l : fusedListeners) {
            try { l.onFusedChargingChanged(t.isCharging, t.source); }
            catch (Exception e) { logger.debug("FusedStateListener error: " + e.getMessage()); }
        }
    }

    private FusedTransition recompute(String trigger) {
        long now = System.currentTimeMillis();
        boolean prev = fusedCharging;
        boolean next;
        String source;

        // Edge override: recent unplug wins for UNPLUG_OVERRIDE_MS.
        if (lastPlugDisconnectedMs > 0
                && now - lastPlugDisconnectedMs < UNPLUG_OVERRIDE_MS) {
            next = false;
            source = "edge-unplug";
        } else {
            // L1: BMS direct.
            boolean l1Says = (bmsState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
            // BMS gives explicit non-charging terminal states we trust.
            boolean l1Negative =
                bmsState == ChargingStateData.CHARGING_BATTERY_STATE_READY
                || bmsState == ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH
                || bmsState == ChargingStateData.CHARGING_BATTERY_STATE_CHARG_TERMINATE
                || bmsState == ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG_FINISH;
            // BMS ambiguous: UNAVAILABLE, IDLE (15 — buggy on PHEVs), or
            // any other code we don't explicitly recognize as terminal.
            boolean l1Ambiguous = !l1Says && !l1Negative;

            // L2: Power MCU isCharging() — null means unavailable, ignore.
            Boolean l2 = powerIsChargingTri;

            if (l1Says && (l2 == null || l2)) {
                next = true; source = "l1-bms";
            } else if (l1Says && Boolean.FALSE.equals(l2)) {
                // L1 says yes but L2 says no: trust L2 only after the
                // disagreement window. Inside the window, BMS wins (BMS
                // sees cell-level current, more authoritative early on).
                if (now - bmsStateAtMs > INFERENCE_DISAGREEMENT_MIN_MS) {
                    next = false; source = "l2-overrides-l1";
                } else {
                    next = true; source = "l1-bms";
                }
            } else if (l1Negative) {
                // BMS reports an explicit terminal state (READY/FINISHED/
                // TERMINATED/DISCHARG_FINISH). Trust it — even if Power MCU
                // (L2) momentarily disagrees, an L2-overrides path here
                // would produce inconsistent state codes (caller's
                // effectiveState=CHARGING vs raw vd.chargingState=12). The
                // PHEV firmware bug we route around is BMS *stuck at 15 IDLE*
                // while charging, NOT BMS reporting an explicit terminal
                // state by mistake.
                next = false; source = "l1-bms-negative";
                inferenceHysteresis = Math.min(inferenceHysteresis, 0);
                l3Latched = false;
            } else if (Boolean.TRUE.equals(l2)) {
                next = true; source = "l2-power";
            } else if (Boolean.FALSE.equals(l2)) {
                next = false; source = "l2-power-negative";
            } else {
                // L1 ambiguous + L2 unavailable: fall through to L3 inference.
                next = computeL3Inference(now);
                source = next ? "l3-inferred" : "l3-not-inferred";
            }

            // Plug-bias: within PLUG_BIAS_WINDOW_MS of CONNECTED, if any
            // power evidence is positive, force-charging. Handles the
            // ramp-up window where BMS is still initializing.
            if (!next && lastPlugConnectedMs > 0
                    && now - lastPlugConnectedMs < PLUG_BIAS_WINDOW_MS
                    && hasAnyPowerEvidence(now)) {
                next = true;
                source = "plug-bias-power";
            }
        }

        // Update L3 hysteresis counter regardless of which layer fired —
        // this keeps it primed in case L1/L2 go ambiguous.
        updateL3Hysteresis(now);

        // Output-edge debounce on ON->OFF. Without this, a single cycle where
        // the holding layer's input briefly drops (L2 reflection returns null,
        // BMS poll skips, etc.) collapses the verdict to OFF and immediately
        // back to ON next cycle, firing a spurious stopped+started pair. We
        // hold a tentative OFF for OFF_EDGE_DEBOUNCE_MS and only commit it if
        // it persists. A genuine unplug (edge-unplug) is authoritative and
        // bypasses the debounce entirely.
        boolean unplugDriven = "edge-unplug".equals(source);
        if (next || unplugDriven) {
            // Resolved back to charging (or a real unplug): no pending OFF.
            pendingOffSinceMs = 0L;
        } else if (prev) {
            // prev ON, this recompute resolved OFF for a non-unplug reason.
            // Start (or continue) the debounce window; keep reporting ON until
            // the OFF has persisted long enough to be trusted.
            if (pendingOffSinceMs == 0L) {
                pendingOffSinceMs = now;
            }
            if (now - pendingOffSinceMs < OFF_EDGE_DEBOUNCE_MS) {
                next = true;          // suppress the flip for now
                source = fusedSource; // keep prior source; don't churn logs
            } else {
                // Window elapsed — commit the OFF (next stays false) and clear
                // the marker so a future session's debounce starts fresh.
                pendingOffSinceMs = 0L;
            }
        }

        fusedCharging = next;
        fusedAtMs = now;
        fusedSource = source;

        if (next != prev) {
            logger.info("Charging fused " + (prev ? "ON" : "OFF") + "->"
                + (next ? "ON" : "OFF") + " trigger=" + trigger
                + " source=" + source + " bms=" + bmsState
                + " power=" + powerIsChargingTri
                + " gun=" + chargingGunState
                + " engineKw=" + fmt(enginePowerKw)
                + " extKw=" + fmt(externalChargingPowerKw)
                + " chgKw=" + fmt(chargingPowerKw));
            return new FusedTransition(true, next, source);
        }
        return FusedTransition.NONE;
    }

    private boolean computeL3Inference(long now) {
        if (!inPark) {
            inferenceHysteresis = Math.min(inferenceHysteresis, 0);
            l3Latched = false;
            return false;
        }
        // Positive gun assertion. AC=2, DC=3, AC_DC=4. VTOL=5 is V2L
        // (vehicle-to-load) — pack is DISCHARGING through the gun, the
        // exact opposite of charging. We must NOT count gun=5 as evidence,
        // and we similarly reject UNAVAILABLE (the PHEV hole the old
        // "!= 1 disconnected" guard fell through).
        boolean gunPlausible =
            chargingGunState == 2 || chargingGunState == 3
            || chargingGunState == 4;
        if (!gunPlausible) {
            inferenceHysteresis = Math.min(inferenceHysteresis, 0);
            l3Latched = false;
            return false;
        }
        return l3Latched;
    }

    private void updateL3Hysteresis(long now) {
        boolean evidence = false;
        // Engine flow into battery: only count if the value is fresh.
        boolean engineFresh = enginePowerAtMs > 0
            && (now - enginePowerAtMs) < ENGINE_POWER_FRESHNESS_MS;
        if (engineFresh && !Double.isNaN(enginePowerKw)
                && enginePowerKw < -ENGINE_POWER_DEADBAND) {
            evidence = true;
        }
        // External charger power is reported as positive kW being delivered
        // by the charger to the car — always charging-direction by definition.
        if (!Double.isNaN(externalChargingPowerKw)
                && externalChargingPowerKw > EXTERNAL_POWER_THRESHOLD) {
            evidence = true;
        }
        // chargingPowerKw is signed: positive = into pack (charging),
        // negative = out of pack (V2L / V2G discharge). Only positive
        // values count as charging evidence — abs() previously let V2L
        // sessions latch the detector at CHARGING.
        if (!Double.isNaN(chargingPowerKw)
                && chargingPowerKw > DEVICE_POWER_THRESHOLD) {
            evidence = true;
        }

        if (evidence) {
            inferenceHysteresis = Math.max(0, inferenceHysteresis) + 1;
            if (inferenceHysteresis >= HYSTERESIS_SAMPLES) {
                l3Latched = true;
            }
        } else {
            inferenceHysteresis = Math.min(0, inferenceHysteresis) - 1;
            if (-inferenceHysteresis >= HYSTERESIS_SAMPLES) {
                l3Latched = false;
                inferenceHysteresis = -HYSTERESIS_SAMPLES; // clamp
            }
        }
    }

    private boolean hasAnyPowerEvidence(long now) {
        boolean engineFresh = enginePowerAtMs > 0
            && (now - enginePowerAtMs) < ENGINE_POWER_FRESHNESS_MS;
        if (engineFresh && !Double.isNaN(enginePowerKw)
                && enginePowerKw < -ENGINE_POWER_DEADBAND) return true;
        if (!Double.isNaN(externalChargingPowerKw)
                && externalChargingPowerKw > EXTERNAL_POWER_THRESHOLD) return true;
        // Signed: positive only. Negative = V2L draw, not charging.
        if (!Double.isNaN(chargingPowerKw)
                && chargingPowerKw > DEVICE_POWER_THRESHOLD) return true;
        return false;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(java.util.Locale.US, "%.2f", v);
    }
}
