package com.overdrive.app.charging;

import com.overdrive.app.logging.DaemonLogger;

/**
 * Accumulates session energy from the vehicle's charged-energy counter, correctly across counter
 * WRAP, SATURATION and session RESET.
 *
 * <p><b>Why a plain {@code end - start} is not enough.</b> The counter is a 16-bit value at 1 Wh
 * resolution, so its full range is {@code [0, 65.534]} kWh. A single session can exceed that: a
 * 10→100% charge on a large pack (85-109 kWh nominal) delivers 75-98 kWh. At the ceiling the
 * counter either wraps back through zero or pins, and both cases make a naive difference
 * under-report by exactly the amount that overflowed — silently, and in currency.
 *
 * <p>So we integrate the counter's RISES instead of differencing its endpoints:
 * <ul>
 *   <li><b>rise</b> — add it.</li>
 *   <li><b>drop from near the ceiling to near zero</b> — a WRAP. Add the distance to the ceiling
 *       plus the new value. Only credited while the same session is still live, because a wrap
 *       cannot happen without energy flowing.</li>
 *   <li><b>pinned at the ceiling</b> — SATURATION. The counter has stopped counting, so it is no
 *       longer a measurement; the accumulator marks itself unreliable and the caller falls back.</li>
 *   <li><b>any other drop</b> — a session RESET (new session, or BMS re-init). The counter's own
 *       history is gone, so what came before cannot be recovered from it.</li>
 * </ul>
 *
 * <p><b>The ambiguity that matters, and how it is resolved.</b> If the daemon is down while the
 * counter moves, we see only the endpoints and cannot tell a wrap from a reset: a baseline of 50
 * and a later reading of 10 is either "wrapped, 25.5 kWh added" or "reset, 10 kWh added". The
 * counter alone cannot say. So this class does not decide — it reports BOTH candidates and lets
 * the caller pick the one that agrees with an independent SOC-derived estimate. That is why the
 * SOC cross-check is load-bearing rather than a mere sanity net.
 *
 * <p>Not thread-safe; the caller serialises on the sampler/session thread.
 */
public final class ChargeCounterAccumulator {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ChargeCounterAccumulator");

    /** Full-scale of the counter, kWh (0xFFFE at 1 Wh resolution). */
    public static final double COUNTER_FULL_SCALE_KWH = 65.534;
    /**
     * Consecutive unchanged reads at the ceiling before saturation is declared.
     *
     * <p>Only meaningful against a value already near full scale: an idle counter sits unchanged
     * anywhere, so "not moving" alone is not overflow.
     */
    private static final int SATURATION_STREAK = 3;
    /**
     * Ceiling proximity used only for SATURATION, not for wrap detection.
     *
     * <p>Wrap detection deliberately does NOT use a proximity window. A window has to be sized
     * against the energy delivered between two observations, and that varies by three orders of
     * magnitude across the cases this must serve: at a 12 s sample a 7 kW AC charge advances
     * 0.023 kWh, while a 350 kW DC charge advances 1.17 kWh — and on the 120 s tick that same DC
     * charge advances 11.7 kWh, so it can leap from 54 kWh straight past the ceiling and reappear
     * near zero having never been observed "at" it. Any fixed window either misses those wraps or
     * misreads an ordinary taper as one. So a wrap is instead inferred from DIRECTION plus
     * PHYSICAL PLAUSIBILITY (see {@link #observe}), which needs no window.
     */
    private static final double SATURATION_MARGIN_KWH = 0.5;
    /**
     * Maximum rate we will credit across one observation gap, kW. Used to bound how much energy a
     * wrap may be assumed to have delivered while unobserved. Above the fastest production BYD DC
     * rate with headroom, so it never truncates a real charge, but finite so a pathological gap
     * cannot invent unbounded energy.
     */
    private static final double MAX_PLAUSIBLE_RATE_KW = 400.0;
    /**
     * Drops at or below this are noise, not events, kWh.
     *
     * <p>The counter is quantised at 1 Wh, and a BMS may nudge its own figure down by a quantum or
     * two as it refines a measurement. Treating such a dip as a session RESET would abandon the
     * whole accumulated total over 2 Wh of rounding. Three quanta gives margin without being able
     * to hide a real reset, which is orders of magnitude larger.
     */
    private static final double NOISE_DEAD_BAND_KWH = 0.003;

