package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Targeted tests for the shadow-mode multi-batch confirmation counter.
 *
 * The counter models the candidate gate "same CQT-tracked object across
 * N(class) DISTINCT AI batches, within the current motion sequence AND
 * within MAX_CONFIRM_WINDOW_MS of the newest batch". These tests lock the
 * boundaries that field-log analysis depends on: per-batch dedup, sequence
 * scoping, the inclusive 4.5s window, class thresholds, bounded state, and
 * — critically — OUT-OF-ORDER observation stamps. Foveated batches backdate
 * their stamp by the crop's capture age (SurveillanceEngineGpu ~:5491), so
 * a batch that completes later can carry an earlier stamp; the counter must
 * anchor its window and TTL on the maximum retained stamp, not on insertion
 * order.
 */
public class MultiBatchConfirmationShadowTest {

    private static final long T0 = 1_000_000L;

    // ---------- class thresholds ----------

    @Test
    public void requiredBatchesFollowClassGroups() {
        assertEquals(MultiBatchConfirmationShadow.BATCHES_PERSON,
                MultiBatchConfirmationShadow.requiredBatches(0));    // person
        assertEquals(MultiBatchConfirmationShadow.BATCHES_VEHICLE_BIKE,
                MultiBatchConfirmationShadow.requiredBatches(2));    // car
        assertEquals(MultiBatchConfirmationShadow.BATCHES_VEHICLE_BIKE,
                MultiBatchConfirmationShadow.requiredBatches(7));    // truck
        assertEquals(MultiBatchConfirmationShadow.BATCHES_VEHICLE_BIKE,
                MultiBatchConfirmationShadow.requiredBatches(1));    // bicycle
        assertEquals(MultiBatchConfirmationShadow.BATCHES_DEFAULT,
                MultiBatchConfirmationShadow.requiredBatches(16));   // dog (animal)
        assertEquals(MultiBatchConfirmationShadow.BATCHES_DEFAULT,
                MultiBatchConfirmationShadow.requiredBatches(39));   // unknown class
    }

    // ---------- accumulation + dedup ----------

