package com.overdrive.app.monitor;

import com.overdrive.app.byd.ChargeSourceClassifier;
import com.overdrive.app.logging.DaemonLogger;

/**
 * Resolves the instantaneous charge rate in kW from sources whose UNIT is decided at runtime.
 *
 * <p>Since the collector now stores every charging accessor RAW, a stored value is only a kW rate
 * if that source has been classified {@code RATE}. A source classified {@code COUNTER} holds
 * cumulative kWh, so its rate is the slope of successive readings, not the reading itself.
 * Publishing a counter's value as kW is a 10-100x error, which is exactly the defect this
 * subsystem exists to remove.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code RATE} → the value IS the kW, published as measured.</li>
 *   <li>{@code COUNTER} → differentiate. Measured (it comes from a metered quantity), but it needs
 *       two readings, so it is unavailable on the first observation of a charge.</li>
 *   <li>{@code UNKNOWN} → refuse, except for an out-of-physical-range raw value whose exact known
 *       100x unit is proven by a fresh independent same-session pack-flow observation. This narrow
 *       exception handles raw 650/700 without making ordinary ambiguous values publishable.</li>
 * </ul>
 *
 * <p>Drivetrain-independent by construction: the decision is a property of the firmware's
 * behaviour, not of PHEV vs BEV, so the same code serves both. The previous design had to gate the
 * cluster source to one drivetrain precisely because its unit was a guess.
 */
public final class ChargeRateResolver {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ChargeRateResolver");

    /** Slope window. Long enough to beat 1 Wh quantisation, short enough to track a ramp. */
    private static final long MIN_SLOPE_MS = 20_000L;
    /** Beyond this the two readings are not adjacent enough to describe "now". */
    private static final long MAX_SLOPE_MS = 15 * 60_000L;
    /** A derived rate must not outlive one parked-poll interval plus scheduling margin. */
    static final long MAX_HELD_RATE_MS = 120_000L;
    /** A held slope may be displayed longer, but it is too old to establish a permanent unit scale. */
    private static final long MAX_SCALE_REFERENCE_AGE_MS = 60_000L;
    /**
     * A delivered-rate proof must bridge one 90-second parked collection interval, but must not
     * certify a fixed source forever after actual pack flow changes or disappears.
     */
    static final long SESSION_RATE_PROOF_MAX_AGE_MS = 120_000L;
    /** Physical ceiling on a credible derived rate, kW. */
    private static final double MAX_RATE_KW = 500.0;
    /**
     * Raw counter slopes may be one known unit factor above the physical ceiling while their unit is
     * still unresolved. They are retained only as calibration evidence and are never published
     * without a latched divisor that brings them back under {@link #MAX_RATE_KW}.
     */
    private static final double MAX_UNRESOLVED_COUNTER_SLOPE = MAX_RATE_KW * 100.0;
    /**
     * How far a RATE source may disagree with the metered reference before its SCALE is disbelieved.
     *
     * <p>Set at an order of magnitude: real disagreement between a charger-side rate and a
     * pack-side metered rate is charging loss (a few percent to ~20%), so 10x cannot be explained
     * that way and indicates a different unit. Deliberately loose — the check exists to catch a
     * 100x unit error, not to police accuracy, and being loose means it never rejects a correct
     * source over ordinary measurement disagreement.
     */
    private static final double SCALE_DISAGREEMENT_FACTOR = 10.0;
    /** Agreement required before latching that a raw RATE is already expressed in kW. */
    private static final double DIRECT_SCALE_CORROBORATION_FACTOR = 1.35;
    /**
     * The one sub-kW unit this hardware family is known to use: hectowatts (centi-kW), i.e. 100x.
     *
     * <p>Only ever applied when an INDEPENDENT kWh-grounded rate corroborates it to within
     * {@link #UNIT_FACTOR_TOLERANCE}. That is what separates this from the deleted
     * "raw > 50 -> divide" rule, which inferred the unit from the value itself and was therefore a
     * 100x error on whichever firmware family it guessed wrong about.
     */
    private static final double UNIT_FACTOR = 100.0;
    /** Captured PHEV failure signatures; both are impossible as delivered power on that drivetrain. */
    private static final double PHEV_IDLE_SIGNATURE_LOW = 359.0;
    private static final double PHEV_IDLE_SIGNATURE_HIGH = 360.0;
    private static final double PHEV_LARGE_SIGNATURE_LOW = 1320.0;
    private static final double PHEV_LARGE_SIGNATURE_HIGH = 1321.0;
    /**
     * Domain of a value acceptable as the kWh scale reference. Wide enough for the external counter's
     * register (observed at 119.0) as well as the 16-bit capacity counter's 65.534 ceiling.
     */
    private static final double COUNTER_REF_MAX_KWH = 500.0;
    /**
     * How close to the exact factor the observed ratio must sit. Deliberately tight: the point is to
     * recognise a UNIT, and a genuine unit mismatch lands at very nearly exactly 100x. A loose band
     * would start "calibrating" ordinary disagreement, which is how a guess creeps back in.
     */
    private static final double UNIT_FACTOR_TOLERANCE = 0.35;
    private static final double UNIT_FACTOR_LOW = UNIT_FACTOR * (1.0 - UNIT_FACTOR_TOLERANCE);
    private static final double UNIT_FACTOR_HIGH = UNIT_FACTOR * (1.0 + UNIT_FACTOR_TOLERANCE);
    /** One-shot log so a calibrated trim announces itself once rather than every poll. */
    private static volatile boolean loggedUnitCalibration = false;
    /**
     * Highest raw value publishable as kW with NO scale corroboration, kW.
     *
     * <p>22 kW is three-phase AC — the ceiling for onboard charging on this vehicle range. Above it
     * the candidate units diverge into genuinely different real rates (a 189.5 reading is either a
     * 189.5 kW DC session or a 1.895 kW AC one), so the value is withheld until the kWh-grounded
     * reference can settle it.
     *
     * <p>Below it the value is published, but that is a BOUNDED-ERROR decision rather than a safe
     * one, and the bound is what matters. Against a 100x-smaller unit it is genuinely safe: 22 would
     * mean 0.22 kW, which is not a charge anyone is metering. Against a 10x-smaller unit (hectowatts)
     * it is not — a raw 20 is either 20 kW or 2.0 kW, both real rates. Publishing is still the right
     * call there, because the alternative is withholding every AC charge on every trim whose counter
     * is dead (no yardstick can ever exist on those), and the error is bounded at 10x on a value
     * already known to be small. What must NOT happen is that figure being priced as if measured,
     * which is why {@link #WITHHOLD_ABOVE_UNVERIFIED_FOR_ENERGY} exists.
     */
    private static final double UNVERIFIED_SAFE_CEILING_KW = 22.0;
    /**
     * Below this, an unverified reading is safe against BOTH candidate units and may be treated as
     * fully measured. 2.2 kW is the 10x-down reading of the ceiling above: at or under it, even the
     * hectowatt interpretation lands on a rate too small to be a metered charge.
     */
    private static final double UNVERIFIED_FULLY_SAFE_KW = UNVERIFIED_SAFE_CEILING_KW / 10.0;
    /** Marker documenting that unverified mid-band rates are display-only; see {@link #isScaleVerified}. */
    private static final boolean WITHHOLD_ABOVE_UNVERIFIED_FOR_ENERGY = true;

