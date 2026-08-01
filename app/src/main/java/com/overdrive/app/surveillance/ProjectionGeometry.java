package com.overdrive.app.surveillance;

/**
 * Pure projection geometry shared by the SurfaceFlinger mirror and local unit tests.
 *
 * <p>All rectangles use {@code left, top, width, height}. The returned dimensions are
 * always positive, even for a one-pixel destination.
 */
public final class ProjectionGeometry {

    public static final int SCALE_FIT = 0;
    public static final int SCALE_FILL = 1;
    public static final int SCALE_ZOOM = 2;

    private ProjectionGeometry() {}

    public static final class Mapping {
        public final int srcX;
        public final int srcY;
        public final int srcW;
        public final int srcH;
        public final int dstX;
        public final int dstY;
        public final int dstW;
        public final int dstH;

        Mapping(int srcX, int srcY, int srcW, int srcH,
                int dstX, int dstY, int dstW, int dstH) {
            this.srcX = srcX;
            this.srcY = srcY;
            this.srcW = srcW;
            this.srcH = srcH;
            this.dstX = dstX;
            this.dstY = dstY;
            this.dstW = dstW;
            this.dstH = dstH;
        }
    }

    public static Mapping calculate(int sourceW, int sourceH,
                                    int boxX, int boxY, int boxW, int boxH,
                                    int scaleMode) {
        int sw = Math.max(1, sourceW);
        int sh = Math.max(1, sourceH);
        int bw = Math.max(1, boxW);
        int bh = Math.max(1, boxH);

        if (scaleMode == SCALE_FILL) {
            return new Mapping(0, 0, sw, sh, boxX, boxY, bw, bh);
        }

        if (scaleMode == SCALE_ZOOM) {
            double sourceAspect = (double) sw / sh;
            double boxAspect = (double) bw / bh;
            int cropW;
            int cropH;
            if (boxAspect > sourceAspect) {
                cropW = sw;
                cropH = clampDimension((int) Math.round(sw / boxAspect), sh);
            } else {
                cropH = sh;
                cropW = clampDimension((int) Math.round(sh * boxAspect), sw);
            }
            int cropX = (sw - cropW) / 2;
            int cropY = (sh - cropH) / 2;
            return new Mapping(cropX, cropY, cropW, cropH, boxX, boxY, bw, bh);
        }

        // Integer contain/center geometry. This matches ClusterMirrorManager's intended
        // truncating FIT behavior without its float precision risk: the constrained axis
        // fills the box and the other axis is floored, so the destination never exceeds it.
        int drawW;
        int drawH;
        if ((long) bw * sh <= (long) bh * sw) {
            drawW = bw;
            drawH = clampDimension((int) ((long) sh * bw / sw), bh);
        } else {
            drawH = bh;
            drawW = clampDimension((int) ((long) sw * bh / sh), bw);
        }
        int offsetX = boxX + (bw - drawW) / 2;
        int offsetY = boxY + (bh - drawH) / 2;
        return new Mapping(0, 0, sw, sh, offsetX, offsetY, drawW, drawH);
    }

    public static double destinationToSource(double coordinate, int sourceOffset,
                                             int sourceSpan, int destinationOffset,
                                             int destinationSpan) {
        if (!Double.isFinite(coordinate) || sourceSpan <= 0 || destinationSpan <= 0) {
            return sourceOffset;
        }
        return sourceOffset
                + (coordinate - destinationOffset) * (double) sourceSpan / destinationSpan;
    }

    private static int clampDimension(int value, int maximum) {
        return Math.max(1, Math.min(value, Math.max(1, maximum)));
    }
}
