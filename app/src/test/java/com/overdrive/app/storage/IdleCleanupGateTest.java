package com.overdrive.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Focused tests for the idle cleanup gate's decision semantics: first-pass
 * behavior, steady-state skip, dirty wake, compare-before-clear under a
 * mid-pass mutation, hourly backstop, deferred wake, and the lazy low-disk
 * probe ordering.
 */
public class IdleCleanupGateTest {

    private static final long BACKSTOP = 60 * 60 * 1000L;
    private static final IdleCleanupGate.LowDiskProbe HEALTHY = () -> false;
    private static final IdleCleanupGate.LowDiskProbe LOW = () -> true;

    @Test
    public void firstTickAlwaysRunsAPass() {
        IdleCleanupGate gate = new IdleCleanupGate(BACKSTOP);
        IdleCleanupGate.Decision d = gate.evaluate(false, 1_000L, HEALTHY);
        assertTrue(d.runPass);
        assertEquals("dirty", d.reason);
    }

    @Test
    public void steadyStateSkipsAfterCompletedPass() {
        IdleCleanupGate gate = new IdleCleanupGate(BACKSTOP);
        IdleCleanupGate.Decision first = gate.evaluate(false, 1_000L, HEALTHY);
        gate.onPassComplete(first.generationAtStart, 2_000L);

        IdleCleanupGate.Decision next = gate.evaluate(false, 32_000L, HEALTHY);
        assertFalse(next.runPass);
    }

    @Test
    public void markDirtyWakesTheGate() {
        IdleCleanupGate gate = cleanGateAt(2_000L);
        gate.markDirty();
        IdleCleanupGate.Decision d = gate.evaluate(false, 32_000L, HEALTHY);
        assertTrue(d.runPass);
        assertEquals("dirty", d.reason);
    }

    @Test
    public void compareBeforeClearKeepsMidPassMutationDirty() {
        IdleCleanupGate gate = new IdleCleanupGate(BACKSTOP);
        IdleCleanupGate.Decision d = gate.evaluate(false, 1_000L, HEALTHY);
        assertTrue(d.runPass);

        // A file save lands WHILE the pass is running…
        gate.markDirty();
        gate.onPassComplete(d.generationAtStart, 2_000L);

        // …so the next tick must re-run rather than skip.
        IdleCleanupGate.Decision next = gate.evaluate(false, 32_000L, HEALTHY);
        assertTrue(next.runPass);
        assertEquals("dirty", next.reason);
    }

    @Test
    public void cleanPassThenNoMutationClearsTheFlag() {
        IdleCleanupGate gate = new IdleCleanupGate(BACKSTOP);
        IdleCleanupGate.Decision d = gate.evaluate(false, 1_000L, HEALTHY);
        gate.onPassComplete(d.generationAtStart, 2_000L);   // nothing bumped mid-pass

        assertFalse(gate.evaluate(false, 32_000L, HEALTHY).runPass);
    }

    @Test
    public void backstopForcesAPassAfterAnHour() {
        IdleCleanupGate gate = cleanGateAt(2_000L);
        IdleCleanupGate.Decision beforeDue = gate.evaluate(false, 2_000L + BACKSTOP - 1, HEALTHY);
        assertFalse(beforeDue.runPass);

        IdleCleanupGate.Decision due = gate.evaluate(false, 2_000L + BACKSTOP, HEALTHY);
        assertTrue(due.runPass);
        assertEquals("backstop", due.reason);
    }

    @Test
    public void backstopClockAdvancesEvenWhenGenerationStaysDirty() {
        IdleCleanupGate gate = new IdleCleanupGate(BACKSTOP);
        IdleCleanupGate.Decision d = gate.evaluate(false, 1_000L, HEALTHY);
        gate.markDirty();                          // mid-pass mutation
        gate.onPassComplete(d.generationAtStart, 2_000L);

        // Next pass runs because dirty — NOT because backstop — proving the
        // backstop clock advanced with the completed (though dirty) pass.
        IdleCleanupGate.Decision next = gate.evaluate(false, 3_000L, HEALTHY);
        assertEquals("dirty", next.reason);
    }

    @Test
    public void deferredWorkWakesTheGate() {
        IdleCleanupGate gate = cleanGateAt(2_000L);
        IdleCleanupGate.Decision d = gate.evaluate(true, 32_000L, HEALTHY);
        assertTrue(d.runPass);
        assertEquals("deferred", d.reason);
    }

    @Test
    public void lowDiskWakesTheGate() {
        IdleCleanupGate gate = cleanGateAt(2_000L);
        IdleCleanupGate.Decision d = gate.evaluate(false, 32_000L, LOW);
        assertTrue(d.runPass);
        assertEquals("lowDisk", d.reason);
    }

    @Test
    public void lowDiskProbeIsLazyAndLast() {
        IdleCleanupGate gate = new IdleCleanupGate(BACKSTOP);
        AtomicInteger probes = new AtomicInteger();
        IdleCleanupGate.LowDiskProbe counting = () -> {
            probes.incrementAndGet();
            return false;
        };

        // dirty short-circuits — probe must NOT fire
        gate.evaluate(false, 1_000L, counting);
        assertEquals(0, probes.get());

        // clean steady state — probe is the last resort and fires once
        IdleCleanupGate.Decision d = gate.evaluate(false, 1_000L, HEALTHY);
        gate.onPassComplete(d.generationAtStart, 2_000L);
        gate.evaluate(false, 32_000L, counting);
        assertEquals(1, probes.get());
    }

    @Test
    public void staleGenerationFromEarlierPassNeverClearsNewerDirt() {
        IdleCleanupGate gate = new IdleCleanupGate(BACKSTOP);
        IdleCleanupGate.Decision first = gate.evaluate(false, 1_000L, HEALTHY);
        gate.markDirty();
        gate.markDirty();

        // Completing with the STALE snapshot must not mark clean.
        gate.onPassComplete(first.generationAtStart, 2_000L);
        assertTrue(gate.evaluate(false, 3_000L, HEALTHY).runPass);
    }

    /** A gate that has completed one clean pass at {@code atMs}. */
    private static IdleCleanupGate cleanGateAt(long atMs) {
        IdleCleanupGate gate = new IdleCleanupGate(BACKSTOP);
        IdleCleanupGate.Decision d = gate.evaluate(false, atMs - 1, HEALTHY);
        gate.onPassComplete(d.generationAtStart, atMs);
        return gate;
    }
}