    /**
     * A kW reference derived from the vehicle's own charged-energy counter, or NaN.
     *
     * <p>The counter's unit is DOCUMENTED as kWh over a bounded domain, so its slope is the one rate
     * figure whose scale is not in question. That makes it the yardstick for checking another
     * source's scale.
     *
     * <p>Deliberately independent of the classifier. An earlier version read this reference only
     * once {@code SRC_CAPACITY} had earned a COUNTER verdict, which made the scale check circular in
     * effect: the verdict needs 8 transitions and a 20-minute rise run, so for the first 20+ minutes
     * of a vehicle's first charge — and forever on a trim whose counter never earns a verdict — the
     * check silently did nothing and a sub-kW-scaled rate was published ~100x high. The counter needs
     * no verdict to be a valid yardstick, so it is fed here directly by
     * {@link #observeCounterForScale}.
     */
    /**
     * The kWh-grounded reference rate (the charged-energy counter's slope), or NaN when unavailable.
     *
     * <p>Public so the cascade can check a winning rate for ACCURACY, not merely for scale. The scale
     * check inside {@link #rateKw} uses an order-of-magnitude band because it is asking "is this even
     * in kW"; it deliberately tolerates a 4x error, which is exactly the size of the documented
     * EVSE-rated-vs-delivered discrepancy. Answering "is this the rate the PACK is taking" needs a
     * much tighter comparison, and only the caller knows which source won.
     */
    public static double referenceRateKw() {
        return meteredReferenceKw();
    }

    /**
     * The divisor a COUNTER source's raw readings need to be in kWh, or 1.0 when none.
     *
     * <p>Exposed because {@link #rateKw} corrects a counter's derived POWER while the raw value is
     * simultaneously fed into session ENERGY accumulation by a different path. Correcting one and not
     * the other would leave a trim showing plausible kW alongside an energy total 100x wrong — and
     * energy is the figure that gets priced, so it is the worse of the two to leave broken.
     *
     * <p>Returns 1.0 unless the counter's own slope disagrees with the reference by very nearly exactly
     * the unit factor. For the source that IS the reference this is a self-comparison and always yields
     * 1.0, which is correct: its unit is the documented one by definition.
     */
    public static synchronized double counterUnitDivisor(String source) {
        if (source == null || ChargeSourceClassifier.SRC_CAPACITY.equals(source)) return 1.0;
        if (!ChargeSourceClassifier.isCounter(source)) return 1.0;
        // LATCHED, NOT RECOMPUTED. A divisor derived live flips between 1 and 100 as the slope and the
        // reference come and go, and a counter series accumulated across a flip mixes two units — the
        // opening portion is then discarded as a "fall" or double-counted as a rise. The unit is a
        // property of the firmware, so it is decided ONCE per source and then held.
        Double latched = latchedDivisors.get(source);
        if (latched != null) return latched;
        // NEVER JUDGE A SOURCE AGAINST ITS OWN REFERENCE. On a trim where the capacity getter is dead
        // the external counter is the only reference feed, so the comparison becomes a self-comparison
        // that always yields ~1.0 — and latching that permanently blocked the very correction this
        // exists to make. Require an INDEPENDENT reference: one fed by a different source.
        if (!referenceIsIndependentOf(source)) return 1.0;
        double slope = currentRawSlope(source);
        double ref = currentMeteredReferenceKw();
        if (Double.isNaN(slope) || slope <= 0 || Double.isNaN(ref) || ref <= 0.1) return 1.0;
        double ratio = slope / ref;
        if (ratio >= UNIT_FACTOR_LOW && ratio <= UNIT_FACTOR_HIGH) {
            Double previous = latchedDivisors.put(source, UNIT_FACTOR);
            if (previous == null || previous.doubleValue() != UNIT_FACTOR) {
                evidenceMutationGeneration++;
            }
            logger.info("Latched unit divisor " + UNIT_FACTOR + " for '" + source
                    + "' — its slope sits at a factor of "
                    + String.format(java.util.Locale.US, "%.1f", ratio)
                    + " against the kWh reference, so it reports in a smaller unit");
            return UNIT_FACTOR;
        }
        // Only latch a 1.0 once the comparison was genuinely possible AND unambiguous, so a transient
        // absence of the reference cannot freeze the wrong answer.
        if (slope <= MAX_RATE_KW && ratio < UNIT_FACTOR_LOW / 2.0) {
            Double previous = latchedDivisors.put(source, 1.0);
            if (previous == null || previous.doubleValue() != 1.0) {
                evidenceMutationGeneration++;
            }
            return 1.0;
        }
        return 1.0;
    }

    /** Per-source unit divisors, decided once. Cleared with the session (see {@link #onSessionEnded}). */
    private static final java.util.concurrent.ConcurrentHashMap<String, Double> latchedDivisors =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * RATE-source unit divisors proven by an independent reference in the current physical session.
     *
     * <p>The same accessor can expose a retained value from the preceding charge. Carrying its divisor
     * into a new session would turn that stale value into a measured, priceable rate before any
     * current-session evidence existed.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Double> latchedRateDivisors =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Immutable delivered-rate proof; rate and timestamp must never be observed from different writes. */
    private static final class SessionRateProof {
        final double rateKw;
        final long observedAtMs;

        SessionRateProof(double rateKw, long observedAtMs) {
            this.rateKw = rateKw;
            this.observedAtMs = observedAtMs;
        }
    }

    /** Last independently corroborated delivered rate for each source in the current session. */
    private static final java.util.concurrent.ConcurrentHashMap<String, SessionRateProof>
            sessionRateProofs = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Generation of session-scoped resolver state. A pending API read may not publish evidence after
     * a stop/restart cycle, even when the new session happens to expose the same numeric values.
     */
    private static volatile long sessionEvidenceGeneration = 1L;
    /**
     * Advances on every globally visible resolver-evidence mutation. API reads use this as a seqlock
     * so concurrent readers cannot combine divisors/proofs from different calibration moments.
     */
    private static volatile long evidenceMutationGeneration = 1L;