    private double baseline = Double.NaN;   // first value seen this session
    private double last = Double.NaN;       // previous observation
    private long lastAtMs = 0;              // when `last` was observed
    /**
     * Logical version of the complete accumulator image. Unlike {@code lastAtMs}, this remains ordered
     * when the RTC moves backwards and therefore decides whether H2 or the lifecycle journal is newer.
     */
    private long observationGeneration = 0;
    private double accumulated = 0;         // integrated rises, wrap-corrected
    private int wraps = 0;
    private int resets = 0;
    private int ceilingStreak = 0;
    private boolean saturated = false;
    private double abandonedKwh = 0;        // energy the counter discarded at a reset boundary
    private int unattributedGaps = 0;       // wraps across gaps that could have held several cycles
    /** Set by {@link #restore}: the next observation must reconcile the unobserved outage gap. */
    private boolean awaitingGapReconcile = false;
    /** True once any gap has been reconstructed into this total (see containsReconstructedGap). */
    private boolean gapReconstructed = false;
    /** Independent estimate used to attribute that gap; NaN when none is available. */
    private double gapEstimateKwh = Double.NaN;
    /**
     * Smoothed rate at which this counter has actually been advancing, kWh/h; NaN until a rise is
     * observed. This is the counter's OWN behaviour, and it is what decides wrap-vs-reset.
     */
    private double recentRateKwhPerH = Double.NaN;
    /**
     * Full scale of the counter currently bound to this accumulator, kWh.
     *
     * <p>Defaults to the 16-bit {@link #COUNTER_FULL_SCALE_KWH}, which is what the dedicated capacity
     * counter uses. Not every counter shares it: a field capture recorded the external accessor reading
     * 119.0, well past 65.534, so that source is plainly a wider register. Hard-coding one scale meant
     * every reading from such a source was rejected outright and the trim had no metered energy at all.
     * The wrap, saturation and reach arithmetic all key off this, so it must be set BEFORE the first
     * observation of a session.
     */
    private double fullScaleKwh = COUNTER_FULL_SCALE_KWH;

    /**
     * Declare the full scale of the counter about to be observed, kWh.
     *
     * <p>Call at session start, before any {@link #observe}. Ignored once observations have begun,
     * because changing the modulus mid-series would reinterpret every wrap already credited.
     */
    public void setFullScaleKwh(double kwh) {
        if (Double.isNaN(kwh) || kwh <= 1.0) return;
        if (!Double.isNaN(baseline)) return;   // series already started
        if (Double.compare(fullScaleKwh, kwh) == 0) return;
        fullScaleKwh = kwh;
        advanceObservationGeneration();
    }

    /**
     * WIDEN the modulus mid-series, when a reading proves the current one is too small.
     *
     * <p>Normally the scale is immutable once a series starts, because reinterpreting the modulus would
     * change how every wrap already credited should have been read. Widening is the one safe direction,
     * and it is necessary: a session restored from a legacy row that recorded no source, whose stored
     * endpoints both happened to sit below the 16-bit ceiling, is indistinguishable from a capacity
     * session — so it restores at 65.534 and then silently DISCARDS every later reading above that,
     * losing the rest of the charge. A reading above the current ceiling is proof the register is wider.
     *
     * <p>Safe because widening cannot retroactively invent a wrap: {@code wraps == 0} is required, so no
     * already-credited wrap can be reinterpreted. With no wrap yet, the accumulated total is a plain sum
     * of rises and is independent of the modulus entirely.
     *
     * @return true when the modulus was widened
     */
    public boolean widenFullScaleKwh(double kwh) {
        if (Double.isNaN(kwh) || kwh <= fullScaleKwh) return false;
        if (wraps > 0) return false;   // a credited wrap was read against the old modulus
        logger.info(String.format(java.util.Locale.US,
                "Widening counter full scale %.3f -> %.3f kWh: a reading exceeded the assumed ceiling,"
                + " so the register is wider than the restored value implied",
                fullScaleKwh, kwh));
        fullScaleKwh = kwh;
        advanceObservationGeneration();
        return true;
    }

    /** Current modulus, kWh — lets the caller notice a reading it would otherwise silently drop. */
    public double fullScaleKwh() { return fullScaleKwh; }

    /** Weight of the newest interval in {@link #recentRateKwhPerH}. */
    private static final double RATE_EWMA_ALPHA = 0.4;
    /**
     * Head-room on the observed rate when asking whether the counter could have reached full scale.
     *
     * <p>The rate can genuinely climb between two observations (a DC session ramping), so the
     * reachability test must not be exactly at the last measured rate. This is deliberately generous
     * — being wrong toward "wrap" is only safe because the estimate veto below still applies.
     */
    private static final double REACH_RATE_HEADROOM = 2.0;
    /**
     * Fraction of the implied overshoot the post-wrap value must account for.
     *
     * <p>Generous, because the rate can drop sharply across the interval that happens to contain the
     * wrap (a taper beginning, or the charge simply ending there). It only has to reject a landing of
     * ~zero, which is what a reset produces and a wrap at any meaningful rate does not.
     */
    private static final double LANDING_MIN_FRACTION = 0.25;

    /**
     * Absolute bound on the wrap-proximity window, kWh.
     *
     * <p>The window itself is derived from elapsed time ({@code MAX_PLAUSIBLE_RATE_KW * hours}): to
     * cross full scale the counter must first have reached it, so a wrap leaves {@code last} within
     * one interval's worth of energy of the ceiling. That distance is what must be admitted, and it
     * is large — a 120 s interval at 400 kW spans 13.3 kWh — so this bound exists only to stop an
     * unbounded gap making the test vacuous, and must never truncate a physically reachable wrap.
     * A former 5.0 kWh value did exactly that: it forced every wrap observed from below 60.5 kWh to
     * be read as a reset, discarding the session's real metered energy on any charge still running
     * above ~150 kW when it crossed the ceiling. Gaps long enough for this bound to bite are already
     * flagged by {@code gapTooLongToAttribute}.
     */
    private double wrapProximityMaxKwh() { return fullScaleKwh; }
    /**
     * How far a wrap-implied total may exceed the independent estimate before the wrap is vetoed.
     *
     * <p>Tighter than the display-side band: this decides whether to CREATE energy that no other
     * source saw, so it should demand real corroboration rather than merely tolerate disagreement.
     */
    private static final double WRAP_ESTIMATE_TOLERANCE = 1.15;

