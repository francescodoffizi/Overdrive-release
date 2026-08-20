package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the local time-of-day (minutes since midnight) and day-of-week to the
 * automation state once a minute so time/day conditions can be evaluated. The task
 * re-schedules itself aligned to the top of the next minute (+1s) so it never fires
 * before the minute actually rolls over.
 */
public class TimeEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledThreadPoolExecutor scheduler =
            new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "AutomationTime");
                thread.setDaemon(true);
                return thread;
            });
    private static ScheduledFuture<?> active;

    static {
        scheduler.setRemoveOnCancelPolicy(true);
        // Longer than the maximum next-minute delay so an active delayed task always retains a
        // worker; after cancellation the idle worker still exits without periodic wakeups.
        scheduler.setKeepAliveTime(75L, TimeUnit.SECONDS);
        scheduler.allowCoreThreadTimeOut(true);
    }

    private TimeEvent() {}

    private static boolean referenced() {
        return Automations.isEventReferenced(BydEvent.TIME)
                || Automations.isEventReferenced(BydEvent.DAY)
                || Automations.isEventReferenced(BydEvent.DAY_OF_MONTH)
                || Automations.isEventReferenced(BydEvent.MONTH)
                || Automations.isEventReferenced(BydEvent.SUN_PHASE);
    }

    /** Start or cancel the minute-aligned task according to the current automation config. */
    public static synchronized void refresh() {
        if (!referenced()) {
            if (active != null) active.cancel(false);
            active = null;
            return;
        }
        if (active != null && !active.isDone() && !active.isCancelled()) return;

        // Establish the current minute/day as a baseline. Saving a rule must not look like
        // midnight, sunrise, or a day change merely because this poller was previously stopped.
        Automations.runSilentSeed(TimeEvent::publishNow);
        scheduleNextLocked();
    }

    private static void scheduleNextLocked() {
        // Compute the next-minute boundary against LocalDateTime, NOT LocalTime: at 23:59
        // LocalTime.plusMinutes(1) wraps to 00:00 and Duration.between(now, 00:00) on a
        // LocalTime goes BACKWARDS (~ -86340s), which schedule() treats as "run now" and
        // would busy-loop the reschedule chain for the whole final minute of the day.
        // LocalDateTime spans the date boundary so the delay stays correct across midnight.
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = now.plusMinutes(1).truncatedTo(ChronoUnit.MINUTES);
        // Run the task 1 second after the minute to ensure it doesn't run before the minute changes.
        // Clamp to >=1s as a final guard against any clock skew yielding a non-positive delay.
        long delay = Math.max(1, Duration.between(now, nextRun).getSeconds() + 1);

        active = scheduler.schedule(TimeEvent::sendEvent, delay, TimeUnit.SECONDS);
    }

    private static void sendEvent() {
        try {
            if (referenced()) publishNow();
        } catch (Exception e) {
            logger.error("Failed to run time event", e);
        } finally {
            synchronized (TimeEvent.class) {
                active = null;
            }
            refresh();
        }
    }

    public static void seedForEditor() {
        try {
            publishNow();
        } catch (Throwable t) {
            logger.warn("Failed to seed time signals: " + t.getMessage());
        }
    }

    private static void publishNow() {
        LocalDateTime now = LocalDateTime.now();
        Automations.update(BydEvent.TIME, now.get(ChronoField.MINUTE_OF_DAY));
        Automations.update(BydEvent.DAY, now.getDayOfWeek().name().toLowerCase());
        Automations.update(BydEvent.DAY_OF_MONTH, now.getDayOfMonth());
        Automations.update(BydEvent.MONTH, String.valueOf(now.getMonthValue()));
        publishSunPhase(now);
    }

    /**
     * Publish sunPhase = "day"/"night" using the current GPS fix + local date. The
     * automation engine fires a trigger on the day→night (sunset) or night→day
     * (sunrise) transition. Skipped entirely without a location fix so we never
     * manufacture a phase from a null island (0,0) reading.
     */
    private static void publishSunPhase(LocalDateTime now) {
        try {
            com.overdrive.app.monitor.GpsMonitor gps = com.overdrive.app.monitor.GpsMonitor.getInstance();
            if (gps == null || !gps.hasLocation()) return;
            double lat = gps.getLatitude();
            double lon = gps.getLongitude();
            SolarCalculator.SunTimes st = SolarCalculator.compute(
                    now.toLocalDate(), lat, lon, java.time.ZoneId.systemDefault());
            if (st == null) return;
            String phase;
            if (st.alwaysUp) {
                phase = "day";
            } else if (st.alwaysDown) {
                phase = "night";
            } else {
                int minuteOfDay = now.get(ChronoField.MINUTE_OF_DAY);
                // Daytime = at/after sunrise and before sunset.
                phase = (minuteOfDay >= st.sunriseMinute && minuteOfDay < st.sunsetMinute)
                        ? "day" : "night";
            }
            Automations.update(BydEvent.SUN_PHASE, phase);
        } catch (Throwable t) {
            // Solar calc / GPS glitch — just skip this tick's phase publish.
        }
    }
}
