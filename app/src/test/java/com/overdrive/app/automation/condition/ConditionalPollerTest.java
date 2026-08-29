package com.overdrive.app.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies that automation polling consumes no periodic work while unreferenced. */
public class ConditionalPollerTest {

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
    public void taskExistsOnlyWhileReferenced() throws Exception {
        AtomicBoolean referenced = new AtomicBoolean(false);
        AtomicInteger runs = new AtomicInteger();
        ConditionalPoller poller = new ConditionalPoller(
                "lifecycle test", 20L, referenced::get, runs::incrementAndGet);

        poller.refresh();
        assertFalse(poller.isScheduledForTest());
        Thread.sleep(60L);
        assertEquals(0, runs.get());

        referenced.set(true);
        poller.refresh();
        assertTrue(poller.isScheduledForTest());
        awaitAtLeastOneRun(runs);

        referenced.set(false);
        poller.refresh();
        assertFalse(poller.isScheduledForTest());
        // Let a tick already in flight finish, then verify no periodic work remains.
        Thread.sleep(40L);
        int stableRuns = runs.get();
        Thread.sleep(80L);
        assertEquals(stableRuns, runs.get());
    }

    @Test
    public void repeatedRefreshKeepsTheExistingScheduledTask() {
        AtomicBoolean referenced = new AtomicBoolean(true);
        ConditionalPoller poller = new ConditionalPoller(
                "refresh idempotency test", 20L, referenced::get, () -> {});

        poller.refresh();
        long scheduledGeneration = poller.generationForTest();
        assertTrue(poller.isScheduledForTest());

        poller.refresh();
        poller.refresh();

        assertTrue(poller.isScheduledForTest());
        assertEquals(scheduledGeneration, poller.generationForTest());

        referenced.set(false);
        poller.refresh();
        assertFalse(poller.isScheduledForTest());
    }

    private static void awaitAtLeastOneRun(AtomicInteger runs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000L;
        while (runs.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("referenced poller did not run", runs.get() > 0);
    }
}