    /** Ratio band for accepting a gap candidate against the independent estimate. */
    private static final double GAP_RATIO_LOW = 0.70;
    private static final double GAP_RATIO_HIGH = 1.30;

    public void reset() {
        baseline = Double.NaN;
        last = Double.NaN;
        lastAtMs = 0;
        observationGeneration = 0;
        accumulated = 0;
        wraps = 0;
        resets = 0;
        ceilingStreak = 0;
        saturated = false;
        abandonedKwh = 0;
        unattributedGaps = 0;
        awaitingGapReconcile = false;
        gapReconstructed = false;
        gapEstimateKwh = Double.NaN;
        recentRateKwhPerH = Double.NaN;
        fullScaleKwh = COUNTER_FULL_SCALE_KWH;
    }

    /**
     * Restore in-flight state after a daemon restart, from the endpoints persisted on the open
     * session row. Without this the accumulator would re-baseline at the CURRENT counter value and
     * silently drop everything delivered before the restart.
     *
     * @param baselineKwh  persisted counter_start_kwh
     * @param lastKwh      persisted counter_last_kwh (the last value we observed before going down)
     * @param energyKwh    persisted counter_energy_kwh accumulated so far
     * @param incomplete   persisted energy_incomplete flag
     */
    public void restore(double baselineKwh, double lastKwh, double energyKwh, boolean incomplete) {
        restore(baselineKwh, lastKwh, energyKwh, incomplete, Double.NaN);
    }

    /**
     * Restore, supplying the independent estimate used to attribute the unobserved outage gap.
     *
     * @param gapEstimate energy the vehicle is independently believed to have taken since the
     *                    stored {@code lastKwh} (e.g. SOC-derived), or NaN when unknown
     */
    public void restore(double baselineKwh, double lastKwh, double energyKwh, boolean incomplete,
                        double gapEstimate) {
        restore(baselineKwh, lastKwh, energyKwh, incomplete, gapEstimate, COUNTER_FULL_SCALE_KWH);
    }

    /**
     * @param fullScale the modulus of the counter this session was using. Carried explicitly because
     *                  {@link #reset()} restores the 16-bit default, and a resumed external-counter
     *                  session would otherwise have its readings rejected by the domain gate (they can
     *                  exceed 65.534) and its wrap deltas computed against the wrong ceiling.
     */
    public void restore(double baselineKwh, double lastKwh, double energyKwh, boolean incomplete,
                        double gapEstimate, double fullScale) {
        reset();
        if (!Double.isNaN(fullScale) && fullScale > 1.0) fullScaleKwh = fullScale;
        if (!Double.isNaN(baselineKwh)) baseline = baselineKwh;
        if (!Double.isNaN(lastKwh)) last = lastKwh;
        if (!Double.isNaN(energyKwh) && energyKwh > 0) accumulated = energyKwh;
        // Unknown elapsed time across the outage, so the rate bound cannot be applied to the first
        // reading. The gap is attributed by candidate-vs-estimate instead — see observe().
        lastAtMs = 0;
        gapEstimateKwh = gapEstimate;
        awaitingGapReconcile = !Double.isNaN(last);
        if (incomplete) resets = Math.max(resets, 1);
    }

    /** Energy the counter itself discarded at reset boundaries — unrecoverable from the counter. */
    public double abandonedKwh() { return abandonedKwh; }

    /**
     * Supply the current independent (SOC-derived) estimate of session energy, kWh.
     *
     * <p>Used ONLY to arbitrate an ambiguous fall: a reset while the counter is already high looks
     * identical to a wrap by rate alone, and crediting the wrong one invents up to ~10 kWh that gets
     * priced. The estimate never contributes to the accumulated total — it only vetoes a wrap that
     * the pack demonstrably did not take. Pass NaN when unavailable; the arbitration then prefers the
     * reset reading, which claims no energy.
     */
    public void setIndependentEstimate(double kwh) {
        // restore() installs an estimate for the UNOBSERVED restart interval, not for the
        // whole session. A normal live update is total-session energy and must not replace
        // that narrower estimate before the first post-restart counter observation consumes
        // it. Doing so makes candidate arbitration compare a gap delta with a total and can
        // select an extra wrap.
        if (awaitingGapReconcile) return;
        if (Double.compare(gapEstimateKwh, kwh) == 0) return;
        gapEstimateKwh = kwh;
        advanceObservationGeneration();
    }

    /**
     * Exact process-spanning image of this accumulator.
     *
     * <p>Deferred physical charging generations can exist while H2 is unavailable. Their
     * counter state is journaled outside H2 and restored byte-for-byte after restart; using
     * {@link #restore} for that copy would incorrectly create a new restart gap for a
     * generation that had already ended before shutdown.
     */
    public static final class State {
        public double baseline;
        public double last;
        public long lastAtMs;
        public long observationGeneration;
        public double accumulated;
        public int wraps;
        public int resets;
        public int ceilingStreak;
        public boolean saturated;
        public double abandonedKwh;
        public int unattributedGaps;
        public boolean awaitingGapReconcile;
        public boolean gapReconstructed;
        public double gapEstimateKwh;
        public double recentRateKwhPerH;
        public double fullScaleKwh;
    }

