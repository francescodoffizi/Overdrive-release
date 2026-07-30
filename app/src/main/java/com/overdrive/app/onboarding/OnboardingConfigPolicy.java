package com.overdrive.app.onboarding;

import com.overdrive.app.config.VehicleModelSelection;

/**
 * Pure onboarding decisions, kept Android-free so fresh-install and restore
 * behavior can be covered by local unit tests.
 */
public final class OnboardingConfigPolicy {
    public enum Step {
        AUTHORIZE,
        MODE,
        VEHICLE,
        CAMERA,
        DASHBOARD,
        DONE
    }

    private OnboardingConfigPolicy() {
    }

    /**
     * Vehicle selection intentionally precedes camera setup: camera profiles
     * may depend on the selected physical model.
     */
    public static Step nextStep(boolean daemonAuthorized,
                                boolean modeChosen,
                                boolean vehicleConfigured,
                                boolean cameraConfigured,
                                boolean dashboardTourDone) {
        if (!daemonAuthorized) return Step.AUTHORIZE;
        if (!modeChosen) return Step.MODE;
        if (!vehicleConfigured) return Step.VEHICLE;
        if (!cameraConfigured) return Step.CAMERA;
        if (!dashboardTourDone) return Step.DASHBOARD;
        return Step.DONE;
    }

    public static boolean hasConfiguredVehicle(String modelId, String modelSource) {
        return VehicleModelSelection.isResolvedSelection(modelId, modelSource);
    }

    /**
     * A restored camera selection is enough to skip the destructive/redundant
     * first-run preview walk. Profile defaults alone still show the wizard so
     * a genuinely new vehicle gets visual verification.
     */
    public static boolean hasConfiguredCamera(int cameraId,
                                              boolean manualOverride,
                                              boolean probedAndValidated) {
        return cameraId >= 0 && cameraId <= 5
                && (manualOverride || probedAndValidated);
    }
}
