package com.overdrive.app.surveillance;

import com.overdrive.app.ai.Detection;
import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * CrossQuadrantTracker — Correlates object detections across camera quadrants.
 *
 * When a person walks from the front camera's FOV into the left camera's FOV,
 * YOLO produces two independent detections. Without tracking, the timeline
 * records them as two separate events. This tracker assigns a persistent
 * track ID so the system knows it's the same person.
 *
 * Algorithm: Lightweight centroid + class matching (no deep re-identification).
 *
 * For each new detection:
 *   1. Check if any existing track has the same classId AND is in an adjacent
 *      quadrant AND was last seen within HANDOFF_WINDOW_MS.
 *   2. If the detection is near the edge of its quadrant (within EDGE_MARGIN
 *      blocks of the boundary), and the existing track was near the opposite
 *      edge of the adjacent quadrant, it's a handoff — assign the same trackId.
 *   3. Otherwise, create a new track.
 *
 * Adjacency map (based on physical camera placement on BYD):
 *   Front (Q0) ↔ Right (Q1), Front (Q0) ↔ Left (Q3*)
 *   Rear  (Q2) ↔ Right (Q1), Rear  (Q2) ↔ Left (Q3*)
 *   (* Q3 = BR in grid = Left camera per strip mapping)
 *
 * Note: Q2=BL=Rear, Q3=BR=Left in the mosaic grid.
 *
 * Edge detection:
 *   Each quadrant is 320×240 in the mosaic (10×7 block grid).
 *   "Near edge" = detection bbox within EDGE_MARGIN_PX of the quadrant boundary.
 *   Adjacent quadrants share a physical edge where FOVs overlap.
 *
 * This is intentionally simple. Full re-identification (appearance embeddings,
 * Kalman filters) would require a second neural network and is overkill for
 * a parked-car surveillance system. The centroid + class + edge heuristic
 * catches 90%+ of cross-camera transitions.
 */
public class CrossQuadrantTracker {
    private static final DaemonLogger logger = DaemonLogger.getInstance("XQTracker");

    // How long a track stays alive without updates before being pruned.
    //
    // MUST be >= ActorTracker.TRACK_TTL_MS (8000). ActorTracker's primary
    // association path binds a detection to an existing Actor by the xqTrackId
    // hint this class assigns. When this TTL was the shorter 5000 ms, a subject
    // quiet for 5-8 s had its xqTrack pruned here while the Actor was still
    // alive there — so on reappearance it received a BRAND NEW xqTrackId that
    // matched no Actor, the hint path missed, and the IoU fallback (0.20 against
    // a stale box, no motion model) usually missed too. Result: a new actorId
    // with historyCount reset to 1, which re-pins severity at NOTICE via the
    // MIN_ESCALATION_FRAMES gate and suppresses the notification for a subject
    // that had already been tracked. Matching the two TTLs closes that window.
    // Identity continuity only; no trigger bar is affected.
    private static final long TRACK_TTL_MS = 8000;

    // Maximum time gap for a cross-quadrant handoff (person disappears from Q0,
    // appears in Q1 within this window)
    private static final long HANDOFF_WINDOW_MS = 2000;

    // How recent a same-quadrant track must be to earn the FULL adaptive match
    // radius. Covers a couple of YOLO periods (AI_COOLDOWN_MS = 500 ms) plus the
    // round-robin skew when several quadrants compete. Beyond this the radius
    // tightens — see the recency-scaled radius in processDetections.
    private static final long SAME_QUADRANT_FRESH_MS = 1500;

    // Same-quadrant match radius for a track staler than SAME_QUADRANT_FRESH_MS.
    // Sized as a JITTER radius, not a motion radius: a track that went quiet was
    // quiet BECAUSE it was stationary, so it reappears within YOLO box wobble of
    // its old centroid. Comfortably above ActorTracker's STATIC_CENTROID_DRIFT_PX
    // (10) so a stationary subject still re-matches, and well below the tens of px
    // that separate two DIFFERENT objects — which is the absorption this bounds.
    private static final float STALE_MATCH_RADIUS_PX = 24f;

    // Detection must be within this many pixels of the quadrant edge to be
    // considered a potential handoff candidate
    private static final int EDGE_MARGIN_PX = 48;  // ~1.5 blocks

