package com.overdrive.app.surveillance;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;

import java.io.FileInputStream;
import java.nio.ByteBuffer;

/**
 * Looping MP4 playback for the Screen Deterrent, hardware-decoded onto a
 * daemon-owned SurfaceControl layer at z=Integer.MAX_VALUE.
 *
 * Separate layer from {@link ScreenDeterrent}'s static/GIF path because a Surface
 * cannot serve both lockCanvas and a MediaCodec output. The layer is sized to the
 * VIDEO, not the panel — the decoder owns the buffer dimensions, and SurfaceFlinger
 * scales a source-crop of them into the panel rect.
 *
 * All MediaCodec use is synchronous: the daemon render thread has no Looper.
 */
public final class ScreenDeterrentVideo {

    private static final String TAG = "ScreenDeterrentVideo";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private ScreenDeterrentVideo() {}

    /** Same z as the static deterrent layer — the two never render concurrently. */
    private static final int Z_ORDER = Integer.MAX_VALUE;

    private static final long DEQUEUE_TIMEOUT_US = 5_000;
    private static final long DEFAULT_FRAME_GAP_US = 33_333;
    /** Cap on waiting for a frame's presentation time, so a bogus PTS jump can't
     *  park the render thread with the panel frozen mid-deterrent. */
    private static final long MAX_FRAME_SLEEP_MS = 500;
    /** ScreenDeterrent.shouldStop() forceReloads the config file on every call, so
     *  it cannot be polled per frame. Matches the static loop's cadence. */
    private static final long STOP_POLL_INTERVAL_MS = 200;

    public interface StopSignal {
        boolean shouldStop();
    }

    /** ISO-BMFF {@code ftyp} box at offset 4. Magic bytes, not the extension — the
     *  filename comes from a browser upload. */
    public static boolean isMp4File(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] hdr = new byte[12];
            int n = fis.read(hdr);
            return n >= 12
                && hdr[4] == 'f' && hdr[5] == 't' && hdr[6] == 'y' && hdr[7] == 'p';
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Play {@code path} full-screen on loop until {@code stop} fires.
     *
     * @param onFrame invoked after each rendered frame (backlight re-assert).
     * @return true if playback ran and ended on the stop signal; false if setup or
     *         decode failed and the caller should fall back to its static path.
     */
    public static boolean play(String path, int dispW, int dispH,
                               StopSignal stop, Runnable onFrame) {
        if (path == null || path.isEmpty() || dispW <= 0 || dispH <= 0) return false;

        MediaExtractor extractor = null;
        MediaCodec codec = null;
        Object layer = null;
        Surface surface = null;
        boolean codecStarted = false;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);

            int track = selectVideoTrack(extractor);
            if (track < 0) {
                logger.warn("Deterrent video has no video track: " + path);
                return false;
            }
            extractor.selectTrack(track);

            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int vw = displayWidth(format);
            int vh = displayHeight(format);
            if (mime == null || vw <= 0 || vh <= 0) {
                logger.warn("Deterrent video has unusable format: " + format);
                return false;
            }

            layer = createBufferLayer("ScreenDeterrentVideo", vw, vh);
            if (layer == null) return false;
            surface = newSurfaceFor(layer);
            if (surface == null) return false;

