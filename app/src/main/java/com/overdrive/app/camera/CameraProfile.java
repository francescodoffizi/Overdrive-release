package com.overdrive.app.camera;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Vehicle/profile-specific panoramic defaults.
 *
 * Encoder dimensions are derived from the panoramic strip aspect: the mosaic
 * is a 2x2 grid of full-height tiles, so a 5120x960 strip yields 2x1280 wide
 * and 2x960 tall = 2560x1920 (4:3 quadrants). A 5120x720 strip yields
 * 2560x1440 (16:9 quadrants). Hardcoding 2560x1920 stretches Tang content.
 */
public final class CameraProfile {
    private final String id;
    private final String displayName;
    private final int panoCameraId;
    private final int panoWidth;
    private final int panoHeight;
    private final int panoSurfaceMode;
    private final int directPreviewWidth;
    private final int directPreviewHeight;
    private final EnumMap<CameraRole, CameraSourceRef> defaultRoleMappings;
    // Per-quadrant effective vertical FOV in degrees AFTER the BYD HAL's
    // dewarp. BYD AVM systems use different fisheye sensors per camera:
    // front/rear are ultra-wide (mounted in the grille and rear plate
    // looking down/around the car) while the side mirrors carry tighter
    // fisheyes to fit the mirror housing. The validation analysis showed
    // that hardcoding a single 110° constant inflated side-camera
    // distance estimates by ~70%; per-quadrant values close that gap.
    //
    // Quadrant order on the mosaic: Q0=front, Q1=right, Q2=rear, Q3=left.
    // These are estimates derived from typical AVM hardware datasheets;
    // an on-device calibration could tighten them but the per-quadrant
    // split alone is a substantial improvement over the global constant.
    private final int customEncoderWidth;
    private final int customEncoderHeight;
    private final float[] verticalFovDegPerQuadrant;

    public CameraProfile(
            String id,
            String displayName,
            int panoCameraId,
            int panoWidth,
            int panoHeight,
            int panoSurfaceMode,
            int directPreviewWidth,
            int directPreviewHeight,
            Map<CameraRole, CameraSourceRef> defaultRoleMappings,
            float[] verticalFovDegPerQuadrant,
            int encoderWidth,
            int encoderHeight) {
        this.id = id;
        this.displayName = displayName;
        this.panoCameraId = panoCameraId;
        this.panoWidth = panoWidth;
        this.panoHeight = panoHeight;
        this.panoSurfaceMode = panoSurfaceMode;
        this.directPreviewWidth = directPreviewWidth;
        this.directPreviewHeight = directPreviewHeight;
        this.customEncoderWidth = encoderWidth;
        this.customEncoderHeight = encoderHeight;
        this.defaultRoleMappings = new EnumMap<>(CameraRole.class);
        if (defaultRoleMappings != null) {
            this.defaultRoleMappings.putAll(defaultRoleMappings);
        }
        // Default to a uniform 110° if no per-quadrant data was supplied
        // (preserves prior behaviour for any caller still using the legacy
        // 9-arg constructor via the convenience overload below).
        if (verticalFovDegPerQuadrant != null && verticalFovDegPerQuadrant.length == 4) {
            this.verticalFovDegPerQuadrant = verticalFovDegPerQuadrant.clone();
        } else {
            this.verticalFovDegPerQuadrant = new float[]{110f, 110f, 110f, 110f};
        }
    }

    public CameraProfile(
            String id,
            String displayName,
            int panoCameraId,
            int panoWidth,
            int panoHeight,
            int panoSurfaceMode,
            int directPreviewWidth,
            int directPreviewHeight,
            Map<CameraRole, CameraSourceRef> defaultRoleMappings,
            float[] verticalFovDegPerQuadrant) {
        this(id, displayName, panoCameraId, panoWidth, panoHeight,
                panoSurfaceMode, directPreviewWidth, directPreviewHeight,
                defaultRoleMappings, verticalFovDegPerQuadrant, -1, -1);
    }