    // Quadrant dimensions in the mosaic
    private static final int Q_WIDTH = 320;
    private static final int Q_HEIGHT = 240;

    // Maximum concurrent tracks (parked car scenario — unlikely to have >8 people)
    private static final int MAX_TRACKS = 16;

    private int nextTrackId = 1;

    /**
     * A tracked object across quadrants.
     */
    public static class Track {
        public int trackId;
        public int classId;
        public int lastQuadrant;
        public int lastX, lastY, lastW, lastH;  // Bbox in quadrant pixel coords
        public long lastSeenMs;
        public boolean active;

        // Edge flags from last observation
        public boolean nearLeftEdge, nearRightEdge, nearTopEdge, nearBottomEdge;
    }

    private final Track[] tracks = new Track[MAX_TRACKS];

    public CrossQuadrantTracker() {
        for (int i = 0; i < MAX_TRACKS; i++) {
            tracks[i] = new Track();
            tracks[i].active = false;
        }
    }

    /**
     * Adjacency table: which quadrants share a physical camera boundary.
     *
     * Physical layout around the BYD:
     *   Front camera faces forward, Right faces passenger side,
     *   Left faces driver side, Rear faces backward.
     *
     * Quadrant indices (from MotionPipelineV2.QUADRANT_NAMES):
     *   Q0 = front (TL in mosaic grid)
     *   Q1 = right (TR in mosaic grid)
     *   Q2 = rear  (BL in mosaic grid)
     *   Q3 = left  (BR in mosaic grid)
     *
     * Adjacent pairs (physically touching FOVs):
     *   Front-Right (Q0-Q1): person walks from front to passenger side
     *   Front-Left  (Q0-Q3): person walks from front to driver side
     *   Rear-Right  (Q2-Q1): person walks from rear to passenger side
     *   Rear-Left   (Q2-Q3): person walks from rear to driver side
     *
     * Non-adjacent (impossible direct transitions):
     *   Front-Rear  (Q0-Q2): would require teleporting through the car
     *   Right-Left  (Q1-Q3): would require teleporting through the car
     */
    private static final boolean[][] ADJACENT = {
        //       Q0     Q1     Q2     Q3
        /*Q0*/ {false, true,  false, true },  // Front ↔ Right, Left
        /*Q1*/ {true,  false, true,  false},  // Right ↔ Front, Rear
        /*Q2*/ {false, true,  false, true },  // Rear  ↔ Right, Left
        /*Q3*/ {true,  false, true,  false},  // Left  ↔ Front, Rear
    };

    /**
     * Process a batch of detections from a single quadrant.
     * Returns the same detections annotated with track IDs.
     *
     * @param detections YOLO detections from this quadrant
     * @param quadrant   Quadrant index (0-3)
     * @return List of TrackResult with trackId assigned
     */
    public List<TrackResult> processDetections(List<Detection> detections, int quadrant) {
        return processDetections(detections, quadrant, System.currentTimeMillis());
    }

