package com.overdrive.app.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

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
}
