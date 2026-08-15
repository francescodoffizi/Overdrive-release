package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecordingContinuationPolicyTest {
    private static final long POST_RECORD_MS = 10_000L;

    @Test
    public void unconfirmedEventStopsAtBaseCeiling() {
        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.CONTINUE,
                RecordingContinuationPolicy.evaluateCeiling(
                        29_999L, POST_RECORD_MS, false));
        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.STOP_BASE_CEILING,
                RecordingContinuationPolicy.evaluateCeiling(
                        30_000L, POST_RECORD_MS, false));
    }

    @Test
    public void confirmedPersonEventBridgesObservedHardCeilingSplits() {
        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.CONTINUE_CONFIRMED_PERSON,
                RecordingContinuationPolicy.evaluateCeiling(
                        42_600L, POST_RECORD_MS, true));
        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.CONTINUE_CONFIRMED_PERSON,
                RecordingContinuationPolicy.evaluateCeiling(
                        98_000L, POST_RECORD_MS, true));
    }

    @Test
    public void personGraceCoversObservedHeartbeatReacquisitionGap() {
        long lastConfirmationMs = 30_700L;

        assertTrue(RecordingContinuationPolicy.hasFreshConfirmedPerson(
                true, 54_000L, lastConfirmationMs, POST_RECORD_MS));
        assertTrue(RecordingContinuationPolicy.hasFreshConfirmedPerson(
                true, 60_700L, lastConfirmationMs, POST_RECORD_MS));
        assertFalse(RecordingContinuationPolicy.hasFreshConfirmedPerson(
                true, 60_701L, lastConfirmationMs, POST_RECORD_MS));
    }

    @Test
    public void staleOrAbsentPersonDoesNotBypassBaseCeiling() {
        assertFalse(RecordingContinuationPolicy.hasFreshConfirmedPerson(
                false, 40_000L, 30_000L, POST_RECORD_MS));
        assertFalse(RecordingContinuationPolicy.hasFreshConfirmedPerson(
                true, 40_000L, 0L, POST_RECORD_MS));
        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.STOP_BASE_CEILING,
                RecordingContinuationPolicy.evaluateCeiling(
                        40_000L, POST_RECORD_MS, false));
    }

    @Test
    public void confirmedPersonEventStillStopsAtEmergencyCeiling() {
        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.CONTINUE_CONFIRMED_PERSON,
                RecordingContinuationPolicy.evaluateCeiling(
                        299_999L, POST_RECORD_MS, true));
        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.STOP_EMERGENCY_CEILING,
                RecordingContinuationPolicy.evaluateCeiling(
                        300_000L, POST_RECORD_MS, true));
    }

    @Test
    public void configuredPostRecordLongerThanMinimumDefinesPersonGrace() {
        assertTrue(RecordingContinuationPolicy.hasFreshConfirmedPerson(
                true, 60_000L, 15_000L, 45_000L));
        assertFalse(RecordingContinuationPolicy.hasFreshConfirmedPerson(
                true, 60_001L, 15_000L, 45_000L));
    }

    @Test
    public void maximumAllowedPostRecordKeepsEmergencyCeilingAtFiveMinutes() {
        long maximumPostRecordMs = 60_000L;

        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.STOP_BASE_CEILING,
                RecordingContinuationPolicy.evaluateCeiling(
                        180_000L, maximumPostRecordMs, false));
        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.CONTINUE_CONFIRMED_PERSON,
                RecordingContinuationPolicy.evaluateCeiling(
                        299_999L, maximumPostRecordMs, true));
        assertEquals(
                RecordingContinuationPolicy.CeilingDecision.STOP_EMERGENCY_CEILING,
                RecordingContinuationPolicy.evaluateCeiling(
                        300_000L, maximumPostRecordMs, true));
    }
}
