package com.overdrive.app.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.condition.BydEvent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** A failed optional event source must not make the complete automation class unusable. */
public class AutomationStartupIsolationTest {

    @BeforeClass
    public static void configureLogger() {
        com.overdrive.app.logging.DaemonLogger.Config config =
                new com.overdrive.app.logging.DaemonLogger.Config();
        config.enableConsoleLog = false;
        config.enableFileLog = false;
        config.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(config);
    }

    @Test
    public void linkageFailureIsContainedAndLaterStartupStepsStillRun() {
        AtomicBoolean laterStepRan = new AtomicBoolean(false);

        Automations.runIsolatedStartupStep("expected test failure", () -> {
            throw new NoClassDefFoundError("optional OEM source");
        });
        Automations.runIsolatedStartupStep("later step", () -> laterStepRan.set(true));

        assertTrue(laterStepRan.get());
        assertNotNull(Automations.toJson());
        assertFalse(Automations.publishExternalEvent("unsupported-test-event", "value"));
    }

    @Test
    public void savedRuleStartupRunsAfterAllStaticHelpersAreInitialized() {
        assertTrue(Automations.startupSawInitializedStaticFieldsForTest());
    }

    @Test
    public void actionOnlyPassengerSeatbeltReferenceIsDetected() throws Exception {
        String automationId = UUID.randomUUID().toString();
        JSONObject json = new JSONObject()
                .put("triggers", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "callState")
                                .put("variables", new JSONObject())))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "waitUntilState")
                                .put("variables", new JSONObject()
                                        .put("event", "seatbelt:seat=passenger")
                                        .put("state", "buckled")
                                        .put("timeout", 1))))
                .put("name", "passenger seatbelt reference probe")
                .put("disabled", false);

        try {
            assertTrue(Automations.updateAutomation(automationId, json));
            assertTrue(Automations.isEventReferenced(BydEvent.SEATBELT_PASSENGER));
        } finally {
            Automations.deleteAutomation(automationId);
        }
    }
}