    @Test
    public void singleBatchNeverMeetsPersonThreshold() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        int count = s.observe(1, 0, T0, 0);
        assertEquals(1, count);
        assertTrue(count < MultiBatchConfirmationShadow.BATCHES_PERSON);
    }

    @Test
    public void distinctBatchesAccumulateWithinWindow() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        assertEquals(1, s.observe(1, 0, T0, 0));
        assertEquals(2, s.observe(1, 0, T0 + 500, 0));      // person meets 2
        assertEquals(1, s.observe(2, 2, T0, 0));
        assertEquals(2, s.observe(2, 2, T0 + 500, 0));
        assertEquals(3, s.observe(2, 2, T0 + 1000, 0));     // vehicle meets 3
    }

    @Test
    public void sameBatchStampCountsOnce() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        assertEquals(1, s.observe(1, 0, T0, 0));
        // Defensive twin of CQT's claimedInBatch: a second observation of the
        // same (track, batch) must not advance the count.
        assertEquals(1, s.observe(1, 0, T0, 0));
    }

    // ---------- sequence scoping ----------

    @Test
    public void preSequenceStampsDoNotCount() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        assertEquals(1, s.observe(1, 0, T0, 0));
        // A new motion sequence starts after this stamp; the old stamp must
        // not certify the new sequence (the cross-sequence carry trap).
        long seqStart = T0 + 3_000;
        assertEquals(1, s.observe(1, 0, seqStart + 100, seqStart));
    }

    // ---------- recency window ----------

    @Test
    public void heartbeatCadenceRepeatsCannotChain() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        assertEquals(1, s.observe(1, 0, T0, 0));
        // HEARTBEAT_COOLDOWN_MS = 5000 > MAX_CONFIRM_WINDOW_MS = 4500: a
        // parked object misread once per heartbeat never strings two stamps.
        assertEquals(1, s.observe(1, 0,
                T0 + 5_000, 0));
    }

    @Test
    public void windowBoundaryIsInclusive() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        assertEquals(1, s.observe(1, 0, T0, 0));
        // Exactly MAX_CONFIRM_WINDOW_MS apart: cutoff lands ON the first
        // stamp and >= keeps it.
        assertEquals(2, s.observe(1, 0,
                T0 + MultiBatchConfirmationShadow.MAX_CONFIRM_WINDOW_MS, 0));
    }

    @Test
    public void roundRobinCadenceStillConfirmsVehicle() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        // Worst-case legitimate cadence: multi-quadrant round robin refreshes
        // a quadrant only ~every 2s, so N=3 spans ~4s — must fit the window.
        assertEquals(1, s.observe(1, 2, T0, 0));
        assertEquals(2, s.observe(1, 2, T0 + 2_000, 0));
        assertEquals(3, s.observe(1, 2, T0 + 4_000, 0));
    }

    // ---------- out-of-order stamps (foveated backdating) ----------

    @Test
    public void windowSlidesForwardAsNewerBatchesArrive() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        long old = T0 - 4_450;   // inside T0's window, outside T0+400's
        assertEquals(1, s.observe(1, 2, old, 0));
        // At T0: cutoff = T0 - 4500 -> {old, T0} both count.
        assertEquals(2, s.observe(1, 2, T0, 0));
        // At T0+400: cutoff = T0 - 4100 -> `old` (T0-4450) falls out. The
        // count must DROP back to 2 — sporadic old stamps age out of the
        // window instead of accumulating.
        assertEquals(2, s.observe(1, 2, T0 + 400, 0));
    }

    @Test
    public void backdatedArrivalAnchorsWindowAtMaxRetainedStamp() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        assertEquals(1, s.observe(1, 2, T0, 0));
        assertEquals(2, s.observe(1, 2, T0 + 4_400, 0));
        // A foveated batch completes LAST but carries a BACKDATED stamp.
        // Window must stay anchored at the max retained stamp (T0+4400):
        // cutoff = T0-100, so the backdated stamp (T0-200) is excluded and
        // the count is 2. Anchoring at the arriving stamp would yield 3.
        assertEquals(2, s.observe(1, 2, T0 - 200, 0));
    }

    @Test
    public void duplicateOfBackdatedStampCountsOnce() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        assertEquals(1, s.observe(1, 0, T0 + 100, 0));   // foveated, backdated
        assertEquals(2, s.observe(1, 0, T0 + 400, 0));   // mosaic, newer
        // Duplicate of the backdated stamp arrives after a newer one: a
        // last-written dedup would miss it and inflate the count to 3.
        assertEquals(2, s.observe(1, 0, T0 + 100, 0));
    }

    @Test
    public void ttlPruneUsesMaxRetainedStampNotLastWritten() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        long recent = T0 + 100_000;
        s.observe(1, 2, recent, 0);
        s.observe(1, 2, recent - 40_000, 0);   // backdated LAST write, > TTL old
        assertEquals(1, s.size());
        // Another track observed 25s after `recent` triggers prune. The
        // counter's newest activity is `recent` (25s ago, inside the 30s
        // TTL); pruning on the last-written stamp (65s ago) would drop it.
        s.observe(2, 2, recent + 25_000, 0);
        assertEquals(2, s.size());
        // 31s past `recent`: now genuinely stale, prune drops it.
        s.observe(2, 2, recent + 31_000, 0);
        assertEquals(1, s.size());
    }

    // ---------- bounded state ----------

    @Test
    public void entryCountIsBounded() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        for (int t = 1; t <= MultiBatchConfirmationShadow.MAX_ENTRIES + 5; t++) {
            s.observe(t, 0, T0 + t, 0);
        }
        assertTrue(s.size() <= MultiBatchConfirmationShadow.MAX_ENTRIES);
    }

    @Test
    public void resetClearsAllCounters() {
        MultiBatchConfirmationShadow s = new MultiBatchConfirmationShadow();
        s.observe(1, 0, T0, 0);
        s.observe(2, 2, T0, 0);
        s.reset();
        assertEquals(0, s.size());
        // Post-reset, counts restart from 1 (I2: enable() wipes latches).
        assertEquals(1, s.observe(1, 0, T0 + 500, 0));
    }
}