    private static final class PendingCalibrationLog {
        final String source;
        final double raw;
        final double scaleRef;
        final double resolved;
        final String referenceLabel;

        PendingCalibrationLog(String source, double raw, double scaleRef,
                              double resolved, String referenceLabel) {
            this.source = source;
            this.raw = raw;
            this.scaleRef = scaleRef;
            this.resolved = resolved;
            this.referenceLabel = referenceLabel;
        }
    }

    private static final class EvidenceTransaction {
        final long sessionGeneration;
        final long mutationGeneration;
        final java.util.HashMap<String, Double> rateDivisors = new java.util.HashMap<>();
        final java.util.HashMap<String, SessionRateProof> proofs = new java.util.HashMap<>();
        PendingCalibrationLog calibrationLog;

        EvidenceTransaction(long sessionGeneration, long mutationGeneration) {
            this.sessionGeneration = sessionGeneration;
            this.mutationGeneration = mutationGeneration;
        }

        boolean hasWrites() {
            return !rateDivisors.isEmpty() || !proofs.isEmpty();
        }
    }

    private static final ThreadLocal<EvidenceTransaction> EVIDENCE_TRANSACTION =
            new ThreadLocal<>();
    /** Diagnostic deferred until the caller releases detector/publication locks. */
    private static final ThreadLocal<PendingCalibrationLog> COMMITTED_CALIBRATION_LOG =
            new ThreadLocal<>();

    /**
     * Stage read-derived calibration until the caller validates the complete detector/collector view.
     */
    static void beginEvidenceTransaction() {
        flushCommittedEvidenceLog();
        synchronized (ChargeRateResolver.class) {
            if (EVIDENCE_TRANSACTION.get() != null) {
                throw new IllegalStateException("Nested charge-rate evidence transaction");
            }
            EVIDENCE_TRANSACTION.set(new EvidenceTransaction(
                    sessionEvidenceGeneration, evidenceMutationGeneration));
        }
    }

    /**
     * Publish the current thread's staged evidence if no session or resolver evidence changed.
     *
     * <p>The caller must invoke this while holding the detector's atomic publication-validation
     * boundary. Returning {@code false} means the state build must be retried.
     */
    static boolean commitEvidenceTransaction() {
        EvidenceTransaction transaction = EVIDENCE_TRANSACTION.get();
        if (transaction == null) return true;
        EVIDENCE_TRANSACTION.remove();

        synchronized (ChargeRateResolver.class) {
            if (transaction.sessionGeneration != sessionEvidenceGeneration
                    || transaction.mutationGeneration != evidenceMutationGeneration) {
                return false;
            }
            if (transaction.hasWrites() && !sessionOpenForScaleRef) {
                return false;
            }
            latchedRateDivisors.putAll(transaction.rateDivisors);
            sessionRateProofs.putAll(transaction.proofs);
            if (transaction.hasWrites()) {
                evidenceMutationGeneration++;
            }
            if (transaction.calibrationLog != null) {
                COMMITTED_CALIBRATION_LOG.set(transaction.calibrationLog);
            }
        }
        return true;
    }

    /** Emit diagnostics only after the caller has released its atomic publication locks. */
    static void flushCommittedEvidenceLog() {
        PendingCalibrationLog calibrationLog = COMMITTED_CALIBRATION_LOG.get();
        if (calibrationLog == null) return;
        COMMITTED_CALIBRATION_LOG.remove();
        emitUnitCalibrationLog(calibrationLog);
    }

    /** Drop staged evidence after a failed/exceptional state build. */
    static void discardEvidenceTransaction() {
        EVIDENCE_TRANSACTION.remove();
    }

    private static double meteredReferenceKw() {
        return meteredReferenceKw(MAX_SLOPE_MS);
    }

    /** A reference recent enough to establish or change a persistent source-unit decision. */
    private static double currentMeteredReferenceKw() {
        return meteredReferenceKw(MAX_SCALE_REFERENCE_AGE_MS);
    }

    private static double meteredReferenceKw(long maxAgeMs) {
        Slope s = slopes.get(SCALE_REF_KEY);
        if (s == null) return Double.NaN;
        synchronized (s) {
            long nowMs = System.currentTimeMillis();
            if (Double.isNaN(s.lastRateKw) || s.lastRateAtMs <= 0
                    || nowMs - s.lastRateAtMs > maxAgeMs) {
                return Double.NaN;
            }
            return s.lastRateKw;
        }
    }

    /** Slope key for the scale reference, kept separate from the classifier-driven slope state. */
    private static final String SCALE_REF_KEY = "__scaleRef";

    /**
     * Feed the charged-energy counter's raw reading purely to maintain the scale reference.
     *
     * <p>Called on every admitted counter observation regardless of what the classifier has decided,
     * so the yardstick exists from the second reading of the first charge onward.
     *
     * @param kwh raw counter value, kWh (the SDK-documented domain is [0, 65.534])
     */
    public static void observeCounterForScale(double kwh) {
        // Legacy entry point: the capacity counter's unit is the documented one, so no normalisation.
        observeCounterForScale(ChargeSourceClassifier.SRC_CAPACITY, kwh);
    }

    /**
     * Advance a source's slope state from the COLLECT path, at the moment the value was read.
     *
     * <p>This is the only place a counter's slope may be advanced. {@link #rateKw} used to do it, but
     * that method is called from {@code getChargingState()} — a READ path reached from HTTP, MQTT,
     * ABRP, the SoC recorder and the fast sampler — so the slope's time base was the API read
     * cadence rather than the telemetry observation time. Two consequences, both real: the same
     * counter increment resolved to a different kW for each caller (whoever read first consumed the
     * delta and re-anchored, leaving the others to see dt≈0), and a burst of HTTP polls could
     * manufacture or destroy a slope window without any new telemetry. A rate that gets PERSISTED as
     * measured power and integrated into priced energy cannot depend on who happened to poll.
     *
     * @param source one of {@link ChargeSourceClassifier}'s {@code SRC_*} keys
     * @param raw    the RAW stored value as just observed, or NaN to ignore
     */
    public static void observe(String source, double raw) {
        observe(source, raw, System.currentTimeMillis());
    }

    /** Testable variant taking the telemetry observation time explicitly. */
    static void observe(String source, double raw, long observedAtMs) {
        if (!sessionOpenForScaleRef || source == null || Double.isNaN(raw)) return;
        derive(source, raw, observedAtMs);
    }

