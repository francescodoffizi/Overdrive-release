package com.overdrive.app.surveillance;

/**
 * SHADOW-MODE multi-batch AI-confirmation counter (parked-car single-frame
 * FP audit). LOG-ONLY: nothing in the trigger chain reads its verdicts.
 *
 * <p>Today a SINGLE qualifying YOLO frame stamps
 * {@code lastAiConfirmationElapsedMs} and authorizes recording (the final AI
 * gate's only key). The observed FP: shadows/reflections trip motion near a
 * baselined parked car, and one bad YOLO frame — the car split into two
 * vehicle boxes that fail the baseline's IoU/foot-point match, or part of the
 * car misread as a low-confidence person (class 0 bypasses the baseline by
 * design) — confirms the sequence and starts a recording. The next frame
 * returns to normal.
 *
 * <p>Candidate replacement under field validation: only confirm once the SAME
 * physical object has been observed across N(class) DISTINCT AI batches
 * within the current motion sequence. This class measures what that gate
 * WOULD have decided so the FN cost (real short-window subjects deferred) and
 * the FP win (one-frame blips that never reach N) are quantified on field
 * logs before anything is enforced.
 *
 * <p>Design choices (from the audit discussion):
 * <ul>
 *   <li><b>Keyed by CrossQuadrantTracker trackId</b>, not ActorTracker state.
 *       CQT is the identity layer: it has per-batch claim dedup
 *       ({@code claimedInBatch}, audit R11-9 — so a split-box pair cannot
 *       double-hit one track in a single batch), its IDs survive cross-camera
 *       handoff (ActorTracker's historyCount resets there), and it mints IDs
 *       for EVERY class (ActorTracker drops UNKNOWN class groups, which would
 *       silently make unknown-class new objects unconfirmable).</li>
 *   <li><b>One stamp per distinct batch</b>: a counter records the batch's
 *       source-frame observation time (elapsedRealtime domain) at most once,
 *       so within-batch duplicates can never inflate the count.</li>
 *   <li><b>Sequence scoping by comparison, not by reset</b>: "confirmed for
 *       this sequence" = stamps {@code >= firstMotionElapsedMs} reach
 *       N(class) — the same idiom {@code sequenceConfirmed} already uses.
 *       Stamps from a previous sequence stop counting on their own, closing
 *       the trap where a track carries hits across sequences for up to the
 *       tracker TTL (8s) and instantly certifies a later unrelated burst.</li>
 *   <li><b>Bounded confirmation window</b> ({@link #MAX_CONFIRM_WINDOW_MS}):
 *       the N batches must also fall within a recency window ending at the
 *       newest batch. A live sequence can persist for MINUTES (motion gap
 *       tolerance keeps refreshing it), so "within the sequence" alone would
 *       let a sporadic false box — one bad frame every ~30s of a long shadow
 *       storm — accumulate to N eventually. Real subjects produce batches at
 *       the motion-driven cadence and cluster well inside the window.</li>
 *   <li><b>Class-specific thresholds</b>: PERSON needs 2 (short-window
 *       walk-ups get few YOLO opportunities — the 2.5m bike/walk-up FN class
 *       cannot afford 3), VEHICLE/BIKE need 3 (persistent by nature; latency
 *       is cheap and they are the split-box FP shape), everything else
 *       (animals, unknown classes) 2 — the lenient, fail-open direction. No
 *       movement predicate in v1: a car that arrives and parks must stay
 *       confirmable by count alone, or its arrival clip (and the baseline
 *       learning it feeds) is lost.</li>
 * </ul>
 *
 * <p>Thread-safety: all methods synchronized; called only from the engine's
 * single-threaded aiExecutor today, but kept safe for future callers. State
 * is bounded ({@link #MAX_ENTRIES} counters × {@link #STAMPS_PER_TRACK}
 * stamps) and self-pruning ({@link #ENTRY_TTL_MS}).
 */
