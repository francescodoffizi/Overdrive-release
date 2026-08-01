package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the radar blind-spot / lane-change / cross-traffic ALERT state into
 * the automation state, per side, as a stable on/off edge.
 *
 * <p><b>What this is not.</b> This is the OEM's radar warning — "is there a
 * vehicle beside me right now" — not the side-camera blind-spot overlay. The two
 * are unrelated: the overlay is a video feature in the app process, this is a
 * daemon-side vehicle signal.
 *
 * <p><b>Belt and braces, because the raw signal is a pulse.</b> The HAL reports
 * these warnings as momentary events, not as a level that stays asserted for the
 * duration of the hazard. Two paths therefore feed the same state:
 * <ul>
 *   <li><b>Instant</b> — the ADAS {@code onDataEventChanged} callback calls
 *       {@link #onAlert} the moment a warning arrives. This is what makes the
 *       trigger feel immediate.</li>
 *   <li><b>Poll</b> — a fast self-gated poll reads the same registers, covering
 *       trims where the filtered event registration isn't honoured. Without it a
 *       firmware that never delivers the event would silently never fire.</li>
 * </ul>
 *
 * <p><b>Alert hold.</b> A pulse can't be published raw: "on" would be followed by
 * "off" on the very next tick, so a rule would see a strobe rather than a
 * warning. Any alert therefore holds "on" for {@link #ALERT_HOLD_MS}, and only a
 * genuinely quiet window publishes "off". The hold is refreshed by every new
 * alert, so a sustained hazard stays "on" throughout.
 *
 * <p><b>Zero cost unless a blind-spot automation exists.</b> Same self-gating as
 * the other fast pollers: the poll reschedules itself but performs no SDK read
 * unless {@link Automations#isEventReferenced} reports an enabled automation that
 * actually uses the signal. The instant path costs nothing either way — it is a
 * branch inside an ADAS callback that already fires.
 */
public final class BlindSpotEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "BlindSpotEvent");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    // A blind-spot hazard is a fast-moving situation, so sample at the same rate
    // as the other safety-relevant inputs. Only paid while a blind-spot
    // automation exists (see poll()).
    private static final long POLL_MS = 250L;

    // How long an alert holds "on" after the last warning. Must exceed the poll
    // interval by a wide margin so the hold spans several ticks; a few seconds
    // also matches how long the hazard itself realistically persists (a
    // overtaking vehicle clearing the blind spot). Too short strobes the event,
    // too long lags the "clear" edge.
    private static final long ALERT_HOLD_MS = 4000L;

    // Per-side clock of the last observed alert. 0 = never alerted.
    private static final AtomicLong lastLeftAlertMs = new AtomicLong(0);
    private static final AtomicLong lastRightAlertMs = new AtomicLong(0);
    // Whether each side is currently published as "on", so the hold expiry only
    // publishes the "off" edge once instead of every tick.
    private static final AtomicBoolean leftOn = new AtomicBoolean(false);
    private static final AtomicBoolean rightOn = new AtomicBoolean(false);
    // Whether a readable value has ever been seen. Until then nothing is
    // published: an unreadable sensor must not assert "clear", and the automation
    // layer ignores the first transition out of an unseeded state, so seeding an
    // explicit "off" is what makes the FIRST real alert fire.
    private static final AtomicBoolean seeded = new AtomicBoolean(false);

    private BlindSpotEvent() {}

    public static void scheduleBlindSpotEvent() {
        ScheduledFuture<?> next = scheduler.schedule(BlindSpotEvent::poll, POLL_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    /**
     * Instant entry point, called from the ADAS event callback when a warning for
     * one side arrives. Cheap and non-blocking: it stamps the hold clock and
     * publishes "on" — no SDK read, no scheduling.
     */
    public static void onAlert(boolean left) {
        try {
            if (Automations.isDisabled()) return;
            long now = System.currentTimeMillis();
            // publish() seeds the baseline itself, which matters when an alert beats
            // the poll to it: the automation layer ignores a transition out of an
            // unseeded state, so publishing "on" with nothing stored would silently
            // swallow the very first alert — the event that matters most.
            if (left) {
                lastLeftAlertMs.set(now);
                publish(BydEvent.BLIND_SPOT_LEFT, leftOn, true);
            } else {
                lastRightAlertMs.set(now);
                publish(BydEvent.BLIND_SPOT_RIGHT, rightOn, true);
            }
        } catch (Throwable t) {
            logger.error("Failed to handle blind-spot alert", t);
        }
    }

    /**
     * Publish the "clear" baseline once, so the state map holds a known previous
     * value before any alert edge.
     *
     * <p>Synchronized, not a bare CAS. A CAS only guarantees that one thread
     * publishes — it does NOT stop a second thread from racing ahead and
     * publishing "on" while the seeding thread is still between its CAS and its
     * update. That interleaving stores "on" against a null previous value, which
     * the automation layer discards as seeding, so the first genuine alert would
     * be silently swallowed and then followed by a phantom "off". Holding the lock
     * across the publish makes the seed atomic with respect to the alert paths,
     * which both call this first.
     */
    private static synchronized void seedIfNeeded() {
        if (seeded.compareAndSet(false, true)) {
            // Published INSIDE the lock, unlike an alert edge. This is what
            // establishes the ordering: a thread that wants to publish "on" must
            // take the same monitor, so it cannot get there until these two
            // updates have landed. It happens once per process and touches only an
            // in-memory map, so the brief hold is not a stall risk.
            Automations.update(BydEvent.BLIND_SPOT_LEFT, "off");
            Automations.update(BydEvent.BLIND_SPOT_RIGHT, "off");
        }
    }

    private static void poll() {
        try {
            // Gate on a real listener: only touch the SDK when an enabled
            // automation actually references the blind-spot signal.
            if (Automations.isEventReferenced(BydEvent.BLIND_SPOT_LEFT)
                    || Automations.isEventReferenced(BydEvent.BLIND_SPOT_RIGHT)) {
                sample();
            } else {
                // Not referenced (or the rule was just disabled) — no SDK read.
                // Still expire any outstanding hold: the instant event path can
                // publish "on" without the poll ever having run, and if nothing
                // released it that side would stay stuck "on" forever.
                expireHolds();
            }
        } catch (Throwable t) {
            logger.error("Failed to run blind-spot event", t);
        } finally {
            scheduleBlindSpotEvent();
        }
    }

    /**
     * Release a held "on" once its window has elapsed, without reading the SDK.
     * Safety net for the instant path, which publishes the alert edge but relies
     * on a timer for the clear edge.
     */
    private static void expireHolds() {
        if (!leftOn.get() && !rightOn.get()) return;
        long now = System.currentTimeMillis();
        reconcile(BydEvent.BLIND_SPOT_LEFT, false, lastLeftAlertMs, leftOn, now);
        reconcile(BydEvent.BLIND_SPOT_RIGHT, false, lastRightAlertMs, rightOn, now);
    }

    /**
     * Read the live warning registers and reconcile both sides against the hold.
     * Re-guards on {@code isDisabled} so a race that disables the last automation
     * mid-tick is a no-op.
     */
    private static void sample() {
        if (Automations.isDisabled()) return;
        int packed;
        try {
            packed = com.overdrive.app.byd.BydDataCollector.getInstance().readBlindSpotNow();
        } catch (Throwable t) {
            // ADAS device unreachable this tick. Do NOT just return: if a side is
            // currently held "on", nothing else would ever release it and the
            // state would read "occupied" forever. Expire the hold instead, so an
            // unreadable sensor decays to "clear" rather than latching.
            expireHolds();
            return;
        }
        // -1 = unavailable. Same reasoning as above — never publish a fresh state
        // from a non-reading, but still let an outstanding hold expire.
        if (packed < 0) {
            expireHolds();
            return;
        }

        long now = System.currentTimeMillis();
        seedIfNeeded();
        reconcile(BydEvent.BLIND_SPOT_LEFT,
                (packed & com.overdrive.app.byd.BydDataCollector.BS_LEFT_BIT) != 0,
                lastLeftAlertMs, leftOn, now);
        reconcile(BydEvent.BLIND_SPOT_RIGHT,
                (packed & com.overdrive.app.byd.BydDataCollector.BS_RIGHT_BIT) != 0,
                lastRightAlertMs, rightOn, now);
    }

    /**
     * Apply the hold to one side and publish only on a real edge.
     * {@code alerting} is whether the radar is warning for this side right now.
     */
    private static void reconcile(EventData key, boolean alerting,
                                  AtomicLong lastAlert, AtomicBoolean on, long now) {
        if (alerting) {
            lastAlert.set(now);
            publish(key, on, true);
            return;
        }
        if (!on.get()) return;                       // already clear — nothing to do
        long since = lastAlert.get();
        // Still inside the hold window → keep "on" so a pulsed warning doesn't
        // flicker. Only a quiet window publishes the "off" edge.
        if (since != 0 && (now - since) < ALERT_HOLD_MS) return;
        publish(key, on, false);
    }

    /**
     * Flip one side's published state, if it isn't already there.
     *
     * <p>The whole method is serialised, and the {@link Automations#update} call is
     * INSIDE the lock on purpose. The {@code on} flag is what suppresses duplicate
     * edges, so it must never disagree with the state map. Committing the flag
     * under the lock but delivering outside it lets two writer threads — the poller
     * and the HAL callback — commit in monitor order yet deliver in wall-clock
     * order: the flag then says "on" while the map says "off", and because the flag
     * is what gates publishing, every subsequent real alert is suppressed by the
     * very flag that failed to deliver. Keeping them together is the only way the
     * pair stays coherent.
     *
     * <p>Holding the monitor across the call is safe and bounded: it is taken
     * nowhere else, nothing reachable from {@code update} re-enters this class, and
     * the work it does is an in-memory map write plus condition evaluation — actions
     * are dispatched to a worker thread, never run inline. So the HAL callback
     * thread is not exposed to action latency.
     */
    private static synchronized void publish(EventData key, AtomicBoolean on, boolean value) {
        if (Automations.isDisabled()) {
            // With automations off, update() drops the write. Clearing our flags
            // rather than leaving them asserting a state we failed to publish is
            // what keeps the pair coherent: the next enabled tick re-seeds from a
            // known baseline instead of suppressing the first alert after re-enable.
            resetState();
            return;
        }
        // Reentrant: seeds (and publishes the baseline) if this is the first
        // activity, so an alert can never precede the baseline.
        seedIfNeeded();
        if (on.get() == value) return;   // already there — no edge to publish
        on.set(value);
        Automations.update(key, value ? "on" : "off");
    }

    /** Drop all published state, so the next activity re-seeds from scratch. */
    private static synchronized void resetState() {
        seeded.set(false);
        leftOn.set(false);
        rightOn.set(false);
        lastLeftAlertMs.set(0);
        lastRightAlertMs.set(0);
    }
}