    private ChargeRateResolver() {}

    /**
     * Whether a raw charging-power accessor is returning a captured PHEV failure signature.
     *
     * <p>This is deliberately drivetrain-aware: roughly 359 kW can be a real BEV DC rate, but the
     * same raw value and the 1320.10 signature are junk on the captured PHEV. Callers must apply this
     * before detector movement, behavioral classification, or unit calibration.
     */
    public static boolean isKnownPhevRawPowerJunk(double raw, boolean phev) {
        if (!phev || !Double.isFinite(raw)) return false;
        return (raw >= PHEV_IDLE_SIGNATURE_LOW && raw < PHEV_IDLE_SIGNATURE_HIGH)
                || (raw >= PHEV_LARGE_SIGNATURE_LOW && raw < PHEV_LARGE_SIGNATURE_HIGH);
    }

    private static final class Slope {
        double lastValue = Double.NaN;
        long lastAtMs = 0;
        double lastRawRateKw = Double.NaN;
        long lastRawRateAtMs = 0;
        double lastRateKw = Double.NaN;
        long lastRateAtMs = 0;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Slope> slopes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Interpret one source's raw stored value as a kW rate.
     *
     * @param source one of {@link ChargeSourceClassifier}'s {@code SRC_*} keys
     * @param raw    the RAW stored value, or NaN
     * @return kW, or NaN when this source cannot currently express a rate
     */
    public static double rateKw(String source, double raw) {
        return rateKw(source, raw, Double.NaN);
    }

    /**
     * Resolve a source with an optional independent same-session pack-flow reference.
     *
     * <p>The separately sampled charging-direction motor-bus flow is preferred because it describes
     * the current observation. A recent charged-energy-counter slope is the fallback yardstick; an
     * older held slope may still be displayed elsewhere, but it cannot establish a unit. Callers must
     * pass NaN unless the pack-flow reference is fresh, same-session, and independent of {@code source}.
     */
    public static double rateKw(String source, double raw, double independentPackFlowKw) {
        if (Double.isNaN(raw)) return Double.NaN;
        // The capacity accessor is a documented cumulative kWh counter. Resolve it by slope
        // unconditionally so a stale persisted classifier verdict can never publish the total as kW.
        if (ChargeSourceClassifier.SRC_CAPACITY.equals(source)) {
            return calibratedCounterSlope(source);
        }
        ChargeSourceClassifier.Kind kind = ChargeSourceClassifier.kindOf(source);
        double packFlowRef = validReference(independentPackFlowKw)
                ? independentPackFlowKw : Double.NaN;
        if (kind == ChargeSourceClassifier.Kind.UNKNOWN) {
            // A current independent pack measurement can prove both an in-band raw register
            // (320 -> 3.2 kW) and an out-of-band one (650 -> 6.5 kW). Once proved, retain that
            // divisor only for this physical session so the 12-second sampler does not lose the
            // measured rate when the 15-second pack-flow sample expires between 90-second polls.
            if (!Double.isNaN(packFlowRef)) {
                double resolved = resolveSessionRateValue(source, raw, packFlowRef);
                if (!Double.isNaN(resolved) && resolved != raw) {
                    logUnitCalibration(source, raw, packFlowRef, resolved, "pack-flow");
                }
                return resolved;
            }
            return resolveWithSessionDivisor(source, raw);
        }
        if (kind == ChargeSourceClassifier.Kind.RATE) {
            Double latchedRateDivisor = currentSessionRateDivisor(source);
            // A current independent pack-flow observation outranks every held/latching decision.
            // This both avoids rejecting a correct current rate against an old counter slope and lets
            // current evidence repair a divisor that was learned from an older operating point.
            if (latchedRateDivisor != null && Double.isNaN(packFlowRef)) {
                double scaled = raw / latchedRateDivisor;
                return scaled > 0 && scaled <= MAX_RATE_KW ? scaled : Double.NaN;
            }
            // A RATE still has to be in kW. Classification proves a value BEHAVES like a rate; it
            // says nothing about its scale, and a firmware reporting in a sub-kW unit would publish
            // a plausible-looking figure ~100x high. Rather than guess from the magnitude — the
            // exact mistake this subsystem removed — cross-check against the vehicle's own metered
            // energy counter, which is unambiguously kWh: if the two disagree by about two orders of
            // magnitude, the rate source is not in kW and is refused.
            double scaleRef = preferredScaleReference(
                    packFlowRef, currentMeteredReferenceKw());
            String referenceLabel = !Double.isNaN(packFlowRef)
                    ? "pack-flow" : "counter-derived";
            double resolved = validReference(scaleRef)
                    ? resolveSessionRateValue(source, raw, scaleRef)
                    : resolveRateValueAgainstReference(raw, Double.NaN);
            if (!Double.isNaN(resolved) && resolved != raw) {
                logUnitCalibration(source, raw, scaleRef, resolved, referenceLabel);
            }
            return resolved;
        }
        if (kind == ChargeSourceClassifier.Kind.COUNTER) {
            double slope = currentSlope(source);
            // Corroborate a DERIVED slope exactly as a RATE reading is corroborated. Classification
            // can be wrong: a rate accessor whose reading only ever creeps upward can earn a COUNTER
            // verdict, and differentiating a kW series then produces an arbitrary figure (a +0.5 kW
            // creep over 120 s "derives" 15 kW; +2.0 over 20 s derives 360 kW) that is in-band and
            // would publish unflagged. The charged-energy counter is the one source whose unit is
            // documented, so its own slope is the yardstick — and for that source this check is a
            // self-comparison that always passes.
            if (Double.isNaN(slope)) {
                return slope;
            }
            double scaleRef = counterScaleReferenceForSource(
                    source, packFlowRef, currentMeteredReferenceKw());
            if (!Double.isNaN(scaleRef) && scaleRef > 0.1) {
                double ratio = slope / scaleRef;
                // Same unit calibration as the RATE branch: a counter whose readings are in a
                // 100x-smaller unit produces a slope 100x too large, and the kWh-grounded reference
                // identifies that as a unit rather than as noise.
                if (ratio >= UNIT_FACTOR_LOW && ratio <= UNIT_FACTOR_HIGH) {
                    double resolved = slope / UNIT_FACTOR;
                    rememberSessionRateProof(source, resolved, scaleRef);
                    return resolved;
                }
                if (ratio > SCALE_DISAGREEMENT_FACTOR || ratio < 1.0 / SCALE_DISAGREEMENT_FACTOR) {
                    return Double.NaN;
                }
                rememberSessionRateProof(source, slope, scaleRef);
                return slope;
            }
            // No yardstick: same rule as an unverified RATE — publish only what is self-evidently a
            // plausible kW rate, and withhold the rest rather than assert it.
            return (slope <= UNVERIFIED_SAFE_CEILING_KW) ? slope : Double.NaN;
        }
        // The charged-energy counter needs no verdict to be differentiable. Its unit and its
        // cumulative nature are DOCUMENTED (a bounded kWh total), so its slope is a valid measured
        // rate from the second reading onward. Requiring a classifier verdict here would withhold it
        // for the first 20+ minutes of every install — exactly when no other source has been
        // corroborated either, which is the window where the cascade most needs a trustworthy figure.
        // Any other UNKNOWN source is still refused: only this one has an a-priori unit.
        return Double.NaN;   // UNKNOWN — refuse rather than guess
    }

