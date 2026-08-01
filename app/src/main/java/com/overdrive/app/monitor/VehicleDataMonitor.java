package com.overdrive.app.monitor;

import android.content.Context;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton coordinator for BYD vehicle data.
 * 
 * Phase 3: Thin wrapper around BydDataCollector.
 * All data reads delegate to the collector. Keeps the same API surface
 * so existing consumers (HttpServer, SurveillanceIpcServer, TripDetector, etc.)
 * don't need changes.
 * 
 * The BatteryPowerMonitor is kept for AccSentryDaemon's voltage-based MCU control
 * (it needs listener callbacks for real-time voltage changes).
 */
public class VehicleDataMonitor {
    
    private static final String TAG = "VehicleDataMonitor";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private static VehicleDataMonitor instance;
    private static final Object lock = new Object();
    
    // Only BatteryPowerMonitor kept — AccSentryDaemon needs its listener for voltage-based MCU control
    private final BatteryPowerMonitor batteryPowerMonitor;
    
    private final CopyOnWriteArrayList<VehicleDataListener> listeners = new CopyOnWriteArrayList<>();
    private boolean isRunning = false;
    private Context context;

    // Throttle for the charging-power resolution diagnostic (see getChargingState).
    private volatile long lastChargePowerLogMs = 0L;

    /**
     * Last observed {@code clusterChargePowerKw} and when it last CHANGED, used only by the
     * PHEV CV-taper admission in {@link #getChargingState()}. The cluster feature id keeps
     * answering its last in-band value after the gun comes out and nothing else ages that field,
     * so "in band" alone would keep a finished session alive forever; requiring recent MOVEMENT
     * turns a frozen reading back into the sentinel it is. {@code volatile} — getChargingState()
     * is called from HTTP, MQTT, ABRP and daemon threads.
     */
    /** Guards the taper freshness pair below — read-then-write must be atomic across the many
     *  threads that call getChargingState(). */
    private final Object taperLock = new Object();
    private volatile double lastTaperClusterKw = Double.NaN;
    private volatile long lastTaperClusterChangeMs = 0L;
    /**
     * How long a CV-taper reading may go unchanged before it stops counting as live. A real taper
     * keeps moving as it decays; a value pinned for this long is the sticky-getter artifact.
     * Generous enough to tolerate the 90 s parked poll cadence plus quantisation plateaus.
     */
    private static final long TAPER_CLUSTER_TTL_MS = 10 * 60_000L;

    /** Compact formatter for a possibly-NaN candidate value in the diag line. */
    private static String fmt(double v) {
        return Double.isNaN(v) ? "NaN" : String.format("%.2f", v);
    }
    
    private VehicleDataMonitor() {
        this.batteryPowerMonitor = new BatteryPowerMonitor();
    }
    
