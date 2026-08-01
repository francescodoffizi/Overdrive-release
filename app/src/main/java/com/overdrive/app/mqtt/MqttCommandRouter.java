package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-connection ingress for Home Assistant control commands.
 *
 * Takes an inbound {@code (entityKey, subKey, payload)} parsed from a
 * {@code <base>/<key>[/<sub>]/set} topic, resolves it against
 * {@link VehicleControlCatalog}, and dispatches the resulting command
 * <b>SDK-only</b> via {@link VehicleCommandRouter#executeSdkOnly} — the BYD cloud
 * is never touched. On success it optimistically echoes the commanded value back
 * to the entity's retained state topic so HA reflects the change immediately; the
 * next telemetry poll reconciles the true value.
 *
 * Commands run on a dedicated single-thread executor so the Paho callback thread
 * (which delivers {@code messageArrived}) is never blocked by a 1–2 s HAL round-trip.
 */
public class MqttCommandRouter {

    private static final DaemonLogger logger = DaemonLogger.getInstance("MqttCommandRouter");

    /** Publishes {@code value} (retained) to {@code <base>/<key>} on the owning connection. */
    public interface Echo {
        void publish(String key, String value);
    }

    private final ExecutorService exec;
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
    }

    /** Handle one inbound control command. Non-blocking — work runs on the executor. */
    public void handle(String key, String sub, String payload) {
        VehicleControlCatalog.ControlEntity entity = VehicleControlCatalog.get(key);
        if (entity == null) {
            logger.warn("Ignoring command for unknown control entity: " + key);
            return;
        }
        final String p = payload == null ? "" : payload;
        exec.submit(() -> dispatch(entity, sub, p));
    }

    private void dispatch(VehicleControlCatalog.ControlEntity entity, String sub, String payload) {
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            BydVehicleData snap = collector.isInitialized() ? collector.getData() : null;

            VehicleControlCatalog.ControlAction action = entity.toAction(sub, payload, snap);
            if (action == null || action.command == null) {
                logger.debug("No action for " + entity.key + (sub != null ? "/" + sub : "") + " payload='" + payload + "'");
                return;
            }

            VehicleCommandRouter.CommandResult r =
                    VehicleCommandRouter.getInstance().executeSdkOnly(action.command);

            // Record what we commanded for a blind-toggle entity once the command reached the
            // vehicle (either outcome), but never when it was refused before getting there —
            // see ControlAction.commitIfAttempted. Kept outside the SUCCESS branch on purpose:
            // committing only on SUCCESS would freeze a no-readback toggle on one value.
            action.commitIfAttempted(r.outcome);

            if (r.outcome == VehicleCommandRouter.Outcome.SUCCESS) {
                logger.info("Control '" + entity.key + (sub != null ? "/" + sub : "")
                        + "' -> " + action.command.name() + " ok (" + r.latencyMs + "ms)");
                if (action.echoKey != null && action.echoValue != null && echo != null) {
                    echo.publish(action.echoKey, action.echoValue);
                }
            } else {
                logger.warn("Control '" + entity.key + "' -> " + action.command.name()
                        + " " + r.outcome + " (" + r.displayMessage + ")");
            }
        } catch (Exception e) {
            logger.error("Command dispatch error for " + entity.key + ": " + e.getMessage());
        }
    }

    public void shutdown() {
        try { exec.shutdownNow(); } catch (Exception ignored) {}
    }
}