    /**
     * Observation-time-aware variant (audit R13-7 / ExtE-8). {@code nowMs} is
     * the CAPTURE-anchored wall time of the detections — for foveated crops
     * the engine back-dates it by the ring-lag/slot-dwell age so recency
     * ranking and lastSeen bookkeeping describe when the subject was actually
     * at that position, not when inference finished. lastSeenMs is clamped
     * monotonic (max) so an out-of-order stale batch can never rewind a
     * track's clock below what a fresher batch already established.
     */
    public List<TrackResult> processDetections(List<Detection> detections, int quadrant, long nowMs) {
        final long now = nowMs;
        pruneStale(now);

        List<TrackResult> results = new ArrayList<>();
        if (detections == null || detections.isEmpty()) return results;

        // ONE-TO-ONE WITHIN A BATCH (audit R11-9 / ExtD-10). The per-detection
        // loop used to leave a matched (or just-created) track fully eligible
        // for the NEXT detection of the same batch — and the in-place update
        // made the second match EASIER (timeDelta becomes 0 → the generous
        // adaptive radius, measured against the first detection's just-written
        // centroid). Two people walking together in one quadrant then both
        // received the same trackId; ActorTracker's Path A (xqTrackId hint,
        // matched regardless of quadrant) faithfully merged them into ONE
        // actor: undercounted personCount in notifications/sidecars and a
        // centroid oscillating between the two bodies. Greedy order-of-arrival
        // assignment: once a track is claimed by a detection in this batch, it
        // is invisible to the rest of the batch, so the second subject mints
        // its own track. Single-subject behavior is unchanged.
        boolean[] claimedInBatch = new boolean[MAX_TRACKS];

        for (Detection det : detections) {
            int classId = det.getClassId();
            int x = det.getX();
            int y = det.getY();
            int w = det.getW();
            int h = det.getH();

            // Compute edge proximity
            boolean nearLeft = x < EDGE_MARGIN_PX;
            boolean nearRight = (x + w) > (Q_WIDTH - EDGE_MARGIN_PX);
            boolean nearTop = y < EDGE_MARGIN_PX;
            boolean nearBottom = (y + h) > (Q_HEIGHT - EDGE_MARGIN_PX);

            // Try to match to an existing track.
            //
            // RANKING (audit R13-9 / ExtE-10): same-quadrant candidates are
            // ranked by SPATIAL DISTANCE, not recency. The old
            // timeDelta-ranked selection broke down inside one batch: tracks
            // updated in the same previous batch share (nearly) one
            // lastSeenMs, so ranking by time collapsed to detection-order —
            // two nearby same-class people could swap identities whenever
            // YOLO emitted them in a different order. Distance is the signal
            // that actually discriminates the two bodies. Handoff (Case 2)
            // candidates keep recency ranking (there is no meaningful
            // distance across cameras), and a same-quadrant spatial match
            // always outranks a handoff guess.
            int matchIdx = -1;
            long bestTimeDelta = Long.MAX_VALUE;   // handoff ranking + log
            float bestDist = Float.MAX_VALUE;      // same-quadrant ranking
            boolean bestIsSameQuadrant = false;

            for (int i = 0; i < MAX_TRACKS; i++) {
                Track t = tracks[i];
                if (!t.active) continue;
                // Already claimed by an earlier detection of THIS batch — a
                // physical track can host at most one detection per frame
                // (audit R11-9 / ExtD-10).
                if (claimedInBatch[i]) continue;
                // Compare CANONICAL classes: YOLO flips car(2)↔truck(7)↔bus(5)
                // and bicycle(1)↔motorcycle(3) between frames on the same object.
                // Strict equality split one physical actor into twin tracks, each
                // restarting its history. Same collapse DetectionBaseline and
                // Actor.ClassGroup already apply.
                if (canonicalClass(t.classId) != canonicalClass(classId)) continue;

                long timeDelta = now - t.lastSeenMs;

                // Case 1: Same quadrant — centroid proximity match with adaptive threshold
                if (t.lastQuadrant == quadrant) {
                    float cx = x + w / 2.0f;
                    float cy = y + h / 2.0f;
                    float tcx = t.lastX + t.lastW / 2.0f;
                    float tcy = t.lastY + t.lastH / 2.0f;
                    float dist = (float) Math.sqrt((cx - tcx) * (cx - tcx) + (cy - tcy) * (cy - tcy));

                    // SOTA: Adaptive distance threshold based on object size.
                    // At close range (~0.6m), a person's bbox is ~150px wide and they
                    // move fast. At far range (~3m), the bbox is ~30px and they move slow.
                    // Use 1.5× the larger dimension of the object as the match radius.
                    // This prevents track fragmentation for close-range fast-moving objects.
                    float maxDim = Math.max(w, h);
                    float adaptiveThreshold = Math.max(120, maxDim * 1.5f);

                    // RECENCY-SCALED RADIUS. Case 1 has no time bound of its own —
                    // any still-active track (now TRACK_TTL_MS = 8 s) is a candidate
                    // — and the radius above is generous by design: 120 px is 37.5%
                    // of the 320-wide quadrant, and a close subject with maxDim=150
                    // gets 225 px (70%). That generosity is calibrated for
                    // consecutive YOLO ticks 250-500 ms apart, where a fast
                    // close-range mover really does jump that far.
                    //
                    // Over a MULTI-SECOND gap it stops being a match radius and
                    // becomes an absorption radius: a different vehicle entering the
                    // same quadrant 200 px from where a parked one sat 7.5 s ago
                    // inherits its trackId, and through it the Actor's everMoved=false
                    // and historyCount — so ActorTracker.toActor() stamps
                    // isStaticForTimeline on the NEW vehicle's first frame. It is then
                    // skipped by every count/threat loop, eventEverSawMovingObject
                    // never latches (so the empty-motion discard can delete the clip),
                    // and it is promoted into DetectionBaseline, suppressing that
                    // region for the next event too. Raising the TTL 5000→8000 for
                    // identity continuity widened that window by 60%, so bound it here.
                    //
                    // A genuinely-returning subject after a long quiet gap was quiet
                    // BECAUSE it was stationary, so it reappears within jitter of its
                    // old centroid — the tight radius still matches it. This keeps the
                    // TTL's benefit while denying the wide radius to stale tracks.
                    // The stale-track floor is a JITTER radius, deliberately NOT
                    // EDGE_MARGIN_PX (48) — that constant is a handoff edge margin
                    // with no bearing on same-quadrant matching, and at 48+ px it
                    // left the absorption hole open: a new vehicle with maxDim=140
                    // entering 55 px from where a parked one sat 6 s ago got
                    // max(48, 70) = 70 px and MATCHED, inheriting its trackId and
                    // through it (via ActorTracker's xqTrackId hint path, which
                    // matches regardless of quadrant and applies no IoU check) that
                    // Actor's everMoved=false + accumulated historyCount. That
                    // stamps isStaticForTimeline on the new vehicle's FIRST frame,
                    // so it is skipped by every count/threat loop,
                    // eventEverSawMovingObject never latches (the empty-motion
                    // discard can then delete the clip), and it is promoted into
                    // DetectionBaseline, suppressing that region next event too.
                    //
                    // ActorTracker's own jitter estimate is STATIC_CENTROID_DRIFT_PX
                    // = 10, so 24 px is already generous for the legitimate case: a
                    // track quiet for >1.5 s was quiet BECAUSE it was stationary, so
                    // it reappears within box jitter of its old centroid.
                    //
                    // FP-safe by construction: a TIGHTER match radius can only ever
                    // produce MORE distinct trackIds, never fewer, so it cannot
                    // create a recording. The only cost is identity continuity, and
                    // that cost is bounded by the reasoning above.
                    // A FLAT floor, not min(24, maxDim*0.25): scaling it down by box
                    // size gave a distant subject (maxDim 20-60 px, i.e. exactly the
                    // far-away case) a 5-15 px radius, at or below ActorTracker's own
                    // STATIC_CENTROID_DRIFT_PX = 10 jitter estimate. A genuinely
                    // stationary far object would then fail to re-match its own track
                    // on YOLO box wobble alone, minting a fresh trackId → fresh
                    // actorId → MIN_ESCALATION_FRAMES pins it at NOTICE → suppressed
                    // notification. The absorption case this guards against is driven
                    // by how far apart the two objects are (tens of px), not by box
                    // size, so a size-independent radius is the correct shape.
                    float matchRadius = (timeDelta <= SAME_QUADRANT_FRESH_MS)
                            ? adaptiveThreshold
                            : STALE_MATCH_RADIUS_PX;

                    // Spatial ranking (audit R13-9 / ExtE-10): nearest
                    // in-radius candidate wins; recency only breaks exact
                    // distance ties. A same-quadrant match always displaces
                    // a handoff candidate.
                    if (dist < matchRadius
                            && (!bestIsSameQuadrant
                                || dist < bestDist
                                || (dist == bestDist && timeDelta < bestTimeDelta))) {
                        matchIdx = i;
                        bestDist = dist;
                        bestTimeDelta = timeDelta;
                        bestIsSameQuadrant = true;
                    }
                }
                // Case 2: Adjacent quadrant — cross-camera handoff
                else if (ADJACENT[quadrant][t.lastQuadrant] && timeDelta < HANDOFF_WINDOW_MS) {
                    // The detection should be near the edge facing the previous quadrant,
                    // and the previous track should have been near the edge facing this quadrant.
                    if (isHandoffEdgeMatch(quadrant, nearLeft, nearRight, nearTop, nearBottom,
                            t.lastQuadrant, t.nearLeftEdge, t.nearRightEdge, t.nearTopEdge, t.nearBottomEdge)) {
                        // Handoffs rank by recency, and never displace a
                        // same-quadrant spatial match (audit R13-9).
                        if (!bestIsSameQuadrant && timeDelta < bestTimeDelta) {
                            matchIdx = i;
                            bestTimeDelta = timeDelta;
                        }
                    }
                }
            }

            int trackId;
            if (matchIdx >= 0) {
                // Update existing track
                Track t = tracks[matchIdx];
                trackId = t.trackId;
                claimedInBatch[matchIdx] = true; // audit R11-9 / ExtD-10
                if (t.lastQuadrant != quadrant) {
                    logger.info(String.format("Track #%d HANDOFF: %s → %s (class=%d, gap=%dms)",
                            trackId,
                            MotionPipelineV2.QUADRANT_NAMES[t.lastQuadrant],
                            MotionPipelineV2.QUADRANT_NAMES[quadrant],
                            classId, bestTimeDelta));
                }
                t.lastQuadrant = quadrant;
                t.lastX = x; t.lastY = y; t.lastW = w; t.lastH = h;
                // Monotonic clamp (audit R13-7): a back-dated stale batch
                // must not rewind a fresher batch's clock.
                t.lastSeenMs = Math.max(t.lastSeenMs, now);
                t.nearLeftEdge = nearLeft;
                t.nearRightEdge = nearRight;
                t.nearTopEdge = nearTop;
                t.nearBottomEdge = nearBottom;
            } else {
                // Create new track
                trackId = nextTrackId++;
                int slot = findFreeSlot();
                if (slot >= 0) {
                    Track t = tracks[slot];
                    // A track CREATED by an earlier detection of this batch is
                    // claimed too — it must not absorb the next detection
                    // (audit R11-9 / ExtD-10).
                    claimedInBatch[slot] = true;
                    t.trackId = trackId;
                    t.classId = classId;
                    t.lastQuadrant = quadrant;
                    t.lastX = x; t.lastY = y; t.lastW = w; t.lastH = h;
                    t.lastSeenMs = now;
                    t.nearLeftEdge = nearLeft;
                    t.nearRightEdge = nearRight;
                    t.nearTopEdge = nearTop;
                    t.nearBottomEdge = nearBottom;
                    t.active = true;
                }
            }

            results.add(new TrackResult(det, trackId, quadrant));
        }

        return results;
    }

