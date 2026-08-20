package com.overdrive.app.surveillance;

/** Pure trigger/evidence policy for empty-motion event discard decisions. */
final class EmptyMotionDiscardPolicy {
    private EmptyMotionDiscardPolicy() {}

    static boolean isMotionSourceTrigger(String triggerSource) {
        return "motion".equals(triggerSource);
    }

    static boolean rawDetectionBelongsToSequence(
            long detectionElapsedMs,
            long sequenceStartElapsedMs) {
        return detectionElapsedMs > 0
                && sequenceStartElapsedMs > 0
                && detectionElapsedMs >= sequenceStartElapsedMs;
    }

    static boolean shouldKeepAsYoloBlind(
            boolean triggerWasAiTimeout,
            boolean sawRawDetections) {
        return triggerWasAiTimeout && !sawRawDetections;
    }
}