final class MultiBatchConfirmationShadow {

    /** Distinct in-sequence AI batches a PERSON detection needs before the
     *  candidate gate would confirm. 2, not 3: person recall is the
     *  safety-critical direction and short close-range windows yield very
     *  few inference opportunities (250-500ms cadence, ~250-300ms CPU
     *  inference). 2 with per-batch dedup still kills the stated one-frame
     *  failure mode. */
    static final int BATCHES_PERSON = 2;

    /** VEHICLE/BIKE requirement. 3: a real new/moved vehicle persists across
     *  batches by nature, so the added latency is cheap — and a stationary
     *  vehicle-class box is exactly the split-parked-car FP shape. */
    static final int BATCHES_VEHICLE_BIKE = 3;

    /** Everything else (animals, unknown classes): lenient fail-open 2. A
     *  stationary animal is a legitimate subject (never require persistence
     *  beyond flicker rejection), and unknown-class objects must not become
     *  unconfirmable. */
    static final int BATCHES_DEFAULT = 2;

    /** Hard bound on concurrently tracked counters (mirrors the trackers'
     *  own MAX_TRACKS idiom). */
    static final int MAX_ENTRIES = 32;

    /** Stamps retained per track — largest threshold plus slack, so the
     *  in-sequence count saturates correctly even with a stale leftover. */
    static final int STAMPS_PER_TRACK = 4;

    /** Counters not observed for this long (elapsedRealtime ms) are pruned.
     *  Comfortably above the trackers' 8s TTL: once CQT itself would have
     *  minted a fresh trackId, retaining the old counter is pointless. */
    static final long ENTRY_TTL_MS = 30_000L;

    /** Maximum span the N confirming batches may spread over, measured back
     *  from the newest batch. Calibration against the real dispatch cadences:
     *  <ul>
     *    <li>Must ADMIT the legitimate worst case — multi-quadrant round
     *        robin refreshes a quadrant only ~every 2s, so N=3 spans ~4s;
     *        single-quadrant motion (250-500ms cadence) is far inside.</li>
     *    <li>Must EXCLUDE heartbeat-cadence repeats — heartbeats are paced
     *        by HEARTBEAT_COOLDOWN_MS = 5000 per quadrant, so a parked
     *        object misread once per heartbeat produces stamps >= 5s apart
     *        and can never chain two inside this window.</li>
     *  </ul>
     *  4500ms sits between those bounds. */
    static final long MAX_CONFIRM_WINDOW_MS = 4_500L;

    /** Per-trackId stamp ring. */
    private static final class Counter {
        final int classId;   // class at first observation — diagnostic only
        final long[] stamps = new long[STAMPS_PER_TRACK];
        int written;         // total distinct stamps ever written

        Counter(int classId) {
            this.classId = classId;
        }

        /** Maximum RETAINED stamp — deliberately NOT the last-written one.
         *  Observation stamps are NOT monotonic in arrival order: a foveated
         *  batch backdates its stamp by the crop's capture age
         *  (SurveillanceEngineGpu ~:5491, detectionObservationElapsedMs =
         *  elapsedRealtime - fovAge), so a foveated batch that completes
         *  after a mosaic batch can carry an EARLIER stamp. Every consumer
         *  that needs "the newest observation" (prune, evict, the window
         *  anchor) must use this, not insertion order. */
        long maxStamp() {
            long m = 0L;
            int n = Math.min(written, STAMPS_PER_TRACK);
            for (int i = 0; i < n; i++) {
                if (stamps[i] > m) m = stamps[i];
            }
            return m;
        }

        /** Whether this exact stamp is already retained. Order-independent
         *  dedup: a last-written comparison would miss a duplicate of a
         *  BACKDATED stamp arriving after a newer one and inflate the
         *  count. */
        boolean contains(long stamp) {
            int n = Math.min(written, STAMPS_PER_TRACK);
            for (int i = 0; i < n; i++) {
                if (stamps[i] == stamp) return true;
            }
            return false;
        }