    /**
     * Narrow UNKNOWN-source admission for an out-of-band raw RATE corroborated at exactly 100x.
     * The caller contract on {@link #rateKw(String, double, double)} supplies the freshness and
     * same-session guarantees for {@code independentPackFlowKw}.
     */
    static double resolveUnknownRateAgainstPackFlow(double raw, double independentPackFlowKw) {
        if (!(raw > MAX_RATE_KW) || !validReference(independentPackFlowKw)) {
            return Double.NaN;
        }
        double ratio = raw / independentPackFlowKw;
        if (ratio < UNIT_FACTOR_LOW || ratio > UNIT_FACTOR_HIGH) return Double.NaN;
        double resolved = raw / UNIT_FACTOR;
        return resolved > 0 && resolved <= MAX_RATE_KW ? resolved : Double.NaN;
    }

    /**
     * The charged-energy counter's slope, corrected by any PROVEN register-width fault, or NaN.
     *
     * <p>The counter's unit is documented, but a field capture proved the documentation wrong on at
     * least one trim: the register advanced at half the energy actually delivered, so its slope was
     * published as half the real rate and every consumer inherited that. {@link CounterScaleCalibrator}
     * decides the factor from a long-baseline comparison against an independent register.
     *
     * <p>WITHHOLDING IS THE SAFE DIRECTION. While a fault is INDICATED but not yet proven the slope is
     * refused rather than published: the cascade then falls to another source, or to a flagged
     * estimate, both of which are recoverable. Publishing an unproven slope is not — this source is
     * the yardstick that vetoes every other rate, so a wrong value here silently suppresses the
     * sources that would have exposed it, and then gets priced.
     */
    private static double calibratedCounterSlope(String source) {
        double slope = currentSlope(source);
        if (Double.isNaN(slope)) return slope;
        double factor = com.overdrive.app.charging.CounterScaleCalibrator.factorFor(source);
        if (factor != 1.0) {
            double corrected = slope * factor;
            return corrected > 0 && corrected <= MAX_RATE_KW ? corrected : Double.NaN;
        }
        if (com.overdrive.app.charging.CounterScaleCalibrator.isScaleSuspect(source)) {
            return Double.NaN;
        }
        return slope;
    }

    /** Prefer a current independent pack measurement to a potentially held counter slope. */
    static double preferredScaleReference(double independentPackFlowKw,
                                          double counterDerivedKw) {
        if (validReference(independentPackFlowKw)) return independentPackFlowKw;
        return validReference(counterDerivedKw) ? counterDerivedKw : Double.NaN;
    }

    /** A counter cannot corroborate its own unit through the shared metered-reference slope. */
    static double counterScaleReferenceForSource(String source, double independentPackFlowKw,
                                                 double counterDerivedKw) {
        double independentCounterRef = referenceIsIndependentOf(source)
                ? counterDerivedKw : Double.NaN;
        return preferredScaleReference(independentPackFlowKw, independentCounterRef);
    }

    private static boolean validReference(double kw) {
        return !Double.isNaN(kw) && kw > 0.1 && kw <= MAX_RATE_KW;
    }

    /**
     * Require direct same-scale corroboration for a raw value that is also a known idle signature.
     *
     * <p>A current reference outranks a held proof. When no current reference is available, a recent
     * proof from this physical session may bridge the sparse parked-poll interval.
     */
    static boolean hasSameSessionDirectCorroboration(String source, double raw,
                                                     double independentReferenceKw) {
        if (!validReference(raw)) return false;
        if (validReference(independentReferenceKw)) {
            double ratio = raw / independentReferenceKw;
            return ratio >= 1.0 / DIRECT_SCALE_CORROBORATION_FACTOR
                    && ratio <= DIRECT_SCALE_CORROBORATION_FACTOR;
        }
        return isSessionRateCorroborated(source, raw);
    }

    /**
     * Resolve a raw instantaneous source and retain independently-proven scale/accuracy for this
     * physical session. With no current reference, a proven divisor may be reused; otherwise the raw
     * value is returned only for display and the caller's scale gate keeps it out of persistence.
     */
    static double resolveSessionRateValue(String source, double raw, double referenceKw) {
        if (source == null || Double.isNaN(raw) || !(raw > 0)
                || raw > MAX_RATE_KW * UNIT_FACTOR) {
            return Double.NaN;
        }
        if (validReference(referenceKw)) {
            double resolved = resolveRateValueAgainstReference(raw, referenceKw);
            if (!Double.isNaN(resolved)) {
                latchRateDivisorFromReference(source, raw, referenceKw);
                rememberSessionRateProof(source, resolved, referenceKw);
            }
            return resolved;
        }
        double held = resolveWithSessionDivisor(source, raw);
        if (!Double.isNaN(held)) return held;
        return raw <= MAX_RATE_KW ? raw : Double.NaN;
    }

    /** Apply only a divisor established during the current physical charging session. */
    private static double resolveWithSessionDivisor(String source, double raw) {
        Double divisor = currentSessionRateDivisor(source);
        if (divisor == null || Double.isNaN(raw) || !(raw > 0)) return Double.NaN;
        double resolved = raw / divisor;
        return resolved > 0 && resolved <= MAX_RATE_KW ? resolved : Double.NaN;
    }

    private static Double currentSessionRateDivisor(String source) {
        if (source == null) return null;
        EvidenceTransaction transaction = EVIDENCE_TRANSACTION.get();
        if (transaction != null && transaction.rateDivisors.containsKey(source)) {
            return transaction.rateDivisors.get(source);
        }
        return latchedRateDivisors.get(source);
    }

