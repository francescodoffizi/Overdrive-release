package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;

/**
 * Publishes the left/right turn-indicator on/off state into the automation state at a
 * FAST cadence so a "when I signal left" automation fires promptly.
 *
 * <p><b>Why a dedicated fast poll.</b> The other vehicle events piggy-back on the
 * telemetry snapshot ({@link BydEvent#bydEvent}), which is built sub-second while
 * DRIVING but only every ~5s while STATIONARY (the 250ms fast-dynamics poll does not
 * call {@code build()}). A turn signal is most often used while stopped or crawling
 * (waiting to turn / pulling out), so sampling it on the 5s stationary cadence meant
 * the trigger lagged the driver's flick by up to ~5s — the reported "long delay". The
 * blind-spot overlay already reads the lamps at 250ms, but that runs in the APP
 * process and never feeds the (daemon-side) automation state. This poll closes that
 * gap by reading the same reliable combined getter ({@code readTurnNow}) directly on
 * the daemon at a fast cadence.
 *
 * <p><b>Zero cost unless a turn automation exists.</b> The scheduled task exists only
 * while an enabled rule references a turn or hazard signal. Disabling the last reference
 * cancels the task rather than leaving a scheduler waking in the background.
 */
public final class TurnSignalEvent {
    static final long POLL_MS = 250L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "turn signal",
            POLL_MS,
            TurnSignalEvent::referenced,
            BydEvent::pollTurnSignals);

    private TurnSignalEvent() {}

    private static boolean referenced() {
        return Automations.isEventReferenced(BydEvent.TURN_LEFT)
                || Automations.isEventReferenced(BydEvent.TURN_RIGHT)
                || Automations.isEventReferenced(BydEvent.LIGHTS_HAZARD);
    }

    public static void refresh() {
        poller.refresh();
    }
}
