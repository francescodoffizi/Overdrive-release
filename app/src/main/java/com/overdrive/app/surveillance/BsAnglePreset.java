package com.overdrive.app.surveillance;

/**
 * The two free-angle blind-spot framings, as measured.
 *
 * <p>40° and 310° are offered alongside the quarter turns, but they are not just
 * angles: picking one applies a whole framing — crop, mirror, zoom, nudge — so the
 * driver gets the intended view without touching anything else. The numbers here are
 * transcribed from a reference implementation whose framing this is meant to match;
 * they are not derived, and changing one changes what the driver sees.
 *
 * <p>The two are deliberately NOT a mirror pair. 310 is 50° short of a full turn while
 * 40 is 40° past zero, so the right view is turned 10° less than the left. That is how
 * the reference measures; a symmetric 320/40 would be a different framing.
 *
 * <p>Everything is expressed as a FRACTION rather than in pixels, so a card of any size
 * on any panel gets the same framing. The reference stated its two nudges in pixels
 * against its own window, so those two numbers carry an assumption — see
 * {@link #TRANSLATE_REFERENCE_NOTE}.
 */
public final class BsAnglePreset {

    /**
     * The nudges below were pixel offsets against the reference's own window, which
     * came from a fixed set of 16:9 window sizes. They are converted here against the
     * middle size (960×540, so a 480×540 half-width window). If the driver's window was
     * one of the other sizes, the nudge is off by the size ratio — a couple of percent
     * of the card, visible only as a slightly different framing offset, never as a
     * wrong angle or a stretch. This is the one number in the table worth re-checking
     * against a real car; the BsAngle log prints what was applied.
     */
    public static final String TRANSLATE_REFERENCE_NOTE =
            "translate fractions assume the reference's 960x540 window (480x540 after the crop)";

    /**
     * The reference presented the camera at its window's shape, and every one of its
     * window sizes was 16:9. It never asked the camera for a matching preview size, so
     * whatever the camera delivered was simply stretched to fill 16:9. Reproducing the
     * framing therefore means presenting the source at 16:9 too, regardless of the
     * shape the frame arrives in — which is also why this is a property of the preset
     * rather than something read off the incoming frame.
     */
    public static final float PRESENT_ASPECT = 16f / 9f;

    /** Left camera: keep the left half, mirror, turn 310°. */
    public static final BsAnglePreset LEFT = new BsAnglePreset(
            310f,
            0f, 0f, 0.5f, 0f,       // crop: drop the right half
            true, false,            // mirror horizontally
            1.4f,
            -15f / 480f, -30f / 540f);

    /** Right camera: keep the right half, mirror, turn 40°. */
    public static final BsAnglePreset RIGHT = new BsAnglePreset(
            40f,
            0.5f, 0f, 0f, 0f,       // crop: drop the left half
            true, false,
            1.4f,
            0f, -30f / 540f);

    public final float rotationDeg;
    public final float cropLeft;
    public final float cropTop;
    public final float cropRight;
    public final float cropBottom;
    public final boolean flipHorizontal;
    public final boolean flipVertical;
    public final float zoom;
    public final float translateXFrac;
    public final float translateYFrac;

    private BsAnglePreset(float rotationDeg,
                          float cropLeft, float cropTop, float cropRight, float cropBottom,
                          boolean flipHorizontal, boolean flipVertical,
                          float zoom, float translateXFrac, float translateYFrac) {
        this.rotationDeg = rotationDeg;
        this.cropLeft = cropLeft;
        this.cropTop = cropTop;
        this.cropRight = cropRight;
        this.cropBottom = cropBottom;
        this.flipHorizontal = flipHorizontal;
        this.flipVertical = flipVertical;
        this.zoom = zoom;
        this.translateXFrac = translateXFrac;
        this.translateYFrac = translateYFrac;
    }

    /**
     * Is this one of the free-angle presets rather than a quarter turn?
     *
     * <p>Everything else keeps the old path — including 0/90/180/270, which stay on the
     * quad rotation they already used. Only these two angles reach the new code.
     */
    public static boolean isPresetAngle(int deg) {
        int d = ((deg % 360) + 360) % 360;
        return d == 40 || d == 310;
    }

    /**
     * The framing for an angle, or null if it is not a preset angle.
     *
     * <p>Looked up by ANGLE, not by side. The angle is what the driver picked and what
     * the config stores, so a driver who puts 310 on the right camera gets the 310
     * framing there — surprising perhaps, but it is what they asked for, and it keeps
     * the setting meaning one thing everywhere it appears.
     */
    public static BsAnglePreset forAngle(int deg) {
        int d = ((deg % 360) + 360) % 360;
        if (d == 310) return LEFT;
        if (d == 40) return RIGHT;
        return null;
    }

    /** Fraction of the source width kept after cropping. */
    public float keptWidthFraction() {
        return 1f - cropLeft - cropRight;
    }

    /** Fraction of the source height kept after cropping. */
    public float keptHeightFraction() {
        return 1f - cropTop - cropBottom;
    }

    /** Centre of the kept crop in source uv — where the card's centre should read
     *  from. For a half-width crop this is 0.25 or 0.75, never 0.5, which makes it the
     *  quickest check that a framing landed on the intended half. */
    public float cropCentreX() {
        return (cropLeft + 1f - cropRight) * 0.5f;
    }

    /** Vertical counterpart of {@link #cropCentreX()}. */
    public float cropCentreY() {
        return (cropTop + 1f - cropBottom) * 0.5f;
    }

    /** Short name for logs. Named after the camera each framing was measured for,
     *  which is not necessarily the camera it gets applied to — framings are keyed by
     *  angle, so the log prints this and the actual view side separately. */
    public String name() {
        return (this == RIGHT) ? "RIGHT-40" : "LEFT-310";
    }

    @Override
    public String toString() {
        return "BsAnglePreset{rot=" + rotationDeg
                + " crop=(" + cropLeft + "," + cropTop + "," + cropRight + "," + cropBottom + ")"
                + " flipH=" + flipHorizontal + " flipV=" + flipVertical
                + " zoom=" + zoom
                + " translate=(" + translateXFrac + "," + translateYFrac + ")}";
    }
}