    private static void rememberSessionRateProof(String source, double resolvedKw,
                                                 double referenceKw) {
        if (!sessionOpenForScaleRef || source == null
                || !validReference(resolvedKw) || !validReference(referenceKw)) {
            return;
        }
        double ratio = resolvedKw / referenceKw;
        if (ratio >= 1.0 / DIRECT_SCALE_CORROBORATION_FACTOR
                && ratio <= DIRECT_SCALE_CORROBORATION_FACTOR) {
            long nowMs = System.currentTimeMillis();
            SessionRateProof proof = new SessionRateProof(resolvedKw, nowMs);
            EvidenceTransaction transaction = EVIDENCE_TRANSACTION.get();
            if (transaction != null) {
                SessionRateProof existing = transaction.proofs.containsKey(source)
                        ? transaction.proofs.get(source) : sessionRateProofs.get(source);
                // API reads must not renew proof age merely because another consumer asked for the
                // same snapshot. The collect path (which has no transaction) refreshes it when a new
                // telemetry observation actually arrives.
                if (existing != null
                        && Double.doubleToLongBits(existing.rateKw)
                            == Double.doubleToLongBits(resolvedKw)) {
                    return;
                }
                transaction.proofs.put(source, proof);
                return;
            }
            synchronized (ChargeRateResolver.class) {
                if (!sessionOpenForScaleRef) return;
                sessionRateProofs.put(source, proof);
                evidenceMutationGeneration++;
            }
        }
    }

    /**
     * Whether this source's current rate remains near a delivered-rate observation independently
     * proved during the same physical session.
     */
    public static boolean isSessionRateCorroborated(String source, double publishedKw) {
        return isSessionRateCorroborated(source, publishedKw, System.currentTimeMillis());
    }

    static boolean isSessionRateCorroborated(String source, double publishedKw, long nowMs) {
        if (!sessionOpenForScaleRef || source == null || !validReference(publishedKw)) return false;
        EvidenceTransaction transaction = EVIDENCE_TRANSACTION.get();
        SessionRateProof proof = transaction != null && transaction.proofs.containsKey(source)
                ? transaction.proofs.get(source) : sessionRateProofs.get(source);
        if (proof == null || !validReference(proof.rateKw)
                || nowMs < proof.observedAtMs
                || nowMs - proof.observedAtMs > SESSION_RATE_PROOF_MAX_AGE_MS) {
            return false;
        }
        double ratio = publishedKw / proof.rateKw;
        return ratio >= 1.0 / DIRECT_SCALE_CORROBORATION_FACTOR
                && ratio <= DIRECT_SCALE_CORROBORATION_FACTOR;
    }

    private static void latchRateDivisorFromReference(String source, double raw, double referenceKw) {
        if (!sessionOpenForScaleRef || source == null
                || !validReference(referenceKw) || !(raw > 0)) {
            return;
        }
        double ratio = raw / referenceKw;
        Double divisor = null;
        if (ratio >= UNIT_FACTOR_LOW && ratio <= UNIT_FACTOR_HIGH) {
            divisor = UNIT_FACTOR;
        } else if (ratio >= 1.0 / DIRECT_SCALE_CORROBORATION_FACTOR
                && ratio <= DIRECT_SCALE_CORROBORATION_FACTOR) {
            divisor = 1.0;
        }
        if (divisor == null) return;

        EvidenceTransaction transaction = EVIDENCE_TRANSACTION.get();
        if (transaction != null) {
            Double existing = currentSessionRateDivisor(source);
            if (existing != null
                    && existing.doubleValue() == divisor.doubleValue()) {
                return;
            }
            transaction.rateDivisors.put(source, divisor);
            return;
        }
        synchronized (ChargeRateResolver.class) {
            if (!sessionOpenForScaleRef) return;
            Double previous = latchedRateDivisors.put(source, divisor);
            if (previous == null || previous.doubleValue() != divisor.doubleValue()) {
                evidenceMutationGeneration++;
            }
        }
    }

    private static void logUnitCalibration(String source, double raw, double scaleRef,
                                           double resolved, String referenceLabel) {
        EvidenceTransaction transaction = EVIDENCE_TRANSACTION.get();
        if (transaction != null) {
            if (transaction.calibrationLog == null) {
                transaction.calibrationLog = new PendingCalibrationLog(
                        source, raw, scaleRef, resolved, referenceLabel);
            }
            return;
        }
        emitUnitCalibrationLog(new PendingCalibrationLog(
                source, raw, scaleRef, resolved, referenceLabel));
    }

    private static void emitUnitCalibrationLog(PendingCalibrationLog calibration) {
        synchronized (ChargeRateResolver.class) {
            if (loggedUnitCalibration) return;
            loggedUnitCalibration = true;
        }
        logger.info(String.format(java.util.Locale.US,
                "Source '%s' reports in a %.0fx-smaller unit: raw %.3f against a"
                + " %s %.3f kW is calibrated to %.3f kW",
                calibration.source, UNIT_FACTOR, calibration.raw,
                calibration.referenceLabel, calibration.scaleRef, calibration.resolved));
    }

    /**
     * Pure RATE-scale decision used by {@link #rateKw(String, double, double)} and focused tests.
     * Magnitude alone never selects a divisor; conversion requires a reference near the exact unit ratio.
     */
    static double resolveRateValueAgainstReference(double raw, double referenceKw) {
        // Admit the hectowatt range: the unit is exactly what is in question. The resolved value is
        // bounded below, so nothing above the physical kW ceiling escapes.
        if (!(raw > 0 && raw <= MAX_RATE_KW * UNIT_FACTOR)) return Double.NaN;
        if (!Double.isNaN(referenceKw) && referenceKw > 0.1 && referenceKw <= MAX_RATE_KW) {
            double ratio = raw / referenceKw;
            if (ratio >= UNIT_FACTOR_LOW && ratio <= UNIT_FACTOR_HIGH) {
                double scaled = raw / UNIT_FACTOR;
                return scaled <= MAX_RATE_KW ? scaled : Double.NaN;
            }
            if (ratio > SCALE_DISAGREEMENT_FACTOR
                    || ratio < 1.0 / SCALE_DISAGREEMENT_FACTOR) {
                return Double.NaN;
            }
            return raw <= MAX_RATE_KW ? raw : Double.NaN;
        }
        // No yardstick: retain the existing bounded display-only behavior and never guess a divisor.
        return raw <= UNVERIFIED_SAFE_CEILING_KW ? raw : Double.NaN;
    }

