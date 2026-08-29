package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes blind-spot, rear-cross-traffic, and door-open warning families as
 * distinct per-side automation states.
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
 * <p><b>Zero cost unless a blind-spot automation exists.</b> The fallback task is absent
 * while neither side is referenced. The instant callback also returns before touching state
 * when its side has no enabled reference.
 */
public final class BlindSpotEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");

    // A blind-spot hazard is a fast-moving situation, so sample at the same rate
    // as the other safety-relevant inputs. Only paid while a blind-spot
    // automation exists (see poll()).
    private static final long POLL_MS = 250L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "blind spot",
            POLL_MS,
            BlindSpotEvent::referenced,
            BlindSpotEvent::sample);

    // How long an alert holds "on" after the last warning. Must exceed the poll
    // interval by a wide margin so the hold spans several ticks; a few seconds
    // also matches how long the hazard itself realistically persists (a
    // overtaking vehicle clearing the blind spot). Too short strobes the event,
    // too long lags the "clear" edge.
    private static final long ALERT_HOLD_MS = 4000L;

    // Per-side clock of the last observed alert. 0 = never alerted.
    private static final AtomicLong lastLeftAlertMs = new AtomicLong(0);
    private static final AtomicLong lastRightAlertMs = new AtomicLong(0);
    private static final AtomicLong lastRctaLeftAlertMs = new AtomicLong(0);
    private static final AtomicLong lastRctaRightAlertMs = new AtomicLong(0);
    private static final AtomicLong lastDowLeftAlertMs = new AtomicLong(0);
    private static final AtomicLong lastDowRightAlertMs = new AtomicLong(0);
    // Whether each side is currently published as "on", so the hold expiry only
    // publishes the "off" edge once instead of every tick.
    private static final AtomicBoolean leftOn = new AtomicBoolean(false);
    private static final AtomicBoolean rightOn = new AtomicBoolean(false);
    private static final AtomicBoolean rctaLeftOn = new AtomicBoolean(false);
    private static final AtomicBoolean rctaRightOn = new AtomicBoolean(false);
    private static final AtomicBoolean dowLeftOn = new AtomicBoolean(false);
    private static final AtomicBoolean dowRightOn = new AtomicBoolean(false);
    // Whether a readable value has ever been seen. Until then nothing is
    // published: an unreadable sensor must not assert "clear", and the automation
    // layer ignores the first transition out of an unseeded state, so seeding an
    // explicit "off" is what makes the FIRST real alert fire.
    private static final AtomicBoolean seeded = new AtomicBoolean(false);

    private BlindSpotEvent() {}

    private static boolean referenced() {
        return Automations.isEventReferenced(BydEvent.BLIND_SPOT_LEFT)
                || Automations.isEventReferenced(BydEvent.BLIND_SPOT_RIGHT)
                || Automations.isEventReferenced(BydEvent.REAR_CROSS_TRAFFIC_LEFT)
                || Automations.isEventReferenced(BydEvent.REAR_CROSS_TRAFFIC_RIGHT)
                || Automations.isEventReferenced(BydEvent.DOOR_OPEN_WARNING_LEFT)
                || Automations.isEventReferenced(BydEvent.DOOR_OPEN_WARNING_RIGHT);
    }

    public static void refresh() {
        poller.refresh();
    }

    /**
     * Instant entry point, called from the ADAS event callback when a warning for
     * one side arrives. Cheap and non-blocking: it stamps the hold clock and
     * publishes "on" — no SDK read, no scheduling.
     */
    public static void onAlert(boolean left) {
        onAlert(left
                ? com.overdrive.app.byd.BydDataCollector.BS_LEFT_BIT
                : com.overdrive.app.byd.BydDataCollector.BS_RIGHT_BIT);
    }

    public static void onAlert(int warningBit) {
        try {
            EventData key;
            AtomicLong lastAlert;
            AtomicBoolean on;
            switch (warningBit) {
                case com.overdrive.app.byd.BydDataCollector.BS_LEFT_BIT:
                    key = BydEvent.BLIND_SPOT_LEFT;
                    lastAlert = lastLeftAlertMs;
                    on = leftOn;
                    break;
                case com.overdrive.app.byd.BydDataCollector.BS_RIGHT_BIT:
                    key = BydEvent.BLIND_SPOT_RIGHT;
                    lastAlert = lastRightAlertMs;
                    on = rightOn;
                    break;
                case com.overdrive.app.byd.BydDataCollector.RCTA_LEFT_BIT:
                    key = BydEvent.REAR_CROSS_TRAFFIC_LEFT;
                    lastAlert = lastRctaLeftAlertMs;
                    on = rctaLeftOn;
                    break;
                case com.overdrive.app.byd.BydDataCollector.RCTA_RIGHT_BIT:
                    key = BydEvent.REAR_CROSS_TRAFFIC_RIGHT;
                    lastAlert = lastRctaRightAlertMs;
                    on = rctaRightOn;
                    break;
                case com.overdrive.app.byd.BydDataCollector.DOW_LEFT_BIT:
                    key = BydEvent.DOOR_OPEN_WARNING_LEFT;
                    lastAlert = lastDowLeftAlertMs;
                    on = dowLeftOn;
                    break;
                case com.overdrive.app.byd.BydDataCollector.DOW_RIGHT_BIT:
                    key = BydEvent.DOOR_OPEN_WARNING_RIGHT;
                    lastAlert = lastDowRightAlertMs;
                    on = dowRightOn;
                    break;
                default:
                    return;
            }
            if (!Automations.isEventReferenced(key)) return;
            long now = System.currentTimeMillis();
            // publish() seeds the baseline itself, which matters when an alert beats
            // the poll to it: the automation layer ignores a transition out of an
            // unseeded state, so publishing "on" with nothing stored would silently
            // swallow the very first alert — the event that matters most.
            lastAlert.set(now);
            publish(key, on, true);
        } catch (Throwable t) {
            logger.error("Failed to handle ADAS warning alert", t);
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
            Automations.update(BydEvent.REAR_CROSS_TRAFFIC_LEFT, "off");
            Automations.update(BydEvent.REAR_CROSS_TRAFFIC_RIGHT, "off");
            Automations.update(BydEvent.DOOR_OPEN_WARNING_LEFT, "off");
            Automations.update(BydEvent.DOOR_OPEN_WARNING_RIGHT, "off");
        }
    }

    /**
     * Release a held "on" once its window has elapsed, without reading the SDK.
     * Safety net for the instant path, which publishes the alert edge but relies
     * on a timer for the clear edge.
     */
    private static void expireHolds() {
        if (!leftOn.get() && !rightOn.get()
                && !rctaLeftOn.get() && !rctaRightOn.get()
                && !dowLeftOn.get() && !dowRightOn.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        reconcile(BydEvent.BLIND_SPOT_LEFT, false, lastLeftAlertMs, leftOn, now);
        reconcile(BydEvent.BLIND_SPOT_RIGHT, false, lastRightAlertMs, rightOn, now);
        reconcile(BydEvent.REAR_CROSS_TRAFFIC_LEFT, false,
                lastRctaLeftAlertMs, rctaLeftOn, now);
        reconcile(BydEvent.REAR_CROSS_TRAFFIC_RIGHT, false,
                lastRctaRightAlertMs, rctaRightOn, now);
        reconcile(BydEvent.DOOR_OPEN_WARNING_LEFT, false,
                lastDowLeftAlertMs, dowLeftOn, now);
        reconcile(BydEvent.DOOR_OPEN_WARNING_RIGHT, false,
                lastDowRightAlertMs, dowRightOn, now);
    }

    /**
     * Read the live warning registers and reconcile both sides against the hold.
     * Re-guards on {@code isDisabled} so a race that disables the last automation
     * mid-tick is a no-op.
     */
    /**
     * Read the warning registers NOW and publish, for the editor's live-value hints. The poll below
     * only samples while an enabled rule references the signal, so in the editor blindSpot had no
     * publisher and read "not reported yet on this car". Store-only while nothing is enabled
     * (Automations.update forces {@code fire = false}), so it cannot raise a false alert.
     */
    public static void seedForEditor() {
        try { sample(); } catch (Throwable ignored) { /* best-effort hint */ }
    }

    private static void sample() {
        // Seed-aware like the other samplers: with nothing enabled this returned before publishing,
        // so blindSpot read "not reported yet on this car" in the editor. Storing is not firing —
        // Automations.update forces fire=false while disabled.
        if (Automations.isDisabled() && !Automations.editorSeedActive()) return;
        int packed;
        try {
            packed = com.overdrive.app.byd.BydDataCollector.getInstance().readAdasWarningsNow();
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
        reconcile(BydEvent.REAR_CROSS_TRAFFIC_LEFT,
                (packed & com.overdrive.app.byd.BydDataCollector.RCTA_LEFT_BIT) != 0,
                lastRctaLeftAlertMs, rctaLeftOn, now);
        reconcile(BydEvent.REAR_CROSS_TRAFFIC_RIGHT,
                (packed & com.overdrive.app.byd.BydDataCollector.RCTA_RIGHT_BIT) != 0,
                lastRctaRightAlertMs, rctaRightOn, now);
        reconcile(BydEvent.DOOR_OPEN_WARNING_LEFT,
                (packed & com.overdrive.app.byd.BydDataCollector.DOW_LEFT_BIT) != 0,
                lastDowLeftAlertMs, dowLeftOn, now);
        reconcile(BydEvent.DOOR_OPEN_WARNING_RIGHT,
                (packed & com.overdrive.app.byd.BydDataCollector.DOW_RIGHT_BIT) != 0,
                lastDowRightAlertMs, dowRightOn, now);
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
        rctaLeftOn.set(false);
        rctaRightOn.set(false);
        dowLeftOn.set(false);
        dowRightOn.set(false);
        lastLeftAlertMs.set(0);
        lastRightAlertMs.set(0);
        lastRctaLeftAlertMs.set(0);
        lastRctaRightAlertMs.set(0);
        lastDowLeftAlertMs.set(0);
        lastDowRightAlertMs.set(0);
    }
}
