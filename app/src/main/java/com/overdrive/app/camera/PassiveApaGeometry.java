package com.overdrive.app.camera;

/** Native preview geometry used by the DiLink 4 passive APA compatibility mode. */
public final class PassiveApaGeometry {
    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;

    private PassiveApaGeometry() {
    }

    /** Preserve the native 16:9 aspect and keep MediaCodec dimensions even. */
    public static int heightForWidth(int width) {
        int height = Math.round(Math.max(1, width) * (float) HEIGHT / (float) WIDTH);
        return Math.max(2, height & ~1);
    }
}
