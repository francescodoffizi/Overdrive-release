# Sentry detection pipeline — invariants

Contract for the surveillance/sentry detection path. Any change to this package is
checked against these invariants, not just against its local intent. Rationale for
each lives in the referenced code comments; this file is the index.

**Prime directive:** the system's false-positive rejection is GOOD and protected.
Only misses (false negatives) are defects. A change is shippable only if it is
FP-neutral or FP-reducing. "Catches more" is not, by itself, a justification.

## The three evidence channels

A recording is justified by ONE of three independent kinds of evidence. Any change
must be checked against all three, because a stage that serves one can starve another:

1. **Class evidence** — YOLO resolved a person/vehicle (`sequenceConfirmed`).
2. **Dwell evidence** — a TRUSTED HIGH loiter (coherent translation or an in-zone
   person tracker holds it).
3. **Salience evidence** — the motion geometry itself is object-grade: large,
   compact, sustained, photometrically stable, rigidly translating. Opt-in
   (`motionSalienceEnabled`, default OFF).

Channel 3 exists because 1 and 2 both fail on the same real subject: YOLO gets
~2-4 windows on a dark fisheye crop and returns nothing, and a subject that keeps
moving never reads as a loiter. Its own inversion hazard is documented below.

## The funnel

A real event is missed iff any stage wrongly kills the signal:

| Stage | What it is | Where |
|---|---|---|
| S1 | Native block-motion (~8.7 Hz, 10×7×32 px grid on 320×240 quadrant) | `cpp/surveillance/motion_pipeline_v2.*` |
| S2 | Trigger decision (duration bars, fast paths, suppressive latches) | `SurveillanceEngineGpu.processFrameV2` |
| S3 | AI dispatch (cooldowns, lane latch, foveated/mosaic crop) | `SurveillanceEngineGpu.runAiOnQuadrant` |
| S4 | Detection filtering (class → motion-overlap → baseline → threat gate) | aiTask lambda |
| S5 | Identity (CQT trackId → ActorTracker actor/severity/static) | `CrossQuadrantTracker`, `ActorTracker` |
| S6 | Event lifecycle (stop clocks, extension, discard) | start/stop/discard paths |
| S7 | Surfaces (push, Telegram, hero, timeline, automations) | publish*/send*/ThumbnailBuffer |

## Invariants

**I1 — Fail open on missing/invalid data.** A test that cannot run must PASS the
signal, never drop it. Existing instances: `fovMapValid=false` keeps the detection;
`hasAffine()` rejects NaN; degenerate-bbox and unmapped-bottom-band fail-opens (the
grid covers 320×224 of a 320×240 quadrant — the bottom 16 px can never report
motion); null hero mask → "live"; `trackerInZone` throws → in-zone.

**I2 — No suppressive latch without per-tick decay and session reset.** Every latch
that can block a trigger must decay at tick rate (never inside a `frameCount % N`
block — that nesting made one headlight sweep deafen the close-range fast paths for
minutes) and be reset in `enable()`. Current latches: `suppressionWasActive[]`
(per-tick decay + reset), `cachedHighIsTrusted`/`cachedIncoherentLoiter` (reset with
each sequence), deterrent/NO_AI windows (deliberate, bounded, documented FP guards).

**I3 — Suppression is evidence-scoped.** A suppressor kills only the signal class it
has positive evidence against: brightness → lighting-artifact paths only, and the
live per-quadrant flag still applies during a real sweep; baseline → that region +
canonical class, confirmed entries only, persons exempt; scenery verdict → that
actorId only, persons exempt.

