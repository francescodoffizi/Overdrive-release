package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.GpuPipelineConfig;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Streaming API Handler - manages WebSocket streaming configuration and control.
 * 
 * Endpoints:
 * - POST /api/stream/enable - Enable WebSocket streaming
 * - POST /api/stream/disable - Disable WebSocket streaming
 * - GET /api/stream/status - Get streaming status
 * - GET /api/stream/quality - Get available quality presets
 * - POST /api/stream/quality/{preset} - Set streaming quality
 * - POST /api/stream/view/{mode} - Set view mode (0=Mosaic, 1-4=AVM quadrant, 6=OEM Dashcam)
 * - GET /api/stream/view - Get current view mode
 */
public class StreamingApiHandler {

    private static String streamingQuality = "LOW";  // Default to LOW for better performance

    // Last view-mode the user explicitly picked, persisted across scaler
    // teardown / WS idle-shutdown / reconnect cycles. The scaler's own
    // currentViewMode field is cleared every time disableStreaming nulls
    // the scaler — pipeline.getStreamViewMode() returns -1 in that window.
    // Mobile browsers naturally hit this: backgrounding the tab >15s fires
    // the WS idle-shutdown → next WS open finds savedViewMode==-1 → fresh
    // scaler defaults to view 0, even though the user is still on DVR view.
    // This static survives the teardown so HttpServer's WS-open path can
    // re-apply the correct view (and re-route OEM for view 6).
    // -1 = never set; 0..6 valid mode values.
    //
    // Blind-spot views 7/8 are INTENTIONALLY excluded (clamp stays <=6): they are
    // session-only, owned by BlindSpotOverlayService which re-issues
    // /api/stream/view/{7|8} on every reveal AND that path re-applies the saved
    // calibration (handleStreamViewMode). Persisting 7/8 here would restore a
    // blind-spot view on a bare WS reconnect WITHOUT re-applying calibration —
    // worse than reverting to a normal view, which self-heals on the next signal.
    private static volatile int lastDesiredViewMode = -1;
    public static int getLastDesiredViewMode() { return lastDesiredViewMode; }
    public static void setLastDesiredViewMode(int mode) {
        if (mode >= 0 && mode <= 6) lastDesiredViewMode = mode;
    }

    // ── Blind-spot dedicated stream profile ─────────────────────────────────
    // NOTE: the blind-spot view (7/8) no longer rides the shared live-view
    // encoder — it has its own DEDICATED pipeline (GpuSurveillancePipeline.
    // enableBlindSpot, own encoder+scaler+WS on port BS_WS_PORT, driven by the
    // /api/bs/* endpoints). The shared stream's quality is just the user's pick.

