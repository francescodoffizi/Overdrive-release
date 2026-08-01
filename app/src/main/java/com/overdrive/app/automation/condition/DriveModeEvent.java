package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><b>Zero cost unless a drive-mode automation exists.</b> Mirrors {@link TurnSignalEvent}:
 * the poll reschedules itself every tick but does NO SDK read unless
 * {@link Automations#isEventReferenced} reports an enabled automation actually triggers on
 * {@code driveMode}. So the fast cadence is paid ONLY while a drive-mode automation is
 * configured — an idle feature costs just a parked scheduler thread ticking a cheap map check.
 */
public final class DriveModeEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "DriveModeEvent");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    // 1s: a drive-mode selection is a deliberate, low-frequency action, so 1s feels prompt
    // without hammering the SDK. Only paid while a drive-mode automation exists (see poll()).
    private static final long POLL_MS = 1000L;

    private DriveModeEvent() {}

    public static void scheduleDriveModeEvent() {
        ScheduledFuture<?> next = scheduler.schedule(DriveModeEvent::poll, POLL_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    private static void poll() {
        try {
            // Gate on a real listener: only read the axis when an enabled automation actually
            // triggers on drive mode. isEventReferenced is a cheap map walk and short-circuits
            // to false when no automation is enabled at all.
            if (Automations.isEventReferenced(BydEvent.DRIVE_MODE)) {
                BydEvent.pollDriveMode();
            }
        } catch (Throwable t) {
            logger.error("Failed to run drive-mode event", t);
        } finally {
            scheduleDriveModeEvent();
        }
    }
}
