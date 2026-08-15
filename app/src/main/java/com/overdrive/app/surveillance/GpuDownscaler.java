package com.overdrive.app.surveillance;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.camera.GlUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * AsyncGpuDownscaler - Zero-stutter GPU thumbnail generator.
 * 
 * Uses a dedicated background thread with EGL context sharing to avoid
 * expensive eglMakeCurrent calls on the main render thread.
 * 
 * Key features:
 * - Dedicated background thread (never touches main thread's EGL)
 * - Shared EGL context (can read main thread's camera texture)
 * - ImageReader DMA output (zero-copy to system RAM)
 * - Non-blocking postFrame() returns instantly
 * 
 * USAGE:
 * 1. Initialize from GL thread (onSurfaceCreated):
 *    gpuDownscaler.init(EGL14.eglGetCurrentContext());
 * 
 * 2. In onDrawFrame (main thread):
 *    drawCameraPreview();
 *    GLES20.glFlush();  // Ensure texture is ready before background reads it
 *    gpuDownscaler.postFrame(cameraTextureId);
 * 
 * 3. In AI thread:
 *    Image image = gpuDownscaler.acquireLatestImage();
 *    if (image != null) {
 *        ByteBuffer buf = GpuDownscaler.getDirectBuffer(image);
 *        tflite.run(buf, output);  // Zero-copy!
 *        image.close();
 *    }
 */
public class GpuDownscaler {
    private static final String TAG = "GpuDownscaler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    
    // ImageReader for DMA output
    private ImageReader imageReader;
    
    // Background thread
    private HandlerThread renderThread;
    private Handler renderHandler;
    
    // EGL state (owned by background thread)
    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;
    
    // Shared context from main thread
    private EGLContext sharedContext;

    // Per-quadrant strip offsets and the runtime-baked fragment shader.
    // Default mirrors legacy Seal/Atto layout. Pass quadrant offsets from
    // ResolvedCameraConfig.getQuadrantStripOffsetX() to support Tang.
    private final float[] quadrantStripOffsetX;
    private final String fragmentShader;
    private static final float[] DEFAULT_QUADRANT_STRIP_OFFSET_X = {
        0.75f, 0.50f, 0.00f, 0.25f
    };

    // Shader program
    private int programId;
    private int aPositionLocation;
    private int aTexCoordLocation;
    private int uCameraTexLocation;
    private int uTexMatrixLocation;
    private int uApaModeLocation;
    private int uApplyManualYFlipLocation;
    // Per-role producer corner + flip uniforms — main programId.
    private int uProducerForFrontLocation = -1;
    private int uProducerForRightLocation = -1;
    private int uProducerForRearLocation = -1;
    private int uProducerForLeftLocation = -1;
    private int uFlipForFrontLocation = -1;
    private int uFlipForRightLocation = -1;
    private int uFlipForRearLocation = -1;
    private int uFlipForLeftLocation = -1;
    private int uRedMaskStrengthLocation = -1;
    private volatile boolean redMaskEnabled = false;
    private int uApaCenterInsetLocation = -1;
    private volatile float apaCenterInset = 0.0f;
    // Producer-corner remap + per-role X/Y flip flags (Variant A on
    // DiLink 4). UI thread writes via setProducerCornerMap/setFlipFlags;
    // GL thread reads in drawFrame paths under producerCornerMapLock.
    private final float[] producerCornerMap = {
        0.00f, 0.00f,
        0.50f, 0.00f,
        0.00f, 0.50f,
        0.50f, 0.50f
    };
    private final float[] flipFlags = {
        0f, 0f,  0f, 0f,  0f, 0f,  0f, 0f
    };
    private final Object producerCornerMapLock = new Object();

    // SurfaceTexture transform matrix and layout selector. Written from
    // the camera GL thread via setTextureMatrix / setCameraLayout, read on
    // every readPixels / readPixelsDirect call. Plain copy is safe — both
    // setters and the readers run on the same GL thread (the AI-lane GL
    // thread for direct path, the probe thread for legacy readPixels;
    // neither concurrently with the camera thread for this instance).
    private final float[] currentTexMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };
    private volatile int cameraLayout = 0;  // 0=4-strip rearrange, 3=passthrough
    
    // Vertex buffers
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    private volatile boolean initialized = false;
    
    // Fullscreen quad
    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    
    // Texture coordinates — UN-flipped V. Vertex shader applies manual
    // Y-flip on legacy (uTexMatrix=identity); DiLink 4's matrix handles it.
    private static final float[] TEX_COORDS = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    };
    
    // Vertex shader. oem-parity: applies the SurfaceTexture transform
    // matrix so AI-lane samples land inside the HAL's "live" sub-region
    // even when the producer surface contains chrome/letterbox. Identity
    // by default — legacy ImageReader path is unaffected.
    //
    // Manual Y-flip: legacy ImageReader path used pre-flipped TEX_COORDS;
    // we now flip in the vertex shader instead (uApplyManualYFlip=1.0).
    // DiLink 4 path uses the SurfaceTexture matrix's built-in Y-flip
    // (uApplyManualYFlip=0.0). Both yield producer-top at top-of-frame.
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform float uApplyManualYFlip;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vec2 src = aTexCoord;\n" +
        "    if (uApplyManualYFlip > 0.5) src.y = 1.0 - src.y;\n" +
        "    vTexCoord = (uTexMatrix * vec4(src, 0.0, 1.0)).xy;\n" +
        "}\n";
    
    /**
     * Creates the async downscaler with shared EGL context.
     *
     * @param mainThreadContext EGL context from main render thread (for texture sharing)
     */
    public GpuDownscaler(EGLContext mainThreadContext) {
        this(mainThreadContext, null);
    }

    public GpuDownscaler(EGLContext mainThreadContext, float[] quadrantStripOffsetX) {
        this.sharedContext = mainThreadContext;
        this.quadrantStripOffsetX = normalizeOffsets(quadrantStripOffsetX);
        this.fragmentShader = buildFragmentShader(this.quadrantStripOffsetX);

        // Start background thread
        renderThread = new HandlerThread("GpuDownscalerThread");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        // Initialize EGL on background thread
        renderHandler.post(this::initGlOnThread);
    }

    /**
     * Default constructor - call init() later with context.
     */
    public GpuDownscaler() {
        this((float[]) null);
    }

    public GpuDownscaler(float[] quadrantStripOffsetX) {
        this.sharedContext = null;
        this.quadrantStripOffsetX = normalizeOffsets(quadrantStripOffsetX);
        this.fragmentShader = buildFragmentShader(this.quadrantStripOffsetX);
    }
    
    /**
     * Initialize with main thread's EGL context.
     */
    public void init(EGLContext mainThreadContext) {
        this.sharedContext = mainThreadContext;
        
        renderThread = new HandlerThread("GpuDownscalerThread");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
        
        renderHandler.post(this::initGlOnThread);
    }
    
    /**
     * Legacy init - grabs current context automatically.
     * 
     * ⚠️ WARNING: Must be called from GL thread (e.g., onSurfaceCreated), NOT from
     * Activity.onCreate() or UI thread! The UI thread has no EGL context.
     * 
     * If called from wrong thread, EGL14.eglGetCurrentContext() returns EGL_NO_CONTEXT
     * and texture sharing will silently fail.
     */
    public void init() {
        EGLContext ctx = EGL14.eglGetCurrentContext();
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            logger.error("init() called without EGL context! Must call from GL thread (onSurfaceCreated)");
            throw new IllegalStateException("GpuDownscaler.init() must be called from GL thread");
        }
        init(ctx);
    }
    
    /**
     * Legacy init with grayscale flag (ignored, always RGBA).
     * 
     * ⚠️ WARNING: Must be called from GL thread!
     */
    public void init(boolean grayscaleMode) {
        init();
    }
    
    private void initGlOnThread() {
        try {
            // Setup ImageReader
            imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
            
            // Setup EGL with shared context
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1);
            
            int[] configAttribs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0);
            
            // Create context with sharing (can read main thread's textures)
            int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            };
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], sharedContext, contextAttribs, 0);
            
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw new RuntimeException("Failed to create shared EGL context");
            }
            
            // Create surface from ImageReader
            int[] surfaceAttribs = { EGL14.EGL_NONE };
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], 
                imageReader.getSurface(), surfaceAttribs, 0);
            
            // Make current ONCE AND FOREVER (no more context switching!)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            
            // Setup shaders
            setupShaders();
            
            initialized = true;
            logger.info("AsyncGpuDownscaler initialized (shared context, zero-stutter)");
            
        } catch (Exception e) {
            logger.error("Failed to init GL on thread: " + e.getMessage());
        }
    }
    
    private void setupShaders() {
        programId = GlUtil.createProgram(VERTEX_SHADER, fragmentShader);
        if (programId == 0) {
            throw new RuntimeException("Failed to create shader program");
        }
        
        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(programId, "aTexCoord");
        uCameraTexLocation = GLES20.glGetUniformLocation(programId, "uCameraTex");
        uTexMatrixLocation = GLES20.glGetUniformLocation(programId, "uTexMatrix");
        uApaModeLocation = GLES20.glGetUniformLocation(programId, "uApaMode");
        uApplyManualYFlipLocation = GLES20.glGetUniformLocation(programId, "uApplyManualYFlip");
        uProducerForFrontLocation = GLES20.glGetUniformLocation(programId, "uProducerForFront");
        uProducerForRightLocation = GLES20.glGetUniformLocation(programId, "uProducerForRight");
        uProducerForRearLocation  = GLES20.glGetUniformLocation(programId, "uProducerForRear");
        uProducerForLeftLocation  = GLES20.glGetUniformLocation(programId, "uProducerForLeft");
        uFlipForFrontLocation = GLES20.glGetUniformLocation(programId, "uFlipForFront");
        uFlipForRightLocation = GLES20.glGetUniformLocation(programId, "uFlipForRight");
        uFlipForRearLocation  = GLES20.glGetUniformLocation(programId, "uFlipForRear");
        uFlipForLeftLocation  = GLES20.glGetUniformLocation(programId, "uFlipForLeft");
        uRedMaskStrengthLocation = GLES20.glGetUniformLocation(programId, "uRedMaskStrength");
        uApaCenterInsetLocation = GLES20.glGetUniformLocation(programId, "uApaCenterInset");

        vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
        texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);
    }

    /**
     * Non-blocking call to trigger a downscale.
     * Returns immediately - rendering happens on background thread.
     * 
     * @param textureId Camera texture ID from main thread
     */
    public void postFrame(int textureId) {
        if (!initialized || renderHandler == null) return;
        renderHandler.post(() -> drawFrame(textureId));
    }
    
    /**
     * Get the latest image for AI inference.
     * Call from AI thread, not main thread.
     * 
     * @return Image with RGBA data, or null if not available
     */
    public Image acquireLatestImage() {
        if (imageReader == null) return null;
        return imageReader.acquireLatestImage();
    }
    
    private void drawFrame(int textureId) {
        if (!initialized) return;
        
        GLES20.glViewport(0, 0, WIDTH, HEIGHT);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        GLES20.glUseProgram(programId);
        
        // Bind main thread's camera texture (allowed via shared context)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(uCameraTexLocation, 0);
        if (uTexMatrixLocation >= 0) {
            GLES20.glUniformMatrix4fv(uTexMatrixLocation, 1, false, currentTexMatrix, 0);
        }
        if (uApaModeLocation >= 0) {
            GLES20.glUniform1f(uApaModeLocation, (float) cameraLayout);
        }
        if (uApplyManualYFlipLocation >= 0) {
            GLES20.glUniform1f(uApplyManualYFlipLocation,
                cameraLayout == 3 ? 0.0f : 1.0f);
        }
        if (uProducerForFrontLocation >= 0) {
            float[] m = new float[8];
            float[] f = new float[8];
            snapshotProducerCornersAndFlips(m, f);
            GLES20.glUniform2f(uProducerForFrontLocation, m[0], m[1]);
            GLES20.glUniform2f(uProducerForRightLocation, m[2], m[3]);
            GLES20.glUniform2f(uProducerForRearLocation,  m[4], m[5]);
            GLES20.glUniform2f(uProducerForLeftLocation,  m[6], m[7]);
            if (uFlipForFrontLocation >= 0) {
                GLES20.glUniform2f(uFlipForFrontLocation, f[0], f[1]);
                GLES20.glUniform2f(uFlipForRightLocation, f[2], f[3]);
                GLES20.glUniform2f(uFlipForRearLocation,  f[4], f[5]);
                GLES20.glUniform2f(uFlipForLeftLocation,  f[6], f[7]);
            }
        }
        if (uRedMaskStrengthLocation >= 0) {
            GLES20.glUniform1f(uRedMaskStrengthLocation, redMaskEnabled ? 1.0f : 0.0f);
        }
        if (uApaCenterInsetLocation >= 0) {
            GLES20.glUniform1f(uApaCenterInsetLocation, apaCenterInset);
        }

        // Draw quad
        GLES20.glEnableVertexAttribArray(aPositionLocation);
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(aTexCoordLocation);
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        GLES20.glDisableVertexAttribArray(aPositionLocation);
        GLES20.glDisableVertexAttribArray(aTexCoordLocation);
        
        // Swap to ImageReader (DMA transfer)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }
    
    /**
     * Synchronous downscale + readback. Draws the camera texture on the downscaler
     * thread, waits for completion, then reads the result.
     * 
     * SOTA: Previous async pattern (postFrame + sleep(5ms) + acquireLatestImage) was
     * unreliable — the 5ms sleep was often not enough for the render thread to complete,
     * resulting in stale frames. This synchronous approach ensures the readback always
     * gets the current frame.
     */
    public byte[] readPixels(int cameraTextureId, int width, int height) {
        if (!initialized || renderHandler == null) return null;
        
        // Draw synchronously on the downscaler thread and wait for completion
        final Object lock = new Object();
        final boolean[] done = {false};
        
        renderHandler.post(() -> {
            drawFrame(cameraTextureId);
            synchronized (lock) {
                done[0] = true;
                lock.notify();
            }
        });
        
        // Wait for draw to complete (max 50ms — if it takes longer, skip this frame)
        synchronized (lock) {
            if (!done[0]) {
                try {
                    lock.wait(50);
                } catch (InterruptedException ignored) {}
            }
        }
        
        if (!done[0]) {
            // Render thread didn't complete in time — skip this frame
            return null;
        }
        
        Image image = acquireLatestImage();
        if (image == null) return null;
        
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int bufferCapacity = buffer.capacity();
            
            // SOTA FIX: Reuse buffer instead of allocating new byte[] per frame
            int rgbSize = WIDTH * HEIGHT * 3;
            if (reusableRgbBuffer == null || reusableRgbBuffer.length != rgbSize) {
                reusableRgbBuffer = new byte[rgbSize];
                logger.info("Allocated reusable RGB buffer: " + rgbSize + " bytes");
            }
            
            // Validate buffer size before processing
            int expectedSize = (HEIGHT - 1) * rowStride + WIDTH * pixelStride;
            if (bufferCapacity < expectedSize) {
                logger.warn("Buffer too small: " + bufferCapacity + " < " + expectedSize + 
                    " (rowStride=" + rowStride + ", pixelStride=" + pixelStride + ")");
                // Return black frame instead of crashing
                java.util.Arrays.fill(reusableRgbBuffer, (byte) 0);
                return reusableRgbBuffer;
            }
            
            // RGBA -> RGB conversion into reusable buffer
            int srcOffset = 0;
            int dstOffset = 0;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int srcIdx = srcOffset + x * pixelStride;
                    // Safety check (should not trigger if validation above passed)
                    if (srcIdx + 2 >= bufferCapacity) {
                        break;
                    }
                    reusableRgbBuffer[dstOffset++] = buffer.get(srcIdx);     // R
                    reusableRgbBuffer[dstOffset++] = buffer.get(srcIdx + 1); // G
                    reusableRgbBuffer[dstOffset++] = buffer.get(srcIdx + 2); // B
                }
                srcOffset += rowStride;
            }
            return reusableRgbBuffer;
        } catch (Exception e) {
            logger.warn("Buffer read error: " + e.getClass().getSimpleName());
            // Return black frame on error
            if (reusableRgbBuffer != null) {
                java.util.Arrays.fill(reusableRgbBuffer, (byte) 0);
                return reusableRgbBuffer;
            }
            return null;
        } finally {
            image.close();
        }
    }
    
    // ========================================================================
    // SOTA: Direct GL-thread readback (bypasses broken async ImageReader path)
    // ========================================================================
    
    private int directFbo = -1;
    private int directTexture = -1;
    private int directProgram = -1;
    private int directAPosition = -1;
    private int directATexCoord = -1;
    private int directUCameraTex = -1;
    private int directUTexMatrix = -1;
    private int directUApaMode = -1;
    private int directUApplyManualYFlip = -1;
    private int directUProducerForFront = -1;
    private int directUProducerForRight = -1;
    private int directUProducerForRear = -1;
    private int directUProducerForLeft = -1;
    private int directUFlipForFront = -1;
    private int directUFlipForRight = -1;
    private int directUFlipForRear = -1;
    private int directUFlipForLeft = -1;
    private int directURedMaskStrength = -1;
    private int directUApaCenterInset = -1;
    private byte[] directRgbBuffer = null;
    private byte[] directScratchRgba = null;  // bulk-copy RGBA scratch for Y-flip pack
    private boolean directInitialized = false;

    // Tier 2 SOTA: PBO ring + fence-sync replaces the double-FBO ping-pong.
    // glReadPixels into a bound GL_PIXEL_PACK_BUFFER queues a DMA and returns
    // immediately; glClientWaitSync(timeout=0) is what tells us when the DMA
    // has landed without blocking. RING_SIZE=3 lets two readbacks be in
    // flight while a third PBO is being mapped on the CPU.
    private static final int DIRECT_PBO_RING_SIZE = 3;
    private static final int DIRECT_PBO_BYTES = WIDTH * HEIGHT * 4;
    private final int[] directPboIds = new int[DIRECT_PBO_RING_SIZE];
    private final long[] directFenceSyncs = new long[DIRECT_PBO_RING_SIZE];
    private int directRingHead = 0;
    private int directRingTail = 0;
    // True iff the current GL context exposes GLES 3.x — see FoveatedCropper
    // for rationale + fallback semantics.
    private boolean directGles3Available = false;
    private ByteBuffer directFallbackReadBuffer = null;

    // Single-buffered SYNCHRONOUS readback path. Used by the camera-mapping
    // dialog snapshot endpoint where surveillance may be off and the async
    // PBO-ring path would hand back null until enough frames had been
    // queued for a fence to signal. This path renders to a dedicated FBO
    // and reads back in the SAME call — glReadPixels is implicitly a sync
    // point, so the bytes are guaranteed valid on return. Independent
    // FBO/texture from the async path so the AI lane's PBO ring state
    // isn't disturbed.
    private int syncFbo = -1;
    private int syncTexture = -1;
    private ByteBuffer syncReadBuffer = null;
    private byte[] syncRgbBuffer = null;
    private byte[] syncScratchRgba = null;
    private boolean syncInitialized = false;
    
    /**
     * SOTA Tier 2: PBO ring + fence-sync.
     *
     * <p>Issue {@code glReadPixels} with a {@code GL_PIXEL_PACK_BUFFER} bound;
     * driver queues a DMA into the PBO and returns immediately. Drop a
     * {@code glFenceSync} right after. On a future call, drain the OLDEST
     * fence with a zero-timeout {@code glClientWaitSync}; if signaled, map
     * the PBO read-only and bulk-copy out. If not signaled, return null —
     * the AI lane falls back to mosaic for THIS tick. We never block this
     * GL thread on a glReadPixels stall; the readback latency lands as a
     * one-tick AI staleness, which is invisible at V2's 10 Hz.
     *
     * <p>This is the path the previous double-FBO ping-pong was trying to
     * be. Ping-pong reduced GPU-side stall but the host-side
     * {@code glReadPixels(buffer)} into a Java direct ByteBuffer is still
     * a synchronization point on Adreno when an OpenCL job is in the same
     * hardware queue. PBO + fence-sync gives the driver complete freedom
     * to pipeline the readback against unrelated GPU work.
     */
    public byte[] readPixelsDirect(int cameraTextureId) {
        if (!directInitialized) initDirectFbo();
        if (!directInitialized) return null;

        int[] savedViewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);

        // ---- 1. Render the current camera frame into the FBO ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, directFbo);
        GLES20.glViewport(0, 0, WIDTH, HEIGHT);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(directProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(directUCameraTex, 0);
        if (directUTexMatrix >= 0) {
            GLES20.glUniformMatrix4fv(directUTexMatrix, 1, false, currentTexMatrix, 0);
        }
        if (directUApaMode >= 0) {
            GLES20.glUniform1f(directUApaMode, (float) cameraLayout);
        }
        if (directUApplyManualYFlip >= 0) {
            GLES20.glUniform1f(directUApplyManualYFlip,
                cameraLayout == 3 ? 0.0f : 1.0f);
        }
        if (directUProducerForFront >= 0) {
            float[] m = new float[8];
            float[] f = new float[8];
            snapshotProducerCornersAndFlips(m, f);
            GLES20.glUniform2f(directUProducerForFront, m[0], m[1]);
            GLES20.glUniform2f(directUProducerForRight, m[2], m[3]);
            GLES20.glUniform2f(directUProducerForRear,  m[4], m[5]);
            GLES20.glUniform2f(directUProducerForLeft,  m[6], m[7]);
            if (directUFlipForFront >= 0) {
                GLES20.glUniform2f(directUFlipForFront, f[0], f[1]);
                GLES20.glUniform2f(directUFlipForRight, f[2], f[3]);
                GLES20.glUniform2f(directUFlipForRear,  f[4], f[5]);
                GLES20.glUniform2f(directUFlipForLeft,  f[6], f[7]);
            }
        }
        if (directURedMaskStrength >= 0) {
            GLES20.glUniform1f(directURedMaskStrength, redMaskEnabled ? 1.0f : 0.0f);
        }
        if (directUApaCenterInset >= 0) {
            GLES20.glUniform1f(directUApaCenterInset, apaCenterInset);
        }

        GLES20.glEnableVertexAttribArray(directAPosition);
        GLES20.glVertexAttribPointer(directAPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(directATexCoord);
        GLES20.glVertexAttribPointer(directATexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(directAPosition);
        GLES20.glDisableVertexAttribArray(directATexCoord);

        // GLES2 fallback: synchronous read into a direct buffer.
        if (!directGles3Available) {
            directFallbackReadBuffer.clear();
            GLES20.glReadPixels(0, 0, WIDTH, HEIGHT, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, directFallbackReadBuffer);
            directFallbackReadBuffer.rewind();
            directFallbackReadBuffer.get(directScratchRgba, 0, DIRECT_PBO_BYTES);
            byte[] src = directScratchRgba;
            byte[] dst = directRgbBuffer;
            final int rowRgbaBytes = WIDTH * 4;
            int dstIdx = 0;
            for (int y = HEIGHT - 1; y >= 0; y--) {
                int srcRow = y * rowRgbaBytes;
                for (int x = 0; x < WIDTH; x++) {
                    int s = srcRow + (x << 2);
                    dst[dstIdx++] = src[s];
                    dst[dstIdx++] = src[s + 1];
                    dst[dstIdx++] = src[s + 2];
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
            return dst;
        }

        // ---- 2. Queue an async DMA into the head PBO ----
        int nextHead = (directRingHead + 1) % DIRECT_PBO_RING_SIZE;
        boolean ringFull = (nextHead == directRingTail) && directFenceSyncs[directRingTail] != 0L;
        if (!ringFull) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, directPboIds[directRingHead]);
            GLES30.glReadPixels(0, 0, WIDTH, HEIGHT,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            directFenceSyncs[directRingHead] = GLES30.glFenceSync(
                    GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            directRingHead = nextHead;
        }

        // ---- 3. Drain: harvest the oldest signaled slot, if any ----
        byte[] result = null;
        if (directRingTail != directRingHead && directFenceSyncs[directRingTail] != 0L) {
            int sig = GLES30.glClientWaitSync(directFenceSyncs[directRingTail], 0, 0);
            boolean signaled = (sig == GLES30.GL_ALREADY_SIGNALED
                             || sig == GLES30.GL_CONDITION_SATISFIED);
            if (signaled) {
                if (directScratchRgba == null || directScratchRgba.length != DIRECT_PBO_BYTES) {
                    directScratchRgba = new byte[DIRECT_PBO_BYTES];
                }
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, directPboIds[directRingTail]);
                java.nio.Buffer mapped = GLES30.glMapBufferRange(
                        GLES30.GL_PIXEL_PACK_BUFFER, 0, DIRECT_PBO_BYTES,
                        GLES30.GL_MAP_READ_BIT);
                if (mapped instanceof ByteBuffer) {
                    ByteBuffer bb = (ByteBuffer) mapped;
                    bb.order(java.nio.ByteOrder.nativeOrder());
                    bb.rewind();
                    bb.get(directScratchRgba, 0, DIRECT_PBO_BYTES);
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);

                    byte[] src = directScratchRgba;
                    byte[] dst = directRgbBuffer;
                    final int rowRgbaBytes = WIDTH * 4;
                    int dstIdx = 0;
                    for (int y = HEIGHT - 1; y >= 0; y--) {
                        int srcRow = y * rowRgbaBytes;
                        for (int x = 0; x < WIDTH; x++) {
                            int s = srcRow + (x << 2);
                            dst[dstIdx++] = src[s];
                            dst[dstIdx++] = src[s + 1];
                            dst[dstIdx++] = src[s + 2];
                        }
                    }
                    result = dst;
                } else {
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
                    logger.warn("readPixelsDirect: glMapBufferRange returned non-ByteBuffer");
                }

                GLES30.glDeleteSync(directFenceSyncs[directRingTail]);
                directFenceSyncs[directRingTail] = 0L;
                directRingTail = (directRingTail + 1) % DIRECT_PBO_RING_SIZE;
            }
            // Not signaled — leave fence in place; next call will poll it.
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);

        return result;
    }
    
    private void initDirectFbo() {
        try {
            // Reuse the program if initSyncFbo already compiled it. Both
            // paths use the same vertex+fragment shader source, so a second
            // compile here would just leak the first program object.
            if (directProgram <= 0) {
                directProgram = GlUtil.createProgram(VERTEX_SHADER, fragmentShader);
                if (directProgram == 0) { logger.error("Direct FBO shader failed"); return; }
                directAPosition = GLES20.glGetAttribLocation(directProgram, "aPosition");
                directATexCoord = GLES20.glGetAttribLocation(directProgram, "aTexCoord");
                directUCameraTex = GLES20.glGetUniformLocation(directProgram, "uCameraTex");
                directUTexMatrix = GLES20.glGetUniformLocation(directProgram, "uTexMatrix");
                directUApaMode = GLES20.glGetUniformLocation(directProgram, "uApaMode");
                directUApplyManualYFlip = GLES20.glGetUniformLocation(directProgram, "uApplyManualYFlip");
                directUProducerForFront = GLES20.glGetUniformLocation(directProgram, "uProducerForFront");
                directUProducerForRight = GLES20.glGetUniformLocation(directProgram, "uProducerForRight");
                directUProducerForRear  = GLES20.glGetUniformLocation(directProgram, "uProducerForRear");
                directUProducerForLeft  = GLES20.glGetUniformLocation(directProgram, "uProducerForLeft");
                directUFlipForFront = GLES20.glGetUniformLocation(directProgram, "uFlipForFront");
                directUFlipForRight = GLES20.glGetUniformLocation(directProgram, "uFlipForRight");
                directUFlipForRear  = GLES20.glGetUniformLocation(directProgram, "uFlipForRear");
                directUFlipForLeft  = GLES20.glGetUniformLocation(directProgram, "uFlipForLeft");
                directURedMaskStrength = GLES20.glGetUniformLocation(directProgram, "uRedMaskStrength");
                directUApaCenterInset = GLES20.glGetUniformLocation(directProgram, "uApaCenterInset");
            }

            // Single render FBO.
            int[] texIds = new int[1];
            GLES20.glGenTextures(1, texIds, 0);
            directTexture = texIds[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, directTexture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, WIDTH, HEIGHT, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

            int[] fboIds = new int[1];
            GLES20.glGenFramebuffers(1, fboIds, 0);
            directFbo = fboIds[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, directFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, directTexture, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                logger.error("Direct FBO incomplete: " + status);
                return;
            }

            // GL version probe (one-shot). PBO + fence-sync require GLES 3.
            String glVer = GLES20.glGetString(GLES20.GL_VERSION);
            directGles3Available = (glVer != null && glVer.contains("OpenGL ES 3"));
            logger.info("Downscaler GL version: '" + glVer + "' (gles3=" + directGles3Available + ")");

            if (directGles3Available) {
                // PBO ring. STREAM_READ tells the driver these are
                // CPU-readback buffers and to place them in the right memory
                // pool (host-coherent on Adreno).
                GLES30.glGenBuffers(DIRECT_PBO_RING_SIZE, directPboIds, 0);
                for (int i = 0; i < DIRECT_PBO_RING_SIZE; i++) {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, directPboIds[i]);
                    GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, DIRECT_PBO_BYTES, null,
                            GLES30.GL_STREAM_READ);
                    directFenceSyncs[i] = 0L;
                }
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            } else {
                directFallbackReadBuffer = ByteBuffer.allocateDirect(DIRECT_PBO_BYTES);
                directFallbackReadBuffer.order(java.nio.ByteOrder.nativeOrder());
            }

            directRgbBuffer = new byte[WIDTH * HEIGHT * 3];
            directScratchRgba = new byte[DIRECT_PBO_BYTES];

            if (vertexBuffer == null) vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
            if (texCoordBuffer == null) texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);

            directRingHead = 0;
            directRingTail = 0;

            directInitialized = true;
            logger.info("Downscaler direct path initialized (GLES3 PBO ring x"
                    + DIRECT_PBO_RING_SIZE + ", " + WIDTH + "×" + HEIGHT + " RGBA)");
        } catch (Exception e) {
            logger.error("Failed to init direct FBO: " + e.getMessage());
        }
    }

    /**
     * Synchronous single-shot readback. Renders {@code cameraTextureId} into
     * a dedicated 640x480 FBO and reads it back in the same call. Always
     * returns valid bytes on success (no double-buffer warmup race). MUST be
     * called from the GL thread that owns {@code cameraTextureId}.
     *
     * <p>Used by the camera-mapping dialog snapshot endpoint where surveillance
     * may be off and the async {@link #readPixelsDirect(int)} would return
     * null on its first call. Independent of the async path's FBO state, so
     * calling this here doesn't disturb the AI lane's PBO ring cadence.
     *
     * <p>Cost: ~10-15 ms (glReadPixels stalls until the GPU finishes the
     * render). Acceptable for one-shot dialog use; do NOT call per-frame.
     *
     * <p>Lazy-inits its FBO + program on first call. Reuses the existing
     * {@code directProgram} if {@link #initDirectFbo()} ran first; otherwise
     * compiles a fresh program. The shared shader source is the same
     * fragment shader the async path uses.
     *
     * @return RGB byte[] of length WIDTH*HEIGHT*3, Y-flipped to image
     *         convention. Null on init failure.
     */
    public byte[] readPixelsSync(int cameraTextureId) {
        if (!syncInitialized) initSyncFbo();
        if (!syncInitialized) return null;

        int[] savedViewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);

        try {
            // Render the camera OES texture into the sync FBO.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, syncFbo);
            GLES20.glViewport(0, 0, WIDTH, HEIGHT);
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(directProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            GLES20.glUniform1i(directUCameraTex, 0);
            if (directUTexMatrix >= 0) {
                GLES20.glUniformMatrix4fv(directUTexMatrix, 1, false, currentTexMatrix, 0);
            }
            if (directUApaMode >= 0) {
                GLES20.glUniform1f(directUApaMode, (float) cameraLayout);
            }
            if (directUApplyManualYFlip >= 0) {
                GLES20.glUniform1f(directUApplyManualYFlip,
                    cameraLayout == 3 ? 0.0f : 1.0f);
            }
            if (directUProducerForFront >= 0) {
                float[] m = new float[8];
                float[] f = new float[8];
                snapshotProducerCornersAndFlips(m, f);
                GLES20.glUniform2f(directUProducerForFront, m[0], m[1]);
                GLES20.glUniform2f(directUProducerForRight, m[2], m[3]);
                GLES20.glUniform2f(directUProducerForRear,  m[4], m[5]);
                GLES20.glUniform2f(directUProducerForLeft,  m[6], m[7]);
                if (directUFlipForFront >= 0) {
                    GLES20.glUniform2f(directUFlipForFront, f[0], f[1]);
                    GLES20.glUniform2f(directUFlipForRight, f[2], f[3]);
                    GLES20.glUniform2f(directUFlipForRear,  f[4], f[5]);
                    GLES20.glUniform2f(directUFlipForLeft,  f[6], f[7]);
                }
            }
            if (directURedMaskStrength >= 0) {
                GLES20.glUniform1f(directURedMaskStrength, redMaskEnabled ? 1.0f : 0.0f);
            }
            if (directUApaCenterInset >= 0) {
                GLES20.glUniform1f(directUApaCenterInset, apaCenterInset);
            }

            GLES20.glEnableVertexAttribArray(directAPosition);
            GLES20.glVertexAttribPointer(directAPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(directATexCoord);
            GLES20.glVertexAttribPointer(directATexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(directAPosition);
            GLES20.glDisableVertexAttribArray(directATexCoord);

            // Read back from the FBO we just rendered to. glReadPixels is a
            // synchronization point — the GPU finishes the draw before this
            // returns, so the bytes are guaranteed valid.
            syncReadBuffer.clear();
            GLES20.glReadPixels(0, 0, WIDTH, HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, syncReadBuffer);

            syncReadBuffer.rewind();
            syncReadBuffer.get(syncScratchRgba, 0, WIDTH * HEIGHT * 4);

            byte[] src = syncScratchRgba;
            byte[] dst = syncRgbBuffer;
            final int rowRgbaBytes = WIDTH * 4;
            int dstIdx = 0;
            for (int y = HEIGHT - 1; y >= 0; y--) {
                int srcRow = y * rowRgbaBytes;
                for (int x = 0; x < WIDTH; x++) {
                    int s = srcRow + (x << 2);
                    dst[dstIdx++] = src[s];
                    dst[dstIdx++] = src[s + 1];
                    dst[dstIdx++] = src[s + 2];
                }
            }
            return dst;
        } catch (Throwable t) {
            logger.warn("readPixelsSync failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return null;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        }
    }

    private void initSyncFbo() {
        try {
            // Reuse the async path's compiled program if it exists. Otherwise
            // compile our own copy from the shared shader source. The async
            // and sync paths share the same vertex+fragment shader so this is
            // safe regardless of which lazy-inits first.
            if (directProgram <= 0) {
                directProgram = GlUtil.createProgram(VERTEX_SHADER, fragmentShader);
                if (directProgram == 0) {
                    logger.error("Sync FBO shader failed");
                    return;
                }
                directAPosition = GLES20.glGetAttribLocation(directProgram, "aPosition");
                directATexCoord = GLES20.glGetAttribLocation(directProgram, "aTexCoord");
                directUCameraTex = GLES20.glGetUniformLocation(directProgram, "uCameraTex");
                directUTexMatrix = GLES20.glGetUniformLocation(directProgram, "uTexMatrix");
                directUApaMode = GLES20.glGetUniformLocation(directProgram, "uApaMode");
                directUApplyManualYFlip = GLES20.glGetUniformLocation(directProgram, "uApplyManualYFlip");
                directUProducerForFront = GLES20.glGetUniformLocation(directProgram, "uProducerForFront");
                directUProducerForRight = GLES20.glGetUniformLocation(directProgram, "uProducerForRight");
                directUProducerForRear  = GLES20.glGetUniformLocation(directProgram, "uProducerForRear");
                directUProducerForLeft  = GLES20.glGetUniformLocation(directProgram, "uProducerForLeft");
                directUFlipForFront = GLES20.glGetUniformLocation(directProgram, "uFlipForFront");
                directUFlipForRight = GLES20.glGetUniformLocation(directProgram, "uFlipForRight");
                directUFlipForRear  = GLES20.glGetUniformLocation(directProgram, "uFlipForRear");
                directUFlipForLeft  = GLES20.glGetUniformLocation(directProgram, "uFlipForLeft");
                directURedMaskStrength = GLES20.glGetUniformLocation(directProgram, "uRedMaskStrength");
                directUApaCenterInset = GLES20.glGetUniformLocation(directProgram, "uApaCenterInset");
            }

            int[] texIds = new int[1];
            GLES20.glGenTextures(1, texIds, 0);
            syncTexture = texIds[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, syncTexture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, WIDTH, HEIGHT, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

            int[] fboIds = new int[1];
            GLES20.glGenFramebuffers(1, fboIds, 0);
            syncFbo = fboIds[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, syncFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, syncTexture, 0);

            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                logger.error("Sync FBO incomplete: " + status);
                return;
            }

            syncReadBuffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
            syncReadBuffer.order(java.nio.ByteOrder.nativeOrder());
            syncRgbBuffer = new byte[WIDTH * HEIGHT * 3];
            syncScratchRgba = new byte[WIDTH * HEIGHT * 4];

            if (vertexBuffer == null) vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
            if (texCoordBuffer == null) texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);

            syncInitialized = true;
            logger.info("Synchronous FBO readback initialized (640x480)");
        } catch (Exception e) {
            logger.error("Failed to init sync FBO: " + e.getMessage());
        }
    }

    // Utility methods
    public static ByteBuffer getDirectBuffer(Image image) {
        if (image == null) return null;
        return image.getPlanes()[0].getBuffer();
    }
    
    public static int getRowStride(Image image) {
        if (image == null) return 0;
        return image.getPlanes()[0].getRowStride();
    }
    
    public static int getPixelStride(Image image) {
        if (image == null) return 0;
        return image.getPlanes()[0].getPixelStride();
    }
    
    public int getWidth() { return WIDTH; }
    public int getHeight() { return HEIGHT; }
    public boolean isGrayscaleMode() { return false; }
    public int getBytesPerPixel() { return 4; }
    public void recycleBuffer(byte[] buffer) { }
    public String getPoolStats() { return "Async ImageReader (zero-stutter)"; }

    /**
     * Publishes the SurfaceTexture transform matrix that subsequent draws
     * (drawFrame / readPixelsDirect / readPixelsSync) will upload to the
     * shader's uTexMatrix. Plain copy — same GL thread as the readers.
     */
    public void setTextureMatrix(float[] matrix4x4) {
        if (matrix4x4 == null || matrix4x4.length < 16) return;
        System.arraycopy(matrix4x4, 0, currentTexMatrix, 0, 16);
    }

    /**
     * Selects between layouts:
     *   0 = legacy 4-strip → 2x2 rearrangement (Seal/Atto)
     *   3 = oem-parity passthrough (HAL emits final framing natively)
     * Other values fall through to layout 0 in the shader.
     */
    public void setCameraLayout(int layout) { this.cameraLayout = layout; }

    /**
     * Per-role producer corner XY map for DiLink 4. Each pair is the
     * top-left of the role's 0.5×0.5 sub-rect inside the producer surface,
     * in {Front, Right, Rear, Left} order. Default = canonical 2x2.
     * On DiLink 4 the pipeline pushes Variant A constants here so the
     * AI-lane downscaled mosaic is canonically arranged (Front=TL,
     * Right=TR, Rear=BL, Left=BR upright) — which is what V2 motion's
     * hardcoded quadrant indexing assumes.
     */
    public void setProducerCornerMap(float[] front, float[] right,
                                     float[] rear, float[] left) {
        if (front == null || right == null || rear == null || left == null
                || front.length < 2 || right.length < 2
                || rear.length  < 2 || left.length  < 2) {
            return;
        }
        synchronized (producerCornerMapLock) {
            producerCornerMap[0] = front[0]; producerCornerMap[1] = front[1];
            producerCornerMap[2] = right[0]; producerCornerMap[3] = right[1];
            producerCornerMap[4] = rear[0];  producerCornerMap[5] = rear[1];
            producerCornerMap[6] = left[0];  producerCornerMap[7] = left[1];
        }
    }

    /** Per-role X/Y flip flags ({xFlip, yFlip} ∈ {0,1}). {Front, Right, Rear, Left}. */
    public void setFlipFlags(float[] front, float[] right,
                             float[] rear, float[] left) {
        if (front == null || right == null || rear == null || left == null
                || front.length < 2 || right.length < 2
                || rear.length  < 2 || left.length  < 2) {
            return;
        }
        synchronized (producerCornerMapLock) {
            flipFlags[0] = front[0]; flipFlags[1] = front[1];
            flipFlags[2] = right[0]; flipFlags[3] = right[1];
            flipFlags[4] = rear[0];  flipFlags[5] = rear[1];
            flipFlags[6] = left[0];  flipFlags[7] = left[1];
        }
    }

    /**
     * Enables or disables the GL red-overlay suppression on the AI lane.
     * Mirrors GpuMosaicRecorder.setRedMaskEnabled. Off by default; pipeline
     * pulls dilink4RedMask from unified config and pushes it through.
     */
    /** APA center inset (oem APACropFilter parity). See {@link
     *  com.overdrive.app.surveillance.GpuMosaicRecorder#setApaCenterInset}. */
    public void setApaCenterInset(float inset) {
        this.apaCenterInset = Math.max(0.0f, Math.min(0.20f, inset));
    }

    public void setRedMaskEnabled(boolean enabled) {
        this.redMaskEnabled = enabled;
    }

    /** Snapshot the producer corner+flip arrays into caller-provided
     *  scratch buffers under lock. Caller uploads as uniforms after. */
    private void snapshotProducerCornersAndFlips(float[] m, float[] f) {
        synchronized (producerCornerMapLock) {
            System.arraycopy(producerCornerMap, 0, m, 0, 8);
            System.arraycopy(flipFlags, 0, f, 0, 8);
        }
    }
    
    // SOTA FIX: Reusable RGB buffer to eliminate 900KB allocation per frame
    private byte[] reusableRgbBuffer = null;
    
    /**
     * Release the direct-path GL resources (PBO ring, FBO, texture, shader
     * program shared with the sync path). MUST be called on whichever GL
     * thread/context originally allocated these — under Tier 1 wiring,
     * that is the AiLaneGl thread (because readPixelsDirect is invoked
     * from AiLaneGl.processOnce). The pipeline shutdown path calls this
     * via {@code AiLaneGl.runOnGlThreadBlocking(...)} BEFORE tearing down
     * the AI-lane EGL context.
     *
     * <p>Previously this cleanup was posted to {@code renderHandler} (the
     * legacy ImageReader-backed thread), which made the {@code glDelete*}
     * calls execute against the wrong context — they silently no-op
     * because the GL object names aren't visible to that context. Each
     * pipeline stop/start cycle then leaked one full set: 3 PBOs (3.6 MB
     * direct buffer storage backing them, plus driver handles), 3 sync
     * objects, 2 FBOs, 2 textures, 1 program. After many cycles the
     * driver's handle table saturated and inits started failing.
     *
     * <p>Idempotent: every guard checks for the sentinel value, so a
     * double-free is safe.
     */
    public void releaseDirectResources() {
        // Drop in-flight fences first; deleting their PBOs without
        // releasing the syncs leaks driver-side sync objects.
        for (int i = 0; i < DIRECT_PBO_RING_SIZE; i++) {
            if (directFenceSyncs[i] != 0L) {
                try { GLES30.glDeleteSync(directFenceSyncs[i]); } catch (Throwable ignored) {}
                directFenceSyncs[i] = 0L;
            }
        }
        if (directPboIds[0] != 0) {
            try { GLES30.glDeleteBuffers(DIRECT_PBO_RING_SIZE, directPboIds, 0); } catch (Throwable ignored) {}
            for (int i = 0; i < DIRECT_PBO_RING_SIZE; i++) directPboIds[i] = 0;
        }
        if (directFbo >= 0) {
            try { GLES20.glDeleteFramebuffers(1, new int[]{directFbo}, 0); } catch (Throwable ignored) {}
            directFbo = -1;
        }
        if (directTexture >= 0) {
            try { GLES20.glDeleteTextures(1, new int[]{directTexture}, 0); } catch (Throwable ignored) {}
            directTexture = -1;
        }
        if (syncFbo >= 0) {
            try { GLES20.glDeleteFramebuffers(1, new int[]{syncFbo}, 0); } catch (Throwable ignored) {}
            syncFbo = -1;
        }
        if (syncTexture >= 0) {
            try { GLES20.glDeleteTextures(1, new int[]{syncTexture}, 0); } catch (Throwable ignored) {}
            syncTexture = -1;
        }
        if (directProgram > 0) {
            try { GLES20.glDeleteProgram(directProgram); } catch (Throwable ignored) {}
            directProgram = -1;
        }
        // Drop CPU-side scratch + readback buffers. Deleting the GL handles
        // alone leaves ~5 MB of byte[] / direct ByteBuffers retained for the
        // daemon's lifetime; lazy paths re-init these on next call.
        directRgbBuffer = null;
        directScratchRgba = null;
        directFallbackReadBuffer = null;
        syncReadBuffer = null;
        syncRgbBuffer = null;
        syncScratchRgba = null;
        reusableRgbBuffer = null;
        directInitialized = false;
        syncInitialized = false;
    }

    /**
     * Release the legacy ImageReader-backed path resources owned by the
     * downscaler's own internal {@link #renderThread}/{@link #renderHandler}.
     * These belong to the downscaler's private EGL context, NOT the AI-lane
     * context, so they must be deleted from {@link #renderHandler}.
     *
     * <p>This is what stays on {@link #release}'s posted runnable; the
     * direct-path cleanup migrated out via
     * {@link #releaseDirectResources()}.
     */
    public void release() {
        initialized = false;

        if (renderHandler != null) {
            renderHandler.post(() -> {
                if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                if (programId != 0) {
                    GLES20.glDeleteProgram(programId);
                }
            });
        }
        
        if (renderThread != null) {
            renderThread.quitSafely();
            renderThread = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        
        logger.info("AsyncGpuDownscaler released");
    }

    private static float[] normalizeOffsets(float[] quadrantStripOffsetX) {
        if (quadrantStripOffsetX == null || quadrantStripOffsetX.length != 4) {
            return DEFAULT_QUADRANT_STRIP_OFFSET_X.clone();
        }
        return quadrantStripOffsetX.clone();
    }

    /**
     * Build the downscaler fragment shader with the four per-quadrant
     * strip-X offsets baked in. Order: {Front=TL, Right=TR, Rear=BL, Left=BR}.
     */
    private static String buildFragmentShader(float[] offsets) {
        // uApaMode > 2.5 = DiLink 4 / 2x2-native HAL. The producer surface
        // emits a non-canonical 2x2 (Variant A: Front X-mirrored at TL,
        // Rear at TR, Left at BL, Right at BR — no Y flip on any role; see
        // Dilink4Constants, which is the single source of truth. An earlier
        // version of this comment claimed Rear/Left were Y-flipped, which
        // was wrong and matched the constants that rendered front+right
        // upside down). We
        // rearrange to the canonical Front=TL / Right=TR / Rear=BL / Left=BR
        // upright layout — same math as GpuMosaicRecorder — so V2 motion's
        // hardcoded quadrant-index assumption (Q0=Front, Q1=Right, Q2=Rear,
        // Q3=Left at fixed grid positions) holds and the FoveatedCropper
        // sees a coherent canonical frame.
        // Legacy (uApaMode <= 0.5) → 4-strip → 2x2 rearrangement, unchanged.
        return String.format(Locale.US,
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uCameraTex;\n" +
            "uniform float uApaMode;\n" +
            "uniform vec2 uProducerForFront;\n" +
            "uniform vec2 uProducerForRight;\n" +
            "uniform vec2 uProducerForRear;\n" +
            "uniform vec2 uProducerForLeft;\n" +
            "uniform vec2 uFlipForFront;\n" +
            "uniform vec2 uFlipForRight;\n" +
            "uniform vec2 uFlipForRear;\n" +
            "uniform vec2 uFlipForLeft;\n" +
            "uniform float uRedMaskStrength;\n" +
            "uniform float uApaCenterInset;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec2 samplePos;\n" +
            "    if (uApaMode > 2.5) {\n" +
            "        bool inRight = (vTexCoord.x >= 0.5 && vTexCoord.y <  0.5);\n" +
            "        bool inRear  = (vTexCoord.x <  0.5 && vTexCoord.y >= 0.5);\n" +
            "        bool inLeft  = (vTexCoord.x >= 0.5 && vTexCoord.y >= 0.5);\n" +
            "        vec2 localOffset = vec2(0.0);\n" +
            "        if (inRight) localOffset = vec2(0.5, 0.0);\n" +
            "        else if (inRear) localOffset = vec2(0.0, 0.5);\n" +
            "        else if (inLeft) localOffset = vec2(0.5, 0.5);\n" +
            "        vec2 local = vTexCoord - localOffset;\n" +
            "        vec2 producerCorner = uProducerForFront;\n" +
            "        vec2 flip = uFlipForFront;\n" +
            "        if (inRight) { producerCorner = uProducerForRight; flip = uFlipForRight; }\n" +
            "        else if (inRear)  { producerCorner = uProducerForRear;  flip = uFlipForRear;  }\n" +
            "        else if (inLeft)  { producerCorner = uProducerForLeft;  flip = uFlipForLeft;  }\n" +
            "        vec2 sampledLocal = local;\n" +
            "        if (flip.x > 0.5) sampledLocal.x = 0.5 - sampledLocal.x;\n" +
            "        if (flip.y > 0.5) sampledLocal.y = 0.5 - sampledLocal.y;\n" +
            "        samplePos = producerCorner + sampledLocal;\n" +
            com.overdrive.app.camera.GlUtil.APA_CENTER_INSET_GLSL +
            "    } else {\n" +
            "        vec2 gridPos = step(0.5, vTexCoord);\n" +
            "        float frontOffset = %.5f;\n" +
            "        float rightOffset = %.5f;\n" +
            "        float rearOffset  = %.5f;\n" +
            "        float leftOffset  = %.5f;\n" +
            "        float stripOffsetX;\n" +
            "        if (gridPos.x < 0.5) {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? frontOffset : rearOffset;\n" +
            "        } else {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? rightOffset : leftOffset;\n" +
            "        }\n" +
            "        float localX = mod(vTexCoord.x, 0.5) * 0.5;\n" +
            "        float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +
            "        samplePos = vec2(localX + stripOffsetX, localY);\n" +
            "    }\n" +
            "    vec4 src = texture2D(uCameraTex, samplePos);\n" +
            com.overdrive.app.camera.GlUtil.RED_MASK_GLSL +
            "    gl_FragColor = src;\n" +
            "}\n",
            offsets[0], offsets[1], offsets[2], offsets[3]);
    }
}