    public State snapshotState() {
        State state = new State();
        state.baseline = baseline;
        state.last = last;
        state.lastAtMs = lastAtMs;
        state.observationGeneration = observationGeneration;
        state.accumulated = accumulated;
        state.wraps = wraps;
        state.resets = resets;
        state.ceilingStreak = ceilingStreak;
        state.saturated = saturated;
        state.abandonedKwh = abandonedKwh;
        state.unattributedGaps = unattributedGaps;
        state.awaitingGapReconcile = awaitingGapReconcile;
        state.gapReconstructed = gapReconstructed;
        state.gapEstimateKwh = gapEstimateKwh;
        state.recentRateKwhPerH = recentRateKwhPerH;
        state.fullScaleKwh = fullScaleKwh;
        return state;
    }

    public void restoreState(State state) {
        reset();
        if (state == null) return;
        baseline = state.baseline;
        last = state.last;
        lastAtMs = state.lastAtMs;
        observationGeneration = Math.max(0L, state.observationGeneration);
        accumulated = Math.max(0, state.accumulated);
        wraps = Math.max(0, state.wraps);
        resets = Math.max(0, state.resets);
        ceilingStreak = Math.max(0, state.ceilingStreak);
        saturated = state.saturated;
        abandonedKwh = Math.max(0, state.abandonedKwh);
        unattributedGaps = Math.max(0, state.unattributedGaps);
        awaitingGapReconcile = state.awaitingGapReconcile && !Double.isNaN(last);
        gapReconstructed = state.gapReconstructed;
        gapEstimateKwh = state.gapEstimateKwh;
        recentRateKwhPerH = state.recentRateKwhPerH;
        if (!Double.isNaN(state.fullScaleKwh) && state.fullScaleKwh > 1.0) {
            fullScaleKwh = state.fullScaleKwh;
        }
    }

    /**
     * Select one complete image from two durability domains.
     *
     * <p>Never combine an aggregate from one image with the endpoint from the other. Such a torn image
     * double-counts the next delta after an RTC rollback. Generation zero is the defensive legacy case:
     * only there do wall time and then accumulated energy break ties, while still selecting one image.
     */
    public static State newestCompleteState(
            State journal, State durable, boolean durableIncomplete) {
        boolean useDurable = preferSecondCompleteState(journal, durable);
        State selected = copyState(useDurable ? durable : journal);
        if (selected == null) selected = new State();
        if (useDurable && selected.observationGeneration == 0L) {
            applyDurableIncomplete(selected, durableIncomplete);
        }
        return selected;
    }

    /** True when the second complete image should replace the first. Equal versions prefer H2. */
    public static boolean preferSecondCompleteState(State first, State second) {
        if (second == null) return false;
        if (first == null) return true;
        long firstGeneration = Math.max(0L, first.observationGeneration);
        long secondGeneration = Math.max(0L, second.observationGeneration);
        if (secondGeneration != firstGeneration) {
            return secondGeneration > firstGeneration;
        }
        if (secondGeneration > 0L) return true;
        if (second.lastAtMs != first.lastAtMs) return second.lastAtMs > first.lastAtMs;
        if (Double.compare(second.accumulated, first.accumulated) != 0) {
            return second.accumulated > first.accumulated;
        }
        return true;
    }

    private static State copyState(State source) {
        if (source == null) return null;
        State copy = new State();
        copy.baseline = source.baseline;
        copy.last = source.last;
        copy.lastAtMs = source.lastAtMs;
        copy.observationGeneration = source.observationGeneration;
        copy.accumulated = source.accumulated;
        copy.wraps = source.wraps;
        copy.resets = source.resets;
        copy.ceilingStreak = source.ceilingStreak;
        copy.saturated = source.saturated;
        copy.abandonedKwh = source.abandonedKwh;
        copy.unattributedGaps = source.unattributedGaps;
        copy.awaitingGapReconcile = source.awaitingGapReconcile;
        copy.gapReconstructed = source.gapReconstructed;
        copy.gapEstimateKwh = source.gapEstimateKwh;
        copy.recentRateKwhPerH = source.recentRateKwhPerH;
        copy.fullScaleKwh = source.fullScaleKwh;
        return copy;
    }

    private static void applyDurableIncomplete(State state, boolean durableIncomplete) {
        if (!durableIncomplete || state.saturated || state.resets > 0
                || state.unattributedGaps > 0 || state.awaitingGapReconcile) {
            return;
        }
        // Legacy rows carry only the aggregate incomplete bit. Preserve that information even though
        // the older schema cannot identify which exact counter event caused it.
        state.unattributedGaps = 1;
    }

    /** Mark only the next observation as spanning a process outage. */
    public void beginGapReconciliation(double independentGapEstimateKwh) {
        if (Double.isNaN(last)) return;
        if (awaitingGapReconcile
                && Double.compare(gapEstimateKwh, independentGapEstimateKwh) == 0) {
            return;
        }
        gapEstimateKwh = independentGapEstimateKwh;
        awaitingGapReconcile = true;
        advanceObservationGeneration();
    }

