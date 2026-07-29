package com.overdrive.app.camera;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;

/**
 * Resolves persisted + inferred camera settings into a concrete runtime config.
 *
 * Per-role mappings (windshield / cabin / 360-front/right/rear/left) are stored
 * under {@code unified.camera.roleMappings} keyed by role; the resolver merges
 * profile defaults with persisted overrides at runtime. Profile selection
 * ({@code cameraProfile}) tracks vehicle-class geometry,
 * and probe results ({@code probedCameraId}, {@code probedSurfaceMode},
 * {@code probedWidth}, {@code probedHeight}) record the actual stream the GL
 * pipeline locked onto.
 */
public final class CameraConfigResolver {
    private static final DaemonLogger logger = DaemonLogger.getInstance("CameraConfigResolver");

    private CameraConfigResolver() {
    }

    public static ResolvedCameraConfig resolve() {
        return resolve(readVehicleModel());
    }

    public static ResolvedCameraConfig resolve(String vehicleModel) {
        JSONObject camera = getCameraSection();
        String selectedProfileId = camera.optString("cameraProfile", CameraProfiles.PROFILE_AUTO);
        boolean autoProfile = selectedProfileId.isEmpty()
                || CameraProfiles.PROFILE_AUTO.equalsIgnoreCase(selectedProfileId);
        String vehicleModelHint = preferSelectedVehicleModel(
                readSelectedVehicleModel(), vehicleModel);
        CameraProfile profile = autoProfile
                ? CameraProfiles.infer(vehicleModelHint)
                : CameraProfiles.get(selectedProfileId);

        int panoCameraId = optNonNegative(camera, "probedCameraId", profile.getPanoCameraId());
        int panoSurfaceMode = optNonNegative(camera, "probedSurfaceMode", profile.getPanoSurfaceMode());
        int panoWidth = optNonNegative(camera, "probedWidth", profile.getPanoWidth());
        int panoHeight = optNonNegative(camera, "probedHeight", profile.getPanoHeight());
        boolean manual = camera.optBoolean("manualOverride", false);
        boolean validated = camera.optBoolean("probedAndValidated", false);
        boolean fallback = camera.optBoolean("fallbackFromProbe", false);

        EnumMap<CameraRole, CameraSourceRef> roleMappings = profile.getDefaultRoleMappings();
        JSONObject mappingsJson = camera.optJSONObject("roleMappings");
        if (mappingsJson != null) {
            for (CameraRole role : CameraRole.values()) {
                JSONObject item = mappingsJson.optJSONObject(role.getKey());
                CameraSourceRef sourceRef = CameraSourceRef.fromJson(item);
                if (sourceRef != null) {
                    roleMappings.put(role, sourceRef);
                }
            }
        }

        // Field-verified Tang layout: camera 2 is the 360 panoramic strip and
        // camera 0 is the windshield/front camera; both stream concurrently.
        // Older installs can have cameraProfile=auto/legacy but a validated
        // probedCameraId=2 manual override, so expose WINDSHIELD even when the
        // profile defaults don't include it.
        if (!roleMappings.containsKey(CameraRole.WINDSHIELD) && panoCameraId == 2) {
            roleMappings.put(CameraRole.WINDSHIELD, CameraSourceRef.direct(0));
        }

        return new ResolvedCameraConfig(
                profile,
                autoProfile ? CameraProfiles.PROFILE_AUTO : profile.getId(),
                autoProfile,
                panoCameraId,
                panoWidth,
                panoHeight,
                panoSurfaceMode,
                manual,
                validated,
                fallback,
                roleMappings);
    }

    /**
     * Returns the camera section, or an empty JSONObject if absent.
     */
    public static JSONObject getCameraSection() {
        JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject("camera");
        return section != null ? section : new JSONObject();
    }