    /**
     * Whether a published rate's SCALE is corroborated, not merely its plausibility.
     *
     * <p>{@link #rateKw} publishes an uncorroborated reading below {@link #UNVERIFIED_SAFE_CEILING_KW}
     * so that AC charging still shows a figure on trims where no kWh yardstick can ever exist. That
     * is right for DISPLAY but not for PRICING: in the 2.2-22 kW band a hectowatt-scaled firmware
     * would be wrong by 10x, and integrating that into billed energy is a money error. Callers that
     * persist or price a rate should treat an unverified figure as an estimate.
     *
     * @return true when the value came from a yardstick-corroborated source, or is small enough to be
     *         correct under every candidate unit
     */
    public static boolean isScaleVerified(String source, double publishedKw) {
        return isScaleVerified(source, publishedKw, Double.NaN);
    }

    /**
     * Variant accepting the same independent pack-flow yardstick used by the resolver.
     */
    public static boolean isScaleVerified(String source, double publishedKw,
                                          double independentPackFlowKw) {
        if (Double.isNaN(publishedKw)) return false;
        if (publishedKw <= UNVERIFIED_FULLY_SAFE_KW) return true;
        // The charged-energy counter's unit is documented — but a field capture proved a trim whose
        // register advances at half the delivered energy, so "documented" is not the same as verified.
        // A suspected width fault therefore revokes the exemption instead of vouching for it.
        if (ChargeSourceClassifier.SRC_CAPACITY.equals(source)) {
            return !com.overdrive.app.charging.CounterScaleCalibrator.isScaleSuspect(source);
        }
        if (hasProvenUnitScale(source)) return true;
        if (validReference(independentPackFlowKw)) {
            double ratio = publishedKw / independentPackFlowKw;
            return ratio >= 1.0 / DIRECT_SCALE_CORROBORATION_FACTOR
                    && ratio <= DIRECT_SCALE_CORROBORATION_FACTOR;
        }
        return isSessionRateCorroborated(source, publishedKw);
    }

    /**
     * Whether this firmware source has an independently established unit divisor.
     *
     * <p>This proves scale only, not that an instantaneous value matches a lagging interval-average
     * energy slope. Callers must retain their separate accuracy/overbilling gate.
     */
    static boolean hasProvenUnitScale(String source) {
        if (source == null) return false;
        if (ChargeSourceClassifier.SRC_CAPACITY.equals(source)) return true;
        if (currentSessionRateDivisor(source) != null) return true;
        return ChargeSourceClassifier.isCounter(source) && latchedDivisors.containsKey(source);
    }

    /**
     * The slope most recently derived for a source, without advancing it.
     *
     * <p>{@link #rateKw} is a pure read: the slope is advanced only by {@link #observe} on the collect
     * path, so every consumer of one telemetry cycle sees the SAME kW figure regardless of when or how
     * often it asks. The held value carries its own staleness bound (see {@link #heldRate}), so a
     * source that stops advancing goes to NaN rather than republishing forever.
     */
    private static double currentSlope(String source) {
        double rawSlope = currentRawSlope(source);
        if (Double.isNaN(rawSlope)) return Double.NaN;
        Double divisor = latchedDivisors.get(source);
        double resolved = divisor != null && divisor > 1.0 ? rawSlope / divisor : rawSlope;
        return resolved > 0 && resolved <= MAX_RATE_KW ? resolved : Double.NaN;
    }

    /** Raw differentiated slope, retained briefly even when its unresolved unit exceeds 500 kW. */
    private static double currentRawSlope(String source) {
        Slope s = slopes.get(source);
        if (s == null) return Double.NaN;
        synchronized (s) {
            return heldRawRate(s, System.currentTimeMillis());
        }
    }

    /**
     * Slope of a cumulative counter, kWh/h = kW.
     *
     * <p>Holds the previous accepted rate across short intervals so the published figure does not
     * flicker to nothing between counter quanta: at 7 kW a 1 Wh counter advances every ~0.5 s, but
     * at 0.3 kW taper it can sit unchanged for many seconds, and a blank there reads to the user as
     * "not charging" rather than "charging slowly".
     */
    private static double derive(String source, double value, long nowMs) {
        Slope s = slopes.computeIfAbsent(source, k -> new Slope());
        synchronized (s) {
            if (Double.isNaN(s.lastValue)) {
                s.lastValue = value;
                s.lastAtMs = nowMs;
                return Double.NaN;
            }
            long dtMs = nowMs - s.lastAtMs;
            if (dtMs <= 0) return heldRate(s, nowMs);
            double delta = value - s.lastValue;

            if (delta < 0) {
                // Wrap or reset — either way the slope across this boundary is not meaningful.
                // Re-anchor and hold the previous rate briefly rather than publish a negative.
                s.lastValue = value;
                s.lastAtMs = nowMs;
                return heldRate(s, nowMs);
            }
            if (dtMs < MIN_SLOPE_MS) {
                // Too soon for a stable slope; keep the anchor and report the held rate.
                return heldRate(s, nowMs);
            }
            s.lastValue = value;
            s.lastAtMs = nowMs;
            if (dtMs > MAX_SLOPE_MS) {
                // Stale anchor: no claim about "now", and the held rate is older still.
                s.lastRawRateKw = Double.NaN;
                s.lastRawRateAtMs = 0;
                s.lastRateKw = Double.NaN;
                s.lastRateAtMs = 0;
                return Double.NaN;
            }

            double kw = delta / (dtMs / 3_600_000.0);
            if (kw <= 0 || kw > MAX_UNRESOLVED_COUNTER_SLOPE) {
                // A full slope window elapsed and the counter did NOT advance (or advanced
                // implausibly). That is positive evidence the charge has stopped, so the held rate
                // must be INVALIDATED rather than left to be republished on the next sub-window
                // call — otherwise a frozen counter keeps announcing its pre-freeze kW.
                s.lastRawRateKw = Double.NaN;
                s.lastRawRateAtMs = 0;
                s.lastRateKw = Double.NaN;
                s.lastRateAtMs = 0;
                return Double.NaN;
            }
            s.lastRawRateKw = kw;
            s.lastRawRateAtMs = nowMs;
            if (kw > MAX_RATE_KW) {
                // Keep the raw slope solely for an exact independent unit comparison. Until that
                // comparison latches a divisor, currentSlope() remains NaN and nothing can expose it.
                s.lastRateKw = Double.NaN;
                s.lastRateAtMs = 0;
                return Double.NaN;
            }
            s.lastRateKw = kw;
            s.lastRateAtMs = nowMs;
            return kw;
        }
    }

    /** The last derived rate, while it is still recent enough to describe the present. */
    private static double heldRate(Slope s, long nowMs) {
        if (Double.isNaN(s.lastRateKw)) return Double.NaN;
        return isHeldRateFresh(s.lastRateAtMs, nowMs) ? s.lastRateKw : Double.NaN;
    }