    // Cold-start dedup. The first DVR / view-set click on a fresh daemon
    // takes ~4-9s to warm AVC HAL + open AVMCamera + EGL setup. Without
    // dedup, every retry click re-queues the same expensive work onto the
    // HTTP worker pool and floods CameraDaemon's lifecycle lock. The flag
    // is flipped true the moment we spawn the warm-up worker, cleared in
    // the worker's finally; intermediate clicks short-circuit to
    // {success:false, starting:true} so the JS poll loop just waits.
    private static final java.util.concurrent.atomic.AtomicBoolean panoStartInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Kick a pano cold-start asynchronously if the pipeline isn't running
     * yet and no warmup is currently in flight. Returns true iff the
     * pipeline is already warm; false signals "starting" and the caller
     * should respond with starting=true so the client polls.
     */
    private static boolean ensurePanoStartedNonBlocking(GpuSurveillancePipeline pano) {
        if (pano == null) return false;
        if (pano.isRunning()) return true;
        // Spawn a single warm-up worker. Re-entrant clicks see the flag
        // and short-circuit without enqueueing duplicate work.
        if (panoStartInFlight.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    com.overdrive.app.camera.AvcHalWarmup warmup =
                        new com.overdrive.app.camera.AvcHalWarmup();
                    warmup.warmupAndWait();
                    pano.start();
                    // This cold-start ran OUTSIDE RecordingModeManager (it's the
                    // blind-spot arm path), so the camera is now up at the
                    // pipeline's full default profile. Tell RMM to reconcile: if
                    // BS is the sole consumer (no recording mode active), it drops
                    // the H.265 recorder lane and parks global camera fps at the
                    // BS idle rate. No-op if a recording mode owns the camera.
                    try {
                        com.overdrive.app.recording.RecordingModeManager rmm =
                            CameraDaemon.getRecordingModeManager();
                        if (rmm != null) rmm.onPipelineStartedExternally();
                    } catch (Throwable t) {
                        CameraDaemon.log("ensurePanoStartedNonBlocking reconcile: " + t.getMessage());
                    }
                    // BS-DEFECT-A: do NOT self-arm the blind-spot lane here.
                    // This worker runs concurrently with the app's re-arm loop
                    // (BlindSpotControl.armWithRetry), which hammers /api/bs/enable →
                    // handleBsEnable → pano.enableBlindSpot() until the lane reports
                    // enabled. A self-arm enableBlindSpot() here sets bsEnabling=true
                    // and then RELEASES the lifecycle lock during enableBlindSpotInternal's
                    // GL-init wait; a concurrent handleBsEnable() acquiring the lock in
                    // that window hits enableBlindSpot's `if (bsEnabling) return;`
                    // early-return — a VOID return that handleBsEnable cannot distinguish
                    // from success (it only catches exceptions), so it reports
                    // {success:true} while blindSpotEnabled is still false. The app then
                    // sees a false-armed lane and stops driving / its WS reconnect-storms
                    // a dead 8889. Cold-start ONLY warms pano here; ALL arming routes
                    // through handleBsEnable so the app's convergent re-poll wins the race
                    // cleanly (single arming owner, no bsEnabling self-collision).
                } catch (Throwable t) {
                    CameraDaemon.log("ensurePanoStartedNonBlocking: " + t.getMessage());
                } finally {
                    panoStartInFlight.set(false);
                }
            }, "PanoColdStart").start();
        }
        return false;
    }
    
    /**
     * Handle streaming API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/stream/enable") && method.equals("POST")) {
            handleEnableStreaming(out);
            return true;
        }
        if (path.equals("/api/stream/disable") && method.equals("POST")) {
            handleDisableStreaming(out);
            return true;
        }
        if (path.equals("/api/stream/status") && method.equals("GET")) {
            sendStreamStatus(out);
            return true;
        }
        if (path.equals("/api/stream/quality") && method.equals("GET")) {
            sendStreamQualityOptions(out);
            return true;
        }
        if (path.startsWith("/api/stream/quality/") && method.equals("POST")) {
            String quality = path.substring(20).toUpperCase();
            handleSetStreamQuality(out, quality);
            return true;
        }
        if (path.startsWith("/api/stream/view/")) {
            int viewMode = Integer.parseInt(path.substring(17));
            handleStreamViewMode(out, viewMode);
            return true;
        }
        // Blind-spot (view 7/8) LIVE stitch tuning, used by the RoadSense Blind
        // Spot debug editor's slider preview. In-memory only (not persisted) —
        // the debug editor's Save writes the 'blindspot' UCM section separately.
        if (path.startsWith("/api/stream/bs/")) {
            handleBlindSpotParams(out, path.substring(15));
            return true;
        }
        if (path.equals("/api/stream/view") && method.equals("GET")) {
            sendStreamViewMode(out);
            return true;
        }
        if (path.equals("/api/stream/turn") && method.equals("GET")) {
            sendTurnState(out);
            return true;
        }
        return false;
    }

    /**
     * Dedicated blind-spot pipeline API (/api/bs/*). Completely separate from
     * the /api/stream/* live-view stream: drives GpuSurveillancePipeline's
     * second scaler+encoder+WS (port {@link GpuSurveillancePipeline#BS_WS_PORT}),
     * locked to views 7/8 at the fixed BS profile (1280×960@15). Never touches
     * lastDesiredViewMode / streamingQuality, so a live-view WS reconnect can
     * never hijack the blind-spot view, and vice-versa.
     */
    public static boolean handleBlindSpot(String method, String path, String body, OutputStream out) throws Exception {
        // Method-agnostic: all loopback, all idempotent or clearly-scoped. The
        // overlay drives enable/disable via POST but view-select via the GET-based
        // httpGetSucceeded helper, and /api/stream/view never gated on method
        // either — so accept whatever verb arrives rather than 404 a GET into a
        // non-JSON body the caller then fails to parse.
        if (path.equals("/api/bs/enable")) {
            handleBsEnable(out);
            return true;
        }
        if (path.equals("/api/bs/disable")) {
            handleBsDisable(out);
            return true;
        }
        if (path.equals("/api/bs/status")) {
            handleBsStatus(out);
            return true;
        }
        if (path.startsWith("/api/bs/view/")) {
            int mode;
            try { mode = Integer.parseInt(path.substring(13)); }
            catch (NumberFormatException e) { HttpResponse.sendJsonError(out, "invalid view"); return true; }
            handleBsView(out, mode);
            return true;
        }
        if (path.startsWith("/api/bs/geometry")) {
            handleBsGeometry(out, path);
            return true;
        }
        if (path.startsWith("/api/bs/target/")) {
            handleBsTarget(out, path.substring("/api/bs/target/".length()));
            return true;
        }
        // ── Camera-view (shares the BS lane; blind-spot priority) ──────────────
        if (path.startsWith("/api/camview/")) {
            return handleCamView(path, out);
        }
        return false;
    }

    /**
     * Camera-view routes. An on-demand camera view (front/rear/left/right/all-4)
     * on the SAME native SurfaceControl lane the blind-spot feature uses.
     *   POST /api/camview/show?cam=front&target=head_unit&preset=60/center&autoHide=0
     *     (cam ∈ all|front|right|rear|left OR a raw int 0-4; target ∈ head_unit|cluster;
     *      preset=sizePct/corner OR geometry=x/y/w/h; autoHide seconds, 0=until hidden)
     *   POST /api/camview/hide
     *   GET  /api/camview/status
     */
    private static boolean handleCamView(String path, OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        int q = path.indexOf('?');
        String clean = q >= 0 ? path.substring(0, q) : path;
        String query = q >= 0 ? path.substring(q + 1) : "";

        if (clean.equals("/api/camview/status")) {
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("active", pipeline != null && pipeline.isCamViewActive());
            if (pipeline != null) {
                r.put("mode", pipeline.getCamViewMode());
                r.put("target", pipeline.getCamViewTargetString());
            }
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return true;
        }
        if (clean.equals("/api/camview/hide")) {
            pipeline.disableCamView();
            // Persist enabled=false so a daemon restart doesn't re-show it.
            try { com.overdrive.app.config.UnifiedConfigManager.setCamViewValues(
                java.util.Collections.singletonMap("enabled", false)); } catch (Throwable ignored) {}
            JSONObject r = new JSONObject(); r.put("success", true); r.put("active", false);
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        if (clean.equals("/api/camview/show")) {
            // Cold-start pano if needed (same dedup as BS); caller re-polls.
            if (!ensurePanoStartedNonBlocking(pipeline)) {
                JSONObject pending = new JSONObject();
                pending.put("success", false); pending.put("starting", true);
                pending.put("error", "Pipeline starting — try again in a few seconds");
                pending.put("errorCode", "pano_starting");
                HttpResponse.sendJson(out, pending.toString());
                return true;
            }
            java.util.Map<String, String> p = parseQuery(query);
            int mode = parseCamMode(p.get("cam"));
            String target = "cluster".equals(p.get("target")) ? "cluster" : "head_unit";
            int autoHide = 0;
            try { if (p.containsKey("autoHide")) autoHide = Integer.parseInt(p.get("autoHide")); }
            catch (NumberFormatException ignored) {}
            // Persist geometry (preset or absolute) + selection BEFORE enabling so
            // resolveCamViewGeometry reads it. Per-target geometry key.
            persistCamViewGeometry(p, target);
            try {
                java.util.Map<String, Object> cvVals = new java.util.HashMap<>();
                cvVals.put("enabled", true);
                cvVals.put("mode", mode);
                cvVals.put("target", target);
                if (autoHide > 0) cvVals.put("autoHideSec", autoHide);
                com.overdrive.app.config.UnifiedConfigManager.setCamViewValues(cvVals);
            } catch (Throwable ignored) {}
            try {
                pipeline.enableCamView(mode, target, autoHide);
            } catch (GpuSurveillancePipeline.BlindSpotNotReadyException nr) {
                JSONObject pending = new JSONObject();
                pending.put("success", false); pending.put("starting", true);
                pending.put("error", "Camera-view lane arming — try again");
                pending.put("errorCode", "camview_starting");
                HttpResponse.sendJson(out, pending.toString());
                return true;
            }
            JSONObject r = new JSONObject();
            r.put("success", pipeline.isCamViewActive());
            r.put("active", pipeline.isCamViewActive());
            r.put("mode", mode); r.put("target", target);
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        HttpResponse.sendJsonError(out, "unknown camview endpoint");
        return true;
    }

    /** Map a cam token to a scaler view mode: all/mosaic=0, front=1, right=2,
     *  rear=3, left=4; a raw int 0-4 is accepted; default 0 (all-4). */
    private static int parseCamMode(String cam) {
        if (cam == null) return 0;
        String c = cam.trim().toLowerCase();
        switch (c) {
            case "all": case "mosaic": case "all4": case "0": return 0;
            case "front": case "1": return 1;
            case "right": case "2": return 2;
            case "rear": case "3": return 3;
            case "left": case "4": return 4;
            default:
                try { int v = Integer.parseInt(c); return (v >= 0 && v <= 4) ? v : 0; }
                catch (NumberFormatException e) { return 0; }
        }
    }

    /** Persist camview geometry from the query (preset=sizePct/corner OR
     *  geometry=x/y/w/h) into the per-target geometry key. No-op if neither given. */
    private static void persistCamViewGeometry(java.util.Map<String, String> p, String target) {
        try {
            String geomKey = "cluster".equals(target) ? "geometryCluster" : "geometry";
            JSONObject geo = null;
            if (p.containsKey("preset")) {
                String[] parts = p.get("preset").split("/");
                if (parts.length >= 1) {
                    geo = new JSONObject();
                    geo.put("sizePct", Integer.parseInt(parts[0].trim()));
                    if (parts.length >= 2) geo.put("corner", parts[1].trim());
                }
            } else if (p.containsKey("geometry")) {
                String[] parts = p.get("geometry").split("/");
                if (parts.length == 4) {
                    geo = new JSONObject();
                    geo.put("x", Integer.parseInt(parts[0].trim()));
                    geo.put("y", Integer.parseInt(parts[1].trim()));
                    geo.put("w", Integer.parseInt(parts[2].trim()));
                    geo.put("h", Integer.parseInt(parts[3].trim()));
                }
            }
            if (geo != null) {
                com.overdrive.app.config.UnifiedConfigManager.setCamViewValues(
                    java.util.Collections.singletonMap(geomKey, (Object) geo));
            }
        } catch (Throwable t) {
            CameraDaemon.log("persistCamViewGeometry: " + t.getMessage());
        }
    }

    /** Minimal query-string parser (k=v&k2=v2) → map. */
    private static java.util.Map<String, String> parseQuery(String query) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);
                m.put(k, v);
            }
        }
        return m;
    }

    /** POST /api/bs/target/{head_unit|cluster} — set the blind-spot display target.
     *  Persists to UCM blindspot.target and live-retargets the layer (restoring the
     *  gauges if flipping away from the cluster). The web UI also writes target via
     *  the unified-settings POST; this route is for native/instant retarget. */
    private static void handleBsTarget(OutputStream out, String raw) throws Exception {
        String t = raw;
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        if (!"head_unit".equals(t) && !"cluster".equals(t)) {
            HttpResponse.sendJsonError(out, "target must be head_unit or cluster");
            return;
        }
        com.overdrive.app.config.UnifiedConfigManager.setBlindSpotTarget(t);
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline != null && pipeline.isBlindSpotEnabled()) pipeline.retargetBlindSpot();
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("target", t);
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleBsEnable(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }
        // Cold-start the pano pipeline async (same dedup as the stream path);
        // the overlay re-polls /api/bs/enable until running.
        if (!ensurePanoStartedNonBlocking(pipeline)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("starting", true);
            pending.put("error", "Pipeline starting — try again in a few seconds");
            pending.put("errorCode", "pano_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        try {
            // NATIVE path: arming the lane creates the daemon SurfaceControl layer
            // + repoints the BS scaler onto it (GPU → screen). The daemon's own
            // turn-trigger loop shows/hides it; no app-process decoder/WS involved.
            pipeline.enableBlindSpot(pipeline.getBlindSpotViewMode());
            // BS-DEFECT-A: enableBlindSpot() can return VOID without arming the lane
            // when a concurrent enable is mid-flight (its `if (bsEnabling) return;`
            // early-return). A void return is indistinguishable from success to the
            // try/catch above, so reporting success here would falsely tell the app's
            // re-arm loop the lane is up — it stops driving while blindSpotEnabled is
            // still false and 8889 is dead (the observed NO-VIDEO flap). Re-check the
            // lane state and only claim success when it ACTUALLY armed; otherwise reply
            // {starting:true} so BlindSpotControl.armWithRetry keeps re-POSTing enable
            // until the in-flight enable commits the lane. Convergent: no false success.
            if (!pipeline.isBlindSpotEnabled()) {
                CameraDaemon.log("handleBsEnable: enable returned but lane not armed yet"
                    + " (concurrent enable in flight) — reporting starting so caller re-polls");
                JSONObject pending = new JSONObject();
                pending.put("success", false);
                pending.put("starting", true);
                pending.put("error", "Blind-spot lane arming — try again in a few seconds");
                pending.put("errorCode", "bs_starting");
                HttpResponse.sendJson(out, pending.toString());
                return;
            }
            // BS just armed. If the pano was ALREADY running (so the cold-start
            // path's onPipelineStartedExternally did NOT fire), the camera may
            // still be at a full recording/idle profile that doesn't reflect the
            // new BS-only consumer. Reconcile now so a BS-enable while the pipeline
            // is up (e.g. a live-view stream kept it warm, mode=NONE) drops the
            // recorder lane / parks fps at the BS profile. Idempotent + no-op when
            // a recording mode owns the camera. (Covers audit finding: runtime
            // BS-enable while pano already running skipped reconcile.)
            try {
                com.overdrive.app.recording.RecordingModeManager rmm =
                    CameraDaemon.getRecordingModeManager();
                if (rmm != null) rmm.onPipelineStartedExternally();
            } catch (Throwable t) {
                CameraDaemon.log("handleBsEnable reconcile: " + t.getMessage());
            }
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("native", true);
            response.put("view", pipeline.getBlindSpotViewMode());
            response.put("width", 1280);
            response.put("height", 960);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            CameraDaemon.log("handleBsEnable: error - " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    private static void handleBsDisable(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline != null) {
            try { pipeline.disableBlindSpot(); } catch (Throwable t) {
                CameraDaemon.log("handleBsDisable: " + t.getMessage());
            }
            // Reconcile the camera profile now. If BS was the SOLE consumer and
            // its view was already hidden, disableBlindSpot() fires no visibility
            // EDGE (bsLayerVisible was already false), so the camera would
            // otherwise be stranded at the BS-only profile (recorder lane OFF,
            // global fps ~1) with no owner — a live view opened afterward would
            // render at 1fps. Driving a reconcile here lands the camera in the
            // no-owner baseline (lane ON + recording/stream fps). Idempotent +
            // no-op when a recording mode owns the camera. (The blindspot.enabled
            // flag is already cleared by the app before this POST, so
            // bsKeepWarmActive() is false and reconcile won't re-park it as BS.)
            try {
                com.overdrive.app.recording.RecordingModeManager rmm =
                    CameraDaemon.getRecordingModeManager();
                if (rmm != null) rmm.onPipelineStartedExternally();
            } catch (Throwable t) {
                CameraDaemon.log("handleBsDisable reconcile: " + t.getMessage());
            }
        }
        JSONObject response = new JSONObject();
        response.put("success", true);
        HttpResponse.sendJson(out, response.toString());
    }

    // ── Blind-spot daemon-side self-arm ─────────────────────────────────────
    // The app arms the BS lane by POSTing /api/bs/enable (BlindSpotControl.sync,
    // fired from MainActivity / BootReceiver on the com.byd.action.ACC_ON
    // broadcast EDGE). On a HARD REBOOT the car's ACC is already ON, so that
    // broadcast edge fired before the app's receiver existed — the app never
    // gets it, never calls sync(), and the lane stays un-armed until the user
    // manually opens the debug-preview menu (the only other sync() trigger).
    // The daemon, however, learns ACC=ON from its own hardware probe + the
    // AccSentry IPC, so it CAN self-arm. This mirrors the OEM-dashcam
    // edge-only-lifecycle fix (pano-ready hook + 30s self-heal ticker): the
    // resolver is idempotent (no-op once the lane is live), so re-driving it is
    // safe. Gated on blindspot.enabled so a disabled feature never arms.
    private static final long BS_SELF_HEAL_INTERVAL_MS = 30_000L;
    private static volatile Thread bsSelfHealThread;
    private static volatile boolean bsSelfHealRunning;

    /**
     * Bring the BS lane to its desired state from the daemon side, independent
     * of any app-side POST. Arms iff blindspot.enabled is set AND ACC is on AND
     * the pano pipeline is running; idempotent (enableBlindSpot no-ops once the
     * lane is live). Safe to call from any thread — enableBlindSpot serializes
     * on its own lifecycle lock. Never disables: app-driven disable (feature
     * toggled off) flows through handleBsDisable, and gating the arm on the
     * enabled flag means we simply don't re-arm a feature the user turned off.
     */
    public static void resolveBlindSpotLifecycle() {
        try {
            org.json.JSONObject bs = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
            boolean enabled = bs.optBoolean("enabled", false)
                || bs.optBoolean("debugPreview", false);
            if (!enabled) return;
            if (!com.overdrive.app.monitor.AccMonitor.isAccOn()) return;
            GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline == null) return;
            if (pipeline.isBlindSpotEnabled()) return;   // already armed — no-op
            if (!pipeline.isRunning()) {
                // Pano not up yet. Kick a cold-start; the pano-ready hook in
                // GpuSurveillancePipeline.start() re-drives this resolver once
                // running=true, and the 30s ticker is the backstop.
                ensurePanoStartedNonBlocking(pipeline);
                return;
            }
            CameraDaemon.log("BS self-arm: enabled+accOn+pano-running but lane not armed — arming");
            pipeline.enableBlindSpot(pipeline.getBlindSpotViewMode());
        } catch (Throwable t) {
            CameraDaemon.log("BS self-arm: resolve failed: " + t.getMessage());
        }
    }

    /**
     * Start the periodic BS self-heal ticker. Idempotent — a second call while
     * the thread is alive is a no-op. Call once at daemon boot. Bounds the
     * cold-reboot arm latency to ~30s even if every edge is missed.
     */
    public static synchronized void startBsSelfHealTicker() {
        if (bsSelfHealThread != null && bsSelfHealThread.isAlive()) return;
        bsSelfHealRunning = true;
        bsSelfHealThread = new Thread(() -> {
            CameraDaemon.log("BS self-heal ticker started (" + (BS_SELF_HEAL_INTERVAL_MS / 1000) + "s interval)");
            while (bsSelfHealRunning) {
                try {
                    Thread.sleep(BS_SELF_HEAL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    if (!bsSelfHealRunning) { Thread.currentThread().interrupt(); return; }
                    continue;
                }
                if (!bsSelfHealRunning) return;
                resolveBlindSpotLifecycle();
            }
        }, "BlindSpotSelfHeal");
        bsSelfHealThread.setDaemon(true);
        bsSelfHealThread.start();
    }

    private static void handleBsStatus(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        JSONObject response = new JSONObject();
        response.put("success", true);
        boolean enabled = pipeline != null && pipeline.isBlindSpotEnabled();
        response.put("enabled", enabled);
        response.put("running", pipeline != null && pipeline.isRunning());
        response.put("view", pipeline != null ? pipeline.getBlindSpotViewMode() : 7);
        response.put("native", true);
        // Report the PERSISTED target, not the pipeline's in-memory bsTarget field
        // (which stays at its head_unit default until the lane is enabled and
        // resolveBsGeometry runs — so it would diverge from UCM in the pre-enable
        // window). bsTarget is just a cache of blindspot.target; UCM is authoritative.
        // forceReload honors cross-UID freshness (web/app write, daemon reads).
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        response.put("target", com.overdrive.app.config.UnifiedConfigManager.getBlindSpotTarget());
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET/POST /api/bs/geometry[/x/y/w/h] — set the on-screen rect (panel pixels)
     * of the native SurfaceControl blind-spot layer. SurfaceControl layers have no
     * input channel, so position/size are config-driven (RoadSense settings UI),
     * not finger-drag. Persists to UCM blindspot.geometry so it survives restart.
     */
    private static void handleBsGeometry(OutputStream out, String path) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        // Forms: /api/bs/geometry/{x}/{y}/{w}/{h}  (absolute px)
        //   or:  /api/bs/geometry/preset/{sizePct}/{corner}  (daemon does panel math)
        // Optional ?target=head_unit|cluster scopes which target's geometry is set;
        // defaults to the currently-active target.
        String target = com.overdrive.app.config.UnifiedConfigManager.getBlindSpotTarget();
        int q = path.indexOf("?target=");
        if (q >= 0) {
            String tq = path.substring(q + "?target=".length());
            int amp = tq.indexOf('&'); if (amp >= 0) tq = tq.substring(0, amp);
            if ("cluster".equals(tq) || "head_unit".equals(tq)) target = tq;
            path = path.substring(0, q);
        }
        boolean cluster = "cluster".equals(target);
        String geomKey = cluster ? "geometryCluster" : "geometry";
        String tail = path.length() > 16 ? path.substring(16) : "";
        if (tail.startsWith("/")) tail = tail.substring(1);
        if (tail.startsWith("preset/")) {
            try {
                String[] p = tail.substring("preset/".length()).split("/");
                int pct = Integer.parseInt(p[0]);
                String corner = p.length > 1 ? p[1] : "tr";
                if (pipeline != null) pipeline.setBsGeometryPreset(pct, corner, target);
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, "preset must be /preset/{pct}/{corner}: " + e.getMessage());
                return;
            }
        } else if (!tail.isEmpty()) {
            try {
                String[] p = tail.split("/");
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                int w = Integer.parseInt(p[2]);
                int h = Integer.parseInt(p[3]);
                // Persist to the target's geometry key so the daemon restores it on
                // the next enable. Shallow per-key merge — never clobbers the other
                // target's key.
                org.json.JSONObject g = new org.json.JSONObject();
                g.put("x", x); g.put("y", y); g.put("w", w); g.put("h", h);
                com.overdrive.app.config.UnifiedConfigManager.updateSection("blindspot",
                    new org.json.JSONObject().put(geomKey, g));
                // Apply live only when editing the active target.
                if (pipeline != null && cluster == com.overdrive.app.config.UnifiedConfigManager.isBlindSpotCluster()) {
                    pipeline.setBsGeometry(x, y, w, h);
                }
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, "geometry must be /x/y/w/h ints: " + e.getMessage());
                return;
            }
        }
        JSONObject response = new JSONObject();
        response.put("success", true);
        // Return the current (clamped) rect so a settings UI can reflect it.
        if (pipeline != null) {
            int[] r = pipeline.getBsGeometry();
            response.put("x", r[0]); response.put("y", r[1]);
            response.put("w", r[2]); response.put("h", r[3]);
        }
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleBsView(OutputStream out, int mode) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null || (mode != 7 && mode != 8)) {
            HttpResponse.sendJsonError(out, "blind-spot view must be 7 or 8");
            return;
        }
        // CRITICAL (DEFECT-B): never report success while the BS lane is still
        // disabled. Gate BEFORE setBlindSpotViewMode() — that setter is a no-op
        // until enableBlindSpot() has allocated bsScaler + bound the WS server on
        // BS_WS_PORT (it just snapshots a null bsScaler and returns). If the pano
        // is still cold-starting, the initial /api/bs/enable returned
        // starting:true and enableBlindSpot was never called, so the lane is
        // disabled and port 8889 is dead. Returning success here would let the
        // overlay commit streamWarmedView=mode and STOP re-driving the arming
        // loop, leaving WsH264Client reconnect-storming a dead port forever while
        // the lane stays un-armed (the observed NO-VIDEO flap). Mirror
        // handleBsEnable's cold-pano contract: reply {starting:true} so
        // confirmBsLaneAndConnect keeps re-POSTing /api/bs/enable and the overlay
        // keeps retrying selectView until the lane genuinely commits, then it
        // connects to a LIVE 8889.
        if (!pipeline.isBlindSpotEnabled()) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("starting", true);
            pending.put("view", mode);
            pending.put("error", "Blind-spot lane not yet armed — try again in a few seconds");
            pending.put("errorCode", "bs_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        pipeline.setBlindSpotViewMode(mode);
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("view", mode);
        HttpResponse.sendJson(out, response.toString());
    }

    // One-time warning latch so we log the degraded-latency fallback once per
    // daemon lifetime instead of spamming logcat at the overlay's 250ms tick.
    private static volatile boolean turnFallbackWarned = false;

    /**
     * Live turn-indicator state for the blind-spot overlay. The overlay runs in
     * the APP process, which has no BYD device handles (BydDataCollector.init()
     * is daemon-only), so it can't read the turn lamps directly — it polls this
     * loopback endpoint instead. We read from the daemon's own collector (which
     * owns lightDevice) via one inline readTurnNow() per request — the overlay's
     * own 250ms tick drives the cadence; there is NO background scheduler here.
     * Returns {left,right} as ints (>0 = lamp on); -1 when state is unknown.
     *
     * FALLBACK LATENCY: on trims whose light device is unavailable, readTurnNow()
     * returns -1 and we fall back to the ~5s main snapshot (BydVehicleData). That
     * value is stale by up to 5 seconds — orders of magnitude older than the
     * 250ms poll — so on those trims the blind-spot overlay's turn trigger may
     * miss or lag a newly-activated indicator. This is a hard HAL limitation on
     * that trim (no fast turn-lamp read path exists), not a bug here. We log the
     * degradation once (turnFallbackWarned) so it's diagnosable from logs.
     */
    private static void sendTurnState(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        int left = -1;
        int right = -1;
        try {
            com.overdrive.app.byd.BydDataCollector collector =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (collector.isInitialized()) {
                // One inline HAL read per request — no background scheduler. The
                // overlay's own 250ms tick drives the cadence; the daemon just
                // answers each read. Packed bit0=L, bit1=R; -1 if unavailable.
                int packed = collector.readTurnNow();
                if (packed >= 0) {
                    left = (packed & 0x1) != 0 ? 1 : 0;
                    right = (packed & 0x2) != 0 ? 1 : 0;
                } else {
                    // Light device unavailable on this trim — fall back to the
                    // ~5s main snapshot's last-known lamp state. This value can
                    // be up to ~5s stale (vs the overlay's 250ms poll), so the
                    // blind-spot turn trigger has degraded latency on this trim.
                    // Warn once so the degradation is diagnosable from logs
                    // without spamming at the 250ms tick.
                    if (!turnFallbackWarned) {
                        turnFallbackWarned = true;
                        CameraDaemon.log("sendTurnState: readTurnNow() unavailable on this"
                            + " trim — blind-spot turn trigger falling back to the ~5s"
                            + " snapshot (up to ~5s latency vs the normal 250ms poll)");
                    }
                    com.overdrive.app.byd.BydVehicleData d = collector.getData();
                    if (d != null) {
                        int un = com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
                        if (d.leftTurnState != un) left = d.leftTurnState;
                        if (d.rightTurnState != un) right = d.rightTurnState;
                    }
                }
            }
        } catch (Throwable t) {
            CameraDaemon.log("sendTurnState error: " + t.getMessage());
        }
        response.put("success", true);
        response.put("left", left);
        response.put("right", right);
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleEnableStreaming(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        CameraDaemon.log("handleEnableStreaming: pipeline=" + (pipeline != null) + 
                        ", running=" + (pipeline != null && pipeline.isRunning()));
        
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_initialized"));
            return;
        }
        
        // Auto-start pipeline if not running. Cold-start runs on a worker
        // thread (warmup + AVMCamera open is ~4-9s) and we return
        // starting=true so the HTTP worker thread isn't blocked. Client
        // re-polls until pipelineRunning flips true.
        if (!ensurePanoStartedNonBlocking(pipeline)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("starting", true);
            pending.put("error", "Pipeline starting — try again in a few seconds");
            pending.put("errorCode", "pano_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        
        if (pipeline.isStreamingEnabled()) {
            CameraDaemon.log("handleEnableStreaming: already enabled");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", Messages.get("messages.streaming_already_enabled"));
            response.put("wsPort", 8887);
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        
        try {
            GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
            CameraDaemon.log("handleEnableStreaming: quality=" + q.displayName);
            // enableStreaming fires the pipeline's streamStateListener →
            // RMM.reconcileCameraProfile, which floors the global camera fps at
            // the stream fps if the camera was parked at the BS-only idle rate.
            pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);

            CameraDaemon.log("handleEnableStreaming: success");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", Messages.get("messages.streaming_enabled"));
            response.put("wsPort", 8887);
            response.put("quality", q.name());
            response.put("resolution", q.width + "x" + q.height);
            response.put("fps", q.fps);
            response.put("bitrate", q.bitrate);
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("handleEnableStreaming: error - " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
    
    private static void handleDisableStreaming(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();

        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }

        // disableStreaming fires the pipeline's streamStateListener →
        // RMM.reconcileCameraProfile, so the global camera fps drops back from the
        // stream floor to the BS idle / recording rate once the stream is gone.
        // (This path covers BOTH the HTTP DELETE here and the WS idle auto-close
        // inside the pipeline — neither strands the fps at the stream rate.)
        pipeline.disableStreaming();

        // Once the WS pipe goes dark, the OEM-stream "keep warm" reason
        // disappears. Re-evaluate so we tear down OEM if no recording
        // mode is asking for it.
        com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.streaming_disabled"));
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamStatus(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        JSONObject response = new JSONObject();
        response.put("pipelineRunning", pipeline != null && pipeline.isRunning());
        response.put("streamingEnabled", pipeline != null && pipeline.isStreamingEnabled());
        response.put("wsPort", 8887);
        
        if (pipeline != null && pipeline.isStreamingEnabled()) {
            response.put("viewMode", pipeline.getStreamViewMode());
            String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam"};
            int vm = pipeline.getStreamViewMode();
            response.put("viewName", vm >= 0 && vm < modeNames.length ? modeNames[vm] : "Unknown");
        }
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamQualityOptions(OutputStream out) throws Exception {
        CameraDaemon.log("sendStreamQualityOptions: current=" + streamingQuality);
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("current", streamingQuality);
        
        JSONArray options = new JSONArray();
        for (GpuPipelineConfig.StreamingQuality q : GpuPipelineConfig.StreamingQuality.values()) {
            JSONObject opt = new JSONObject();
            opt.put("id", q.name());
            opt.put("name", q.displayName);
            opt.put("width", q.width);
            opt.put("height", q.height);
            opt.put("fps", q.fps);
            opt.put("bitrate", q.bitrate);
            opt.put("bitrateKbps", q.bitrate / 1000);
            options.put(opt);
        }
        response.put("options", options);
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleSetStreamQuality(OutputStream out, String quality) throws Exception {
        GpuPipelineConfig.StreamingQuality newQuality = GpuPipelineConfig.StreamingQuality.fromString(quality);

        streamingQuality = newQuality.name();
        CameraDaemon.setStreamingQuality(quality);

        // Persist to UnifiedConfigManager (streaming.quality) so the choice
        // survives daemon restart. Mirrors the recording-side flow where
        // QualitySettingsApiHandler.persistSettings is the single canonical
        // writer for both recording and streaming sections — without this
        // call the in-memory `streamingQuality` field is the only record of
        // the user's pick, and a kill/restart silently reverts to whatever
        // the on-disk default seeded (MEDIUM).
        QualitySettingsApiHandler.persistSettings();

        // Save quality preference — it will be applied on next stream start.
        // Don't restart the active stream to avoid disrupting the live view.
        // The /ws handler applies the quality when the client reconnects.
        CameraDaemon.log("Streaming quality set to: " + newQuality.displayName + " (persisted)");
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("quality", newQuality.name());
        response.put("displayName", newQuality.displayName);
        response.put("width", newQuality.width);
        response.put("height", newQuality.height);
        response.put("fps", newQuality.fps);
        response.put("bitrate", newQuality.bitrate);
        HttpResponse.sendJson(out, response.toString());
    }
    
    // Blind-spot (view 7/8) LIVE tuning (in-memory; debug editor). tail is up to
    // 10 opaque scalars: "{p0}/{p1}/{p2}/{p3}/{p4}/{p5}/{p6}/{p7}/{p8}/{p9}"
    // (trailing values optional; each defaults to its identity value).
    private static void handleBlindSpotParams(OutputStream out, String tail) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }
        float hfov = 1.66f, sideHFov = 1.98f, yaw = 1.23f, roll = 0.25f,
              feather = 0.38f, projExp = 1.0f, vscale = 1.0f, pitch = -0.275f,
              rearRoll = 0.0f, rearPitch = 0.0f;
        try {
            String[] p = tail.split("/");
            if (p.length > 0 && !p[0].isEmpty()) hfov      = Float.parseFloat(p[0]);
            if (p.length > 1 && !p[1].isEmpty()) sideHFov  = Float.parseFloat(p[1]);
            if (p.length > 2 && !p[2].isEmpty()) yaw       = Float.parseFloat(p[2]);
            if (p.length > 3 && !p[3].isEmpty()) roll      = Float.parseFloat(p[3]);
            if (p.length > 4 && !p[4].isEmpty()) feather   = Float.parseFloat(p[4]);
            if (p.length > 5 && !p[5].isEmpty()) projExp   = Float.parseFloat(p[5]);
            if (p.length > 6 && !p[6].isEmpty()) vscale    = Float.parseFloat(p[6]);
            if (p.length > 7 && !p[7].isEmpty()) pitch     = Float.parseFloat(p[7]);
            if (p.length > 8 && !p[8].isEmpty()) rearRoll  = Float.parseFloat(p[8]);
            if (p.length > 9 && !p[9].isEmpty()) rearPitch = Float.parseFloat(p[9]);
        } catch (NumberFormatException e) {
            HttpResponse.sendJsonError(out, "Bad blind-spot params: " + tail);
            return;
        }
        pipeline.setBlindSpotParams(hfov, sideHFov, yaw, roll, feather, projExp, vscale, pitch,
                                    rearRoll, rearPitch);
        JSONObject ok = new JSONObject();
        ok.put("success", true);
        ok.put("hfov", hfov);
        ok.put("sideHFov", sideHFov);
        ok.put("yaw", yaw);
        ok.put("roll", roll);
        ok.put("feather", feather);
        ok.put("projExp", projExp);
        ok.put("vscale", vscale);
        ok.put("pitch", pitch);
        ok.put("rearRoll", rearRoll);
        ok.put("rearPitch", rearPitch);
        HttpResponse.sendJson(out, ok.toString());
    }

    private static void handleStreamViewMode(OutputStream out, int viewMode) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        
        if (pipeline == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_pipeline_not_available"));
            return;
        }
        
        // Live-view stream accepts only modes 0-6. Blind-spot views 7/8 are
        // NOT valid here — they belong to the dedicated BS pipeline on port
        // 8889 and must route through /api/bs/view/{mode} (validated at
        // handleBsView). Letting 7/8 through here would call
        // pipeline.setStreamViewMode() on the SHARED live-view scaler and
        // hijack the live-view stream.
        if (viewMode < 0 || viewMode > 6) {
            HttpResponse.sendJsonError(out, Messages.get("errors.streaming_invalid_view_mode"));
            return;
        }

        // View mode 6 = OEM Dashcam (separate forward sensor pipeline).
        // View 5 stays the legacy raw passthrough (pano strip debug) so
        // existing tooling that pokes /api/stream/view/5 keeps working.
        // Routes the WebSocket stream to OemDashcamPipeline's encoder
        // bitstream instead of the AVM mosaic. The OEM pipeline must be
        // started separately by RecordingModeManager / Settings; we do NOT
        // auto-start it here because (a) it requires a configured
        // oemDashcamCameraId, and (b) on single-AVM-client HALs starting
        // it would yield the pano pipeline.
        if (viewMode == 6) {
            handleOemDashcamView(out);
            return;
        }

        // Idempotency short-circuit: if pipeline is already running,
        // streaming is already enabled, and view is already at the
        // requested mode, return success without re-running any
        // side-effecting work. Repeated identical GETs from the JS
        // poll loop should be cheap.
        if (pipeline.isRunning() && pipeline.isStreamingEnabled()
                && pipeline.getStreamViewMode() == viewMode) {
            // Defensive: refresh the static so a daemon-restart-then-idempotent-hit
            // can't leave lastDesiredViewMode out of sync with the live scaler.
            setLastDesiredViewMode(viewMode);
            String[] modeNamesIdem = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam",
                                      "BlindSpot L", "BlindSpot R"};
            JSONObject ok = new JSONObject();
            ok.put("success", true);
            ok.put("viewMode", viewMode);
            ok.put("viewName", viewMode < modeNamesIdem.length ? modeNamesIdem[viewMode] : "Unknown");
            HttpResponse.sendJson(out, ok.toString());
            return;
        }

        // Auto-start pipeline if not running. Cold-start is async — the
        // 4-9s warmup runs on a dedup'd worker thread and we report
        // starting=true so the JS poll loop just waits.
        if (!ensurePanoStartedNonBlocking(pipeline)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", viewMode);
            pending.put("starting", true);
            pending.put("error", "Pipeline starting — try again in a few seconds");
            pending.put("errorCode", "pano_starting");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }

        // Enable streaming first if not enabled. enableStreaming is
        // synchronous (allocates encoder + scaler on the GL thread) and
        // typically returns under 200ms, so we keep it inline.
        if (!pipeline.isStreamingEnabled()) {
            try {
                CameraDaemon.log("Enabling streaming before setting view mode");
                GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
                pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, Messages.get("errors.streaming_enable_failed_with_detail", e.getMessage()));
                return;
            }
        }

        // Capture the prior view BEFORE we change it so the OEM lifecycle
        // recalc only fires on transitions in/out of view 6. Pre-fix every
        // AVM quadrant click triggered a recalc, which on smart-mode arms
        // would warm the OEM camera unnecessarily for no consumer.
        int prevView = pipeline.getStreamViewMode();

        pipeline.setStreamViewMode(viewMode);
        // Persist across scaler teardown so a future WS reconnect after
        // idle-shutdown can re-apply the user's pick. See lastDesiredViewMode
        // doc above.
        setLastDesiredViewMode(viewMode);

        // Blind-spot views (7=Rear+Left, 8=Right+Rear): apply the user's SAVED
        // panorama calibration from the 'blindspot' UCM section so the stitch
        // looks right without the debug editor open. forceReload first — the web
        // (different UID) just wrote it. The live /api/stream/bs path still
        // overrides this in-memory for the debug editor's slider preview.
        if (viewMode == 7 || viewMode == 8) {
            try {
                com.overdrive.app.config.UnifiedConfigManager.forceReload();
                org.json.JSONObject bs =
                    com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
                if (bs != null && bs.length() > 0) {
                    pipeline.setBlindSpotParams(
                        (float) bs.optDouble("rearFov", 1.66),
                        (float) bs.optDouble("sideFov", 1.98),
                        (float) bs.optDouble("yaw",     1.23),
                        (float) bs.optDouble("roll",    0.25),
                        (float) bs.optDouble("feather", 0.38),
                        (float) bs.optDouble("projExp", 1.0), 1.0f,
                        (float) bs.optDouble("pitch",  -0.275),
                        (float) bs.optDouble("rearRoll",  0.0),
                        (float) bs.optDouble("rearPitch", 0.0));
                    // Merge mode (both/side/rear) — apply alongside the stitch calib so
                    // a browser switching the live stream to 7/8 matches the persisted
                    // selection (and the on-car overlay), not the scaler's "both" default.
                    String mm = bs.optString("mergeMode", "both");
                    pipeline.setBlindSpotMergeMode(
                        "side".equals(mm) ? 1 : ("rear".equals(mm) ? 2 : 0));
                }
            } catch (Throwable t) {
                CameraDaemon.log("blindspot calib apply failed: " + t.getMessage());
            }
        }

        // If a prior view-6 selection swapped the WS sink to the OEM encoder,
        // restore pano now so view 0..5 actually delivers pano frames again.
        // Direct call — reflection here would have the same R8-rename failure
        // mode as the original routeStreamToOemDashcam bug (the surveillance
        // package members aren't preserved by name, so a getMethod() lookup
        // throws NoSuchMethodException in release builds).
        try {
            pipeline.reattachOwnStreamCallback();
        } catch (Throwable t) {
            CameraDaemon.log("reattachOwnStreamCallback failed: " + t.getMessage());
        }

        // Re-evaluate the OEM lifecycle ONLY on view-6 boundary crossings.
        // Switching from view 0 → view 1 doesn't change OEM's required
        // state (no streaming viewer either way) and used to spuriously
        // boot the pipeline when smart mode was armed.
        if (prevView == 6 || viewMode == 6) {
            com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
        }

        String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam",
                              "BlindSpot L", "BlindSpot R"};
        CameraDaemon.log("Stream view mode set to: " + (viewMode < modeNames.length ? modeNames[viewMode] : "Unknown"));
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("viewMode", viewMode);
        response.put("viewName", viewMode < modeNames.length ? modeNames[viewMode] : "Unknown");
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStreamViewMode(OutputStream out) throws Exception {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();

        int viewMode = (pipeline != null) ? pipeline.getStreamViewMode() : -1;
        String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw", "OEM Dashcam"};

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("viewMode", viewMode);
        response.put("viewName", viewMode >= 0 && viewMode < modeNames.length ? modeNames[viewMode] : "Unknown");
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Handle a stream view mode = 6 request: route the WebSocket stream to
     * the OEM Dashcam pipeline's encoder bitstream. Returns starting=true
     * while the pano + OEM pipelines come up so the JS poll loop just
     * waits — no blocking on the HTTP worker thread. View 5 stays the
     * legacy raw debug passthrough.
     */
    private static void handleOemDashcamView(OutputStream out) throws Exception {
        GpuSurveillancePipeline pano = CameraDaemon.getGpuPipeline();
        // Async-warm pano if needed; return starting=true while it's coming up.
        if (!ensurePanoStartedNonBlocking(pano)) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", 6);
            pending.put("starting", true);
            pending.put("errorCode", "pano_starting");
            pending.put("error", "Pipeline starting — try again in a few seconds");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        // Pano is up but streaming isn't enabled yet. enableStreaming is
        // synchronous + cheap (~100-200ms), so inline is fine.
        if (pano != null && !pano.isStreamingEnabled()) {
            try {
                GpuPipelineConfig.StreamingQuality q =
                    GpuPipelineConfig.StreamingQuality.fromString(streamingQuality);
                pano.enableStreaming(q.width, q.height, q.fps, q.bitrate);
            } catch (Exception e) {
                CameraDaemon.log("handleOemDashcamView: pano.enableStreaming failed: " + e.getMessage());
            }
        }
        // Defensive — if streaming still didn't come up (enableStreaming
        // threw, or a concurrent disable just nulled the scaler), the route
        // call below will fail opaquely. Surface it as starting=true so the
        // client retries with the next poll instead of seeing an
        // unrecoverable "stream routing not yet available".
        if (pano == null || !pano.isStreamingEnabled()) {
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", 6);
            pending.put("starting", true);
            pending.put("errorCode", "stream_starting");
            pending.put("error", "Streaming starting — try again in a few seconds");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }

        com.overdrive.app.camera.OemDashcamPipeline oem = CameraDaemon.getOemDashcamPipeline();
        // Gate on isRouteReady (camera texture + SurfaceTexture allocated)
        // not just isRunning. The pipeline's running flag flips true at the
        // top of start() before initEglAndEncoder allocates the EXTERNAL_OES
        // texture; a view-6 click arriving in that window would otherwise
        // skip the oem_starting path and fall straight into a
        // routeStreamToOemDashcam() call that finds cameraTextureId=0 and
        // surfaces "OEM Dashcam stream routing not yet available" — which
        // the user can't recover from without retrying.
        if (oem == null || !oem.isRouteReady()) {
            int resolved = com.overdrive.app.config.UnifiedConfigManager.resolveOemDashcamId();
            if (resolved < 0) {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("viewMode", 6);
                err.put("errorCode", "oem_disabled");
                err.put("error", "OEM Dashcam disabled on this vehicle");
                HttpResponse.sendJson(out, err.toString());
                return;
            }
            // NOTE: the "warm but on an independent GL context while recording" case is
            // no longer surfaced as a terminal error. startPipeline() now ACTIVELY brings
            // pano up and waits for its EGLCore before creating OEM's context, so OEM
            // starts in pano's share group in the first place and DVR just works. If it
            // still lands unshared (pano genuinely broken), the ordinary "starting" path
            // below keeps polling and the self-heal restart repairs it as soon as no
            // recording is open — a transient wait, not a dead end the user must decode.
            // resolveOemDashcamId honours the XOR-of-pano default, so on
            // every install (even fresh, no manual override) we get back
            // a candidate id and ATTEMPT a start. Only after the start
            // throws (e.g. validateHalDimsOrReject on hardware with no
            // separate forward sensor) does UCM hold a `lastStartError`.
            // If we see one — and we haven't successfully started this
            // pipeline since — surface it as a real terminal failure so
            // the JS poll loop stops retrying. Without this short-circuit
            // the user sits on an "OEM Dashcam starting…" toast for ~30s
            // until the poll geometric backoff exhausts, and even then
            // gets the original opaque message.
            try {
                org.json.JSONObject oemCfg = com.overdrive.app.config.UnifiedConfigManager.getOemDashcam();
                if (oemCfg.has("lastStartError") && !oemCfg.isNull("lastStartError")) {
                    String reason = oemCfg.optString("lastStartError", "");
                    long lastAt = oemCfg.optLong("lastStartErrorAt", 0L);
                    long ageMs = System.currentTimeMillis() - lastAt;
                    // Honor the sticky error only while it's recent (60 s) —
                    // beyond that, treat it as stale (transient HAL warmup
                    // failures shouldn't lock out streaming forever; the
                    // user's only recovery is currently an APK reinstall).
                    // Past the TTL, fall through to the normal lifecycle
                    // recalc path so a fresh start can clear it via
                    // startPipeline's lastStartError reset.
                    if (!reason.isEmpty() && lastAt > 0 && ageMs < 60_000L) {
                        JSONObject err = new JSONObject();
                        err.put("success", false);
                        err.put("viewMode", 6);
                        err.put("errorCode", "oem_unsupported");
                        err.put("error", "OEM Dashcam unavailable: " + reason);
                        HttpResponse.sendJson(out, err.toString());
                        return;
                    }
                }
            } catch (Throwable ignored) {}
            // Streaming-only kick — we never flip recordingMode in UCM.
            // applyTriggerLifecycleFromUcm sees isAnyStreamingViewerActive()
            // (view 6 intent persisted just below) and brings the camera + EGL up
            // without flipping recording on. Only schedule a recalc when
            // the pipeline isn't already started — re-kicking an in-flight
            // start() is wasted lifecycle churn.
            //
            // DO NOT set the scaler to view 6 here. The shader's OEM branch is gated
            // on `uViewMode == 6 && uOemActive == 1`; uOemActive only goes 1 after
            // bindOemSource + the first matrix publish, which cannot have happened yet
            // on this not-ready path. With uViewMode=6 and uOemActive=0 the gate FALLS
            // THROUGH into the uApaMode branches and renders the 4-cam AVM mosaic —
            // the "DVR shows the 4-cam strip" bug. Leaving the view mode alone keeps
            // the previous (correct) feed on screen while OEM warms; view 6 is applied
            // only on the success path below, once the bind actually landed.
            // isAnyStreamingViewerActive() reads the PERSISTED intent
            // (setLastDesiredViewMode(6) below), not the scaler, so the lifecycle kick
            // still sees the view-6 request.
            // Persist intent now (not only on the success branch). A WS
            // reconnect during the OEM warmup window would otherwise read
            // lastDesiredViewMode=-1, fall back to scaler-state, and miss
            // the user's pick if a teardown lands in that gap.
            setLastDesiredViewMode(6);
            if (oem == null || !oem.isRunning()) {
                com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
            }
            JSONObject pending = new JSONObject();
            pending.put("success", false);
            pending.put("viewMode", 6);
            pending.put("starting", true);
            pending.put("errorCode", "oem_starting");
            pending.put("error", "OEM Dashcam starting — try again in a few seconds");
            HttpResponse.sendJson(out, pending.toString());
            return;
        }
        // Route the existing WebSocket stream sink to the OEM encoder. The
        // pano pipeline keeps running but its stream callback is detached
        // for the duration; switching back to view 0..4 reattaches. The
        // routing returns false when the GPU pipeline hasn't yet exposed
        // the attachExternalStreamCallback hook (Phase-9 plumbing) — in
        // that case we MUST tell the client the switch did not actually
        // take effect, otherwise the UI flips to "OEM Dashcam" while the
        // WS continues to deliver AVM mosaic frames.
        boolean routed = CameraDaemon.routeStreamToOemDashcam();
        JSONObject response = new JSONObject();
        if (!routed) {
            // Re-kick the lifecycle so a missed-edge race between
            // isRouteReady() flipping true and attachExternalStreamCallback's
            // own gates (streamingEnabled / streamScaler / oemTextureId)
            // gets retried on the next poll instead of stranding the user
            // on a permanent toast.
            try {
                if (oem == null || !oem.isRunning()) {
                    com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
                }
            } catch (Throwable ignored) {}
            response.put("success", false);
            response.put("viewMode", 6);
            response.put("starting", true);
            response.put("errorCode", "oem_starting");
            response.put("error", "OEM Dashcam starting — try again in a few seconds");
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        // Tell the scaler to switch its sample shader branch to the OEM
        // path (view 6). Without this the scaler keeps rendering the AVM
        // mosaic even though the OEM texture has been bound.
        try {
            pano.setStreamViewMode(6);
        } catch (Throwable t) {
            CameraDaemon.log("setStreamViewMode(6) failed: " + t.getMessage());
        }
        // Persist user's pick across scaler teardown so a WS reconnect
        // after idle-shutdown re-applies view 6 + OEM re-route.
        setLastDesiredViewMode(6);
        response.put("success", true);
        response.put("viewMode", 6);
        response.put("viewName", "OEM Dashcam");
        HttpResponse.sendJson(out, response.toString());
    }
    
    // Static getters/setters for cross-component access
    public static String getStreamingQuality() { return streamingQuality; }
    
    public static void setStreamingQuality(String quality) {
        if (quality == null) return;
        String q = quality.toUpperCase();
        // Mirror the StreamingQuality enum (GpuPipelineConfig). SMOOTH and MAX
        // are recent additions; the cold-start loader (QualitySettingsApiHandler.
        // loadPersistedSettings) calls this with whatever tag is on disk, so if
        // we silently reject MAX here the persisted user pick decays to the
        // hard-coded default after every daemon restart.
        switch (q) {
            case "ULTRA_LOW":
            case "LOW":
            case "MEDIUM":
            case "HIGH":
            case "ULTRA_HIGH":
            case "SMOOTH":
            case "MAX":
            case "LQ":
            case "HQ":
                streamingQuality = q;
                break;
            default:
                // Unknown tag — leave previous value intact.
                break;
        }
    }
}
