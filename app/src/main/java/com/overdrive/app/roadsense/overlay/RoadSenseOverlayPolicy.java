package com.overdrive.app.roadsense.overlay;

/**
 * Pure visibility policy for the RoadSense floating overlay.
 *
 * Keeping this predicate Android-free makes the lifecycle invariant explicit and
 * unit-testable: a remembered overlay preference never overrides the RoadSense
 * master switch.
 */
public final class RoadSenseOverlayPolicy {
    private RoadSenseOverlayPolicy() {}

    public static boolean shouldShow(boolean roadSenseEnabled, boolean overlayVisible) {
        return roadSenseEnabled && overlayVisible;
    }
}