    /**
     * True when any part of this total was RECONSTRUCTED across an unobserved gap rather than
     * integrated from readings we saw.
     *
     * <p>Load-bearing for SOH: a reconstructed gap was attributed by comparing candidates against an
     * independent SOC estimate, so the total is contaminated by that estimate. Calibrating SOH from
     * it would partially close the loop the calibration guard exists to prevent. Callers must pass
     * this through as {@code meteredIsGap} rather than assuming a live session never reconstructs —
     * a daemon restart mid-charge makes any session a gap session.
     */
    public boolean containsReconstructedGap() { return gapReconstructed; }

    /**
     * Mark that this series includes energy reconstructed across an unobserved continuation gap.
     *
     * <p>The caller uses this after seeding a new session from a prior interrupted row. The counter
     * delta remains useful, but it must be persisted as gap-reconstructed and withheld from SOH
     * calibration because continuity was inferred rather than observed live.
     */
    public void markReconstructedGap() {
        if (hasSeriesState() && !gapReconstructed) {
            gapReconstructed = true;
            advanceObservationGeneration();
        }
    }

    /**
     * True when this accumulator holds series state that would be LOST by a reset.
     *
     * <p>Broader than "has it measured energy": a session restored after a restart carries a baseline,
     * a last-observed value and a pending gap reconciliation while its accumulated total is still
     * exactly 0 — nothing has risen since the restore. Testing energy alone declared that state
     * worthless and let it be discarded, which loses both the pre-restart endpoints and the unreconciled
     * outage interval. Any of these means the series is live and must not be restarted.
     */
    public boolean hasSeriesState() {
        return !Double.isNaN(baseline)
                || !Double.isNaN(last)
                || accumulated > 0
                || awaitingGapReconcile
                || gapReconstructed
                || wraps > 0
                || resets > 0;
    }

    /** True once a baseline has been captured, i.e. the counter is contributing. */
    public boolean hasBaseline() { return !Double.isNaN(baseline); }

    /**
     * The counter has stopped being a measurement (pinned at full scale). The caller MUST fall
     * back to an independent estimate rather than publish {@link #energyKwh()}.
     */
    public boolean isSaturated() { return saturated; }

    /** Number of times the counter reset mid-session — each one abandons unrecoverable energy. */
    public int resetCount() { return resets; }

    public int wrapCount() { return wraps; }

    /**
     * True when the accumulated total cannot be trusted as complete.
     *
     * <p>{@code awaitingGapReconcile} counts: after a restore the outage interval has NOT yet been
     * attributed, and if the session closes before the next observation arrives (the charge ended
     * during the outage, or the counter never answers again) that energy is simply missing. Without
     * this term such a session was stored as a confident metered measurement — the restored
     * pre-outage subtotal presented as the whole charge, with no '~' marker and no exclusion from
     * SOH calibration.
     */
    public boolean isIncomplete() {
        return saturated || resets > 0 || unattributedGaps > 0 || awaitingGapReconcile;
    }

    /** Wrap-corrected energy accumulated across this session, kWh. */
    public double energyKwh() { return accumulated; }

    /** Latest raw counter reading, or NaN. */
    public double lastRawKwh() { return last; }

    public double baselineKwh() { return baseline; }

    /**
     * Feed one counter observation. Call ONLY while the session is live and the counter has been
     * admitted by the collector's gate.
     *
     * @param valueKwh raw counter value, kWh
     */
    public void observe(double valueKwh) {
        observe(valueKwh, System.currentTimeMillis());
    }

