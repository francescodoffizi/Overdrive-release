package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;

/**
 * Publishes the fast dynamic-driving inputs — speed, accelerator, brake and steering angle —
 * into the automation state at a FAST cadence so an "accelerator past X%" / "hard brake"
 * / "steering past Y°" automation fires promptly.
 *
 * <p><b>Why a dedicated fast poll.</b> These inputs piggy-backed on the telemetry
 * snapshot ({@link BydEvent#bydEvent}), which is built sub-second WHILE DRIVING but only
 * every ~5s while STATIONARY (the RoadSense fast-dynamics poll refreshes its own atomic
 * but never calls {@code build()}). So a pedal/steering trigger lagged the driver by up
 * to ~5s — the reported "trigger delay". This poll reads the same live SDK getters
 * directly on the daemon at a fast cadence and publishes on value transitions, exactly
 * like {@link TurnSignalEvent} does for the indicators.
 *
 * <p><b>Zero cost unless such an automation exists.</b> The shared task exists only while at
 * least one input is referenced, and each getter remains independently gated inside the task.
 */
public final class DynamicsEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    static final long POLL_MS = 250L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "dynamics",
            POLL_MS,
            DynamicsEvent::referenced,
            DynamicsEvent::poll);

    private DynamicsEvent() {}

    private static boolean referenced() {
        return Automations.isEventReferenced(BydEvent.SPEED_KMPH)
                || Automations.isEventReferenced(BydEvent.SPEED_MPH)
                || Automations.isEventReferenced(BydEvent.ACCELERATOR)
                || Automations.isEventReferenced(BydEvent.BRAKE)
                || Automations.isEventReferenced(BydEvent.STEERING_ANGLE);
    }

    public static void refresh() {
        poller.refresh();
    }

    /**
     * Read all four dynamic inputs NOW and publish them, ignoring the per-signal reference gate.
     * For the editor's live-value hints only: these keys are {@code FAST_POLL_OWNED}, so the
     * snapshot path drops them and this poll is their only publisher — but the poll is gated on a
     * rule already referencing them, so in the editor they had no publisher at all and read "not
     * reported yet on this car". Store-only while nothing is enabled, so it cannot fire a rule.
     */
    public static void seedForEditor() {
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            double speed = collector.readSpeedNowKmh();
            BydEvent.publishSpeedKmh(speed);
            int a = collector.readAccelNow();
            if (a != BydVehicleData.UNAVAILABLE) Automations.update(BydEvent.ACCELERATOR, a);
            int b = collector.readBrakeNow();
            if (b != BydVehicleData.UNAVAILABLE) Automations.update(BydEvent.BRAKE, b);
            int s = collector.readSteeringNow();
            if (s != BydVehicleData.UNAVAILABLE) Automations.update(BydEvent.STEERING_ANGLE, s);
        } catch (Throwable t) {
            logger.warn("Failed to seed dynamics for the editor: " + t.getMessage());
        }
    }

    private static void poll() {
        BydDataCollector collector = BydDataCollector.getInstance();
        if (Automations.isEventReferenced(BydEvent.SPEED_KMPH)
                || Automations.isEventReferenced(BydEvent.SPEED_MPH)) {
            BydEvent.pollSpeed();
        }
        if (Automations.isEventReferenced(BydEvent.ACCELERATOR)) {
            int a = collector.readAccelNow();
            if (a != BydVehicleData.UNAVAILABLE) Automations.update(BydEvent.ACCELERATOR, a);
        }
        if (Automations.isEventReferenced(BydEvent.BRAKE)) {
            int b = collector.readBrakeNow();
            if (b != BydVehicleData.UNAVAILABLE) Automations.update(BydEvent.BRAKE, b);
        }
        if (Automations.isEventReferenced(BydEvent.STEERING_ANGLE)) {
            int s = collector.readSteeringNow();
            if (s != BydVehicleData.UNAVAILABLE) Automations.update(BydEvent.STEERING_ANGLE, s);
        }
    }
}
