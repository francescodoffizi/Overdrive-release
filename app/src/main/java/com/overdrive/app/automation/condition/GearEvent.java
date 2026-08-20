package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;

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
 * <p><b>Zero cost unless a gear automation exists.</b> Its scheduled task is created only while
 * an enabled rule references gear and is cancelled when the last reference is disabled.
 */
public final class GearEvent {
    static final long POLL_MS = 250L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "gear",
            POLL_MS,
            () -> Automations.isEventReferenced(BydEvent.GEAR),
            BydEvent::pollGear);

    private GearEvent() {}

    public static void refresh() {
        poller.refresh();
    }
}