    /**
     * Persist a single role → source mapping. Accepts both {@code DIRECT} and
     * {@code PANORAMIC_SLICE} kinds so the diagnostics dialog can map any
     * discoverable preview candidate to a logical role. Multi-claim safety
     * for direct-camera live previews is enforced at preview time
     * ({@link CameraPreviewHelper}), not at config write time — the config
     * is just durable user intent.
     */
    public static boolean saveRoleMapping(CameraRole role, CameraSourceRef sourceRef) {
        if (role == null || sourceRef == null) return false;
        // Build a fresh JSONObject from the cached section's serialized form.
        // UnifiedConfigManager.loadConfig returns the cached config by
        // reference, so mutating the inner roleMappings JSONObject directly
        // would corrupt the in-memory cache if updateSection's saveConfig
        // fails (the cache then diverges from disk, and readers see a
        // phantom mapping that disappears on next file-mtime reload).
        JSONObject camera = getCameraSection();
        JSONObject existing = camera.optJSONObject("roleMappings");
        JSONObject mappings = (existing != null)
            ? cloneShallow(existing)
            : new JSONObject();
        putSafely(mappings, role.getKey(), sourceRef.toJson());

        JSONObject update = new JSONObject();
        putSafely(update, "roleMappings", mappings);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /**
     * Remove a single role mapping so it falls back to the profile default.
     */
    public static boolean clearRoleMapping(CameraRole role) {
        if (role == null) return false;
        JSONObject camera = getCameraSection();
        JSONObject existing = camera.optJSONObject("roleMappings");
        if (existing == null || !existing.has(role.getKey())) return true;
        // Same defensive clone as saveRoleMapping — never mutate the cache
        // before a successful disk write.
        JSONObject mappings = cloneShallow(existing);
        mappings.remove(role.getKey());

        JSONObject update = new JSONObject();
        putSafely(update, "roleMappings", mappings);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /** Shallow copy of a JSONObject — sufficient because role mappings are
     *  flat (string key → small JSONObject value). The values are themselves
     *  JSONObjects but we only ever overwrite or remove whole entries, never
     *  edit nested fields, so a shallow copy is safe. */
    private static JSONObject cloneShallow(JSONObject src) {
        JSONObject out = new JSONObject();
        java.util.Iterator<String> keys = src.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            putSafely(out, k, src.opt(k));
        }
        return out;
    }

    /**
     * Persist the user-selected camera profile (vehicle class). Empty / "auto"
     * resets to inferred-from-{@code ro.product.model}; any other id must
     * exist in {@link CameraProfiles}. When switching to a known profile we
     * seed {@code probedWidth/Height} from the profile defaults if not yet
     * set, so the next pipeline init has correct strip geometry even before
     * the runtime probe completes.
     */
    public static boolean saveCameraProfile(String profileId) {
        JSONObject update = new JSONObject();
        if (profileId == null || profileId.isEmpty()
                || CameraProfiles.PROFILE_AUTO.equalsIgnoreCase(profileId)) {
            putSafely(update, "cameraProfile", CameraProfiles.PROFILE_AUTO);
        } else if (CameraProfiles.isKnownProfile(profileId)) {
            putSafely(update, "cameraProfile", profileId);
            JSONObject section = getCameraSection();
            CameraProfile profile = CameraProfiles.get(profileId);
            if (!section.has("probedWidth"))  putSafely(update, "probedWidth",  profile.getPanoWidth());
            if (!section.has("probedHeight")) putSafely(update, "probedHeight", profile.getPanoHeight());
        } else {
            logger.warn("Ignoring unknown camera profile: " + profileId);
            return false;
        }
        return UnifiedConfigManager.updateSection("camera", update);
    }

    public static boolean persistPanoramicProbe(int cameraId, int surfaceMode, int width, int height,
                                                boolean validated, boolean fallback) {
        JSONObject update = new JSONObject();
        putSafely(update, "probedCameraId", cameraId);
        putSafely(update, "probedSurfaceMode", surfaceMode);
        putSafely(update, "probedWidth", width);
        putSafely(update, "probedHeight", height);
        putSafely(update, "probedAndValidated", validated);
        putSafely(update, "fallbackFromProbe", fallback);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /** Role catalog for the diagnostics camera-mapping dialog. */
    public static JSONArray roleOptionsJson() {
        JSONArray out = new JSONArray();
        for (CameraRole role : CameraRole.values()) {
            out.put(role.toJson());
        }
        return out;
    }

    /**
     * Build the candidate list shown in the dialog's Prev/Next navigator:
     * direct cameras 0–5 plus the four panoramic slices, each tagged with a
     * recommended preview width/height. UI iterates this list and asks the
     * server for previews via {@code /api/surveillance/camera-preview?kind=…}.
     */
    public static JSONArray buildPreviewCandidates(ResolvedCameraConfig resolved) {
        JSONArray out = new JSONArray();
        for (int cameraId = 0; cameraId <= 5; cameraId++) {
            JSONObject item = CameraSourceRef.direct(cameraId).toJson();
            putSafely(item, "previewWidth", resolved.getProfile().getDirectPreviewWidth());
            putSafely(item, "previewHeight", resolved.getProfile().getDirectPreviewHeight());
            out.put(item);
        }
        for (PanoramicSlice slice : PanoramicSlice.values()) {
            JSONObject item = CameraSourceRef.panoramicSlice(slice).toJson();
            putSafely(item, "previewWidth", resolved.getPanoWidth() / 4);
            putSafely(item, "previewHeight", resolved.getPanoHeight());
            out.put(item);
        }
        return out;
    }

    /**
     * Resolved-config summary merged into {@code GET /api/surveillance/config}
     * so the diagnostics dialog can render in a single round-trip.
     */
    public static JSONObject resolvedSummaryJson(ResolvedCameraConfig resolved) {
        JSONObject out = new JSONObject();
        putSafely(out, "cameraProfile", resolved.getSelectedProfileId());
        putSafely(out, "resolvedCameraProfile", resolved.getProfile().getId());
        putSafely(out, "resolvedCameraProfileLabel", resolved.getProfile().getDisplayName());
        putSafely(out, "panoCameraId", resolved.getPanoCameraId());
        putSafely(out, "panoSurfaceMode", resolved.getPanoSurfaceMode());
        putSafely(out, "panoWidth", resolved.getPanoWidth());
        putSafely(out, "panoHeight", resolved.getPanoHeight());
        putSafely(out, "encoderWidth", resolved.getProfile().getEncoderWidth());
        putSafely(out, "encoderHeight", resolved.getProfile().getEncoderHeight());
        putSafely(out, "cameraManualOverride", resolved.isManualPanoOverride());
        putSafely(out, "cameraValidated", resolved.isValidated());
        putSafely(out, "cameraFallbackFromProbe", resolved.isFallbackFromProbe());
        putSafely(out, "cameraProfiles", CameraProfiles.toJsonArray());
        putSafely(out, "cameraRoleOptions", roleOptionsJson());
        putSafely(out, "cameraRoleMappings", resolved.roleMappingsToJson());
        putSafely(out, "cameraPanoramicSlices", resolved.panoramicSlicesToJson());
        putSafely(out, "cameraPreviewCandidates", buildPreviewCandidates(resolved));
        return out;
    }

    private static int optNonNegative(JSONObject obj, String key, int defaultValue) {
        int value = obj.optInt(key, defaultValue);
        return value >= 0 ? value : defaultValue;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }

    /**
     * Prefer the model explicitly selected in OverDrive over the often-generic
     * Android product string ("BYD AUTO"). A non-auto camera profile and any
     * persisted probe/manual camera ID still take precedence later in resolve().
     */
    static String preferSelectedVehicleModel(String selectedModelId, String systemModel) {
        if (selectedModelId != null && !selectedModelId.trim().isEmpty()) {
            return selectedModelId.trim();
        }
        if (systemModel != null && !systemModel.trim().isEmpty()) {
            return systemModel.trim();
        }
        return "unknown";
    }

    private static String readSelectedVehicleModel() {
        try {
            String selected = UnifiedConfigManager.getSelectedVehicleModelId();
            return selected != null ? selected : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String readVehicleModel() {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.product.model", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