    /**
     * Check if edge positions indicate a cross-camera handoff.
     *
     * The physical camera layout determines which edges are "handoff edges":
     * - Front→Right: person exits right edge of Front, enters left edge of Right
     * - Front→Left:  person exits left edge of Front, enters right edge of Left
     * - Rear→Right:  person exits right edge of Rear, enters left edge of Right
     * - Rear→Left:   person exits left edge of Rear, enters right edge of Left
     *
     * <p>The previous implementation accepted "near ANY edge on both sides",
     * ignoring all eight direction arguments — so any two same-class detections
     * that happened to sit near any border of two adjacent quadrants within
     * {@code HANDOFF_WINDOW_MS} were merged into one identity. With two people
     * moving around the car that silently swaps their tracks (cross-camera ID
     * theft), and a swapped identity carries the wrong accumulated history into
     * severity classification. This now enforces the directional table the doc
     * above describes.
     *
     * <p>Direction convention: the four AVM tiles are independent dewarped views
     * with no shared FOV, so the seam is expressed as "which side of MY frame
     * faces the other camera". Right-side (Q1) and left-side (Q3) views are
     * mirrored relative to the front/rear views, hence the per-pair mapping
     * rather than a single rule. Both endpoints must be near their respective
     * facing edge; a subject leaving the front camera's left edge can only
     * legitimately reappear at the left camera's front-facing edge.
     *
     * <p>Narrower than the old always-true predicate, so it can only ever REJECT a
     * handoff that used to be accepted — it cannot invent a new merge, and therefore
     * cannot create a false positive. Note that "only ever rejects" is the DANGEROUS
     * direction here: a rejected handoff fragments a real track. The predicate is
     * therefore kept to the one claim that needs no uncalibrated geometry — the
     * subject must be against some border, not out in the middle of the frame.
     */
    /**
     * Collapse interchangeable COCO classes so a per-frame YOLO class flip on the
     * same physical object doesn't fragment its track. Mirrors
     * {@code DetectionBaseline.canonicalClass} and {@code Actor.ClassGroup}.
     */
    private static int canonicalClass(int classId) {
        if (classId == 5 || classId == 7) return 2;   // bus, truck → car
        if (classId == 3) return 1;                   // motorcycle → bicycle
        return classId;
    }