**I4 — Identity errors degrade toward FRAGMENTATION, never absorption.** A duplicate
actorId costs MIN_ESCALATION_FRAMES (~1 s of NOTICE pin) — bounded. An absorbed
identity inherits `everMoved=false`/`historyCount`, can stamp `isStaticForTimeline`
on a real mover's first frame, suppress its counts, and feed the discard — unbounded.
Hence: stale same-quadrant CQT match uses a flat jitter radius
(`STALE_MATCH_RADIUS_PX`), the ActorTracker hint path demands IoU>0 after a >1.5 s
same-quadrant gap, and a rejected hint is never re-stamped (`hintRejected`).
Known bounded residuals, accepted deliberately (do NOT patch without a full re-derivation):
same-batch double-match of one CQT track degrades toward `everMoved=true` (more
alerting); a rejected-hint object crossing quadrants within the old track's TTL can
take ≤2 frames of wrong static before the anchor re-seed latches `everMoved`.

**I5 — Coordinates travel with their space.** A bbox crossing a module boundary
carries its space (`qW/qH` into ActorTracker/ThumbnailBuffer; the foveated affine
rides the PBO ring WITH the pixels; CQT input is mapped to 320×240 via that affine,
ax=0.25 + origin + flip — never a bare 0.5 scale). No consumer assumes.

**I6 — Stop/discard may only remove what it can prove is nothing.** Discard requires
positive FP evidence and hard-KEEPs on person/moving-object/approach/coherence
latches. Stop clocks are seeded inside `startRecording()` itself so no caller can
create an unstoppable clip. Extension liveness reads unfiltered native counts (the
per-quadrant override recount must not deflate them on the pass path).

**I8 — Mass is not evidence of a lighting event.** Motion mass grows with object
SIZE and CLOSENESS as well as with illumination change, so any test of the form
"too many blocks ⇒ artifact" inverts on the closest, largest — most threatening —
subjects. The native `>25%` global-flash filter is exactly this shape: a person at
~1.5 m is ~18/70 blocks, and `tierFromMotion` calls ≥20 blocks NEAR, so the events
with the MOST motion evidence were the ones whose `peakCloseZoneDuringSequence`
never latched, closing every YOLO-blind escape path at once. Mass-based
suppression must therefore be a PROBE (measure shape + coherence, then decide),
never an early return. A failed probe must restore state and reproduce the original
suppression byte-for-byte.

**I10 — A trigger channel with no external brake needs an explicit one.** YOLO
confirmation is what rate-limits the class and dwell channels in the field: a real
object has to be re-classified to re-fire. Salience has no such dependency, and its
per-quadrant run counters deliberately survive a sequence boundary, so a scene that
keeps qualifying would re-latch on the next sequence's first tick and re-fire every
`SALIENCE_TRIGGER_MS` — a muxer-init/pre-record-flush storm that leaks MediaCodec
slots until SIGABRT (the failure mode `NO_AI_MIN_GAP_MS` already exists to stop).
Hence `SALIENCE_MIN_GAP_MS` gates salience-ONLY triggers; the other two channels are
never delayed by it.

**I11 — An evidence latch may not outlive its evidence.** Sequence-scoped latches are
only safe when a sequence is short. A sentry sequence ends on a >2 s motion gap, so on
a busy scene ANY quadrant refreshing `lastMotionTime` keeps it open indefinitely — a
600 ms burst in one quadrant would then keep vouching for an unrelated subject in
another minutes later. Hence `salienceConfirmedDuringSequence` carries
`salienceConfirmedAtMs` and expires after `SALIENCE_LATCH_TTL_MS`, re-stamped by each
qualifying tick. Cross-quadrant is the specific trap: `peakCloseZoneDuringSequence` is
latched off the best-threat quadrant while salience is per-quadrant, so the two can
describe different subjects.

**I9 — A channel that only ADDS triggers fails CLOSED.** I1's fail-open applies to
tests that can DROP a signal. The salience channel can only add one, so absent
evidence (`flowCoherence == -1`, `componentCount == 0`, native library without the
salience fields) must mean "do not trigger" — never "assume qualifying". Inverting
this would turn every unmeasurable frame into a recording.

