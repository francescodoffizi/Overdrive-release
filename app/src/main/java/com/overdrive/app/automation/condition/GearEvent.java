package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the current GEAR (P/R/N/D/M/S) into the automation state at a FAST cadence so a
 * "when gear → R" trigger / "only while in P" condition fires promptly.
 *
 * <p><b>Why a dedicated fast poll.</b> Gear otherwise rides the telemetry snapshot
 * ({@link BydEvent#bydEvent} → collectGearbox), which is built every ~5s and only while ACC is
 * on — and gear changes during parking maneuvers (R↔D↔P) are exactly when a prompt trigger
 * matters, so the trigger lagged the shift by up to ~5s. The gearbox HAL LISTENER can't be used
 * to close this gap: registering it invokes {@code BYDAutoGearboxDevice.learningEPB()}, which
 * crashes as shell-UID and cascades into a daemon restart loop (see
 * {@code BydDataCollector.registerAllListeners}). This poll instead reads the SAME safe getter
 * the 5s poll uses ({@code getGearboxAutoModeType} via
 * {@link com.overdrive.app.byd.BydDataCollector#readGearNow()}) directly at a fast cadence and
 * publishes through the same {@link BydEvent#GEAR} path, so the edge/dedup semantics are
 * identical — just sampled faster, and without the crashing listener.
 *
 * <p><b>Zero cost unless a gear automation exists.</b> Mirrors {@link DriveModeEvent}: the poll
 * reschedules itself every tick but does NO SDK read unless {@link Automations#isEventReferenced}
 * reports an enabled automation actually triggers on {@code gear}. So the fast cadence is paid
 * ONLY while a gear automation is configured — an idle feature costs just a parked scheduler
 * thread ticking a cheap map check.
 */
public final class GearEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "GearEvent");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<ScheduledFuture<?>> active = new AtomicReference<>();

    // 500ms: a gear shift is a discrete, deliberate action; 500ms feels immediate without
    // hammering the SDK, and matches the seatbelt poll. Only paid while a gear automation
    // exists (see poll()).
    private static final long POLL_MS = 500L;

    private GearEvent() {}

    public static void scheduleGearEvent() {
        ScheduledFuture<?> next = scheduler.schedule(GearEvent::poll, POLL_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = active.getAndSet(next);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
    }

    private static void poll() {
        try {
            // Gate on a real listener: only read gear when an enabled automation actually
            // triggers on it. isEventReferenced short-circuits to false when no automation
            // is enabled at all.
            if (Automations.isEventReferenced(BydEvent.GEAR)) {
                BydEvent.pollGear();
            }
        } catch (Throwable t) {
            logger.error("Failed to run gear event", t);
        } finally {
            scheduleGearEvent();
        }
    }
}
