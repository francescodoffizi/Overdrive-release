package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-connection ingress for Home Assistant control commands.
 *
 * Takes an inbound {@code (entityKey, subKey, payload)} parsed from a
 * {@code <base>/<key>[/<sub>]/set} topic, resolves it against
 * {@link VehicleControlCatalog}, and dispatches the resulting command
 * <b>SDK-only by default</b> via {@link VehicleCommandRouter#executeSdkOnly}. A narrow
 * command-level opt-in uses the normal SDK-first router path when its cloud fallback is
 * semantically safe. On success it echoes command-only controls to their retained state topic.
 * Controls with a readable backend, such as charge limits, publish only their verified readback
 * rather than the requested value.
 *
 * Commands run on a dedicated single-thread executor so the Paho callback thread
 * (which delivers {@code messageArrived}) is never blocked by a 1–2 s HAL round-trip.
 * Tailgate STOP uses a separate urgent worker so it can cancel an in-flight cloud OPEN without
 * waiting behind that request in the normal per-connection queue.
 */
public class MqttCommandRouter {

    private static final DaemonLogger logger = DaemonLogger.getInstance("MqttCommandRouter");

    /** Publishes {@code value} (retained) to {@code <base>/<key>} on the owning connection. */
    public interface Echo {
        void publish(String key, String value);
    }

    private final ExecutorService exec;
    private final ExecutorService urgentExec;
    /**
     * The newest urgent STOP that an OPEN received later must wait behind. Without this, the
     * normal worker could begin a newer OPEN before the urgent worker has issued the earlier
     * motor STOP, leaving the final state reversed.
     */
    private final AtomicReference<Future<?>> tailgateStopBarrier = new AtomicReference<>();
    /** Serializes tailgate OPEN/STOP ingress so the barrier and cancellation generation agree. */
    private final Object tailgateIngressLock = new Object();
    private final Echo echo;
    private final String connectionId;

    public MqttCommandRouter(String connectionId, Echo echo) {
        this.connectionId = connectionId;
        this.echo = echo;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MqttControl-" + connectionId);
            t.setDaemon(true);
            return t;
        });
        this.urgentExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MqttControlUrgent-" + connectionId);
            t.setDaemon(true);
            return t;
        });
    }

    /** Handle one inbound control command. Non-blocking — work runs on the executor. */
    public void handle(String key, String sub, String payload) {
        VehicleControlCatalog.ControlEntity entity = VehicleControlCatalog.get(key);
        if (entity == null) {
            logger.warn("Ignoring command for unknown control entity: " + key);
            return;
        }
        final String p = payload == null ? "" : payload;
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        final boolean tailgateStop = isTailgateStop(entity, sub, p);
        final boolean tailgateOpen = isTailgateOpen(entity, sub, p);
        // The submits below can throw RejectedExecutionException if shutdown() (from
        // the owning connection's disconnect()) lands between the caller obtaining
        // this router and the submit executing. Uncaught, that propagates out of
        // Paho's messageArrived — which Paho v3 treats as fatal and answers by
        // tearing the connection down itself (noisy, and it races our own orderly
        // disconnect). The connection is shutting down anyway, so a rejected
        // command is correctly just dropped.
        try {
            if (tailgateStop || tailgateOpen) {
                // The cancellation generation and the STOP completion barrier form one ordering
                // transaction. A concurrent OPEN must observe both the preceding STOP and its
                // barrier, never only one of them.
                synchronized (tailgateIngressLock) {
                    if (tailgateStop) {
                        router.abortPendingTailgateOpen();
                        Future<?> stop = urgentExec.submit(
                                () -> dispatch(entity, sub, p, -1L, true, null));
                        tailgateStopBarrier.set(stop);
                    } else {
                        long tailgateOpenGeneration = router.captureTailgateOpenStopGeneration();
                        Future<?> barrier = tailgateStopBarrier.get();
                        exec.submit(() -> dispatch(entity, sub, p, tailgateOpenGeneration,
                                false, barrier));
                    }
                }
            } else {
                exec.submit(() -> dispatch(entity, sub, p, -1L, false, null));
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.info("Dropping command for '" + entity.key
                    + "' — router shut down while the command was in flight");
        }
    }

    private void dispatch(VehicleControlCatalog.ControlEntity entity, String sub, String payload,
                          long tailgateOpenGeneration, boolean tailgateStopAlreadyAborted,
                          Future<?> precedingTailgateStop) {
        try {
            if (!awaitTailgateStop(precedingTailgateStop)) {
                logger.debug("Tailgate open interrupted while waiting for the preceding STOP");
                return;
            }
            BydDataCollector collector = BydDataCollector.getInstance();
            BydVehicleData snap = collector.isInitialized() ? collector.getData() : null;

            // HA can retain an entity after discovery has withdrawn it, and MQTT clients may
            // publish directly to its generic topic. Do not let either path probe or write an
            // unverified charge-stop backend.
            if (VehicleControlCatalog.isGenericChargeCapControl(entity.key)
                    && !hasVerifiedChargeCapBackend(collector.isChargeCapSupported(),
                    collector.getChargeCapPercent(), collector.getChargeCapEnabled())) {
                logger.warn("Ignoring charge-cap command without a verified backend: " + entity.key);
                return;
            }

            VehicleControlCatalog.ControlAction action = entity.toAction(sub, payload, snap);
            if (action == null || action.command == null) {
                logger.debug("No action for " + entity.key + (sub != null ? "/" + sub : "") + " payload='" + payload + "'");
                return;
            }

            VehicleCommandRouter router = VehicleCommandRouter.getInstance();
            VehicleCommandRouter.VehicleCommand command = router.bindTailgateOpenStopGeneration(
                    action.command, tailgateOpenGeneration);
            if (tailgateStopAlreadyAborted) {
                command = router.bindTailgateStopCancellation(command);
            }
            VehicleCommandRouter.CommandResult r = command.allowCloudFallbackFromMqtt()
                    ? router.execute(command) : router.executeSdkOnly(command);

            // Record what we commanded for a blind-toggle entity once the command reached the
            // vehicle (either outcome), but never when it was refused before getting there —
            // see ControlAction.commitIfAttempted. Kept outside the SUCCESS branch on purpose:
            // committing only on SUCCESS would freeze a no-readback toggle on one value.
            action.commitIfAttempted(r.outcome);

            if (r.outcome == VehicleCommandRouter.Outcome.SUCCESS) {
                logger.info("Control '" + entity.key + (sub != null ? "/" + sub : "")
                        + "' -> " + command.name() + " ok (" + r.latencyMs + "ms)");
                if (action.echoKey != null && action.echoValue != null && echo != null) {
                    String value = verifiedEchoValue(action, collector);
                    if (value != null) echo.publish(action.echoKey, value);
                }
            } else {
                logger.warn("Control '" + entity.key + "' -> " + command.name()
                        + " " + r.outcome + " (" + r.displayMessage + ")");
            }
        } catch (Exception e) {
            logger.error("Command dispatch error for " + entity.key + ": " + e.getMessage());
        }
    }

    /**
     * The charge-stop SDK can acknowledge a no-op on unsupported trims. The
     * command itself requires matching readback before success, and this
     * second read prevents HA from ever publishing the requested cap as fact.
     */
    private static String verifiedEchoValue(VehicleControlCatalog.ControlAction action,
                                            BydDataCollector collector) {
        if ("charge_cap_percent".equals(action.echoKey)) {
            int percent = collector.getChargeCapPercent();
            return isVerifiedChargeCapEcho("charge_cap_percent",
                    collector.isChargeCapSupported(), percent) ? String.valueOf(percent) : null;
        }
        if ("charge_cap_enabled".equals(action.echoKey)) {
            int enabled = collector.getChargeCapEnabled();
            return isVerifiedChargeCapEcho("charge_cap_enabled",
                    collector.isChargeCapSupported(), enabled) ? String.valueOf(enabled) : null;
        }
        return action.echoValue;
    }

    static boolean hasVerifiedChargeCapBackend(Boolean supported, int percent, int enabled) {
        return MqttConnectionManager.isVerifiedChargeCapState(supported, percent, enabled);
    }

    static boolean isVerifiedChargeCapEcho(String key, Boolean supported, int value) {
        if (!Boolean.TRUE.equals(supported)) return false;
        if ("charge_cap_percent".equals(key)) return value >= 50 && value <= 100;
        if ("charge_cap_enabled".equals(key)) return value == 0 || value == 1;
        return false;
    }

    static boolean isTailgateOpen(VehicleControlCatalog.ControlEntity entity, String sub,
                                  String payload) {
        return entity != null && "tailgate".equals(entity.key) && sub == null
                && "OPEN".equalsIgnoreCase(payload == null ? "" : payload.trim());
    }

    static boolean isTailgateStop(VehicleControlCatalog.ControlEntity entity, String sub,
                                  String payload) {
        return entity != null && "tailgate".equals(entity.key) && sub == null
                && "STOP".equalsIgnoreCase(payload == null ? "" : payload.trim());
    }

    /**
     * Upper bound on waiting for a preceding tailgate STOP before an OPEN. Generous —
     * a STOP is a local HAL call or one cloud round-trip, both of which resolve (or
     * time out) well within this. The bound exists so a STOP wedged in socket I/O
     * (a downstream timeout that failed to fire) can't park the normal command
     * executor forever, silently killing every subsequent HA command on this
     * connection. On expiry the OPEN is DROPPED, not run: the STOP is still
     * in flight, so dispatching the OPEN could complete before it and leave the
     * final motor state reversed — and a >30s-stale OPEN firing suddenly is worse
     * than a dropped one (the user can simply press it again).
     */
    private static final long TAILGATE_STOP_BARRIER_TIMEOUT_S = 30;

    private static boolean awaitTailgateStop(Future<?> precedingTailgateStop) {
        if (precedingTailgateStop == null) return true;
        try {
            precedingTailgateStop.get(TAILGATE_STOP_BARRIER_TIMEOUT_S,
                    java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.TimeoutException timedOut) {
            logger.warn("Preceding tailgate STOP still in flight after "
                    + TAILGATE_STOP_BARRIER_TIMEOUT_S + "s — dropping the queued OPEN"
                    + " (ordering with the un-finished STOP can't be guaranteed)");
            return false;
        } catch (Exception ignored) {
            // The STOP attempt completed but the local backend could not confirm it. A later
            // OPEN remains the newer user intent, so do not suppress it.
            return true;
        }
    }

    public void shutdown() {
        try { exec.shutdownNow(); } catch (Exception ignored) {}
        try { urgentExec.shutdownNow(); } catch (Exception ignored) {}
    }
}