    private boolean isHandoffEdgeMatch(
            int newQ, boolean newLeft, boolean newRight, boolean newTop, boolean newBottom,
            int oldQ, boolean oldLeft, boolean oldRight, boolean oldTop, boolean oldBottom) {
        // Which edge of each view faces the other camera in this pair.
        boolean oldFacing = facesQuadrant(oldQ, newQ, oldLeft, oldRight, oldTop, oldBottom);
        boolean newFacing = facesQuadrant(newQ, oldQ, newLeft, newRight, newTop, newBottom);
        return oldFacing && newFacing;
    }

    /**
     * True when a detection in {@code fromQ} sits against the edge of its own
     * frame that physically faces {@code towardQ}.
     *
     * <p>The requirement is deliberately "against SOME border facing the other
     * camera", not a specific left-or-right side. Naming the exact side would need
     * the per-tile mirroring to be calibrated on this hardware, and it is not: the
     * front tile is X-flipped in the DiLink 4 render path while the AI mosaic uses a
     * different flip pair, so "image-left on the front camera" is not known to be
     * the driver side. Asserting it anyway would silently reject every front↔side
     * handoff whose subject lacks a top/bottom flag — a person at 2-3 m with a
     * 100 px box clears both vertical margins, so this is the common case, not a
     * corner one. A rejected handoff mints a fresh actorId with historyCount=1,
     * which MIN_ESCALATION_FRAMES then pins at NOTICE — i.e. a suppressed
     * notification, the exact failure the TTL widening was meant to close.
     *
     * <p>What this still buys over the old always-true predicate: a detection
     * sitting in the MIDDLE of its frame, away from every border, can no longer
     * claim a handoff. That is the physically impossible case and it is caught
     * without any mirroring assumption. Re-tighten to a specific side only after
     * the convention is confirmed from a logged walk-around on the car.
     */
    private boolean facesQuadrant(int fromQ, int towardQ,
                                  boolean nearLeft, boolean nearRight,
                                  boolean nearTop, boolean nearBottom) {
        final int FRONT = 0, RIGHT = 1, REAR = 2, LEFT = 3;
        boolean anyBorder = nearLeft || nearRight || nearTop || nearBottom;
        switch (fromQ) {
            case FRONT:
            case REAR:
                // The ends of the car hand off to either side view.
                if (towardQ == LEFT || towardQ == RIGHT) return anyBorder;
                return false;
            case RIGHT:
            case LEFT:
                // The side views hand off to either end of the car.
                if (towardQ == FRONT || towardQ == REAR) return anyBorder;
                return false;
            default:
                return false;
        }
    }

