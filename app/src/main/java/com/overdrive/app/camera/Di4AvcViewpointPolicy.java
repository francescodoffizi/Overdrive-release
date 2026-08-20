package com.overdrive.app.camera;

/**
 * Tracks which DiLink 4 camera client owns the panorama viewpoint.
 *
 * <p>The native AVC UI owns it while foreground. All other camera modes retain
 * their existing behavior by keeping this policy disabled for the session.
 */
final class Di4AvcViewpointPolicy {

    enum Action {
        NONE,
        YIELD,
        RESTORE
    }

    private boolean enabled;
    private boolean nativeAvcForeground;
    private boolean viewpointYielded;

    static boolean isEnabledForCameraMode(String cameraMode) {
        return "dilink4".equalsIgnoreCase(cameraMode);
    }

    void beginSession(boolean enable) {
        enabled = enable;
        nativeAvcForeground = false;
        viewpointYielded = false;
    }

    void endSession() {
        enabled = false;
        nativeAvcForeground = false;
        viewpointYielded = false;
    }

    Action onNativeAvcForeground(Boolean foreground, boolean hasViewpointHolder) {
        if (!enabled || foreground == null || !hasViewpointHolder) return Action.NONE;

        nativeAvcForeground = foreground;
        if (nativeAvcForeground && !viewpointYielded) return Action.YIELD;
        if (!nativeAvcForeground && viewpointYielded) return Action.RESTORE;
        return Action.NONE;
    }

    boolean isYieldPending() {
        return enabled && nativeAvcForeground && !viewpointYielded;
    }

    boolean isRestorePending(boolean hasViewpointHolder) {
        return enabled && hasViewpointHolder && !nativeAvcForeground && viewpointYielded;
    }

    boolean isViewpointYielded() {
        return enabled && viewpointYielded;
    }

    void markViewpointYielded() {
        if (enabled && nativeAvcForeground) viewpointYielded = true;
    }

    void markViewpointRestored() {
        if (enabled && !nativeAvcForeground) viewpointYielded = false;
    }
}
