package com.overdrive.app.storage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Decision state machine for the periodic cleanup's IDLE gate.
 *
 * <p>Extracted from StorageManager's tick so the gate semantics — dirty
 * generation, compare-before-clear, hourly backstop, lazy low-disk probe —
 * are unit-testable in isolation (audit: no focused tests covered the
 * dirty-generation races). Pure Java, no Android dependencies.
 *
 * <p><b>Contract.</b> Mutation paths call {@link #markDirty()}; the idle tick
 * calls {@link #evaluate} to decide whether a full cleanup pass may be
 * skipped, and — when a pass runs — {@link #onPassComplete} with the
 * generation the pass STARTED with. Compare-before-clear: the generation is
 * only marked clean if nothing bumped it while the pass ran, so a file save
 * landing mid-pass leaves the gate dirty and the next tick re-runs. Losing
 * that race the other way would silently skip a needed reap until the
 * backstop.
 *
 * <p><b>Ordering caveat.</b> A marker must call {@link #markDirty()} AFTER
 * (or atomically with) making its mutation visible to the reaper's
 * measurement — the pass that observes the bump must be able to observe the
 * bytes. Every current caller (save hooks, setters, mount transitions)
 * satisfies this trivially by bumping after the filesystem/config write.
 *
 * <p>Thread-safety: markDirty is safe from any thread; evaluate /
 * onPassComplete are only ever called from the single-threaded cleanup
 * scheduler, so the volatile fields need no CAS.
 */
final class IdleCleanupGate {

    /** StatFs-style probe, invoked LAZILY — only when no cheaper signal
     *  already forces a pass — so the steady-state skip stays O(1). */
    interface LowDiskProbe {
        boolean isLow();
    }

    /** Outcome of one {@link #evaluate} call. */
    static final class Decision {
        /** True when the tick must run a full idle pass. */
        final boolean runPass;
        /** Generation snapshot taken at evaluation time; hand back to
         *  {@link #onPassComplete} when the pass finishes. */
        final long generationAtStart;
        /** Which signal forced the pass ("dirty"/"deferred"/"backstop"/
         *  "lowDisk"); null when skipped. For logging only. */
        final String reason;

        private Decision(boolean runPass, long generationAtStart, String reason) {
            this.runPass = runPass;
            this.generationAtStart = generationAtStart;
            this.reason = reason;
        }
    }

    // Starts at 1 with lastCleanedGeneration 0 so the FIRST idle tick after
    // construction always runs one full pass (catches anything that changed
    // while the daemon was offline).
    private final AtomicLong generation = new AtomicLong(1);
    private volatile long lastCleanedGeneration = 0;
    private volatile long lastFullPassAtMs = 0;
    private final long backstopMs;

    IdleCleanupGate(long backstopMs) {
        this.backstopMs = backstopMs;
    }

    /** Storage state changed — the next idle tick must run a real pass. */
    void markDirty() {
        generation.incrementAndGet();
    }

    /**
     * Decide whether this idle tick may skip the full pass.
     *
     * @param deferredPending a recording-time deferred cleanup is waiting
     * @param nowMs           caller's clock (injectable for tests)
     * @param lowDiskProbe    probed ONLY when dirty/deferred/backstop are all
     *                        false — the last, priciest signal
     */
    Decision evaluate(boolean deferredPending, long nowMs, LowDiskProbe lowDiskProbe) {
        long gen = generation.get();
        if (gen != lastCleanedGeneration) return new Decision(true, gen, "dirty");
        if (deferredPending)              return new Decision(true, gen, "deferred");
        if (nowMs - lastFullPassAtMs >= backstopMs) {
            return new Decision(true, gen, "backstop");
        }
        if (lowDiskProbe != null && lowDiskProbe.isLow()) {
            return new Decision(true, gen, "lowDisk");
        }
        return new Decision(false, gen, null);
    }

    /**
     * Record a COMPLETED idle pass. Marks the generation clean only if it is
     * unchanged since {@code generationAtStart} (compare-before-clear); the
     * backstop clock always advances — the pass did just measure everything,
     * whatever the generation did meanwhile.
     */
    void onPassComplete(long generationAtStart, long nowMs) {
        if (generation.get() == generationAtStart) {
            lastCleanedGeneration = generationAtStart;
        }
        lastFullPassAtMs = nowMs;
    }
}