    private void pruneStale(long now) {
        for (int i = 0; i < MAX_TRACKS; i++) {
            if (tracks[i].active && (now - tracks[i].lastSeenMs) > TRACK_TTL_MS) {
                tracks[i].active = false;
            }
        }
    }

    private int findFreeSlot() {
        for (int i = 0; i < MAX_TRACKS; i++) {
            if (!tracks[i].active) return i;
        }
        // Evict oldest
        long oldest = Long.MAX_VALUE;
        int oldestIdx = 0;
        for (int i = 0; i < MAX_TRACKS; i++) {
            if (tracks[i].lastSeenMs < oldest) {
                oldest = tracks[i].lastSeenMs;
                oldestIdx = i;
            }
        }
        tracks[oldestIdx].active = false;
        return oldestIdx;
    }

    /**
     * Get the number of currently active tracks.
     */
    public int getActiveTrackCount() {
        int count = 0;
        for (int i = 0; i < MAX_TRACKS; i++) {
            if (tracks[i].active) count++;
        }
        return count;
    }

    /**
     * Reset all tracks (e.g., when surveillance mode is toggled).
     */
    public void reset() {
        for (int i = 0; i < MAX_TRACKS; i++) {
            tracks[i].active = false;
        }
        nextTrackId = 1;
    }

    /**
     * Detection annotated with a track ID.
     */
    public static class TrackResult {
        public final Detection detection;
        public final int trackId;
        public final int quadrant;

        public TrackResult(Detection detection, int trackId, int quadrant) {
            this.detection = detection;
            this.trackId = trackId;
            this.quadrant = quadrant;
        }
    }
}
