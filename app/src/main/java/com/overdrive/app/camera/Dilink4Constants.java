package com.overdrive.app.camera;

/**
 * Hardcoded DiLink 4 (byd_apa) producer-corner remap + per-role flip
 * constants. Single source of truth for the recorder (GpuMosaicRecorder),
 * the stream scaler (GpuStreamScaler), the blind-spot scaler, the AI-lane
 * downscaler (GpuDownscaler) and the motion cropper (FoveatedCropper), so
 * they can never silently disagree on the mosaic arrangement.
 *
 * <p><b>Producer→canonical mapping (Variant A).</b> The byd_apa HAL emits a
 * non-canonical 2x2. Corners are confirmed against the reference app's
 * {@code APACameraLensFilter} ({@code ml/a.g()}), which selects its source
 * sub-rect per lens index — noting that its UVs are GL-style (y=0 at BOTTOM)
 * while ours are texture-style (y=0 at TOP), so its "front = 上" (y 0.5..1.0)
 * is our TL:
 * <pre>
 *   index 1 front 前视(左上) uv y 0.5..1.0, x 0.0..0.5  → our TL (0.0, 0.0)
 *   index 2 rear  后视(右上) uv y 0.5..1.0, x 0.5..1.0  → our TR (0.5, 0.0)
 *   index 3 left  左视(左下) uv y 0.0..0.5, x 0.0..0.5  → our BL (0.0, 0.5)
 *   index 4 right 右视(右下) uv y 0.0..0.5, x 0.5..1.0  → our BR (0.5, 0.5)
 * </pre>
 * That matches {@code CORNER_*} below exactly, so the corner remap is right
 * and has never been the bug.
 *
 * <p><b>ORIENTATION FIX (front + right were upside down).</b> On-device the
 * FRONT and RIGHT tiles rendered vertically inverted in the live "All
 * Cameras" mosaic while REAR and LEFT were already correct. Canonical output
 * quadrants are Front=TL, Right=TR, Rear=BL, Left=BR (per the shader's
 * {@code inRight}/{@code inRear}/{@code inLeft} tests), which is how the
 * screenshot's two bad tiles were attributed to front and right.
 *
 * <p>Front and right were exactly the two roles carrying {@code y = 1.0f}, so
 * the fix is to CLEAR those two Y bits and change nothing else:
 * <pre>
 *   before:  FRONT {1,1}   RIGHT {0,1}   REAR {0,0}   LEFT {0,0}
 *   after:   FRONT {1,0}   RIGHT {0,0}   REAR {0,0}   LEFT {0,0}
 * </pre>
 * Rear and left keep {@code y = 0} — they rendered upright under the old
 * values, so setting a Y bit on them would invert two working cameras. The X
 * bit is unchanged: only FRONT is X-mirrored, consistent across every feed
 * site and not implicated by the reported symptom.
 *
 * <p><b>Do NOT "restore parity" with the old FoveatedCropper literal.</b> The
 * cropper was separately fed {@code FRONT{1,0} RIGHT{0,0} REAR{0,1} LEFT{0,1}}
 * at {@code PanoramicCameraGpu:1173}. That set agrees with the fix on front and
 * right but carries Y bits on rear/left that the screenshot contradicts, so it
 * was NOT a correct reference for the render paths. It looked authoritative
 * because the cropper never renders the visible mosaic — it only maps a motion
 * centroid into producer space — so its rear/left Y error was unobservable in
 * the picture and only ever mis-placed motion crops for those two roles. All
 * five feed sites now share the values below.
 *
 * <p><b>Flip semantics.</b> {@code {x, y}}, 1.0 = mirror within the role's
 * local 0.5x0.5 producer window. Applied in the fragment shaders as
 * {@code sampledLocal.n = 0.5 - sampledLocal.n} and in
 * {@code FoveatedCropper} as {@code 1.0 - camNorm}. These uniforms are read
 * ONLY under {@code uApaMode > 2.5} (layout 3 = dilink4); on every legacy
 * trim the shader takes the 4-strip branch and never samples them, so these
 * values are inert outside DiLink 4.
 *
 * <p>If a future Variant B SKU lands, switch on a profile field inside
 * this helper rather than duplicating new constants at every call site.
 */
public final class Dilink4Constants {
    private Dilink4Constants() {}

    public static final float[] CORNER_FRONT = { 0.0f, 0.0f };
    public static final float[] CORNER_RIGHT = { 0.5f, 0.5f };
    public static final float[] CORNER_REAR  = { 0.5f, 0.0f };
    public static final float[] CORNER_LEFT  = { 0.0f, 0.5f };

    public static final float[] FLIP_FRONT = { 1.0f, 0.0f };  // X flip only (Y cleared — was upside down)
    public static final float[] FLIP_RIGHT = { 0.0f, 0.0f };  // no flip     (Y cleared — was upside down)
    public static final float[] FLIP_REAR  = { 0.0f, 0.0f };  // no flip     (unchanged — already upright)
    public static final float[] FLIP_LEFT  = { 0.0f, 0.0f };  // no flip     (unchanged — already upright)
}