    /** The unresolved slope is internal calibration evidence, never a directly publishable rate. */
    private static double heldRawRate(Slope s, long nowMs) {
        if (Double.isNaN(s.lastRawRateKw)) return Double.NaN;
        return isHeldRateFresh(s.lastRawRateAtMs, nowMs) ? s.lastRawRateKw : Double.NaN;
    }

    static boolean isHeldRateFresh(long lastRateAtMs, long nowMs) {
        return lastRateAtMs > 0 && nowMs >= lastRateAtMs
                && nowMs - lastRateAtMs <= MAX_HELD_RATE_MS;
    }

    /** Drop all slope state. Called on the charging edge so one session cannot bleed into the next. */
    public static synchronized void onSessionEnded() {
        slopes.clear();
        // RELEASE THE REFERENCE OWNER with the slope it owned. Keeping it meant a later
        // external-counter-only session was refused as a "challenger" to a capacity owner whose slope no
        // longer exists, so it could never establish the reference it needs — and therefore never
        // calibrate. Ownership is only meaningful while a slope backs it.
        scaleRefOwner = null;
        sessionOpenForScaleRef = false;
        latchedRateDivisors.clear();
        sessionRateProofs.clear();
        sessionEvidenceGeneration++;
        evidenceMutationGeneration++;
        // Counter divisors remain firmware-scoped. Unlike an instantaneous retained value, a cumulative
        // counter's persisted endpoints explicitly identify its source and are reconciled across restart.
        // Per-session calibration anchors must go, or the next session pairs its first reading against
        // this session's last one. The decided verdict is firmware-scoped and survives.
        com.overdrive.app.charging.CounterScaleCalibrator.onSessionEnded();
    }

    /**
     * Feed a counter reading intended as the kWh SCALE REFERENCE.
     *
     * <p>Normalised through {@link #counterUnitDivisor} first. The reference is the yardstick every
     * other source's scale is judged against, so admitting an un-normalised hectowatt reading would
     * corrupt the very comparison used to detect hectowatts — a source could validate itself against
     * its own mis-scaled value and conclude it was already in kW.
     */
    /**
     * Session generation, bumped by {@link #onSessionEnded}. A terminal-state callback that lands AFTER a
     * session ends must not recreate the reference for the NEXT session to inherit, so a feed is only
     * accepted while a session is current.
     */
    private static volatile boolean sessionOpenForScaleRef = false;

    /** Re-open the scale-reference window. Called when a session starts. */
    public static synchronized void onSessionStarted() {
        // Start from a hard physical boundary. A callback can arrive after onSessionEnded(); retaining
        // any slope it recreated would let the next session differentiate across two charges. The
        // first live post-start observation becomes the new anchor, costing at most one slope window.
        slopes.clear();
        scaleRefOwner = null;
        latchedRateDivisors.clear();
        sessionRateProofs.clear();
        sessionOpenForScaleRef = true;
        sessionEvidenceGeneration++;
        evidenceMutationGeneration++;
    }

    public static synchronized void observeCounterForScale(String source, double kwh) {
        observeCounterForScale(source, kwh, System.currentTimeMillis());
    }

    /** Testable variant taking the telemetry observation time explicitly. */
    static synchronized void observeCounterForScale(String source, double kwh, long observedAtMs) {
        // A terminal external-counter callback is deliberately admitted by the collector so the session's
        // final energy tail is not lost — but it must not resurrect the scale reference after
        // onSessionEnded() cleared it, or the following session inherits a slope built from the previous
        // one for as long as the hold window lasts.
        if (!sessionOpenForScaleRef) return;
        // synchronized: the poll thread and the HAL callback thread both reach here, and the sequence
        // below (read owner -> maybe take over -> clear the slope -> derive) must be atomic. Interleaved,
        // an external-derived slope could end up under capacity ownership, which is exactly the
        // self-comparison the ownership rule exists to prevent — and the resulting divisor latch is
        // permanent.
        if (Double.isNaN(kwh) || kwh < 0 || kwh > COUNTER_REF_MAX_KWH) return;
        // ONE SOURCE OWNS THE REFERENCE SLOPE. Recording only the LAST feed was not enough: the slope is
        // a single series, so interleaved feeds from two sources produce a reference derived from BOTH
        // while the marker names whichever wrote most recently. An external source could then be judged
        // against a slope it had itself contributed to, see ~1.0, and latch a divisor of 1.0 from what is
        // effectively a self-comparison. Binding the reference to one owner makes the independence check
        // mean what it says; the capacity counter takes it when present, since its unit is documented.
        String owner = scaleRefOwner;
        if (owner == null) {
            scaleRefOwner = source;
        } else if (!owner.equals(source)) {
            if (!ChargeSourceClassifier.SRC_CAPACITY.equals(source)) return;   // challenger ignored
            // The documented counter appeared: it takes over, and the slope must restart from it rather
            // than continue a series the other source built.
            scaleRefOwner = source;
            slopes.remove(SCALE_REF_KEY);
        }
        double div = counterUnitDivisor(source);
        double normalised = (div > 1.0) ? kwh / div : kwh;
        // A REGISTER-WIDTH FAULT MUST NOT REACH THE YARDSTICK. This slope is what vetoes every other
        // rate source (see shouldRejectCandidateBeforeSelection), so a half-scale reference disqualifies
        // precisely the correct sources that would have contradicted it — the faulty sensor becomes the
        // authority that suppresses its own detection. While a fault is suspected but unproven, feed
        // nothing: no yardstick makes callers withhold or flag, which is recoverable.
        double factor = com.overdrive.app.charging.CounterScaleCalibrator.factorFor(source);
        if (factor == 1.0
                && com.overdrive.app.charging.CounterScaleCalibrator.isScaleSuspect(source)) {
            return;
        }
        derive(SCALE_REF_KEY, normalised * factor, observedAtMs);
    }

    /** The single source that owns the scale-reference slope; null before any has claimed it. */
    private static volatile String scaleRefOwner = null;

    /**
     * True when the scale reference comes from a source OTHER than the one being judged.
     *
     * <p>A self-comparison is vacuous — it always lands at ~1.0 — so allowing it to latch would freeze
     * the wrong answer on exactly the trims that need calibrating (those whose only counter is the one
     * under test). The capacity counter is exempt: its unit is documented, so it needs no verdict.
     */
    private static boolean referenceIsIndependentOf(String source) {
        String refSrc = scaleRefOwner;
        if (refSrc == null) return false;
        return !refSrc.equals(source);
    }
}
