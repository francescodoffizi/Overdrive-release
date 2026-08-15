package com.overdrive.app.surveillance;

/** Pure timing policy for bounded event-recording continuation. */
final class RecordingContinuationPolicy {
    static final long MIN_CONFIRMED_PERSON_GRACE_MS = 30_000L;
    static final long CONFIRMED_PERSON_EMERGENCY_CEILING_MS = 5 * 60_000L;

    enum CeilingDecision {
        CONTINUE,
        CONTINUE_CONFIRMED_PERSON,
        STOP_BASE_CEILING,
        STOP_EMERGENCY_CEILING
    }

    private RecordingContinuationPolicy() {}

    static boolean hasFreshConfirmedPerson(
            boolean eventEverSawPerson,
            long nowElapsedMs,
            long lastPersonConfirmationElapsedMs,
            long postRecordMs) {
        if (!eventEverSawPerson || lastPersonConfirmationElapsedMs <= 0
                || nowElapsedMs < lastPersonConfirmationElapsedMs) {
            return false;
        }
        long graceMs = Math.max(MIN_CONFIRMED_PERSON_GRACE_MS, positive(postRecordMs));
        return nowElapsedMs - lastPersonConfirmationElapsedMs <= graceMs;
    }

    static CeilingDecision evaluateCeiling(
            long elapsedSinceTriggerMs,
            long postRecordMs,
            boolean confirmedPersonEvent) {
        if (elapsedSinceTriggerMs < 0 || postRecordMs <= 0) {
            return CeilingDecision.CONTINUE;
        }

        long baseCeilingMs = saturatedMultiply(postRecordMs, 3L);
        long emergencyCeilingMs = Math.max(
                CONFIRMED_PERSON_EMERGENCY_CEILING_MS, baseCeilingMs);

        if (confirmedPersonEvent) {
            if (elapsedSinceTriggerMs >= emergencyCeilingMs) {
                return CeilingDecision.STOP_EMERGENCY_CEILING;
            }
            if (elapsedSinceTriggerMs >= baseCeilingMs) {
                return CeilingDecision.CONTINUE_CONFIRMED_PERSON;
            }
            return CeilingDecision.CONTINUE;
        }

        return elapsedSinceTriggerMs >= baseCeilingMs
                ? CeilingDecision.STOP_BASE_CEILING
                : CeilingDecision.CONTINUE;
    }

    static long baseCeilingMs(long postRecordMs) {
        return saturatedMultiply(positive(postRecordMs), 3L);
    }

    static long emergencyCeilingMs(long postRecordMs) {
        return Math.max(CONFIRMED_PERSON_EMERGENCY_CEILING_MS, baseCeilingMs(postRecordMs));
    }

    private static long positive(long value) {
        return Math.max(0L, value);
    }

    private static long saturatedMultiply(long value, long multiplier) {
        if (value <= 0 || multiplier <= 0) return 0L;
        if (value > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
        return value * multiplier;
    }
}