            // Centre-crop to cover: letterbox bars would expose BYD's AccAnimation
            // layer underneath, which is what the deterrent exists to prevent.
            applyGeometry(layer, coverCrop(vw, vh, dispW, dispH),
                          new Rect(0, 0, dispW, dispH));

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, surface, null, 0);
            codec.start();
            codecStarted = true;

            logger.info("Deterrent video: " + vw + "x" + vh + " " + mime
                    + " on " + dispW + "x" + dispH + " panel");

            return decodeLoop(codec, extractor, frameGapUs(format), stop, onFrame);

        } catch (Throwable t) {
            logger.warn("Deterrent video setup failed: " + t.getMessage());
            return false;
        } finally {
            if (codec != null) {
                if (codecStarted) {
                    try { codec.stop(); } catch (Throwable ignored) {}
                }
                try { codec.release(); } catch (Throwable ignored) {}
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Throwable ignored) {}
            }
            if (surface != null) {
                try { surface.release(); } catch (Throwable ignored) {}
            }
            if (layer != null) releaseLayer(layer);
        }
    }

    private static boolean decodeLoop(MediaCodec codec, MediaExtractor extractor,
                                      long frameGapUs, StopSignal stop, Runnable onFrame)
            throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ThrottledStop shouldStop = new ThrottledStop(stop);

        long loopOffsetUs = 0;
        long lastSampleUs = 0;
        long anchorNs = -1;
        boolean rendered = false;
        boolean inputDone = false;

        while (!shouldStop.check()) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer in = codec.getInputBuffer(inIdx);
                    int size = in == null ? -1 : extractor.readSampleData(in, 0);
                    if (size < 0) {
                        // Loop by rewinding rather than signalling end-of-stream: the
                        // clip opens on an IDR, so the decoder just sees another
                        // keyframe. The offset keeps PTS monotonic across the seam.
                        loopOffsetUs += lastSampleUs + frameGapUs;
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        size = in == null ? -1 : extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                    if (!inputDone && size >= 0) {
                        lastSampleUs = Math.max(0, extractor.getSampleTime());
                        codec.queueInputBuffer(inIdx, 0, size,
                                lastSampleUs + loopOffsetUs, 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            if (outIdx >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0) {
                    if (anchorNs < 0) {
                        anchorNs = System.nanoTime() - info.presentationTimeUs * 1000L;
                    }
                    if (!sleepUntil(anchorNs + info.presentationTimeUs * 1000L, shouldStop)) {
                        codec.releaseOutputBuffer(outIdx, false);
                        return rendered;
                    }
                    codec.releaseOutputBuffer(outIdx, true);
                    rendered = true;
                    if (onFrame != null) onFrame.run();
                } else {
                    codec.releaseOutputBuffer(outIdx, false);
                }
                if (eos) return rendered && holdLastFrame(shouldStop, onFrame);
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                logger.debug("Deterrent video output format: " + codec.getOutputFormat());
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && rendered) {
                return holdLastFrame(shouldStop, onFrame);
            }
        }
        return rendered;
    }

    /** Hold the last decoded frame on the layer until the deterrent's deadline. */
    private static boolean holdLastFrame(ThrottledStop shouldStop, Runnable onFrame) {
        while (!shouldStop.check()) {
            if (onFrame != null) onFrame.run();
            try {
                Thread.sleep(STOP_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return true;
    }

    /** False if the caller should abandon the frame and unwind. */
    private static boolean sleepUntil(long targetNs, ThrottledStop shouldStop) {
        while (true) {
            long remainingNs = targetNs - System.nanoTime();
            if (remainingNs <= 0) return true;
            long sleepMs = Math.min(remainingNs / 1_000_000L, MAX_FRAME_SLEEP_MS);
            if (sleepMs <= 0) return true;
            if (shouldStop.check()) return false;
            try {
                Thread.sleep(Math.min(sleepMs, STOP_POLL_INTERVAL_MS));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (sleepMs >= MAX_FRAME_SLEEP_MS) return true;
        }
    }

    /** Rate-limits the disk-reading stop predicate; see STOP_POLL_INTERVAL_MS. */
    private static final class ThrottledStop {
        private final StopSignal delegate;
        private long lastCheckMs = 0;
        private boolean cached = false;

        ThrottledStop(StopSignal delegate) { this.delegate = delegate; }

        boolean check() {
            if (cached) return true;
            if (Thread.currentThread().isInterrupted()) return true;
            long now = System.currentTimeMillis();
            if (now - lastCheckMs < STOP_POLL_INTERVAL_MS) return false;
            lastCheckMs = now;
            try {
                cached = delegate == null || delegate.shouldStop();
            } catch (Throwable t) {
                cached = true;
            }
            return cached;
        }
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    /** Crop rect, not KEY_WIDTH/HEIGHT: H.264 pads coded dimensions to a multiple
     *  of 16, so a 1080-tall clip reports 1088 and would show 8 rows of garbage. */
    private static int displayWidth(MediaFormat f) {
        if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
            return f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
        }
        return f.containsKey(MediaFormat.KEY_WIDTH) ? f.getInteger(MediaFormat.KEY_WIDTH) : 0;
    }

    private static int displayHeight(MediaFormat f) {
        if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
            return f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
        }
        return f.containsKey(MediaFormat.KEY_HEIGHT) ? f.getInteger(MediaFormat.KEY_HEIGHT) : 0;
    }

    private static long frameGapUs(MediaFormat f) {
        try {
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                int fps = f.getInteger(MediaFormat.KEY_FRAME_RATE);
                if (fps > 0) return 1_000_000L / fps;
            }
        } catch (Throwable ignored) {
            // Some extractors store KEY_FRAME_RATE as a float.
        }
        return DEFAULT_FRAME_GAP_US;
    }

    /**
     * Centred sub-rect of the video with the panel's aspect ratio; scaled to the
     * full panel this gives cover/centre-crop.
     *
     * Returns {left, top, right, bottom} rather than a Rect so the arithmetic is
     * reachable from a plain JVM unit test.
     */
    static int[] coverCropBounds(int vw, int vh, int dispW, int dispH) {
        float videoAspect = (float) vw / vh;
        float panelAspect = (float) dispW / dispH;
        int cw, ch;
        if (videoAspect > panelAspect) {
            ch = vh;
            cw = Math.max(1, Math.round(vh * panelAspect));
        } else {
            cw = vw;
            ch = Math.max(1, Math.round(vw / panelAspect));
        }
        cw = Math.min(cw, vw);
        ch = Math.min(ch, vh);
        int cx = (vw - cw) / 2;
        int cy = (vh - ch) / 2;
        return new int[] { cx, cy, cx + cw, cy + ch };
    }

    private static Rect coverCrop(int vw, int vh, int dispW, int dispH) {
        int[] b = coverCropBounds(vw, vh, dispW, dispH);
        return new Rect(b[0], b[1], b[2], b[3]);
    }

    /** Used by the upload endpoint to reject a clip this unit cannot decode. */
    public static boolean hasDecoderFor(String mime) {
        if (mime == null || mime.isEmpty()) return false;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            MediaFormat probe = MediaFormat.createVideoFormat(mime, 1920, 1080);
            return list.findDecoderForFormat(probe) != null;
        } catch (Throwable t) {
            // Probe unavailable — don't block the upload on a diagnostic.
            return true;
        }
    }

    // ── SurfaceControl reflection (mirrors BsNativeLayer's proven path) ────

    private static Object createBufferLayer(String name, int w, int h) {
        try {
            Class<?> b = Class.forName("android.view.SurfaceControl$Builder");
            Object builder = b.getDeclaredConstructor().newInstance();
            b.getMethod("setName", String.class).invoke(builder, name);
            b.getMethod("setBufferSize", int.class, int.class).invoke(builder, w, h);
            // Opaque, unlike the blind-spot card: video has no alpha, and this lets
            // SurfaceFlinger skip blending the whole panel.
            try {
                b.getMethod("setOpaque", boolean.class).invoke(builder, true);
            } catch (NoSuchMethodException ignored) {}
            return b.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            logger.warn("Video buffer layer creation failed: " + t.getMessage());
            return null;
        }
    }

    private static Surface newSurfaceFor(Object layer) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            return Surface.class.getConstructor(sc).newInstance(layer);
        } catch (Throwable t) {
            logger.warn("Surface-from-SurfaceControl failed: " + t.getMessage());
            return null;
        }
    }

    private static void applyGeometry(Object layer, Rect src, Rect dst) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try {
                txCls.getMethod("setLayer", scCls, int.class).invoke(tx, layer, Z_ORDER);
            } catch (Throwable ignored) {}
            try {
                txCls.getMethod("setAlpha", scCls, float.class).invoke(tx, layer, 1.0f);
            } catch (Throwable ignored) {}
            boolean geom = false;
            try {
                txCls.getMethod("setGeometry", scCls, Rect.class, Rect.class, int.class)
                        .invoke(tx, layer, src, dst, 0);
                geom = true;
            } catch (Throwable ignored) {}
            if (!geom) {
                // No 4-arg setGeometry: scale the whole buffer from the origin. Loses
                // the centre-crop (a mismatched aspect stretches) but still covers.
                try {
                    txCls.getMethod("setPosition", scCls, float.class, float.class)
                            .invoke(tx, layer, 0f, 0f);
                } catch (Throwable ignored) {}
                try {
                    txCls.getMethod("setMatrix", scCls, float.class, float.class,
                                    float.class, float.class)
                            .invoke(tx, layer,
                                    (float) dst.width() / Math.max(1, src.width()), 0f,
                                    0f, (float) dst.height() / Math.max(1, src.height()));
                } catch (Throwable ignored) {}
            }
            try {
                txCls.getMethod("show", scCls).invoke(tx, layer);
            } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable t) {
            logger.warn("Video layer geometry failed: " + t.getMessage());
        }
    }

    private static void releaseLayer(Object layer) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("hide", scCls).invoke(tx, layer); } catch (Throwable ignored) {}
            try {
                txCls.getMethod("reparent", scCls, scCls).invoke(tx, layer, null);
            } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
            try { scCls.getMethod("release").invoke(layer); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logger.debug("Video layer release failed: " + t.getMessage());
        }
    }
}