        void add(long stamp) {
            // Per-batch dedup: one stamp per distinct batch observation time.
            // (CQT's claimedInBatch already prevents two same-batch detections
            // from sharing a trackId; this is the defensive twin.) Checked
            // against ALL retained stamps — see maxStamp() for why insertion
            // order is not time order. The ring overwrites the oldest-WRITTEN
            // slot, which under out-of-order arrival may not be the oldest
            // VALUE; acceptable for a 4-slot ring with thresholds <= 3.
            if (contains(stamp)) return;
            stamps[written % STAMPS_PER_TRACK] = stamp;
            written++;
        }

        int countSince(long since) {
            int n = Math.min(written, STAMPS_PER_TRACK);
            int c = 0;
            for (int i = 0; i < n; i++) {
                if (stamps[i] >= since) c++;
            }
            return c;
        }
    }

    private final java.util.HashMap<Integer, Counter> counters = new java.util.HashMap<>();

    /** Class-specific batch requirement for the candidate gate. */
    static int requiredBatches(int classId) {
        Actor.ClassGroup g = Actor.groupOf(classId);
        if (g == Actor.ClassGroup.PERSON) return BATCHES_PERSON;
        if (g == Actor.ClassGroup.VEHICLE || g == Actor.ClassGroup.BIKE) {
            return BATCHES_VEHICLE_BIKE;
        }
        return BATCHES_DEFAULT;
    }

    /**
     * Record one observation of {@code trackId} in the AI batch whose source
     * frame was captured at {@code batchElapsedMs} (elapsedRealtime), and
     * return how many DISTINCT batches this track has been observed in that
     * are BOTH within the current motion sequence ({@code >=
     * sinceElapsedMs}, the sequence-start stamp) AND within
     * {@link #MAX_CONFIRM_WINDOW_MS} of this batch. With
     * {@code sinceElapsedMs == 0} (no live sequence) the recency window
     * alone bounds the count.
     */
    synchronized int observe(int trackId, int classId, long batchElapsedMs,
                             long sinceElapsedMs) {
        prune(batchElapsedMs);
        Counter c = counters.get(trackId);
        if (c == null) {
            if (counters.size() >= MAX_ENTRIES) evictOldest();
            c = new Counter(classId);
            counters.put(trackId, c);
        }
        c.add(batchElapsedMs);
        // Sequence scoping AND recency bound (review fix): without the
        // window, a sporadic false box inside one long-lived sequence
        // accumulates to N across minutes. The window is anchored at the
        // MAX RETAINED stamp, not this batch's stamp: a backdated foveated
        // batch arriving after a newer mosaic batch must not slide the
        // window backwards (see Counter.maxStamp()).
        long cutoff = Math.max(sinceElapsedMs, c.maxStamp() - MAX_CONFIRM_WINDOW_MS);
        return c.countSince(cutoff);
    }

    private void prune(long now) {
        java.util.Iterator<java.util.Map.Entry<Integer, Counter>> it =
                counters.entrySet().iterator();
        while (it.hasNext()) {
            // maxStamp, not last-written: an out-of-order backdated stamp
            // must not make a freshly-observed counter prunable.
            if (now - it.next().getValue().maxStamp() > ENTRY_TTL_MS) {
                it.remove();
            }
        }
    }

    private void evictOldest() {
        Integer oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (java.util.Map.Entry<Integer, Counter> e : counters.entrySet()) {
            long n = e.getValue().maxStamp();
            if (n < oldest) {
                oldest = n;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) counters.remove(oldestKey);
    }

    /** Session reset (invariant I2: every latch resets in enable()). */
    synchronized void reset() {
        counters.clear();
    }

    /** Read-only counter count (diagnostics). */
    synchronized int size() {
        return counters.size();
    }
}