    /**
     * Feed one counter observation with an explicit timestamp.
     *
     * @param valueKwh raw counter value, kWh
     * @param nowMs    observation time, used to bound how much energy a wrap may have delivered
     */
    public void observe(double valueKwh, long nowMs) {
        if (Double.isNaN(valueKwh) || valueKwh < 0 || valueKwh > fullScaleKwh + 1.0) return;
        advanceObservationGeneration();

        if (Double.isNaN(baseline)) {
            baseline = valueKwh;
            last = valueKwh;
            lastAtMs = nowMs;
            return;
        }
        // FIRST OBSERVATION AFTER A RESTORE. The counter ran unobserved while the daemon was down,
        // and a plain rise-difference silently assumes it did NOT wrap. It may have: down 8 h at
        // 11 kW delivers 88 kWh, so a counter last seen at 25 kWh reappears at 47.5 kWh having
        // passed full scale once — and differencing gives 22.5 kWh instead of 88, under-reporting
        // by a whole cycle. Both readings are equally consistent with either story, so the choice
        // is made against the independent SOC estimate, exactly as for any other gap.
        if (awaitingGapReconcile) {
            awaitingGapReconcile = false;
            // An exactly unchanged endpoint with no positive independent gap estimate is a
            // successfully reconciled zero delta, not an ambiguous lost interval. In particular,
            // restoring a row whose counter energy is exactly zero and then observing the same
            // counter value must clear the restart fence without manufacturing incompleteness.
            if (Double.compare(valueKwh, last) == 0
                    && (Double.isNaN(gapEstimateKwh) || gapEstimateKwh <= 0)) {
                lastAtMs = nowMs;
                return;
            }
            gapReconstructed = true;
            double[] cands = gapCandidatesKwh(last, valueKwh, fullScaleKwh);
            double chosen = chooseCandidate(cands, gapEstimateKwh,
                    GAP_RATIO_LOW, GAP_RATIO_HIGH);
            boolean unverifiedChoice = Double.isNaN(gapEstimateKwh) || gapEstimateKwh <= 0;
            if (!Double.isNaN(chosen) && chosen >= 0) {
                if (chosen > 0) accumulated += chosen;
                // Whether the gap contained one cycle or several cannot be known from two readings,
                // so a gap credited at more than a full cycle is flagged rather than presented as
                // measured.
                if (chosen >= fullScaleKwh) unattributedGaps++;
                // NO ESTIMATE = NO VERIFICATION. chooseCandidate falls back to the SMALLEST candidate
                // when it has nothing to compare against, which is the right default (it under-reports
                // rather than inventing energy) but it is still an unresolved coin-flip between
                // interpretations that differ by a whole 65.534 kWh cycle. A 50 -> 10 gap is either
                // +10 or +75.534, and picking 10 must not then be stored and PRICED as a complete
                // measurement. Flag it so the row reads as a floor.
                else if (unverifiedChoice && cands.length > 1) {
                    unattributedGaps++;
                    logger.warn(String.format(java.util.Locale.US,
                            "Restart gap credited at the conservative candidate %.3f kWh with no"
                            + " independent estimate to choose between %d interpretations — marked"
                            + " incomplete (the total is a floor, not a measurement)",
                            chosen, cands.length));
                }
                logger.info(String.format(java.util.Locale.US,
                        "Reconciled restart gap: counter %.3f -> %.3f, credited %.3f kWh"
                        + " (estimate %.3f), accumulated=%.3f",
                        last, valueKwh, chosen, gapEstimateKwh, accumulated));
            } else {
                // No candidate was credible against the estimate. Do not invent energy; mark the
                // total incomplete so the row is not presented as a measurement.
                unattributedGaps++;
                logger.warn(String.format(java.util.Locale.US,
                        "Restart gap NOT attributable: counter %.3f -> %.3f, estimate %.3f"
                        + " — marked incomplete", last, valueKwh, gapEstimateKwh));
            }
            last = valueKwh;
            lastAtMs = nowMs;
            return;
        }

        if (valueKwh == last) {
            // Unchanged. Only overflow if it is unchanged AT full scale — an idle counter sits
            // unchanged anywhere, so position is what distinguishes the two.
            if (valueKwh >= fullScaleKwh - SATURATION_MARGIN_KWH) {
                if (++ceilingStreak >= SATURATION_STREAK && !saturated) {
                    saturated = true;
                    logger.warn("Charged-energy counter SATURATED at " + valueKwh
                            + " kWh — session energy falls back to an independent estimate");
                }
            }
            return;
        }
        ceilingStreak = 0;

        if (valueKwh > last) {
            double rise = valueKwh - last;
            // A counter that advances is not pinned. Saturation can be declared from several
            // identical near-ceiling reads and then disproved by the very next finer-grained value.
            saturated = false;
            // Learn how fast this counter actually advances. Used below to decide whether a fall
            // could physically be a wrap — the counter's own recent behaviour is a far better
            // predictor of that than any fixed proximity window.
            if (lastAtMs > 0 && nowMs > lastAtMs) {
                double h = (nowMs - lastAtMs) / 3_600_000.0;
                if (h > 0) {
                    double inst = rise / h;
                    recentRateKwhPerH = Double.isNaN(recentRateKwhPerH) ? inst
                            : (RATE_EWMA_ALPHA * inst + (1 - RATE_EWMA_ALPHA) * recentRateKwhPerH);
                }
            }
            accumulated += rise;
            lastAtMs = nowMs;
            last = valueKwh;
            return;
        }

        // A dip inside the quantisation dead-band is measurement noise, not an event. Hold the
        // accumulated total and the timestamp, and do NOT re-baseline `last` downward — otherwise
        // the same rise would be credited twice once the counter recovers.
        if (last - valueKwh <= NOISE_DEAD_BAND_KWH) {
            return;
        }

        // The value FELL materially. Two physical explanations, and they must be told apart
        // correctly because one credits energy and the other abandons it.
        //
        //   WRAP  — the counter passed full scale and continued from zero. The energy delivered is
        //           (FULL_SCALE - last) + value.
        //   RESET — the BMS restarted the counter (new session, or a pause/resume sub-phase). The
        //           energy it held is gone; only `value` has been counted since.
        //
        // The discriminator is PLAUSIBILITY, not proximity to the ceiling. A fixed proximity
        // window cannot work: on a 120 s tick a 350 kW charge advances 11.7 kWh, so it can pass
        // from 54 kWh to beyond full scale and back to ~6 kWh without ever being observed near the
        // ceiling. Instead, ask whether the wrap interpretation implies a rate the hardware could
        // actually have delivered in the elapsed time. A reset implies no rate claim at all, so it
        // is the safe default whenever the wrap story is not physically supportable.
        double wrapEnergy = (fullScaleKwh - last) + valueKwh;
        boolean wrapPlausible;
        boolean gapTooLongToAttribute = false;
        if (lastAtMs <= 0 || nowMs <= lastAtMs) {
            // RTC rollback removes the rate evidence that distinguishes a wrap from a reset. Only an
            // independent estimate close to the complete wrap-implied total can prove the wrap.
            double candidateTotal = accumulated + wrapEnergy;
            double estimateRatio = !Double.isNaN(gapEstimateKwh) && gapEstimateKwh > 0
                    ? candidateTotal / gapEstimateKwh : Double.NaN;
            wrapPlausible = !Double.isNaN(estimateRatio)
                    && estimateRatio >= GAP_RATIO_LOW
                    && estimateRatio <= WRAP_ESTIMATE_TOLERANCE;
        } else {
            double hours = (nowMs - lastAtMs) / 3_600_000.0;
            double impliedKw = wrapEnergy / hours;
            wrapPlausible = impliedKw <= MAX_PLAUSIBLE_RATE_KW;
            // A RESET occurring while the counter is already high is indistinguishable from a wrap by
            // rate alone: both leave a high `last` and a near-zero `value`, and the wrap story implies
            // a rate the hardware could plausibly have delivered. Crediting the wrap then adds
            // (FULL_SCALE - last) kWh that never flowed — up to ~10 kWh on a 120 s tick from
            // last=55 — and on a large session that over-credit is under the resolver's 30% ratio
            // tolerance, so it is PRICED.
            //
            // The discrimination that does exist is external: a wrap means the pack really did take
            // that energy, so an independent estimate would corroborate it. Require that when one is
            // available; with none, prefer the RESET reading, which claims no energy at all. Erring
            // toward reset can only UNDER-report (and the total is flagged incomplete), whereas erring
            // toward wrap invents energy and bills for it.
            // A wrap is only credited when the counter was ALREADY near full scale. That is a physical
            // necessity — to cross full scale it must first have reached it — and it is what separates
            // the two stories without relying on an estimate. The window is one observation
            // interval's worth of energy at the fastest plausible rate, plus a small margin, so a real
            // wrap is never missed however coarse the sampling.
            // Reachability, measured against how fast THIS counter has been advancing rather than
            // against the theoretical maximum. A counter creeping at 7 kWh/h cannot have covered the
            // 11 kWh to the ceiling in 120 s, so a fall from 54 kWh is a reset; one advancing at
            // 300 kWh/h plainly could, so the same fall is a wrap. Using the observed rate is what
            // lets the window be honest in both directions: a fixed cap either discards real
            // fast-charge wraps (when too small) or credits ordinary mid-range resets (when too
            // large). Falls back to the theoretical bound only before any rise has been seen.
            double reachRate = Double.isNaN(recentRateKwhPerH)
                    ? MAX_PLAUSIBLE_RATE_KW
                    : Math.min(MAX_PLAUSIBLE_RATE_KW, recentRateKwhPerH * REACH_RATE_HEADROOM);
            double reachKwh = reachRate * hours;
            double wrapFloor = fullScaleKwh - Math.min(reachKwh, wrapProximityMaxKwh());
            if (wrapPlausible && last < wrapFloor) {
                wrapPlausible = false;
            }
            // WHERE IT LANDED. Reachability alone cannot separate the two stories on a fast charge:
            // a reset from 64 kWh at 250 kW passes every test above, because the counter plainly
            // COULD have reached the ceiling — it just did not. The distinguishing evidence is the
            // landing value. A wrap carries on past zero at the rate it was already running, so the
            // new value should be close to the overshoot the interval implies. A reset lands at
            // ~zero no matter how fast the charge was going, so a near-zero value after a
            // fast-advancing interval is a reset. Requiring the landing to account for a reasonable
            // share of the interval's energy is what rules that in or out; with no learned rate there
            // is no expectation to test against, so the check is skipped.
            if (wrapPlausible && !Double.isNaN(recentRateKwhPerH)) {
                double expectedLanding = (recentRateKwhPerH * hours) - (fullScaleKwh - last);
                if (expectedLanding <= 0) {
                    // The interval's energy does not even close the gap to the ceiling at the rate
                    // this counter has been running, so it cannot have wrapped: it would have had to
                    // accelerate mid-interval by more than the headroom above allows. The fall is a
                    // reset. (This is the case the landing comparison below cannot judge, because
                    // there is no positive overshoot to compare against.)
                    wrapPlausible = false;
                } else if (valueKwh < expectedLanding * LANDING_MIN_FRACTION) {
                    wrapPlausible = false;
                }
            }
            // Belt and braces: if an independent estimate exists and the wrap story would claim more
            // energy than the pack plausibly took, veto it. Tighter than the display-side tolerance
            // because this decides whether to CREATE energy from nothing.
            if (wrapPlausible && !Double.isNaN(gapEstimateKwh) && gapEstimateKwh > 0) {
                if ((accumulated + wrapEnergy) / gapEstimateKwh > WRAP_ESTIMATE_TOLERANCE) {
                    wrapPlausible = false;
                }
            }
            // A gap long enough to hold MORE than one full counter cycle is unattributable: two
            // wraps look exactly like one, so crediting a single wrap would under-report by a whole
            // full scale. At the fastest plausible rate that needs ~10 min; below it a second wrap
            // cannot have occurred. Mark the total incomplete rather than guess the cycle count.
            gapTooLongToAttribute = (MAX_PLAUSIBLE_RATE_KW * hours) > fullScaleKwh;
        }

        if (wrapPlausible) {
            accumulated += wrapEnergy;
            wraps++;
            saturated = false;   // it wrapped rather than pinning, so it is still counting
            if (gapTooLongToAttribute) {
                // Credited one cycle, but the gap could have held several. Flag it: the figure is
                // a floor, not a measurement.
                unattributedGaps++;
                logger.warn(String.format(java.util.Locale.US,
                        "Charged-energy counter wrapped across a %.1f min gap — cycle count"
                        + " ambiguous, credited one wrap (+%.3f kWh) and marked incomplete",
                        (nowMs - lastAtMs) / 60000.0, wrapEnergy));
            } else {
                logger.info(String.format(java.util.Locale.US,
                        "Charged-energy counter wrapped (%.3f -> %.3f kWh, +%.3f) accumulated=%.3f kWh",
                        last, valueKwh, wrapEnergy, accumulated));
            }
        } else {
            // RESET. `value` is energy already delivered since the reset and this is the first
            // observation of that new segment, so credit it now. The next rise contributes only
            // (next - value), which does not double-count it. Omitting this value lost the entire
            // first post-reset segment whenever the first observed reading was above zero.
            resets++;
            abandonedKwh += last;
            accumulated += valueKwh;
            saturated = false;
            // The counter restarted, so the rate learned from the abandoned run no longer describes
            // it. Keeping it would let a pre-pause fast rate justify a wrap on the new, slower run.
            recentRateKwhPerH = Double.NaN;
            logger.warn(String.format(java.util.Locale.US,
                    "Charged-energy counter RESET mid-session (%.3f -> %.3f kWh); %.3f kWh"
                    + " abandoned — total marked incomplete", last, valueKwh, last));
        }
        lastAtMs = nowMs;
        last = valueKwh;
    }

