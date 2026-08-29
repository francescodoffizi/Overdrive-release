package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * Receives cabin audio from the app process, which owns AudioRecord because
 * the daemon cannot. Recording sessions send AAC for muxing; an authenticated
 * live listener receives bounded PCM fan-out from the same capture.
 * The daemon
 * (UID 2000 / shell) cannot open AudioRecord directly because BYD's
 * AudioPolicy denies non-app UIDs — see /api/audio/probe-mic-spoof
 * verification — so capture lives app-side and the encoded bitstream is
 * piped here for muxing into the same .mp4 the video pipeline writes.
 *
 * <h3>Wire format</h3>
 * Each message is length-prefixed:
 * <pre>
 *   [4 bytes BE]  totalLength (excludes the 4 length bytes themselves)
 *   [4 bytes BE]  msgType
 *   [N bytes]     payload
 * </pre>
 *
 * <h4>msgType=1 (CONFIG)</h4>
 * Sent once at connection start. Payload:
 * <pre>
 *   [4 bytes BE]  sampleRate (Hz)
 *   [4 bytes BE]  channelCount
 *   [4 bytes BE]  bitrate (bps)
 *   [4 bytes BE]  csd0Length
 *   [csd0Length bytes]  AudioSpecificConfig (CSD-0)
 * </pre>
 *
 * <h4>msgType=2 (DATA)</h4>
 * One AAC access unit (raw, NO ADTS header — MediaMuxer wants raw AU).
 * Payload:
 * <pre>
 *   [8 bytes BE]  ptsUs (microseconds, monotonic with video)
 *   [N bytes]     AAC AU bytes (no ADTS)
 * </pre>
 *
 * <h4>msgType=3 (PCM)</h4>
 * Mono 48 kHz PCM_16LE for the active remote listener.
 *
 * <h4>msgType=4 (LISTENER_ONLY)</h4>
 * Empty marker sent by a listener-only capture. It clears stale recording
 * audio config from a client this connection replaced.
 *
 * <h3>Lifecycle</h3>
 * Single-client. A new connection terminates the previous one. The accept
 * loop runs on the thread spawned by CameraDaemon; the per-client read
 * loop runs on a short-lived worker thread so accept() can keep servicing
 * reconnects even while the previous worker is still draining its finally
 * block. The app disconnects (graceful close) when capture stops; we then
 * call {@link HardwareEventRecorderGpu#disableAudioMuxing} so any
 * in-flight recording closes out video-only — but ONLY if the closing
 * worker is still the active client (so a stale-closer can't undo a
 * fresh client's CONFIG). A surprise disconnect (process killed) hits
 * the same path via {@link IOException} on the next read.
 *
 * <h3>Why not extend SurveillanceIpcServer?</h3>
 * That server is JSON-line-based — fine for low-rate control messages,
 * but framing AAC packets as base64-in-JSON would burn ~33% bandwidth
 * for nothing. A separate length-prefixed binary socket keeps audio
 * cheap and keeps the JSON IPC simple.
 */
public class AacIngestServer implements Runnable {

    private static final String TAG = "AacIngest";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final int PORT = 19878;

    private static final int MSG_CONFIG = 1;
    private static final int MSG_DATA = 2;
    private static final int MSG_PCM = 3;
    private static final int MSG_LISTENER_ONLY = 4;
    private static final int MAX_PCM_FRAME_BYTES = 8 * 1024;

    // Hard cap so a runaway / corrupt client can't allocate 2 GB.
    // 64 KB is far above any reasonable AAC frame at 64 kbps mono.
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    // How long we wait for a previous worker thread's finally block to
    // run before accepting a new client. Bounded so a wedged worker
    // can't block reconnect indefinitely.
    private static final long EVICT_JOIN_MS = 2000;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private volatile Socket activeClient;
    private volatile Thread activeWorker;

    // Cached CONFIG so a CONFIG that arrived before the encoder existed
    // can be replayed once the encoder comes up. Also replayed when the
    // encoder identity changes (recording-mode switch tears down + spins
    // a new instance) or when the encoder reports hasAudioConfig=false.
    // Reset on stop() and on connection close.
    private volatile byte[] cachedCsd0;
    private volatile int cachedSampleRate;
    private volatile int cachedChannelCount;
    private volatile int cachedBitrate;
    private volatile boolean configCachedButNotApplied;
    // Identity tracker: the encoder instance we last pushed setAudioConfig
    // into. When this drifts from currentEncoder() (mode switch, pipeline
    // reinit), we re-apply the cached CONFIG so the new instance has its
    // muxer audio track wired up before its first triggerEventRecording.
    // Held by reference because HardwareEventRecorderGpu instances are
    // garbage-collected when the pipeline tears down — comparing references
    // detects "different live instance" without needing an explicit ID.
    private volatile HardwareEventRecorderGpu lastConfiguredEncoder;

    @Override
    public void run() {
        running = true;
        try {
            serverSocket = new ServerSocket();
            // Survive quick stop()/start() cycles — without this, a
            // restart inside the TIME_WAIT window throws "Address
            // already in use" and the daemon is mute until the OS
            // reclaims the socket.
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), PORT), 1);
            logger.info("AAC ingest server listening on 127.0.0.1:" + PORT);

            while (running) {
                Socket client;
                try {
                    client = serverSocket.accept();
                } catch (SocketException se) {
                    if (running) logger.warn("accept() error: " + se.getMessage());
                    break;
                } catch (IOException ioe) {
                    if (running) logger.warn("accept() IOException: " + ioe.getMessage());
                    continue;
                }

                // Evict the previous client cleanly. Critical ordering:
                // PUBLISH the new client+worker as the active pair BEFORE
                // closing/interrupting/joining the old one. The old
                // worker's finally block compares `activeClient == myClient`
                // to decide whether to call disableAudioMuxing(); if we
                // close+join first and only then assign, the old worker's
                // finally observes activeClient still == myClient and
                // disables audio muxing — leaving a brief window where
                // the new client's CONFIG races with a stale teardown.
                // By assigning first, the old worker's finally sees
                // activeClient != myClient and skips the disable, which
                // is what we want when a fresher client is taking over.
                //
                // Snapshot the old socket+worker into locals BEFORE the
                // assignment so we can still close/interrupt/join them.
                // The old worker's own `myClient` local also keeps the
                // socket reachable for its read loop's IO unwind.
                Socket previous = activeClient;
                Thread previousWorker = activeWorker;

                logger.info("Client connected from " + client.getRemoteSocketAddress());

                // Per-client worker thread. Keeps the accept loop
                // responsive so a wedged client can't stall reconnects,
                // and lets the previous worker's finally block run to
                // completion before we touch encoder state for the new
                // client. Daemon thread so JVM exit isn't blocked.
                final Socket workerClient = client;
                Thread worker = new Thread(new Runnable() {
                    @Override public void run() { handleClient(workerClient); }
                }, "AacClient");
                worker.setDaemon(true);

                // PUBLISH new active pair BEFORE evicting the old one.
                // Order matters: activeClient first, then activeWorker —
                // handleClient() (about to start) only inspects
                // activeClient in its finally, so the new worker's start
                // must NOT precede the activeClient write or there's a
                // sliver where the new worker could observe its own
                // not-yet-published activeClient.
                activeClient = client;
                activeWorker = worker;

                if (previous != null && !previous.isClosed()) {
                    logger.info("Evicting previous client");
                    try { previous.close(); } catch (IOException ignored) {}
                }
                if (previousWorker != null && previousWorker.isAlive()) {
                    // Interrupt before join so a worker blocked in readInt /
                    // readFully (or a slow disableAudioMuxing logger call)
                    // unblocks promptly instead of letting the join time out
                    // and the new worker proceed without the old one's
                    // finally having run. The read loop is already inside
                    // a SocketException catch path, so the interrupt won't
                    // crash it — it just unblocks the IO faster.
                    previousWorker.interrupt();
                    try {
                        previousWorker.join(EVICT_JOIN_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

                // Start the new worker AFTER the old one has finished its
                // finally (or the join timed out). This way the new
                // worker's first DATA packet will see a stable encoder
                // state — either the old worker's finally already ran
                // and skipped disableAudioMuxing (because activeClient
                // != myClient), or the join timed out and the old
                // worker is unlikely to ever fire its finally again.
                worker.start();
            }
        } catch (Exception e) {
            logger.error("AAC ingest server fatal error", e);
        } finally {
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
            // Tear down the active worker so we don't leak a thread or
            // leave audio muxing in a stale state. The worker's finally
            // will run disableAudioMuxing() if it's still the active
            // client (which it is, since stop() implies daemon teardown).
            Socket c = activeClient;
            if (c != null) {
                try { c.close(); } catch (IOException ignored) {}
            }
            Thread w = activeWorker;
            if (w != null && w.isAlive()) {
                // Same hygiene as the eviction path: interrupt before join
                // so the worker's IO unblocks promptly on shutdown rather
                // than letting the join time out.
                w.interrupt();
                try { w.join(EVICT_JOIN_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            // Belt-and-suspenders — if the server dies while a recording
            // is in flight and the worker didn't get there, drop the
            // audio track so the muxer doesn't wait on packets that
            // will never come.
            HardwareEventRecorderGpu enc = currentEncoder();
            if (enc != null) enc.disableAudioMuxing();
            // Don't carry CONFIG state across a stop()/restart cycle.
            cachedCsd0 = null;
            cachedSampleRate = 0;
            cachedChannelCount = 0;
            cachedBitrate = 0;
            configCachedButNotApplied = false;
            lastConfiguredEncoder = null;
            activeClient = null;
            activeWorker = null;
            logger.info("AAC ingest server stopped");
        }
    }

    private void handleClient(Socket client) {
        // Capture this worker's socket so the finally block can tell
        // whether it's still the active client. If a newer client has
        // taken over, we MUST NOT call disableAudioMuxing() — that
        // would undo the new client's CONFIG.
        final Socket myClient = client;
        DataInputStream in = null;
        try {
            client.setTcpNoDelay(true);
            // 5 s read timeout — well above the 20 ms inter-frame interval.
            // If reads stall longer than this the app process is wedged or
            // the mic was claimed by another client; either way, drop the
            // connection so the next reconnect attempt has a clean slot.
            client.setSoTimeout(5000);
            // Catch a half-open peer (app crashed without RST) within
            // ~2 hours via the kernel's keepalive probes.
            client.setKeepAlive(true);
            in = new DataInputStream(client.getInputStream());

            boolean configReceived = false;
            // Reusable read buffer — sized for the largest AAC frame we
            // expect (~256 B at 64 kbps × 20 ms). Grown on demand if a
            // larger frame arrives, capped at MAX_PAYLOAD_BYTES.
            byte[] payloadBuf = new byte[1024];

            while (running && !client.isClosed()) {
                int totalLength = in.readInt();
                if (totalLength <= 0 || totalLength > MAX_PAYLOAD_BYTES) {
                    logger.warn("Invalid frame length " + totalLength + " — closing");
                    break;
                }
                int msgType = in.readInt();
                int payloadLength = totalLength - 4;  // length includes msgType bytes
                if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                    logger.warn("Invalid payload length " + payloadLength + " — closing");
                    break;
                }
                if (payloadLength > payloadBuf.length) {
                    payloadBuf = new byte[payloadLength];
                }
                in.readFully(payloadBuf, 0, payloadLength);

                if (msgType == MSG_CONFIG) {
                    if (!parseConfig(payloadBuf, payloadLength)) {
                        logger.warn("Malformed CONFIG — closing");
                        break;
                    }
                    configReceived = true;
                } else if (msgType == MSG_DATA) {
                    if (!configReceived) {
                        logger.warn("DATA before CONFIG — closing");
                        break;
                    }
                    if (payloadLength < 8) {
                        logger.warn("DATA payload too short for PTS — skipping");
                        continue;
                    }
                    long ptsUs = readLongBE(payloadBuf, 0);
                    int aacLength = payloadLength - 8;

                    // Resolve the live encoder ONCE per packet so the
                    // identity-change check, the config replay, and the
                    // push all see the same instance.
                    HardwareEventRecorderGpu enc = currentEncoder();

                    // Encoder identity / config replay. Three cases trigger
                    // a replay:
                    //   (a) Cold-start: CONFIG arrived before encoder existed.
                    //       configCachedButNotApplied is set true; flip false
                    //       on apply.
                    //   (b) Recording-mode switch: daemon tore down the old
                    //       encoder + spun a new one with audioConfig=null,
                    //       but our long-lived TCP client kept streaming.
                    //       lastConfiguredEncoder no longer matches enc, so
                    //       re-apply.
                    //   (c) Lost config: the encoder disabled audio muxing
                    //       internally (e.g. setAudioConfig(null) from a
                    //       teardown path) but stayed alive. hasAudioConfig
                    //       returns false; re-apply so the next muxer start
                    //       picks up the audio track.
                    //
                    // Without this re-apply, a mode switch leaves audio
                    // permanently silent until the user restarts the app —
                    // exactly the "doesn't work seamlessly" symptom.
                    if (enc != null && cachedCsd0 != null) {
                        boolean replayNeeded = configCachedButNotApplied
                            || lastConfiguredEncoder != enc
                            || !enc.hasAudioConfig();
                        if (replayNeeded) {
                            // Snapshot the cause BEFORE the assignment below
                            // flips lastConfiguredEncoder to enc — otherwise
                            // the ternary always reads enc == enc and the log
                            // line is permanently wrong.
                            boolean wasColdStart = configCachedButNotApplied;
                            enc.setAudioConfig(cachedCsd0, cachedSampleRate,
                                cachedChannelCount, cachedBitrate);
                            configCachedButNotApplied = false;
                            lastConfiguredEncoder = enc;
                            logger.info("Replayed cached CONFIG to encoder ("
                                + (wasColdStart
                                    ? "cold-start"
                                    : "identity-changed-or-lost-config") + ")");
                        }
                    }

                    if (enc != null) {
                        // Use the (data, offset, length, ptsUs) overload to
                        // skip the per-frame copy. AAC bytes start at
                        // offset 8 (after the 8-byte BE PTS).
                        enc.pushAudioPacket(payloadBuf, 8, aacLength, ptsUs);
                    }
                    // else: no recording active — drop the packet, the app
                    // should also be checking before encoding, but this is
                    // the canonical gate.
                } else if (msgType == MSG_PCM) {
                    if (payloadLength <= 0
                            || payloadLength > MAX_PCM_FRAME_BYTES
                            || (payloadLength & 1) != 0) {
                        logger.warn("Malformed PCM frame — closing");
                        break;
                    }
                    CabinAudioWebSocket.offerPcm(
                            payloadBuf, 0, payloadLength);
                } else if (msgType == MSG_LISTENER_ONLY) {
                    if (payloadLength != 0) {
                        logger.warn("Malformed LISTENER_ONLY marker — closing");
                        break;
                    }
                    configReceived = false;
                    clearRecordingConfig();
                } else {
                    logger.warn("Unknown msgType=" + msgType + " — skipping");
                }
            }
        } catch (java.io.EOFException eof) {
            logger.info("Client closed connection");
        } catch (SocketException se) {
            logger.info("Client socket reset: " + se.getMessage());
        } catch (Exception e) {
            logger.warn("Client error: " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { client.close(); } catch (IOException ignored) {}
            // Only disable audio muxing if WE are still the active
            // client. If a newer client has already taken over, its
            // CONFIG is live and we must not undo it.
            if (activeClient == myClient) {
                clearRecordingConfig();
                logger.info("Client disconnected; audio muxing disabled, cache cleared");
            } else {
                logger.info("Client disconnected; newer client active, leaving muxing alone");
            }
        }
    }

    /**
     * Parse a CONFIG payload and push the AAC parameters into the
     * encoder. Caches the parsed values so a CONFIG that arrives before
     * the encoder is up can be replayed lazily on the first DATA packet.
     * Returns false if the payload is malformed. Doesn't take a lock —
     * setAudioConfig is internally thread-safe.
     */
    private boolean parseConfig(byte[] buf, int length) {
        if (length < 16) return false;
        int sampleRate = readIntBE(buf, 0);
        int channelCount = readIntBE(buf, 4);
        int bitrate = readIntBE(buf, 8);
        int csd0Length = readIntBE(buf, 12);
        if (csd0Length < 0 || csd0Length > 64) return false;  // CSD-0 is 2-5 bytes typically
        if (16 + csd0Length > length) return false;
        if (sampleRate <= 0 || sampleRate > 96000) return false;
        if (channelCount <= 0 || channelCount > 2) return false;
        if (bitrate <= 0 || bitrate > 320000) return false;

        byte[] csd0 = new byte[csd0Length];
        System.arraycopy(buf, 16, csd0, 0, csd0Length);

        // Cache unconditionally so the DATA path can replay if needed.
        this.cachedCsd0 = csd0;
        this.cachedSampleRate = sampleRate;
        this.cachedChannelCount = channelCount;
        this.cachedBitrate = bitrate;

        HardwareEventRecorderGpu enc = currentEncoder();
        if (enc == null) {
            this.configCachedButNotApplied = true;
            logger.info("CONFIG received but encoder not ready yet — cached for replay");
            return true;
        }
        enc.setAudioConfig(csd0, sampleRate, channelCount, bitrate);
        this.configCachedButNotApplied = false;
        this.lastConfiguredEncoder = enc;
        return true;
    }

    private void clearRecordingConfig() {
        HardwareEventRecorderGpu enc = currentEncoder();
        if (enc != null) enc.disableAudioMuxing();
        cachedCsd0 = null;
        cachedSampleRate = 0;
        cachedChannelCount = 0;
        cachedBitrate = 0;
        configCachedButNotApplied = false;
        lastConfiguredEncoder = null;
    }

    private static HardwareEventRecorderGpu currentEncoder() {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null) return null;
        return pipeline.getEncoder();
    }

    private static int readIntBE(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24)
            | ((b[o + 1] & 0xFF) << 16)
            | ((b[o + 2] & 0xFF) << 8)
            | (b[o + 3] & 0xFF);
    }

    private static long readLongBE(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 56)
            | ((long) (b[o + 1] & 0xFF) << 48)
            | ((long) (b[o + 2] & 0xFF) << 40)
            | ((long) (b[o + 3] & 0xFF) << 32)
            | ((long) (b[o + 4] & 0xFF) << 24)
            | ((long) (b[o + 5] & 0xFF) << 16)
            | ((long) (b[o + 6] & 0xFF) << 8)
            | ((long) (b[o + 7] & 0xFF));
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        try {
            Socket c = activeClient;
            if (c != null) c.close();
        } catch (IOException ignored) {}
    }
}
