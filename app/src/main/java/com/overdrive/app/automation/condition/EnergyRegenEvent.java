package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.logging.DaemonLogger;

/**
 * Publishes the energy-recuperation (regen) level — standard / high / max — into the
 * automation state at a FAST cadence so a "when regen → max" automation fires promptly.
 *
 * <p><b>Why a dedicated fast poll.</b> Regen level was previously read only inside the
 * telemetry snapshot ({@link BydEvent#bydEvent}), which is built on the ~5s ACC-on poll
 * (and 90s parked), so a regen-level change lagged the driver by up to ~5s (the reported
 * "2-4s trigger delay"). This poll reads the same live SDK getter
 * ({@code BydDataCollector.getEnergyFeedback}) directly at a fast cadence and publishes
 * on value transitions, exactly like {@link TurnSignalEvent} / {@link DynamicsEvent}.
 *
 * <p><b>Zero cost unless such an automation exists.</b> The task is scheduled only while an
 * enabled automation references {@code energyRegen}.
 *
 * <p><b>500ms cadence (not 250ms).</b> Regen strength is a user-initiated setting, not a
 * sub-second gesture like the pedals, so two reads per second stays responsive without polling
 * it at the dynamics rate.
 */
public final class EnergyRegenEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    static final long POLL_MS = 500L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "energy regen",
            POLL_MS,
            () -> Automations.isEventReferenced(BydEvent.ENERGY_REGEN),
            EnergyRegenEvent::poll);

    private EnergyRegenEvent() {}

    public static void refresh() {
        poller.refresh();
    }

    /**
     * Read the regen level NOW and publish, for the editor's live-value hints. The poll below only
     * reads while an enabled rule references the key, and nothing else publishes energyRegen — so
     * in the editor it had no publisher at all and read "not reported yet on this car".
     * Store-only while nothing is enabled (Automations.update forces {@code fire = false}).
     */
    public static void seedForEditor() {
        try {
            String word = regenWord(BydDataCollector.getInstance().getEnergyFeedback());
            if (word != null) Automations.update(BydEvent.ENERGY_REGEN, word);
        } catch (Throwable t) {
            logger.warn("Failed to seed energyRegen for the editor: " + t.getMessage());
        }
    }

    private static void poll() {
        int regen = BydDataCollector.getInstance().getEnergyFeedback();
        String word = regenWord(regen);
        if (word != null) Automations.update(BydEvent.ENERGY_REGEN, word);
    }

    /** Map the app-level regen level (0/1/2) to a word, or null if unavailable (-1 → skip,
     *  leaving the event unseeded so no spurious edge). Mirrors the daemon's app-level map. */
    private static String regenWord(int level) {
        switch (level) {
            case 0:  return "standard";
            case 1:  return "high";
            case 2:  return "max";
            default: return null;
        }
    }
}
