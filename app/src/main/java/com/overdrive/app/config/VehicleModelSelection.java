package com.overdrive.app.config;

import java.util.Locale;

/**
 * Provenance rules for {@code unified.vehicle.modelId}.
 *
 * <p>The 3D vehicle renderer needs a visual fallback, but that fallback must
 * not masquerade as a user-selected physical vehicle. In particular, a fresh
 * install historically wrote {@code modelId=seal}; camera auto-configuration
 * then treated every unconfigured BYD as a Seal before onboarding had asked
 * the owner which car they had.
 */
public final class VehicleModelSelection {
    public static final String SOURCE_UNSET = "unset";
    public static final String SOURCE_USER = "user";
    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_LEGACY = "legacy";

    private VehicleModelSelection() {
    }

    /**
     * Normalize persisted provenance. Configs written before provenance was
     * introduced keep their model as a legacy selection for compatibility.
     */
    public static String normalizeSource(String source, String modelId) {
        String normalized = source == null
                ? ""
                : source.trim().toLowerCase(Locale.US);
        if (SOURCE_UNSET.equals(normalized)
                || SOURCE_USER.equals(normalized)
                || SOURCE_AUTO.equals(normalized)
                || SOURCE_LEGACY.equals(normalized)) {
            return normalized;
        }
        return hasModelId(modelId) ? SOURCE_LEGACY : SOURCE_UNSET;
    }

    /**
     * True only when the model represents physical-vehicle intent rather than
     * the renderer's cosmetic fallback.
     */
    public static boolean isResolvedSelection(String modelId, String source) {
        if (!hasModelId(modelId)) return false;
        return !SOURCE_UNSET.equals(normalizeSource(source, modelId));
    }

    public static String resolvedModelId(String modelId, String source) {
        return isResolvedSelection(modelId, source) ? modelId.trim() : null;
    }

    private static boolean hasModelId(String modelId) {
        return modelId != null && !modelId.trim().isEmpty();
    }
}
