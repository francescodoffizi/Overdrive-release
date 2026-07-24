package com.overdrive.app.camera;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Ordered fallback camera IDs for a panoramic slot that opens but never streams.
 *
 * <p>Background (issue #170, and the #117-class reports before it): on BYD
 * head units running the "2602" firmware generation
 * ({@code 13.1.33.2602030.1}) with a legacy {@code pano_h} board, the profile's
 * panoramic camera ID (1 for {@code legacy_seal_atto}) still opens
 * successfully — {@code AVMCamera.open()} returns true — but the HAL reports a
 * 0x0 preview, so {@code addPreviewSurface} fails with err 423 and
 * {@code startPreview} with err 279. Those failures happen inside the HAL and
 * never surface as a Java exception, so the pipeline believes it has a healthy
 * camera and waits forever for a frame that cannot arrive.
 *
 * <p>Camera ID <b>0</b> on those boards delivers the raw four-fisheye strip
 * directly and needs no pano wake ({@code sys.byd.pano} stays 0 throughout), so
 * it is the reliable escape hatch. The BYD AVM HAL's own tag map agrees:
 * {@code BMMCamProp} reports {@code mCamIdPanoH=0} on this hardware.
 *
 * <p>The order below is therefore: the HAL's own panoramic tag mapping first
 * (authoritative when {@code BmmCameraInfo} is present), then the raw-strip
 * slot 0. The list is deliberately short — it is walked only when a camera has
 * <em>never</em> produced a single frame, and each attempt costs a HAL
 * close/open cycle, so we do not sweep every slot 0-5 and risk grabbing the
 * reverse-camera or dashcam sensor. The existing full-matrix auto-probe already
 * covers the "camera streams, but the picture is wrong" case.
 *
 * <p>Pure logic, no Android dependencies, so it is directly unit-testable.
 *
 * @see AvmCameraHelper#discoverPanoCameraId()
 */
public final class PanoCameraFallbackOrder {

    /** Returned by {@link #next} when every candidate has already been tried. */
    public static final int NO_CANDIDATE = -1;

    /**
     * The raw four-fisheye strip slot. Field-verified as the healthy sensor on
     * 2602-generation {@code pano_h} boards, and independently corroborated by
     * the OEM-dashcam path, which resolves to this same ID and records fine.
     */
    public static final int RAW_STRIP_CAMERA_ID = 0;

    /** Highest camera ID the AVM HAL exposes; mirrors PanoramicCameraGpu. */
    public static final int MAX_CAMERA_ID = 5;

    private PanoCameraFallbackOrder() {
    }

    /**
     * The fallback order, most-trustworthy first, with duplicates and
     * out-of-range IDs removed.
     *
     * @param halPanoCameraId panoramic ID reported by {@code BmmCameraInfo}
     *                        (see {@link AvmCameraHelper#discoverPanoCameraId()}),
     *                        or negative when that API is unavailable — which is
     *                        the common case on DiLink 3.0, where only the
     *                        native {@code BMMCamProp} knows the mapping.
     */
    public static List<Integer> candidates(int halPanoCameraId) {
        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        if (isValidCameraId(halPanoCameraId)) {
            ordered.add(halPanoCameraId);
        }
        ordered.add(RAW_STRIP_CAMERA_ID);
        return new ArrayList<>(ordered);
    }

    /**
     * Next camera ID to try, skipping anything already attempted.
     *
     * @param tried IDs already opened this session, including the one that is
     *              currently wedged. May be null or empty.
     * @return the next candidate, or {@link #NO_CANDIDATE} when the list is
     *         exhausted — at which point the caller should stop switching IDs
     *         and leave recovery to the normal restart path.
     */
    public static int next(int halPanoCameraId, Collection<Integer> tried) {
        for (Integer candidate : candidates(halPanoCameraId)) {
            if (tried == null || !tried.contains(candidate)) {
                return candidate;
            }
        }
        return NO_CANDIDATE;
    }

    private static boolean isValidCameraId(int cameraId) {
        return cameraId >= 0 && cameraId <= MAX_CAMERA_ID;
    }
}
