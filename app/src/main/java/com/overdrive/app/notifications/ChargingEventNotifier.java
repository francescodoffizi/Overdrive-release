package com.overdrive.app.notifications;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.server.Messages;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes vehicle.charging.* notifications:
 * <ul>
 *   <li>{@code vehicle.charging.started} / {@code .stopped} — driven directly
 *       by {@link com.overdrive.app.monitor.ChargingDetector} fused-state
 *       edges. The detector already fuses BMS + Power.isCharging() + L3
 *       inference + plug edges with hysteresis (30s plug bias, 10s L1↔L2
 *       disagreement, 15s unplug override, 3-sample L3), so re-debouncing
 *       here is redundant and was the cause of silently-dropped sessions.</li>
 *   <li>{@code vehicle.charging.full} — once per session when SOC crosses
 *       {@link #FULL_SOC_THRESHOLD} (or plateaus near the top) while a
 *       session is active. Suppressed when the session began at or above
 *       the threshold (plugged-in-already-full).</li>
 *   <li>{@code vehicle.charging.fault} — every distinct breakdown transition
 *       on the BMS edge stream. Independent of session bookkeeping so a
 *       breakdown is always announced even if no session was active.</li>
 * </ul>
 *
 * <p>This notifier is purely a downstream consumer — it never mutates
 * {@code chargingState} or {@code chargingPowerKw}. ABRP, MQTT, and the
 * SOC-history graph all read the snapshot directly via {@code getData()}
 * and are unaffected by this code path.
 */
public final class ChargingEventNotifier {

    /**
     * Threshold for "full" notification. BYD's BMS reports SOC as a whole
     * integer, so any fractional threshold like 99.5 is unreachable below
     * 100. 99 is the earliest reachable signal that the pack is effectively
     * full and the user can unplug.
     */
    private static final double FULL_SOC_THRESHOLD = 99.0;

    /**
     * Plateau-based completion: if SOC hits this floor, rises substantially
     * from start, and then stays flat for {@link #PLATEAU_HOLD_MS}, treat
     * that as full. Catches the BYD pattern of plateauing at 99 during
     * balancing without ever quite hitting 100 before the user unplugs.
     */
    private static final double PLATEAU_SOC_FLOOR = 98.0;
    private static final long PLATEAU_HOLD_MS = 90_000L;

    /** Minimum SOC rise to call a plateau "complete" (filters short top-ups). */
    private static final double MIN_SOC_RISE_FOR_PLATEAU = 5.0;

    /** SOC polling cadence while a session is active. */
    private static final long SOC_POLL_INTERVAL_MS = 10_000L;

    private static volatile ChargingEventNotifier instance;

    private final com.overdrive.app.monitor.ChargingDetector.FusedStateListener fusedListener =
            (isCharging, source) -> onFusedEdge(isCharging, source);

    /**
     * Faults come from the raw BMS edge stream, independent of session
     * bookkeeping. A breakdown reported while we never opened a session
     * still warrants a notification.
     */
    private final BydDataCollector.ChargingStateListener faultListener =
            (prev, now) -> {
                if (statusOf(now) == ChargingStateData.ChargingStatus.ERROR) {
                    publishFault(now);
                }
            };

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ChargingEventNotifier");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> socPoller;
    private final Object pollerLock = new Object();
    private final FullSessionState fullSessionState = new FullSessionState();

    private ChargingEventNotifier() {}

    interface FullPublisher {
        void publish(double socPercent);
    }

    static final class SessionEdge {
        final long generation;
        final boolean changed;

        SessionEdge(long generation, boolean changed) {
            this.generation = generation;
            this.changed = changed;
        }
    }

    /**
     * Session generation and full-detection state share one monitor. The publisher callback runs
     * while that monitor is held so a stop/new-session edge cannot pass its generation update
     * between the final eligibility check and publication.
     */
    static final class FullSessionState {
        private volatile long generation;
        private volatile boolean active;
        private boolean fullFired;
        private double startSoc = Double.NaN;
        private double maxSoc = Double.NaN;
        private long plateauStartedAtMs;

        synchronized SessionEdge onEdge(boolean nextActive, double socPercent) {
            boolean changed = active != nextActive;
            active = nextActive;
            if (changed && nextActive) {
                startSoc = socPercent;
                maxSoc = socPercent;
                plateauStartedAtMs = 0L;
                fullFired = false;
            } else if (changed) {
                plateauStartedAtMs = 0L;
            }
            // Publish the generation last. A lock-free poller check that observes it also observes
            // the active flag and all session initialization that precede this volatile write.
            long nextGeneration = ++generation;
            return new SessionEdge(nextGeneration, changed);
        }

        synchronized void initializeStartSoc(long expectedGeneration,
                                             double socPercent) {
            if (expectedGeneration != generation || !active
                    || isFinite(startSoc) || !isFinite(socPercent)) {
                return;
            }
            startSoc = socPercent;
            maxSoc = socPercent;
        }

        synchronized void checkAndPublish(long expectedGeneration,
                                          double soc, long nowMs,
                                          FullPublisher publisher) {
            if (expectedGeneration != generation || !active || fullFired
                    || !isFinite(soc)) {
                return;
            }

            if (!isFinite(maxSoc) || soc > maxSoc) maxSoc = soc;
            if (soc >= PLATEAU_SOC_FLOOR) {
                if (plateauStartedAtMs == 0L) plateauStartedAtMs = nowMs;
            } else {
                plateauStartedAtMs = 0L;
            }

            boolean startedFull =
                    isFinite(startSoc) && startSoc >= FULL_SOC_THRESHOLD;
            boolean thresholdFull = soc >= FULL_SOC_THRESHOLD;
            boolean plateauFull = plateauStartedAtMs != 0L
                    && nowMs - plateauStartedAtMs >= PLATEAU_HOLD_MS
                    && !startedFull
                    && isFinite(startSoc)
                    && soc - startSoc >= MIN_SOC_RISE_FOR_PLATEAU;
            if (!thresholdFull && !plateauFull) return;

            fullFired = true;
            if (!startedFull) publisher.publish(soc);
        }

        boolean isCurrent(long expectedGeneration, boolean expectedActive) {
            return generation == expectedGeneration
                    && active == expectedActive;
        }
    }

    public static synchronized void start() {
        if (instance != null) return;
        ChargingEventNotifier n = new ChargingEventNotifier();
        // Single source of truth for session edges. Detector is already
        // fused and debounced; trust its verdict directly.
        com.overdrive.app.monitor.ChargingDetector detector =
                com.overdrive.app.monitor.ChargingDetector.getInstance();
        detector.addFusedStateListener(n.fusedListener);
        // Faults are independent — wire to raw BMS edges.
        BydDataCollector.getInstance().addChargingStateListener(n.faultListener);
        instance = n;

        // Boot-race replay. The detector does not re-emit current state on
        // subscribe; if BydDataCollector.init() has already driven fused
        // state to true (cable plugged at cold boot), the listener above
        // would otherwise miss the start edge entirely and the user would
        // only get a "stopped" later. Synthesise a single self-call so the
        // session opens and the SOC poller starts.
        if (detector.isCharging()) {
            n.onFusedEdge(true, "boot-replay");
        }
    }

    private void onFusedEdge(boolean isCharging, String source) {
        // Fence the prior session before any snapshot read. A stop/new-session callback must make
        // an already-running full check stale at its first synchronized transition, rather than
        // leaving a read window in which that check can publish for the old session.
        SessionEdge edge = fullSessionState.onEdge(isCharging, Double.NaN);
        BydVehicleData snap = null;
        try {
            snap = BydDataCollector.getInstance().getData();
        } catch (Throwable ignored) {
            // The generation/poller transition must still complete if telemetry is unavailable.
        }
        double startSoc = (snap != null) ? snap.socPercent : Double.NaN;
        if (isCharging) {
            fullSessionState.initializeStartSoc(edge.generation, startSoc);
        }

        // Fused listeners are edge-driven, but a duplicate callback is still allowed to invalidate
        // an already-running check. Restart its poller with the new generation without re-publishing
        // the user-facing start event.
        if (!edge.changed) {
            if (isCharging) startSocPoller(edge.generation);
            else stopSocPoller(edge.generation);
            return;
        }

        if (isCharging) {
            startSocPoller(edge.generation);
            int stateCode = (snap != null)
                    ? snap.chargingState
                    : ChargingStateData.CHARGING_BATTERY_STATE_CHARGING;
            publishStarted(stateCode);
        } else {
            stopSocPoller(edge.generation);
            int stateCode = (snap != null)
                    ? snap.chargingState
                    : ChargingStateData.CHARGING_BATTERY_STATE_IDLE;
            publishStopped(stateCode);
        }
    }

    private void startSocPoller(long generation) {
        synchronized (pollerLock) {
            if (!fullSessionState.isCurrent(generation, true)) return;
            cancelSocPollerLocked();
            socPoller = scheduler.scheduleWithFixedDelay(
                    () -> checkSocFull(generation),
                    0L, SOC_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void stopSocPoller(long generation) {
        synchronized (pollerLock) {
            if (!fullSessionState.isCurrent(generation, false)) return;
            cancelSocPollerLocked();
        }
    }

    private void cancelSocPollerLocked() {
        ScheduledFuture<?> f = socPoller;
        if (f != null) {
            f.cancel(false);
            socPoller = null;
        }
    }

    private void checkSocFull(long generation) {
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        if (snap == null) return;
        double soc = snap.socPercent;
        fullSessionState.checkAndPublish(
                generation, soc, System.currentTimeMillis(),
                this::publishFull);
    }

    private void publishStarted(int stateCode) {
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        // RESOLVED rate, not the raw field. Raw charging accessors are stored unscaled and their
        // unit is decided at runtime — a value may be a cumulative kWh counter rather than a kW
        // rate — so printing one directly could put a counter reading in a notification as "kW".
        double powerKw = Double.NaN;
        try {
            com.overdrive.app.monitor.ChargingStateData cs =
                    com.overdrive.app.monitor.VehicleDataMonitor.getInstance().getChargingState();
            // !isEstimated, matching every other outbound surface. On the fused START edge nothing
            // measured has usually resolved yet (all classifier verdicts begin UNKNOWN), so without
            // this the notification announces the nominal placeholder — "Charging started, 7.0 kW" on
            // a 2 kW charge — as if it were the real rate. Omitting the figure is the honest option.
            if (cs != null && !cs.isEstimated
                    && !Double.isNaN(cs.chargingPowerKW) && cs.chargingPowerKW > 0) {
                powerKw = cs.chargingPowerKW;
            }
        } catch (Throwable ignored) { /* leave NaN — the body simply omits the rate */ }
        double socPercent = (snap != null) ? snap.socPercent : Double.NaN;

        StringBuilder body = new StringBuilder();
        if (isFinite(powerKw) && Math.abs(powerKw) >= 0.1) {
            body.append(formatKw(powerKw)).append(" kW");
        }
        if (isFinite(socPercent)) {
            if (body.length() > 0) body.append(" • ");
            body.append((int) Math.round(socPercent)).append("%");
        }

        JSONObject data = new JSONObject();
        try {
            data.put("stateCode", stateCode);
            if (isFinite(powerKw)) data.put("powerKw", powerKw);
            if (isFinite(socPercent)) data.put("socPercent", socPercent);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.started",
                NotificationEvent.Severity.INFO,
                Messages.get("notifications.charging_started"),
                body.toString(),
                "charging-session",
                null,
                data));
    }

    private void publishStopped(int stateCode) {
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        double socPercent = (snap != null) ? snap.socPercent : Double.NaN;

        String reason = stateLabel(stateCode);
        StringBuilder body = new StringBuilder(reason);
        if (isFinite(socPercent)) {
            body.append(" • ").append((int) Math.round(socPercent)).append("%");
        }

        JSONObject data = new JSONObject();
        try {
            data.put("stateCode", stateCode);
            data.put("stateName", reason);
            if (isFinite(socPercent)) data.put("socPercent", socPercent);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.stopped",
                NotificationEvent.Severity.INFO,
                Messages.get("notifications.charging_stopped"),
                body.toString(),
                "charging-session",
                null,
                data));
    }

    private void publishFull(double socPercent) {
        JSONObject data = new JSONObject();
        try {
            data.put("socPercent", socPercent);
            data.put("threshold", FULL_SOC_THRESHOLD);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.full",
                NotificationEvent.Severity.WARN,
                Messages.get("notifications.charging_complete"),
                Messages.get("notifications.battery_ready_to_unplug",
                        (int) Math.round(socPercent)),
                "charging-full",
                null,
                data));
    }

    private void publishFault(int stateCode) {
        String label = stateLabel(stateCode);

        JSONObject data = new JSONObject();
        try {
            data.put("stateCode", stateCode);
            data.put("stateName", label);
        } catch (Exception ignored) {}

        publish(new NotificationEvent(
                "vehicle.charging.fault",
                NotificationEvent.Severity.CRITICAL,
                Messages.get("notifications.charging_fault"),
                label,
                "charging-fault",
                null,
                data));
    }

    private static void publish(NotificationEvent event) {
        try { NotificationBus.get().publish(event); } catch (Throwable ignored) {}
    }

    private static ChargingStateData.ChargingStatus statusOf(int stateCode) {
        return new ChargingStateData(stateCode).status;
    }

    private static String stateLabel(int stateCode) {
        String key;
        switch (stateCode) {
            case ChargingStateData.CHARGING_BATTERY_STATE_READY:
                key = "ready"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_CHARGING:
                key = "charging"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH:
                key = "finished"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG:
                key = "discharging"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_CHARG_TERMINATE:
                key = "terminated"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_C10:
                key = "fault_c10"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_CHARGING_GUN:
                key = "fault_gun"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_AC:
                key = "fault_ac"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_CHARGER:
                key = "fault_charger"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_SCHEDULE:
                key = "scheduled"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_TIMEOUT:
                key = "timeout"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG_CBU:
                key = "discharging_cbu"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG_FINISH:
                key = "discharge_finished"; break;
            case ChargingStateData.CHARGING_BATTERY_STATE_IDLE:
                key = "idle"; break;
            default:
                return Messages.get("notifications.charging_state.unknown", stateCode);
        }
        return Messages.get("notifications.charging_state." + key);
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static String formatKw(double kw) {
        return String.format(java.util.Locale.US, "%.1f", Math.abs(kw));
    }
}
