package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes seat-heat / seat-cool (per seat) and high/low beam into the automation state at a
 * FAST cadence so a "when driver seat cooling turns off" (the reported seat-cooling ELSE) or
 * "when high beam on" trigger fires promptly.
 *
 * <p><b>Why a dedicated fast poll.</b> These signals otherwise ride the telemetry snapshot
 * ({@link BydEvent#bydEvent} → collectSettings / collectLight), built every ~5s. The settings
 * HAL callback (onSettingsCallback) only refreshes seat climate when the HAL happens to push a
 * {@code SET_*_SEAT_*_STATE} event, and the light callback refreshes ONLY the DRL — so a seat
 * climate or beam change made from the car's own UI lagged up to ~5s. This poll reads each
 * signal via its dedicated collector getter ({@link
 * com.overdrive.app.byd.BydDataCollector#readSeatClimateNow(boolean, int)} /
 * {@link com.overdrive.app.byd.BydDataCollector#readBeamNow(boolean)}) and publishes through the
 * SAME {@link BydEvent} event keys, so edge/dedup semantics match the snapshot path exactly —
 * just sampled faster.
 *
 * <p><b>Zero cost unless one of these automations exists.</b> Mirrors {@link DriveModeEvent} and
 * {@link SeatbeltEvent}: the poll reschedules itself every tick but does NO SDK read unless an
 * enabled automation triggers on one of the six signals (the outer gate here, plus a per-signal
 * {@link Automations#isEventReferenced} inside {@link BydEvent#pollClimate}, so a rule using only
 * one seat never reads the others' getters). So the fast cadence is paid ONLY while such an
 * automation is configured — an idle feature costs just a parked scheduler thread ticking a
 * cheap map check.
 */
public final class ClimateEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "ClimateEvent");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    // 1s: seat-climate and beam changes are deliberate, low-frequency actions, so 1s feels
    // prompt without hammering the SDK (matches DriveModeEvent). Only paid while one of these
    // automations exists (see poll()).
    private static final long POLL_MS = 1000L;

    private ClimateEvent() {}

    public static void scheduleClimateEvent() {
        ScheduledFuture<?> next = scheduler.schedule(ClimateEvent::poll, POLL_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    private static void poll() {
        try {
            // Outer gate: skip the whole read unless at least one of the six signals is
            // referenced by an enabled automation. isEventReferenced short-circuits to false
            // when no automation is enabled at all; pollClimate re-checks each signal so a
            // rule using only one never reads the others' SDK getters.
            if (Automations.isEventReferenced(BydEvent.SEAT_COOL_DRIVER)
                    || Automations.isEventReferenced(BydEvent.SEAT_COOL_PASSENGER)
                    || Automations.isEventReferenced(BydEvent.SEAT_HEAT_DRIVER)
                    || Automations.isEventReferenced(BydEvent.SEAT_HEAT_PASSENGER)
                    || Automations.isEventReferenced(BydEvent.LIGHTS_HIGH_BEAM)
                    || Automations.isEventReferenced(BydEvent.LIGHTS_LOW_BEAM)) {
                BydEvent.pollClimate();
            }
        } catch (Throwable t) {
            logger.error("Failed to run climate event", t);
        } finally {
            scheduleClimateEvent();
        }
    }
}
