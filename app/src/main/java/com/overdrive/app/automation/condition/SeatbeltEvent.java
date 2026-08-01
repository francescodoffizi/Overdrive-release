package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the per-seat seatbelt buckled/unbuckled state — and the per-seat OCCUPANCY state,
 * which comes off the same safety-belt device — into the automation state at a FAST cadence so
 * a "when the driver buckles" / "when the passenger seat becomes occupied" automation fires
 * promptly. The two signals are gated independently, so a rule on one never pays for the other.
 *
 * <p><b>Why a dedicated fast poll.</b> The seatbelt state otherwise rides only the main
 * telemetry poll ({@link BydEvent#bydEvent}), which builds every ~5s while stationary —
 * and buckling happens while parked/stopped. So the trigger lagged the buckle by up to
 * ~5s (the reported "takes 2-3 seconds"). This poll reads the same reliable
 * {@code getSafetyBeltStatus(area)} getter the telemetry-recording overlay uses, directly
 * on the daemon at a fast cadence, and publishes on change. Exact mirror of
 * {@link TurnSignalEvent}.
 *
 * <p><b>Zero cost unless a seatbelt automation exists.</b> The poll reschedules itself
 * every tick but does NO SDK read unless {@link Automations#isEventReferenced} reports an
 * enabled automation actually triggers on {@code seatbelt} (either seat). So the fast
 * cadence is paid ONLY while a seatbelt automation is configured — an idle feature costs
 * just a parked scheduler thread ticking a cheap O(small) map check. The sanitize /
 * de-glitch semantics live in {@link BydEvent#pollSeatbelts} (shared read with the 5s
 * poll), so the edge behaviour is identical, just sampled faster.
 */
public final class SeatbeltEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "SeatbeltEvent");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    // Fast enough that a buckle trigger feels immediate, but a belt is a slow, discrete
    // event (buckle once and stay), so 500ms is ample and matches the turn-signal poll.
    // Only paid while a seatbelt automation exists (see poll()).
    private static final long POLL_MS = 500L;

    private SeatbeltEvent() {}

    public static void scheduleSeatbeltEvent() {
        ScheduledFuture<?> next = scheduler.schedule(SeatbeltEvent::poll, POLL_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    private static void poll() {
        try {
            // Gate on a real listener: only read the belts when an enabled automation
            // actually triggers on a seatbelt. isEventReferenced is a cheap map walk and
            // short-circuits to false when no automation is enabled at all.
            if (Automations.isEventReferenced(BydEvent.SEATBELT_DRIVER)
                    || Automations.isEventReferenced(BydEvent.SEATBELT_PASSENGER)) {
                BydEvent.pollSeatbelts();
            }
            // Seat occupancy rides this poller too (same safety-belt device, same cadence),
            // separately gated so an occupancy rule doesn't pay for the belt reads or vice
            // versa. Occupancy otherwise only rode the telemetry snapshot, which is 90s while
            // parked — and getting in/out of a parked car is precisely the parked case.
            if (Automations.isEventReferenced(BydEvent.OCCUPANT_PASSENGER)) {
                BydEvent.pollOccupants();
            }
            // Inferred DRIVER presence (reminder mask + driver belt) — separately gated so it
            // costs nothing unless a driver-occupancy rule exists. Positive-only; see
            // BydEvent.pollDriverOccupant.
            if (Automations.isEventReferenced(BydEvent.OCCUPANT_DRIVER)) {
                BydEvent.pollDriverOccupant();
            }
        } catch (Throwable t) {
            logger.error("Failed to run seatbelt event", t);
        } finally {
            scheduleSeatbeltEvent();
        }
    }
}