    /** Convenience constructor — defaults to uniform 110° vertical FOV. */
    public CameraProfile(
            String id,
            String displayName,
            int panoCameraId,
            int panoWidth,
            int panoHeight,
            int panoSurfaceMode,
            int directPreviewWidth,
            int directPreviewHeight,
            Map<CameraRole, CameraSourceRef> defaultRoleMappings) {
        this(id, displayName, panoCameraId, panoWidth, panoHeight,
                panoSurfaceMode, directPreviewWidth, directPreviewHeight,
                defaultRoleMappings, null, -1, -1);
    }

    /**
     * Vertical FOV in degrees for a given quadrant (0=front, 1=right,
     * 2=rear, 3=left). Out-of-range quadrant returns the front FOV.
     */
    public float getVerticalFovDeg(int quadrant) {
        if (quadrant < 0 || quadrant >= verticalFovDegPerQuadrant.length) {
            return verticalFovDegPerQuadrant[0];
        }
        return verticalFovDegPerQuadrant[quadrant];
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getPanoCameraId() {
        return panoCameraId;
    }

    public int getPanoWidth() {
        return panoWidth;
    }

    public int getPanoHeight() {
        return panoHeight;
    }

    public int getPanoSurfaceMode() {
        return panoSurfaceMode;
    }

    public int getDirectPreviewWidth() {
        return directPreviewWidth;
    }

    public int getDirectPreviewHeight() {
        return directPreviewHeight;
    }

    /**
     * Encoder/mosaic output width. When explicit customEncoderWidth is set (>0),
     * returns that value. Otherwise, the mosaic is a 2x2 grid of camera tiles,
     * each tile = (panoWidth/4) wide. Two tiles side-by-side = panoWidth/2.
     * For 5120 strip → 2560.
     */
    public int getEncoderWidth() {
        if (customEncoderWidth > 0) {
            return customEncoderWidth;
        }
        return Math.max(1, panoWidth / 2);
    }

    /**
     * Encoder/mosaic output height. When explicit customEncoderHeight is set (>0),
     * returns that value. Otherwise, each tile is panoHeight tall, two tiles
     * stacked = panoHeight*2. For 960 strip → 1920 (Seal). For 720 strip → 1440 (Tang).
     */
    public int getEncoderHeight() {
        if (customEncoderHeight > 0) {
            return customEncoderHeight;
        }
        return Math.max(1, panoHeight * 2);
    }

    public EnumMap<CameraRole, CameraSourceRef> getDefaultRoleMappings() {
        return new EnumMap<>(defaultRoleMappings);
    }

    public JSONArray getVirtualViewsJson() {
        JSONArray out = new JSONArray();
        for (CameraVirtualView view : CameraVirtualView.values()) {
            out.put(view.toJson());
        }
        return out;
    }

    public JSONArray getPanoramicSlicesJson() {
        JSONArray out = new JSONArray();
        for (PanoramicSlice slice : PanoramicSlice.values()) {
            out.put(slice.toJson());
        }
        return out;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        putSafely(out, "id", id);
        putSafely(out, "label", displayName);
        putSafely(out, "panoCameraId", panoCameraId);
        putSafely(out, "panoWidth", panoWidth);
        putSafely(out, "panoHeight", panoHeight);
        putSafely(out, "panoSurfaceMode", panoSurfaceMode);
        putSafely(out, "directPreviewWidth", directPreviewWidth);
        putSafely(out, "directPreviewHeight", directPreviewHeight);
        putSafely(out, "encoderWidth", getEncoderWidth());
        putSafely(out, "encoderHeight", getEncoderHeight());

        JSONObject mappings = new JSONObject();
        for (Map.Entry<CameraRole, CameraSourceRef> entry : defaultRoleMappings.entrySet()) {
            putSafely(mappings, entry.getKey().getKey(), entry.getValue().toJson());
        }
        putSafely(out, "defaultRoleMappings", mappings);
        putSafely(out, "panoramicSlices", getPanoramicSlicesJson());
        putSafely(out, "virtualViews", getVirtualViewsJson());
        return out;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }
}