    public static VehicleDataMonitor getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) instance = new VehicleDataMonitor();
            }
        }
        return instance;
    }

    // ==================== LIFECYCLE ====================
    
    public void init(Context context) {
        this.context = context;
        logger.info("Initializing VehicleDataMonitor (BydDataCollector mode)");
        
        // Only init battery power monitor (for AccSentryDaemon voltage listener)
        try {
            batteryPowerMonitor.init(context);
        } catch (Exception e) {
            logger.error("Failed to init BatteryPowerMonitor", e);
        }
        
        logger.info("Initialization complete (data from BydDataCollector)");
    }
    
    public void initBatteryPowerOnly(Context context) {
        this.context = context;
        try {
            batteryPowerMonitor.init(context);
        } catch (Exception e) {
            logger.error("Failed to init BatteryPowerMonitor", e);
        }
    }
    
    public synchronized void start() {
        if (isRunning) return;
        try { batteryPowerMonitor.start(); } catch (Exception e) { logger.error("BatteryPowerMonitor start failed", e); }
        isRunning = true;
        logger.info("VehicleDataMonitor started");
    }
    
    public synchronized void startBatteryPowerOnly() {
        if (isRunning) return;
        try { batteryPowerMonitor.start(); } catch (Exception e) { logger.error("BatteryPowerMonitor start failed", e); }
        isRunning = true;
    }
    
    public synchronized void stop() {
        if (!isRunning) return;
        try { batteryPowerMonitor.stop(); } catch (Exception ignored) {}
        isRunning = false;
        logger.info("VehicleDataMonitor stopped");
    }
    
    public synchronized void stopBatteryPowerOnly() {
        if (!isRunning) return;
        try { batteryPowerMonitor.stop(); } catch (Exception ignored) {}
        isRunning = false;
    }
    
    public boolean isRunning() { return isRunning; }
    
    // ==================== DATA ACCESS (delegates to BydDataCollector) ====================
    
    public BydVehicleData getVd() {
        try {
            BydDataCollector c = BydDataCollector.getInstance();
            return c.isInitialized() ? c.getData() : null;
        } catch (Exception e) { return null; }
    }
    
    public BatteryVoltageData getBatteryVoltage() {
        BydVehicleData vd = getVd();
        if (vd != null && vd.voltageLevelRaw != BydVehicleData.UNAVAILABLE) {
            return new BatteryVoltageData(vd.voltageLevelRaw);
        }
        return null;
    }
    
    public BatteryPowerData getBatteryPower() {
        // Try collector first, fallback to monitor (for AccSentryDaemon compatibility)
        BydVehicleData vd = getVd();
        if (vd != null && !Double.isNaN(vd.voltage12v)) {
            return new BatteryPowerData(vd.voltage12v);
        }
        return batteryPowerMonitor.getCurrentValue();
    }
    
    public BatterySocData getBatterySoc() {
        BydVehicleData vd = getVd();
        if (vd != null && !Double.isNaN(vd.socPercent)) {
            return new BatterySocData(vd.socPercent);
        }
        return null;
    }

    /**
     * Drivetrain probe — true on PHEV/HEV vehicles where {@code fuelPercent}
     * and {@code fuelRangeKm} carry real readings. Trips code uses this to
     * decide whether to populate fuel-cost fields and whether the per-trip UI
     * should render the petrol-leg breakdown.
     */
    public boolean isPhev() {
        try {
            BydDataCollector c = BydDataCollector.getInstance();
            return c != null && c.isInitialized() && c.isPhevPublic();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Charging state derivation — fused detector.
     *
     * The "is charging?" decision is owned by {@link ChargingDetector},
     * which fuses three independent signals and two edge inputs:
     *
     *   L1. BMS state edge (chargingState == 1) via the typed
     *       AbsBYDAutoChargingListener registered in BydDataCollector.
     *   L2. BYDAutoPowerDevice.isCharging() polled once per cycle as a
     *       cross-check that catches the PHEV "BMS stuck at 15 IDLE while
     *       charging" firmware bug.
     *   L3. Power-flow inference with hysteresis and a positive AC/DC gun
     *       assertion (gun==2/3/4/5; UNAVAILABLE no longer slips through
     *       like the old "!= 1 disconnected" guard).
     *   E1. ACTION_POWER_CONNECTED — biases fusion toward charging during
     *       the ramp-up window before BMS reports.
     *   E2. ACTION_POWER_DISCONNECTED — overrides the fused state to
     *       NOT_CHARGING for {@code UNPLUG_OVERRIDE_MS} so a stale BMS
     *       value can't keep us in "charging" after the cable comes out.
     *
     * This method is now a thin presentation wrapper: ask the detector
     * for the verdict, choose an effective state code (CHARGING when the
     * detector says yes, else the BMS state if known, else null), and
     * resolve power magnitude.
     *
     * Power magnitude resolution (when fused says CHARGING) SPLITS BY DRIVETRAIN,
     * because the hardware getters that are authoritative on BEV are actively
     * wrong on PHEV — getExternalChargingPower() reports the EVSE's RATED
     * capacity there (a flat 7.13 kW on a real ~1.7 kW charge) and
     * getChargePower() has been seen returning the AC-side figure:
     *
     *   PHEV:
     *     1. clusterChargePowerKw — the cluster's own dash readout (feature id
     *        0x32300018). Measured, available on the first poll, and PHEV-only
     *        because its raw scale is inferred (see the branch comment below).
     *     2. ring-buffer estimator — SOC-derived, needs ≥2 SOC ticks to warm up.
     *     3. abs(engine power) when current is flowing into the pack.
     *     4. nominal-capacity hint (3.3/7 kW, marked estimated).
     *   PHEV deliberately NEVER falls through to externalChargingPower /
     *   chargingDevice.chargingPower — a confidently-wrong measured-looking
     *   value is worse than an honest estimate.
     *
     *   BEV:
     *     1. chargePower (InstrumentDevice.getChargePower — real DC rate INTO the
     *        pack; matches the BYD app/cloud). Preferred over externalChargingPower
     *        because that one is the AC wall-side input (higher: includes
     *        onboard-charger conversion loss) or a 104857.5 sentinel.
     *     2. external charging power (InstrumentDevice — charger-reported, AC-side)
     *     3. chargingDevice.chargingPower
     *     4. abs(engine power) — only when ACC is on (NaN-guarded after
     *        ACC OFF invalidation in BydDataCollector.setAccState)
     *     5. ring-buffer counter-derivative estimator (measured, not nominal)
     *     6. nominal-capacity hint (7 kW, marked estimated)
     *
     * @return ChargingStateData populated from the fused detector, or
     *         null when no state signal is available at all.
     */
    public ChargingStateData getChargingState() {
        BydVehicleData vd = getVd();
        if (vd == null) return null;

        boolean fusedCharging = ChargingDetector.getInstance().isCharging();

        // TAPER OVERRIDE — BMS says FINISHED but the cluster still reports a real rate through a
        // connected gun. On BYD PHEV firmware "FINISHED" means bulk-charge complete, NOT cable
        // removed: the CV taper continues and the dash keeps showing kW. Field capture
        // (log_X5RRX996): at 07:01:28 the BMS flipped to 2 at 100% SOC with gunState=2 and the
        // pack still drawing, and the detector fused ON->OFF on `l1-bms-negative`.
        //
        // Without this, relaxing the collector-side suppression of clusterChargePowerKw was
        // INERT: the whole power block below is gated on effectiveState==CHARGING, which only
        // fusedCharging can produce, so a stored cluster value could never be read in exactly the
        // scenario the relaxation targeted. Requires a live cluster reading (the collector already
        // refuses to store one on a terminal state or a disconnected/V2L gun), so this cannot
        // resurrect a finished session on its own — when the taper truly ends the cluster read
        // goes out of band, the collector stores NaN, and this override stops applying.
        boolean gunCharging = vd.chargingGunState == 2
                || vd.chargingGunState == 3 || vd.chargingGunState == 4;
        // PHEV-ONLY. clusterChargePowerKw's unit scale is a magnitude GUESS
        // (scaleClusterChargePowerKw divides anything >22 by 100), and that guess is only safe
        // where the ambiguous band is physically unreachable — i.e. below a PHEV onboard
        // charger's ~7 kW ceiling. Consuming it on BEV would let a genuine 150 kW DC session
        // read as 1.5 kW, which is squarely in-band and would satisfy this test (invariant I1 +
        // asymmetry 1). Computed here rather than reusing the cascade's own `phev` local, which
        // is not resolved until inside the power block below.
        boolean phevForTaper;
        try { phevForTaper = isPhev(); } catch (Throwable t) { phevForTaper = false; }
        // MOVEMENT BOUND. In-band is NOT enough: the cluster feature id is documented to keep
        // answering the last in-band rate after the gun comes out, and nothing else ages this
        // field — so an in-band-only test held "charging" for as long as the cable stayed in,
        // which never closed the session row and fed a permanent phantom to ABRP/MQTT. Requiring
        // the value to have CHANGED recently makes a frozen reading a sentinel by I4, per
        // asymmetry 6 ("freshness means the DATA moved").
        // Tracking is confined to PHEV and serialised. Previously it ran on every call before the
        // drivetrain gate, so getChargingState() — a READ path reached from HTTP, MQTT, ABRP, the SoC
        // recorder and the CPS sampler — mutated shared state on BEV too, and the read-then-write was
        // not atomic across those threads (two callers could each see clusterLive true off the
        // other's write). The values are only ever consumed inside the phev-gated expression below.
        boolean clusterLive = false;
        if (phevForTaper) {
            synchronized (taperLock) {
                clusterLive = vd.clusterChargePowerKw != lastTaperClusterKw
                        || (System.currentTimeMillis() - lastTaperClusterChangeMs) < TAPER_CLUSTER_TTL_MS;
                if (vd.clusterChargePowerKw != lastTaperClusterKw) {
                    lastTaperClusterKw = vd.clusterChargePowerKw;
                    lastTaperClusterChangeMs = System.currentTimeMillis();
                }
            }
        }
        boolean taperCharging = !fusedCharging
                && phevForTaper
                && vd.chargingState == ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH
                && gunCharging
                && !Double.isNaN(vd.clusterChargePowerKw)
                && vd.clusterChargePowerKw > 0.1 && vd.clusterChargePowerKw <= 300
                && clusterLive;

        int effectiveState;
        if (fusedCharging) {
            effectiveState = ChargingStateData.CHARGING_BATTERY_STATE_CHARGING;
        } else if (vd.chargingState != BydVehicleData.UNAVAILABLE) {
            // Pass through whatever the BMS reports (READY, FINISHED, IDLE, error...)
            effectiveState = vd.chargingState;
        } else {
            // No BMS state and detector is OFF — caller has nothing to show.
            return null;
        }

        ChargingStateData data = new ChargingStateData(effectiveState);
        data.isTaperCharging = taperCharging;

        // ---- Power magnitude ----
        // Entered when the fused detector says CHARGING, OR during a PHEV CV taper that the BMS
        // has already called FINISHED (see taperCharging). The taper case keeps the FINISHED state
        // code — only the POWER block opens — so `full`/`plugged`/session-close all keep seeing
        // the truth (asymmetry 8: a C1 relaxation needs a matching C3 admission, but it must not
        // be bought by lying about the state).
        if (effectiveState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING || taperCharging) {
            String powerSource;  // which cascade branch won — surfaced in the diag log below
            double estKw = ChargingPowerEstimator.getInstance().estimatePowerKw();
            // On PHEV, externalChargingPower is NOT trustworthy for charging power:
            // it reports the EVSE's RATED capacity (observed a flat 7.13 kW on a real
            // ~1.7 kW charge), not the actual draw. So on PHEV the SOC-derived ring
            // estimator (which tracks the true rate via the SOC gauge) OUTRANKS the
            // external/device getters. On BEV the external getter is genuine, so it
            // keeps priority and the estimator stays the last-resort fallback.
            boolean phev;
            try { phev = isPhev(); } catch (Throwable t) { phev = false; }
            boolean estUsable = !Double.isNaN(estKw);

            // TOP PRIORITY ON PHEV: the INSTRUMENT CLUSTER's own charge readout (feature id
            // 0x32300018). This is the number on the dash, it is MEASURED, and unlike the typed
            // getters it is correct on PHEV — where getExternalChargingPower() returns the
            // EVSE's rated capacity and getChargePower() has been seen returning the AC-side
            // figure. It outranks the SOC-derived estimator because the estimator needs ≥2 SOC
            // ticks (≈15 min on a slow charge) to say anything at all, and even then it is a
            // derivative of a 1%-granular gauge; this is the charger's own number, available on
            // the first poll.
            //
            // NOTE: this read used to be gated behind "only if the typed getters found nothing"
            // in BydDataCollector, which on PHEV never opened (the wrong-but-present external
            // value satisfied the gate) — and it passed a wrapper Class to a HAL that matches
            // primitives only. Both are fixed, so this branch can finally win.
            //
            // DELIBERATELY PHEV-ONLY, and this gate is load-bearing. The raw feature value is
            // reported in two different units across firmware families with no unit flag, so
            // scaleClusterChargePowerKw() has to GUESS from the magnitude, and it resolves the
            // ambiguous 22..500 band as hectowatts (which is what our PHEV field captures
            // show). That guess is safe on PHEV — an onboard charger physically cannot exceed
            // ~7 kW, so the band is unreachable — but on a BEV a genuine 60-250 kW DC fast
            // charge lands squarely in it and would be divided by 100, displaying 0.6-2.5 kW
            // for a 60-250 kW session and poisoning the session peak/avg. BEV already has a
            // trustworthy source in chargePowerKw (getChargePower, the battery-side figure the
            // OEM app and cloud show), so there is nothing to gain there and a 100x error to
            // lose. Restricting the branch to PHEV — the drivetrain that actually had the bug —
            // keeps BEV behaviour bit-identical to before this change.
            if (phev && !Double.isNaN(vd.clusterChargePowerKw)
                    && vd.clusterChargePowerKw > 0.1 && vd.clusterChargePowerKw <= 300) {
                data.updateChargingPower(vd.clusterChargePowerKw);
                powerSource = "clusterChargePowerKw(DD)";
            } else if (phev && estUsable) {
                // PHEV, cluster readout unavailable on this trim: the SOC-derived ring
                // estimator is the next best thing, and it OUTRANKS the remaining hardware
                // getters. On PHEV those are unreliable: getChargePower() has been seen
                // returning the AC/EVSE-side ~7 kW (not the true DC pack rate) on some
                // trims, and getExternalChargingPower() reports the EVSE's rated
                // capacity. Trusting either produced the "shows 7 kW on a 1.7 kW
                // charge" bug. Only once the estimator has a value do we prefer it;
                // before warm-up we fall to the phev placeholder branch below.
                data.updateChargingPower(estKw);
                powerSource = "estimator(ring,phev)";
            } else if (!phev && !Double.isNaN(vd.chargePowerKw) && vd.chargePowerKw > 0.1 && vd.chargePowerKw <= 300) {
                // Real DC charge rate into the pack (InstrumentDevice.getChargePower).
                // BEV ONLY: it is the battery-side power the BYD app/cloud shows. This
                // is NOT trusted on PHEV — getChargePower() has been seen returning the
                // AC/EVSE-side ~7 kW there (not the true DC pack rate), so PHEV is
                // handled entirely by the estimator/placeholder branches (above and
                // below), never by a raw hardware getter.
                data.updateChargingPower(vd.chargePowerKw);
                powerSource = "chargePowerKw(DC)";
            } else if (phev) {
                // PHEV but the SOC estimator hasn't warmed up yet (needs ≥2 SOC ticks
                // ≈ 15 min on a slow charge). Do NOT fall through to
                // externalChargingPower/chargingPower here: on PHEV externalChargingPower
                // is the EVSE's RATED capacity (a flat, confidently-WRONG 7.13 kW), not
                // the real draw — displaying it would poison the session peak/avg and
                // mislead. Skip to the engine-power / nominal-placeholder branches so
                // the early window shows an honest estimated hint (isEstimated=true)
                // instead of a wrong measured-looking value.
                if (!Double.isNaN(vd.enginePowerKw) && vd.enginePowerKw < -0.3) {
                    data.updateChargingPower(Math.abs(vd.enginePowerKw));
                    // FLAGGED ESTIMATED. abs(engine power) is an INFERENCE — it is the
                    // motor/generator's own figure, not a charger-side measurement, and the
                    // reference OEM app deliberately refuses this substitution for charging power
                    // (its charging band is unsigned, precisely so ICE/regen watts cannot surface
                    // as a charge rate). Publishing it unflagged let it into the CPS ramp curve and
                    // hence session energy/cost (invariant I3) — the same defect class as the
                    // frozen estimator. Newly reachable mid-session now that the estimator can
                    // expire, so this is no longer a warm-up-only path.
                    //
                    // COST: isEstimated is overloaded as both "don't persist" and "don't display"
                    // — core.js:1015 and local/index.html:887 both hide the NUMBER when it is set,
                    // so the card shows "Charging"/"--" instead of a value here. That is the right
                    // trade (an unflagged inference in the cost curve is money-wrong; a blank is
                    // only unhelpful), but it IS a visible loss. Splitting the flag into
                    // isEstimated (display) + isInferred (persistence) would let this branch be
                    // shown-but-not-persisted; left as a deliberate residual, not an oversight.
                    data.isEstimated = true;
                    powerSource = "enginePowerKw(phev)";
                } else {
                    powerSource = "phev-awaiting-estimator";
                    try {
                        com.overdrive.app.abrp.SohEstimator soh =
                            com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                        if (soh != null && soh.getNominalCapacityKwh() > 0) {
                            double nominal = soh.getNominalCapacityKwh();
                            data.updateChargingPower(nominal < 30 ? 3.3 : 7.0);
                            data.isEstimated = true;
                            powerSource = "nominalPlaceholder(phev)";
                        }
                    } catch (Exception ignored) { /* leave power at 0 */ }
                }
            } else if (!Double.isNaN(vd.externalChargingPowerKw) && vd.externalChargingPowerKw > 0) {
                data.updateChargingPower(vd.externalChargingPowerKw);
                powerSource = "externalChargingPowerKw(AC)";
            } else if (!Double.isNaN(vd.chargingPowerKw) && vd.chargingPowerKw > 0) {
                data.updateChargingPower(vd.chargingPowerKw);
                powerSource = "chargingPowerKw(device)";
            } else if (estUsable) {
                // FALLBACK: no charger-reported power on this model. Derive it
                // from the time-derivative of the rising charge-energy counter
                // (ring-buffer estimator). This is a MEASURED-from-counter value,
                // not a nominal guess, so it is NOT flagged isEstimated — it can
                // legitimately seed the session peak/avg and drive the live curve.
                // The estimator only produces a value while fused-CHARGING + Park,
                // so it is regen/V2L-safe by construction.
                //
                // OUTRANKS enginePowerKw below (it used to sit under it). Both describe the same
                // charge, but only this one is unflagged — and because isEstimated doubles as
                // "don't persist", losing the race to the inference zeroed the CPS samples, hence
                // peak_power_kw, hence deriveIsDc's peak guard, hence DC pricing and both graphs.
                // Mirrors the PHEV ordering above, where the estimator already outranks it.
                data.updateChargingPower(estKw);
                powerSource = "estimator(ring)";
            } else if (!Double.isNaN(vd.enginePowerKw) && vd.enginePowerKw < -0.3) {
                // engine current flowing into pack. setAccState(false) wipes
                // this to NaN, so a value here is fresh from an ACC-on cycle.
                data.updateChargingPower(Math.abs(vd.enginePowerKw));
                // FLAGGED, exactly like the PHEV copy of this same inference above. abs(motor power)
                // is not a charger-side measurement, and I3 requires the flag on every such branch.
                // The PHEV branch got it and this one did not — an asymmetry with real cost: the
                // -1.0 sentinel filter is deliberately exact-match, so any OTHER placeholder
                // (-1.5, -2.0) or a genuine parked motor draw published here as MEASURED, entered
                // the CPS curve, and priced the session. I1 says BEV must stay bit-identical; I3 is
                // the load-bearing guard for I5 (money). Where they conflict, I3 wins — an unflagged
                // inference in the cost integral is wrong in currency, a blank display is only
                // unhelpful. Recorded as a second sanctioned BEV-visible change in I1.
                data.isEstimated = true;
                powerSource = "enginePowerKw";
            } else {
                // Detector says CHARGING but no real kW signal arrived.
                // Show a nominal-based hint so the UI doesn't say "Charging at 0 kW".
                powerSource = "none";
                try {
                    com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                    if (soh != null && soh.getNominalCapacityKwh() > 0) {
                        double nominal = soh.getNominalCapacityKwh();
                        // < 30 kWh nominal pack → PHEV (3.3 kW AC); else BEV (7 kW AC)
                        data.updateChargingPower(nominal < 30 ? 3.3 : 7.0);
                        data.isEstimated = true;
                        powerSource = "nominalPlaceholder";
                    }
                } catch (Exception ignored) { /* leave power at 0 */ }
            }
            // Diagnostic (throttled 1/min): which branch resolved the displayed
            // charging power, the chosen value, and EVERY candidate's raw value —
            // so a single charge log says definitively whether 3.4 kW is the DC
            // getter reading half, or the ring estimator. INFO so it lands in a
            // default-level capture.
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastChargePowerLogMs > 60_000L) {
                lastChargePowerLogMs = nowMs;
                logger.info(String.format(
                    "ChargingPower resolved=%.2fkW source=%s phev=%s | candidates: clusterKw=%s chargePowerKw=%s extChgKw=%s chgDevKw=%s engineKw=%s estimatorKw=%s",
                    data.chargingPowerKW, powerSource, phev,
                    fmt(vd.clusterChargePowerKw),
                    fmt(vd.chargePowerKw), fmt(vd.externalChargingPowerKw),
                    fmt(vd.chargingPowerKw), fmt(vd.enginePowerKw),
                    // Reuse the SNAPSHOT the cascade actually used, not a fresh read: re-reading
                    // here can observe a concurrent derive (or reset) on the collector thread and
                    // print a candidate that contradicts the resolved `source` field — e.g.
                    // "source=nominalPlaceholder(phev) ... estimatorKw=1.54" — which would send a
                    // future reader after a phantom cascade bug.
                    fmt(estKw)));
            }
        }
        return data;
    }
    
    public DrivingRangeData getDrivingRange() {
        BydVehicleData vd = getVd();
        if (vd != null && vd.elecRangeKm != BydVehicleData.UNAVAILABLE) {
            return new DrivingRangeData(
                vd.elecRangeKm,
                vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0,
                vd.fuelPercent  // NaN on BEVs (BydDataCollector only sets it on PHEVs)
            );
        }
        return null;
    }

    /**
     * PHEV cumulative liquid-fuel consumption counter, in litres, straight from
     * the BYD statistic HAL ({@code getTotalFuelConValue}). This is the vehicle's
     * own metered lifetime fuel-burned accumulator — a delta between two reads
     * gives the true litres consumed over an interval, independent of tank size
     * and free of the 1%-resolution gauge quantisation.
     *
     * <p>Intentionally NOT routed through {@link #getDrivingRange()}: that helper
     * returns null whenever {@code elecRangeKm} is momentarily unavailable, which
     * would silently drop the fuel snapshot. Trip code reads this directly so the
     * accumulator capture is decoupled from the elec-range gate.
     *
     * @return cumulative litres consumed, or {@code NaN} when the HAL doesn't
     *         report it (pure BEV, or trim without the accumulator).
     */
    public double getTotalFuelCon() {
        BydVehicleData vd = getVd();
        return vd != null ? vd.totalFuelCon : Double.NaN;
    }

    /**
     * Cumulative electricity-consumption counter, in kWh, straight from the BYD
     * statistic HAL ({@code getTotalElecConValue}) — the electric twin of
     * {@link #getTotalFuelCon()}.
     *
     * <p>A delta between two reads is the metered kWh drawn over the interval.
     * This matters most on SHORT trips: {@link #getBatteryRemainPowerKwh()} is
     * derived from SoC, which is integer-resolution on this trim (~0.6 kWh on a
     * 60 kWh pack ≈ 4 km of driving), so any trip below that shows zero energy.
     * This counter keeps advancing regardless, and needs neither the SoC
     * resolution nor a pack-capacity estimate.
     *
     * <p>Read directly rather than via a composite helper so the snapshot can't
     * be dropped when an unrelated field is momentarily unavailable.
     *
     * @return cumulative kWh consumed, or {@code NaN} when the HAL doesn't
     *         report it.
     */
    public double getTotalElecCon() {
        BydVehicleData vd = getVd();
        return vd != null ? vd.totalElecCon : Double.NaN;
    }

    /**
     * The vehicle's own lifetime average petrol consumption in L/100km, from the
     * BYD statistic HAL ({@code getTotalFuelConPHMValue}).
     *
     * <p>Preferred over deriving L/100km ourselves when showing a lifetime figure:
     * it is the same number the instrument cluster shows, so the two can't
     * disagree. Per-trip consumption is still computed from the litres delta over
     * the trip's distance — this accumulator is lifetime-wide and can't answer
     * "what did THIS drive use".
     *
     * @return L/100km, or {@code NaN} when unreported (BEV, or trim without it)
     */
    public double getAvgFuelConPer100Km() {
        BydVehicleData vd = getVd();
        return vd != null ? vd.avgFuelConPer100Km : Double.NaN;
    }
    
    public BatteryThermalData getBatteryThermal() {
        BydVehicleData vd = getVd();
        if (vd != null) {
            double hi = vd.highCellTempC;
            double lo = vd.lowCellTempC;
            double avg = vd.avgCellTempC;
            if (!Double.isNaN(hi) || !Double.isNaN(lo) || !Double.isNaN(avg)) {
                return new BatteryThermalData(hi, lo, avg, System.currentTimeMillis());
            }
        }
        return null;
    }
    
    public double getBatteryRemainPowerKwh() {
        BydVehicleData vd = getVd();
        if (vd == null) return 0.0;

        double soc = Double.isNaN(vd.socPercent) ? 0 : vd.socPercent;
        double rawKwh = Double.isNaN(vd.remainKwh) ? 0 : vd.remainKwh;

        try {
            com.overdrive.app.abrp.SohEstimator soh =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (soh != null && soh.getNominalCapacityKwh() > 0 && soc > 0) {
                double nominal = soh.getNominalCapacityKwh();
                // SINGLE SOURCE OF TRUTH for remaining energy. SOH is the
                // displayed (capped ≤100, independently-anchored) value so this
                // number always agrees with the SOH chip/card. Default 100 until
                // a real measurement exists.
                double sohPercent = soh.hasDisplaySoh() ? soh.getDisplaySoh() : 100.0;
                if (sohPercent <= 0) sohPercent = 100.0;
                double computedKwh = (soc / 100.0) * nominal * (sohPercent / 100.0);

                // PHEV: the BYD HAL remaining-energy getters are unreliable —
                // half-scale on some firmwares, STALE/FROZEN when the ICE is
                // running, and frame-ambiguous (no single sample can tell half
                // from gross). We therefore do NOT trust the raw getter for
                // display or accounting on PHEV: remaining is ALWAYS synthesized
                // from the reliable SOC + the user's nominal + the capped SOH.
                // This is the one value every surface (dash, MQTT, ABRP, trips,
                // history) reads, so they agree by construction and it tracks SOC
                // live — eliminating the frozen / doubled / halved / divergent
                // symptoms at the root. (The raw getter still feeds the INDEPENDENT
                // SOH anchors — capacity-Ah coulomb count, calibration — never this
                // display path, so there is no self-reference loop.)
                if (isPhev()) {
                    return computedKwh;
                }

                // BEV: getBatteryRemainPowerEV is authoritative. Trust it within a
                // plausible band (a pack can't exceed nameplate → 1.12 ceiling;
                // a degraded pack reads below → 0.5 floor); else synthesize.
                if (rawKwh > 0) {
                    double impliedCap = rawKwh / (soc / 100.0);
                    double ratio = impliedCap / nominal;
                    if (ratio < 0.5 || ratio > 1.12) {
                        return computedKwh;
                    }
                    return rawKwh;
                }

                // No raw reading: synthesize from SOC × nominal × SOH.
                return computedKwh;
            }
        } catch (Exception e) { /* fall through to raw */ }

        // SohEstimator not ready: use raw BMS value if available
        if (rawKwh > 0) return rawKwh;

        return 0.0;
    }
    
    public JSONObject getAllData() {
        JSONObject json = new JSONObject();
        BydVehicleData vd = getVd();
        
        try {
            // Battery voltage (old format for BatteryMonitor compatibility)
            if (vd != null && vd.voltageLevelRaw != BydVehicleData.UNAVAILABLE) {
                JSONObject bvJson = new JSONObject();
                bvJson.put("level", vd.voltageLevelRaw);
                bvJson.put("levelName", vd.voltageLevelRaw == 1 ? "NORMAL" : vd.voltageLevelRaw == 0 ? "LOW" : "INVALID");
                json.put("batteryVoltage", bvJson);
            }
            
            // Battery power (old format)
            if (vd != null && !Double.isNaN(vd.voltage12v)) {
                JSONObject bpJson = new JSONObject();
                bpJson.put("voltageVolts", vd.voltage12v);
                bpJson.put("isWarning", vd.voltage12v < 11.5);
                bpJson.put("isCritical", vd.voltage12v < 10.5);
                bpJson.put("healthStatus", vd.voltage12v < 10.5 ? "CRITICAL" : vd.voltage12v < 11.5 ? "WARNING" : "NORMAL");
                json.put("batteryPower", bpJson);
            }
            
            // Battery SOC (old format)
            if (vd != null && !Double.isNaN(vd.socPercent)) {
                JSONObject bsJson = new JSONObject();
                bsJson.put("socPercent", vd.socPercent);
                bsJson.put("isLow", vd.socPercent < 20);
                bsJson.put("isCritical", vd.socPercent < 10);
                json.put("batterySoc", bsJson);
            }
            
            // Charging state — single source of truth via getChargingState()
            // so this JSON dump matches what SOC graph / ABRP / MQTT see. The
            // raw BMS field (vd.chargingState) is no longer surfaced standalone
            // because it's known to lag and to misreport on PHEVs.
            ChargingStateData cs = getChargingState();
            if (cs != null) {
                JSONObject csJson = new JSONObject();
                csJson.put("stateCode", cs.stateCode);
                csJson.put("stateName", cs.stateName);
                csJson.put("status", cs.status.name());
                csJson.put("isError", cs.isError);
                csJson.put("chargingPowerKW", cs.chargingPowerKW);
                csJson.put("isDischarging", cs.isDischarging);
                csJson.put("isEstimated", cs.isEstimated);
                json.put("chargingState", csJson);
            }
            
            // Driving range (old format)
            if (vd != null && vd.elecRangeKm != BydVehicleData.UNAVAILABLE) {
                JSONObject drJson = new JSONObject();
                drJson.put("elecRangeKm", vd.elecRangeKm);
                drJson.put("fuelRangeKm", vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0);
                drJson.put("totalRangeKm", vd.elecRangeKm + (vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0));
                json.put("drivingRange", drJson);
            }
            
            // Battery thermal (old format)
            if (vd != null && (!Double.isNaN(vd.highCellTempC) || !Double.isNaN(vd.avgCellTempC))) {
                JSONObject btJson = new JSONObject();
                if (!Double.isNaN(vd.highCellTempC)) btJson.put("highestTempC", vd.highCellTempC);
                if (!Double.isNaN(vd.lowCellTempC)) btJson.put("lowestTempC", vd.lowCellTempC);
                if (!Double.isNaN(vd.avgCellTempC)) btJson.put("averageTempC", vd.avgCellTempC);
                json.put("batteryThermal", btJson);
            }
            
            json.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            logger.error("Failed to create JSON", e);
        }
        
        return json;
    }
    
    public Map<String, Boolean> getAvailability() {
        Map<String, Boolean> availability = new HashMap<>();
        BydDataCollector c = BydDataCollector.getInstance();
        boolean ready = c.isInitialized();
        availability.put("batteryVoltage", ready);
        availability.put("batteryPower", ready || batteryPowerMonitor.isAvailable());
        availability.put("batterySoc", ready);
        availability.put("chargingState", ready);
        availability.put("drivingRange", ready);
        availability.put("batteryThermal", ready);
        return availability;
    }
    
    // ==================== MONITOR ACCESS (kept for backward compat) ====================
    
    public BatteryPowerMonitor getBatteryPowerMonitor() { return batteryPowerMonitor; }
    
    // These return null now — consumers should use the data access methods above
    public BatteryVoltageMonitor getBatteryVoltageMonitor() { return null; }
    public DrivingRangeMonitor getDrivingRangeMonitor() { return null; }
    
    // ==================== LISTENER MANAGEMENT ====================
    
    public void addListener(VehicleDataListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(VehicleDataListener listener) {
        if (listener != null) listeners.remove(listener);
    }
    
    public void notifyBatteryVoltageChanged(BatteryVoltageData data) {
        for (VehicleDataListener l : listeners) { try { l.onBatteryVoltageChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyBatteryPowerChanged(BatteryPowerData data) {
        for (VehicleDataListener l : listeners) { try { l.onBatteryPowerChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyChargingStateChanged(ChargingStateData data) {
        for (VehicleDataListener l : listeners) { try { l.onChargingStateChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyChargingPowerChanged(double powerKW) {
        for (VehicleDataListener l : listeners) { try { l.onChargingPowerChanged(powerKW); } catch (Exception ignored) {} }
    }
    
    public void notifyDataUnavailable(String monitorName, String reason) {
        for (VehicleDataListener l : listeners) { try { l.onDataUnavailable(monitorName, reason); } catch (Exception ignored) {} }
    }
}
