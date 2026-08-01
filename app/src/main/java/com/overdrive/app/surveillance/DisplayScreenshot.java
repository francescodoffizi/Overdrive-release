package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.IBinder;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One-shot PNG capture of a whole display, for the "Take Screenshot" actions.
 *
 * <h3>Why not {@code screencap}</h3>
 * The shipped screenshot action shelled {@code screencap -d <id>}. On API 29 that {@code -d}
 * argument is a <b>PhysicalDisplayId</b> (resolved via {@code getPhysicalDisplayToken}), not the
 * logical DisplayManager id: {@code -d 0} is not reliably the panel (the internal display is used
 * only when {@code -d} is OMITTED), and the driver cluster is a VIRTUAL display which has no
 * physical token at all — so neither head-unit nor cluster capture could work that way.
 *
 * <p>Instead we use the mechanism {@link ClusterMirrorController} already proves on this device
 * (its primary MODE_DIRECT path): ask SurfaceFlinger to composite a display's <b>layer stack</b>
 * into an {@link ImageReader} we own via a throwaway {@code SurfaceControl} virtual display, then
 * encode the first non-empty frame as a PNG. That addresses displays by layer stack, which works
 * for the head unit (stack 0) and for the cluster's virtual display alike.
 */
public final class DisplayScreenshot {

    private static final DaemonLogger logger = DaemonLogger.getInstance("DisplayScreenshot");

    /** Head-unit SurfaceFlinger compositing stack — the default display is always 0. */
    public static final int HEAD_UNIT_STACK = 0;

    private static final String CAPTURE_DISPLAY_NAME = "OverDriveShot";
    private static final long FRAME_TIMEOUT_MS = 2000;

    private DisplayScreenshot() {}

    /** Outcome of a capture: either a written file, or the reason nothing was written. */
    public static final class Result {
        public final boolean ok;
        public final String path;
        public final String error;
        private Result(boolean ok, String path, String error) {
            this.ok = ok; this.path = path; this.error = error;
        }
        static Result success(String path) { return new Result(true, path, null); }
        static Result failure(String error) { return new Result(false, null, error); }
    }