**I7 — Every dropped signal is observable.** Each kill site logs or counts
(`noteAiSkip`, OVERRIDE_DEMOTED, suppression logs, funnel stats) so a field miss can
be attributed to a stage without a rebuild.

## Audit status

| Stage | Last audited | Result |
|---|---|---|
| Salience channel (native probe + S2 wiring) | 2026-07-31 | Added; see I8/I9. Compiles; native `.so` verified rebuilt. DEVICE-UNVERIFIED — the five per-tick terms' thresholds (12 blocks, 0.60 dominance, ≤3 blobs, 6 ticks, 12 luma) are reasoned, not measured. |
| S1 native motion | 2026-07-25 | No actionable MISS findings. Verified: `-1` coherence sentinel survives `memset`; `suppressionCountdown` decrements every frame AND fast-adapts baseline during suppression (α=0.2) so post-flash drift can't re-trigger forever; global-flash sync checks only NEW shifts, never existing countdowns (self-reinforcing-loop guard); exposure hysteresis has an 85/95 deadband + 15-frame ISP blindfold; Stage 3 increment/decay mutually exclusive; `edgeChangedCount>=2` exempts a block from shadow suppression (self-erasing-person guard); all block loops bounded by `V2_GRID_ROWS` with an extra `gy >= HEIGHT` guard. |
| S2-S7 Java | 2026-07-25 | See git history for the fix set; residuals listed under I4. |

Known non-defects in S1, deliberately left: `stage5_behaviorClassification`'s `maxDrift`
compares each history entry against the NEWEST centroid rather than pairwise, so "loiter"
means "stayed within radius of the current position" — intended, and the permissive
direction. `prevModes` (motion_pipeline_v2.cpp) is `static`, i.e. process-global rather
than per-quadrant-instance; affects only a log line.

## Asymmetries that look inconsistent but are load-bearing

- The S4 motion-overlap filter is dilated by one block; `ThumbnailBuffer`'s copy is
  NOT. Opposite penalties: drop-the-detection vs cosmetic scenery flag. Do not unify.
- `beatsAsThreat` demotes an all-scenery headline, but the count loops are untouched
  and `pushWorthy` latches before the demotion. Cosmetic surfaces may narrow;
  gates may not.
- Train/boat/airplane are retained in the detector's vehicle mask but dropped by the
  engine class filter. Deliberate: re-admitting them reopens a documented phantom-FP
  channel. Leave.
- Salience is a KEEP override for nothing. Every other trigger-evidence latch
  (person / moving object / lateral mass / AI-blind timeout) hard-KEEPs its clip in
  `shouldDiscardEvent`, but `eventTriggerWasSalience` deliberately does NOT: the
  no-actor discard is the intended precision partner of the salience recall, so a
  salience clip with no actor is exactly what the user asked to be filtered when
  they enable that toggle. It is logged in both discard/keep lines for attribution.
- The salience per-quadrant run counters (`salienceRunTicks`) reset on any
  non-qualifying tick and in `enable()`, but NOT at motion-sequence start. They
  describe the scene, not the sequence, and a sequence boundary is itself >2 s of
  non-qualifying ticks.

### Salience: accepted residual (audited 2026-07-31)

A **coherently translating illumination front** — a swept headlight beam on a
textured wall, or a wiper blade — satisfies the native probe's three geometric
tests (mass, single dominant blob, rigid translation). It is bounded, not
eliminated, by four independent existing mechanisms, which is why it is accepted
rather than patched: Stage 1's mean-luma shift and the PASS-1 global sync usually
suppress a beam that large before the probe runs; Stage 2's chroma + edge gate
kills soft shadow fronts; `stage5` caps a >15%-area result at THREAT_MEDIUM, so
Java still demands YOLO or salience confirmation; and the Java channel additionally
requires 6 consecutive ticks of luma stability (`salienceMaxLumaDelta`), which a
sweep breaks. Do not add a fourth geometric test here without re-deriving all four.
