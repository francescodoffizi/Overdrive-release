package com.overdrive.app.byd;

/**
 * Resolves the front-passenger belt getter's ambiguous empty-seat value.
 *
 * <p>On affected BYD firmware, getter value {@code 1} means both "buckled" and the idle value of
 * an empty seat. A getter value {@code 1} is therefore trusted only inside a closed-door session
 * that has already produced a getter value {@code 0}. Opening the passenger door ends that
 * session. Typed SDK callbacks remain authoritative and temporarily override a lagging getter.
 */
final class PassengerSeatbeltTracker {
    static final long CALLBACK_OVERRIDE_MS = 2_000L;

    private enum Phase {
        AWAITING_CLOSED_DOOR_UNBUCKLED,
        DOOR_OPEN,
        TRACKING
    }

    static final class Reading {
        final int beltState;
        final int automationState;
        final boolean buckledGetterTrusted;
        final boolean callbackAuthoritative;

        Reading(int beltState, int automationState, boolean buckledGetterTrusted,
                boolean callbackAuthoritative) {
            this.beltState = beltState;
            this.automationState = automationState;
            this.buckledGetterTrusted = buckledGetterTrusted;
            this.callbackAuthoritative = callbackAuthoritative;
        }
    }

    private Phase phase = Phase.AWAITING_CLOSED_DOOR_UNBUCKLED;
    private int pendingCallbackState = BydVehicleData.UNAVAILABLE;
    private long pendingCallbackAtMs;
    private int lastCallbackState = BydVehicleData.UNAVAILABLE;

    synchronized Reading resolveGetter(int getterState) {
        return resolveGetter(getterState, monotonicMs());
    }

    synchronized Reading resolveGetter(int getterState, long nowMs) {
        if (isBeltState(pendingCallbackState)) {
            int callbackState = pendingCallbackState;
            if (getterState == callbackState) {
                clearPendingCallback();
                return new Reading(callbackState, callbackState, true, true);
            }
            if (nowMs >= pendingCallbackAtMs
                    && nowMs - pendingCallbackAtMs <= CALLBACK_OVERRIDE_MS) {
                return new Reading(callbackState, callbackState, true, true);
            }
            // A callback is an observed edge, but it must not mask a later getter transition
            // forever if this firmware never converges the getter to the callback value.
            clearPendingCallback();
        }

        if (getterState == 0) {
            // A 0 while the door is open is still a real unbuckle value to publish, but it cannot
            // establish the next passenger session: the empty-seat getter may rebound to 1.
            if (phase != Phase.DOOR_OPEN) {
                phase = Phase.TRACKING;
            }
            return new Reading(0, 0, phase == Phase.TRACKING, false);
        }

        if (getterState == 1) {
            boolean trusted = phase == Phase.TRACKING;
            return new Reading(
                    1,
                    trusted ? 1 : BydVehicleData.UNAVAILABLE,
                    trusted,
                    false);
        }

        return new Reading(
                BydVehicleData.UNAVAILABLE,
                BydVehicleData.UNAVAILABLE,
                phase == Phase.TRACKING,
                false);
    }

    /**
     * Record a typed SDK belt edge.
     *
     * @return true when the callback value changed within the current passenger session
     */
    synchronized boolean recordCallback(int state) {
        return recordCallback(state, monotonicMs());
    }

    synchronized boolean recordCallback(int state, long nowMs) {
        if (!isBeltState(state)) return false;
        boolean changed = lastCallbackState != state;
        lastCallbackState = state;
        pendingCallbackState = state;
        pendingCallbackAtMs = nowMs;
        if (phase != Phase.DOOR_OPEN) {
            phase = Phase.TRACKING;
        }
        return changed;
    }

    /**
     * Apply a physical passenger-door edge.
     *
     * @return true when the tracker changed phase
     */
    synchronized boolean onPassengerDoorState(boolean open) {
        if (open) {
            if (phase == Phase.DOOR_OPEN) return false;
            phase = Phase.DOOR_OPEN;
            clearPendingCallback();
            lastCallbackState = BydVehicleData.UNAVAILABLE;
            return true;
        }

        if (phase != Phase.DOOR_OPEN) return false;
        phase = Phase.AWAITING_CLOSED_DOOR_UNBUCKLED;
        clearPendingCallback();
        return true;
    }

    synchronized void reset() {
        phase = Phase.AWAITING_CLOSED_DOOR_UNBUCKLED;
        clearPendingCallback();
        lastCallbackState = BydVehicleData.UNAVAILABLE;
    }

    synchronized String diagnosticState() {
        return phase.name();
    }

    private static boolean isBeltState(int state) {
        return state == 0 || state == 1;
    }

    private void clearPendingCallback() {
        pendingCallbackState = BydVehicleData.UNAVAILABLE;
        pendingCallbackAtMs = 0L;
    }

    private static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