    private void advanceObservationGeneration() {
        if (observationGeneration < Long.MAX_VALUE) observationGeneration++;
    }

    /**
     * Advance the complete-image version when metadata stored beside the numeric accumulator changes.
     * The owning counter source is persisted by SocHistoryDatabase, but participates in the same
     * journal-vs-H2 image selection.
     */
    public void markPersistenceMetadataChanged() {
        advanceObservationGeneration();
    }

    /**
     * The candidate interpretations of a gap in observation (daemon down, ACC off) between a
     * persisted baseline and the current reading.
     *
     * <p>Both are arithmetically valid; only an independent estimate can choose. Returned smallest
     * first so a caller with no estimate at all takes the conservative one — under-reporting
     * energy is a visible disappointment, over-reporting is an overcharge.
     *
     * @param baselineKwh the counter value when we last saw it
     * @param currentKwh  the counter value now
     * @return {1, 2 or 3} candidates in ascending order
     */
    public static double[] gapCandidatesKwh(double baselineKwh, double currentKwh) {
        return gapCandidatesKwh(baselineKwh, currentKwh, COUNTER_FULL_SCALE_KWH);
    }

    /**
     * @param fullScale the counter's own modulus, kWh. Must be the scale of the source that produced
     *                  these readings — a wrap candidate computed against the wrong modulus is wrong by
     *                  the difference between the two, which on the external counter is hundreds of kWh.
     */
    public static double[] gapCandidatesKwh(double baselineKwh, double currentKwh, double fullScale) {
        if (Double.isNaN(baselineKwh) || Double.isNaN(currentKwh)) return new double[0];
        if (Double.isNaN(fullScale) || fullScale <= 1.0) fullScale = COUNTER_FULL_SCALE_KWH;
        if (currentKwh >= baselineKwh) {
            double plain = currentKwh - baselineKwh;
            // It may also have wrapped all the way round and come back above the baseline.
            double wrapped = plain + fullScale;
            return new double[] { plain, wrapped };
        }
        // Counter is lower than the baseline: either it reset (current holds the whole session),
        // or it wrapped through the ceiling.
        double asReset = currentKwh;
        double asWrap = (fullScale - baselineKwh) + currentKwh;
        return asReset <= asWrap ? new double[] { asReset, asWrap }
                                 : new double[] { asWrap, asReset };
    }

    /**
     * Pick the candidate closest to an independent estimate, provided it is credible.
     *
     * @param candidates    from {@link #gapCandidatesKwh}
     * @param estimateKwh   independent SOC-derived estimate, or NaN when unavailable
     * @param toleranceLow  lower bound on candidate/estimate
     * @param toleranceHigh upper bound on candidate/estimate
     * @return the chosen candidate, or NaN when none is credible
     */
    public static double chooseCandidate(double[] candidates, double estimateKwh,
                                         double toleranceLow, double toleranceHigh) {
        if (candidates == null || candidates.length == 0) return Double.NaN;
        if (Double.isNaN(estimateKwh) || estimateKwh <= 0) {
            // No way to disambiguate — take the conservative (smallest) candidate.
            return candidates[0];
        }
        double best = Double.NaN;
        double bestErr = Double.MAX_VALUE;
        for (double c : candidates) {
            if (c <= 0) continue;
            double ratio = c / estimateKwh;
            if (ratio < toleranceLow || ratio > toleranceHigh) continue;
            double err = Math.abs(ratio - 1.0);
            if (err < bestErr) { bestErr = err; best = c; }
        }
        return best;
    }
}