    /**
     * Capture {@code stack} at {@code w}x{@code h} and write a PNG to {@code outFile}.
     *
     * <p>Runs synchronously (bounded by {@link #FRAME_TIMEOUT_MS}); callers must not invoke it on
     * a thread that cannot block briefly. The throwaway display + reader are always torn down.
     */
    public static Result capture(int stack, int w, int h, File outFile) {
        if (w <= 0 || h <= 0) return Result.failure("invalid capture size " + w + "x" + h);
        if (stack < 0) return Result.failure("unresolved display layer stack");

        ImageReader reader = null;
        Object token = null;
        // ImageReader's callback needs a Looper and the caller's thread (an HTTP worker) has
        // none — passing a null Handler would throw. Short-lived thread, quit in finally.
        android.os.HandlerThread cbThread = new android.os.HandlerThread("OverDriveShotCb");
        cbThread.start();
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] shot = { null };
        try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null || shot[0] != null) return;
                    // Skip all-black frames rather than saving the first thing that arrives.
                    // A createDisplay/applyDisplay pair can "succeed" and still deliver black
                    // (the reason the cluster mirror probes for exactly this), and the first
                    // composite of a fresh virtual display can land before real content —
                    // either would otherwise be written out and reported as a good screenshot.
                    // Keep consuming until the timeout; a genuinely black screen simply fails.
                    if (isBlank(img)) return;
                    shot[0] = toBitmap(img);
                    if (shot[0] != null) latch.countDown();
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) try { img.close(); } catch (Throwable ignored) {}
                }
            }, new android.os.Handler(cbThread.getLooper()));

            token = createDisplay();
            if (token == null) return Result.failure("SurfaceControl.createDisplay unavailable");
            if (!applyDisplay(token, reader.getSurface(), stack, w, h)) {
                return Result.failure("display capture transaction failed");
            }
            if (!latch.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS) || shot[0] == null) {
                return Result.failure("no frame from display (stack " + stack + ")");
            }
            return writePng(shot[0], outFile);
        } catch (SecurityException se) {
            return Result.failure("capture denied: " + se.getMessage());
        } catch (Throwable t) {
            return Result.failure("capture failed: " + t.getMessage());
        } finally {
            if (token != null) { detachDisplay(token); destroyDisplay(token); }
            if (reader != null) try { reader.close(); } catch (Throwable ignored) {}
            if (shot[0] != null) try { shot[0].recycle(); } catch (Throwable ignored) {}
            try { cbThread.quitSafely(); } catch (Throwable ignored) {}
        }
    }

    /** Capture the head unit at its real panel size. */
    public static Result captureHeadUnit(Context ctx, File outFile) {
        Point size = headUnitSize(ctx);
        return capture(HEAD_UNIT_STACK, size.x, size.y, outFile);
    }

    /**
     * Capture the driver cluster. Only possible while an OEM projection is open — the fission
     * display does not exist otherwise, so there is genuinely nothing to capture.
     */
    public static Result captureCluster(Context ctx, File outFile) {
        BsNativeLayer.FissionDisplay fd = BsNativeLayer.resolveFissionDisplay();
        if (fd == null || fd.layerStack == BsNativeLayer.STACK_UNRESOLVED || fd.layerStack == 0) {
            return Result.failure("cluster display not active (open a cluster projection first)");
        }
        Point size = BsNativeLayer.clusterDisplaySize(ctx, fd);
        return capture(fd.layerStack, size.x, size.y, outFile);
    }

    /** Real head-unit panel size, falling back to this platform's 1920x1080 panel. */
    private static Point headUnitSize(Context ctx) {
        try {
            android.hardware.display.DisplayManager dm = (ctx == null) ? null
                    : (android.hardware.display.DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            android.view.Display d = (dm == null) ? null
                    : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d != null) {
                Point p = new Point();
                d.getRealSize(p);
                if (p.x > 0 && p.y > 0) return p;
            }
        } catch (Throwable t) {
            logger.debug("headUnitSize failed: " + t.getMessage());
        }
        return new Point(1920, 1080);
    }

    /**
     * True if the frame is (near) uniformly black — a coarse sample.
     *
     * <p>An RGBA_8888 plane is [R,G,B,A,...] and a black-but-OPAQUE frame has A=0xFF, so summing
     * raw bytes including alpha would read ~64 average and call a black frame non-black. Sample
     * only R/G/B, walking the plane's pixel/row strides.
     */
    private static boolean isBlank(Image img) {
        try {
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) return true;
            Image.Plane plane = planes[0];
            ByteBuffer buf = plane.getBuffer();
            if (buf == null) return true;
            int pixelStride = Math.max(1, plane.getPixelStride());
            int rowStride = Math.max(pixelStride, plane.getRowStride());
            int w = img.getWidth(), h = img.getHeight();
            // ~32x32 sample points is plenty to tell "some content" from "all black".
            int stepX = Math.max(1, w / 32), stepY = Math.max(1, h / 32);
            long sum = 0;
            int counted = 0;
            for (int y = 0; y < h; y += stepY) {
                int row = y * rowStride;
                for (int x = 0; x < w; x += stepX) {
                    int i = row + x * pixelStride;
                    if (i + 2 >= buf.limit()) break;
                    sum += (buf.get(i) & 0xFF) + (buf.get(i + 1) & 0xFF) + (buf.get(i + 2) & 0xFF);
                    counted++;
                }
            }
            if (counted == 0) return true;
            // Mean RGB per sampled pixel; a lit panel clears this trivially.
            return (sum / (double) counted) <= 3.0;
        } catch (Throwable t) {
            logger.debug("isBlank failed: " + t.getMessage());
            return false;   // can't tell → don't discard a possibly-good frame
        }
    }

    /** Copy an RGBA_8888 image out of the reader, honouring its row stride. */
    private static Bitmap toBitmap(Image img) {
        try {
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) return null;
            Image.Plane plane = planes[0];
            ByteBuffer buf = plane.getBuffer();
            if (buf == null) return null;
            int w = img.getWidth(), h = img.getHeight();
            // A hardware buffer's rows are padded to its stride, so the bitmap must be created
            // stride-wide and then cropped — otherwise every row shears progressively.
            int pixelStride = Math.max(1, plane.getPixelStride());
            int rowStride = Math.max(pixelStride * w, plane.getRowStride());
            int padded = rowStride / pixelStride;
            Bitmap full = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888);
            buf.rewind();
            full.copyPixelsFromBuffer(buf);
            if (padded == w) return full;
            Bitmap cropped = Bitmap.createBitmap(full, 0, 0, w, h);
            if (cropped != full) full.recycle();
            return cropped;
        } catch (Throwable t) {
            logger.warn("toBitmap failed: " + t.getMessage());
            return null;
        }
    }

    private static Result writePng(Bitmap bmp, File outFile) {
        try {
            File dir = outFile.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return Result.failure("could not create " + dir.getAbsolutePath());
            }
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    return Result.failure("PNG encode failed");
                }
                fos.flush();
            }
            if (outFile.length() == 0) return Result.failure("wrote an empty file");
            // World-readable: the daemon (UID 2000) writes it, the app UI reads it.
            try { outFile.setReadable(true, false); } catch (Throwable ignored) {}
            return Result.success(outFile.getAbsolutePath());
        } catch (Throwable t) {
            return Result.failure("write failed: " + t.getMessage());
        }
    }

    // ── SurfaceControl virtual-display reflection (mirrors ClusterMirrorController) ──

    private static Object createDisplay() {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            return sc.getMethod("createDisplay", String.class, boolean.class)
                    .invoke(null, CAPTURE_DISPLAY_NAME, false);   // secure=false
        } catch (Throwable t) {
            logger.warn("createDisplay failed: " + t.getMessage());
            return null;
        }
    }

    private static void destroyDisplay(Object token) {
        try {
            Class.forName("android.view.SurfaceControl")
                    .getMethod("destroyDisplay", IBinder.class).invoke(null, token);
        } catch (Throwable t) {
            logger.debug("destroyDisplay failed: " + t.getMessage());
        }
    }

    /** Drop SurfaceFlinger's producer reference before the display dies (see the mirror's
     *  teardown note — a dangling producer can take the native side down). */
    private static void detachDisplay(Object token) {
        try {
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setDisplaySurface", IBinder.class, Surface.class)
                    .invoke(tx, token, (Surface) null); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
            try { txCls.getMethod("close").invoke(tx); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logger.debug("detachDisplay failed: " + t.getMessage());
        }
    }

    /** Point a throwaway virtual display at {@code stack} and render it 1:1 into {@code out}. */
    private static boolean applyDisplay(Object token, Surface out, int stack, int w, int h) {
        try {
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            txCls.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(tx, token, out);
            txCls.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(tx, token, stack);
            Rect full = new Rect(0, 0, w, h);
            try {
                txCls.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                        .invoke(tx, token, 0, full, full);
            } catch (Throwable ignored) { /* best-effort */ }
            try {
                txCls.getMethod("setDisplaySize", IBinder.class, int.class, int.class)
                        .invoke(tx, token, w, h);
            } catch (Throwable ignored) { /* best-effort */ }
            txCls.getMethod("apply").invoke(tx);
            try { txCls.getMethod("close").invoke(tx); } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            logger.warn("applyDisplay failed: " + t.getMessage());
            return false;
        }
    }
}
