package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p><b>Zero cost unless a turn automation exists.</b> Mirrors {@link NetworkEvent}
 * self-gating, but tightened one level: the poll reschedules
 * itself every tick yet does NO SDK read unless {@link Automations#isEventReferenced}
 * reports an enabled automation actually triggers on {@code turnSignal}. So the fast
 * cadence is paid ONLY while a turn-signal automation is configured — an idle feature
 * costs just a parked scheduler thread ticking a cheap O(small) map check. The debounce
 * / on-hold semantics live in {@link BydEvent#publishTurnFromPoll}, shared with the
 * previous snapshot path so the edge behaviour is identical, just sampled faster.
 */
public final class TurnSignalEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "TurnSignalEvent");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    // Fast enough that a turn-signal trigger feels immediate (the lamp blinks ~1.5 Hz,
    // so 500ms always catches a lit phase within one blink). Only paid while a
    // turn-signal automation exists (see poll()).
    private static final long POLL_MS = 500L;

    private TurnSignalEvent() {}

    public static void scheduleTurnSignalEvent() {
        ScheduledFuture<?> next = scheduler.schedule(TurnSignalEvent::poll, POLL_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    private static void poll() {
        try {
            // Gate on a real listener: only read the lamps when an enabled automation
            // actually triggers on a turn signal. isEventReferenced is a cheap map walk
            // and short-circuits to false when no automation is enabled at all.
            if (Automations.isEventReferenced(BydEvent.TURN_LEFT)
                    || Automations.isEventReferenced(BydEvent.TURN_RIGHT)) {
                BydEvent.pollTurnSignals();
            }
        } catch (Throwable t) {
            logger.error("Failed to run turn-signal event", t);
        } finally {
            scheduleTurnSignalEvent();
        }
    }
}
