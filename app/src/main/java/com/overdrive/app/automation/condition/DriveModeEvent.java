package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;

/**
 * Publishes the drive mode (normal/eco/sport/snow) into the automation state at a FAST
 * cadence so a "when drive mode → sport" trigger fires promptly.
 *
 * <p><b>Why a dedicated fast poll.</b> Drive mode otherwise rides the telemetry snapshot
 * ({@link BydEvent#bydEvent} → collectEnergy), which is built sub-second while DRIVING but
 * only every ~5s while STATIONARY — and drive-mode changes are most often made while
 * stopped or crawling, so the trigger lagged the driver's selection by up to ~5s (the
 * reported delay). This poll closes the gap by reading the same drive-config axis
 * ({@code getDriveConfigMode}) directly on the daemon at a fast cadence, publishing through
 * the same {@link BydEvent#DRIVE_MODE} path so the edge/dedup behaviour is identical.
 *
 * <p><b>Zero cost unless a drive-mode automation exists.</b> No periodic task exists while the
 * signal is unreferenced.
 */
public final class DriveModeEvent {
    private static final long POLL_MS = 1000L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "drive mode",
            POLL_MS,
            () -> Automations.isEventReferenced(BydEvent.DRIVE_MODE),
            BydEvent::pollDriveMode);

    private DriveModeEvent() {}

    public static void refresh() {
        poller.refresh();
    }
}
