package com.overdrive.app.server;

import android.util.Base64;

import com.overdrive.app.communication.CabinAudioController;
import com.overdrive.app.communication.RemoteCommunicationAvailability;
import com.overdrive.app.communication.RemoteCommunicationSettings;
import com.overdrive.app.communication.VehicleCommunicationSafety;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Authenticated cabin-microphone listener at {@code /ws/cabin-audio}. */
public final class CabinAudioWebSocket {

    private static final String WS_MAGIC =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final DaemonLogger logger =
            DaemonLogger.getInstance("CabinAudio");
    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();
    private static final AtomicBoolean TALK_ACTIVE = new AtomicBoolean(false);

    private CabinAudioWebSocket() {}

    public static boolean isBusy() {
        return ACTIVE.get() != null;
    }

    public static void stopActive(String reason) {
        Session session = ACTIVE.get();
        if (session != null) session.requestStop(reason);
    }

    public static void setTalkActive(boolean active) {
        if (TALK_ACTIVE.getAndSet(active) == active) return;
        Session session = ACTIVE.get();
        if (session != null) session.setTalkPaused(active);
    }

    public static void offerPcm(byte[] data, int offset, int length) {
        Session session = ACTIVE.get();
        if (session != null) session.offer(data, offset, length);
    }

    public static void handle(Socket client, String websocketKey) {
        Session session = new Session(client);
        try {
            session.handshake(websocketKey);
            if (!ACTIVE.compareAndSet(null, session)) {
                session.failAndClose(
                        "Another cabin listener is already active", 1013);
                return;
            }
            session.run();
        } catch (Throwable error) {
            logger.warn("Cabin listener WebSocket failed: " + error.getMessage());
            if (!session.stopRequested) session.requestStop("Connection lost");
        } finally {
            try {
                session.close();
            } finally {
                ACTIVE.compareAndSet(session, null);
            }
        }
    }

    private static final class Session {
        private static final int SAMPLE_RATE = 48_000;
        private static final int CHANNELS = 1;
        private static final int MAX_PCM_BYTES = 8 * 1024;
        private static final int QUEUE_CAPACITY = 12;
        private static final long FIRST_PCM_TIMEOUT_MS = 8_000L;
        private static final long PCM_INACTIVITY_MS = 4_000L;
        private static final long CLIENT_STALL_TIMEOUT_MS = 5_000L;

        private final Socket socket;
        private final String serviceToken = UUID.randomUUID().toString();
        private final Object outputLock = new Object();
        private final ArrayBlockingQueue<PcmFrame> queue =
                new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final ArrayBlockingQueue<PcmFrame> pool =
                new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private volatile boolean stopRequested;
        private volatile boolean talkPaused = TALK_ACTIVE.get();
        private volatile boolean controlPending;
        private volatile boolean readySent;
        private volatile boolean serviceStarted;
        private volatile long awaitingPcmSince = System.currentTimeMillis();
        private volatile long lastPcmAt;
        private volatile long lastSendProgressAt = System.currentTimeMillis();
        private volatile String stopReason = "Connection closed";
        private InputStream input;
        private OutputStream output;
        private Thread sender;
        private long sentFrames;
        private long droppedFrames;

        Session(Socket socket) {
            this.socket = socket;
            for (int i = 0; i < QUEUE_CAPACITY; i++) {
                pool.offer(new PcmFrame(MAX_PCM_BYTES));
            }
        }

        void handshake(String key) throws Exception {
            input = socket.getInputStream();
            output = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + websocketAccept(key)
                    + "\r\n\r\n";
            output.write(response.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(1_000);
        }

        void run() throws Exception {
            RemoteCommunicationSettings.Snapshot settings =
                    RemoteCommunicationSettings.load();
            RemoteCommunicationAvailability.Result availability =
                    RemoteCommunicationAvailability.listener(
                            VehicleCommunicationSafety.isCarKnownOff(),
                            settings,
                            false);
            if (!availability.ready) {
                failAndClose(availability.reason, 1008);
                return;
            }

            sendJson(new JSONObject()
                    .put("type", "starting")
                    .put("sampleRate", SAMPLE_RATE)
                    .put("channels", CHANNELS)
                    .put("format", "pcm_s16le"));
            startSender();
            CabinAudioController.start(serviceToken);
            serviceStarted = true;

            while (!stopRequested && !socket.isClosed()) {
                long now = System.currentTimeMillis();
                if (VehicleCommunicationSafety.isCarKnownOff()) {
                    stopReason = "The car is off, so cabin listening is unavailable";
                    break;
                }
                if (!talkPaused
                        && !readySent
                        && now - awaitingPcmSince >= FIRST_PCM_TIMEOUT_MS) {
                    failAndClose("The cabin microphone did not start", 1011);
                    break;
                }
                if (!talkPaused
                        && readySent
                        && now - lastPcmAt >= PCM_INACTIVITY_MS) {
                    failAndClose(
                            "The cabin microphone stream became inactive", 1011);
                    break;
                }
                if (!talkPaused
                        && readySent
                        && now - lastSendProgressAt >= CLIENT_STALL_TIMEOUT_MS) {
                    stopReason = "The cabin audio client stopped receiving data";
                    logger.warn(stopReason);
                    requestStop(stopReason);
                    try { socket.close(); } catch (Throwable ignored) {}
                    break;
                }

                Frame frame;
                try {
                    frame = readFrame(input);
                } catch (IdleReadTimeout timeout) {
                    continue;
                }
                if (frame == null || frame.opcode == 0x8) break;
                if (frame.opcode == 0x9) {
                    sendFrame(0xA, frame.payload, frame.payload.length);
                }
            }
            if (!stopRequested && !socket.isClosed()) {
                try {
                    sendJson(new JSONObject()
                            .put("type", "stopped")
                            .put("reason", stopReason));
                    sendClose(1000, stopReason);
                } catch (Throwable ignored) {}
                requestStop(stopReason);
            }
        }

        void offer(byte[] data, int offset, int length) {
            if (stopRequested || talkPaused || length <= 0
                    || length > MAX_PCM_BYTES) {
                return;
            }
            PcmFrame frame = pool.poll();
            if (frame == null) {
                frame = queue.poll();
                if (frame == null) {
                    droppedFrames++;
                    return;
                }
                droppedFrames++;
            }
            System.arraycopy(data, offset, frame.data, 0, length);
            frame.length = length;
            lastPcmAt = System.currentTimeMillis();
            if (!queue.offer(frame)) {
                droppedFrames++;
                pool.offer(frame);
            }
        }

        void setTalkPaused(boolean paused) {
            talkPaused = paused;
            clearQueue();
            if (!paused) {
                long now = System.currentTimeMillis();
                awaitingPcmSince = now;
                lastSendProgressAt = now;
                if (readySent) lastPcmAt = now;
            }
            controlPending = true;
            Thread worker = sender;
            if (worker != null) worker.interrupt();
        }

        void requestStop(String reason) {
            stopRequested = true;
            if (reason != null && !reason.trim().isEmpty()) stopReason = reason;
            Thread worker = sender;
            if (worker != null) worker.interrupt();
            try { socket.shutdownInput(); } catch (Throwable ignored) {}
        }

        void failAndClose(String reason, int code) {
            try {
                sendJson(new JSONObject()
                        .put("type", "failed")
                        .put("reason", reason));
                sendClose(code, reason);
            } catch (Throwable ignored) {}
            requestStop(reason);
        }

        void close() {
            requestStop(stopReason);
            if (serviceStarted) CabinAudioController.stop(serviceToken);
            try { socket.close(); } catch (Throwable ignored) {}
            Thread worker = sender;
            if (worker != null && worker != Thread.currentThread()) {
                try { worker.join(1_000L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            clearQueue();
            if (sentFrames > 0 || droppedFrames > 0) {
                logger.info("Cabin listener stats: sent=" + sentFrames
                        + ", dropped=" + droppedFrames);
            }
        }

        private void startSender() {
            lastSendProgressAt = System.currentTimeMillis();
            controlPending = talkPaused;
            sender = new Thread(this::sendLoop, "CabinAudioSender");
            sender.setDaemon(true);
            sender.start();
        }

        private void sendLoop() {
            while (!stopRequested) {
                PcmFrame frame = null;
                try {
                    if (controlPending) {
                        controlPending = false;
                        boolean paused = talkPaused;
                        sendJson(new JSONObject()
                                .put("type", paused ? "paused" : "resumed")
                                .put("reason",
                                        paused ? "Push-to-talk active" : ""));
                        lastSendProgressAt = System.currentTimeMillis();
                        continue;
                    }
                    frame = queue.poll(500L, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;
                    if (talkPaused) continue;
                    if (!readySent) {
                        sendJson(new JSONObject()
                                .put("type", "ready")
                                .put("sampleRate", SAMPLE_RATE)
                                .put("channels", CHANNELS)
                                .put("format", "pcm_s16le"));
                        readySent = true;
                        lastSendProgressAt = System.currentTimeMillis();
                    }
                    sendFrame(0x2, frame.data, frame.length);
                    sentFrames++;
                    lastSendProgressAt = System.currentTimeMillis();
                } catch (InterruptedException interrupted) {
                    if (stopRequested) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Throwable error) {
                    requestStop("Cabin audio connection was lost");
                    break;
                } finally {
                    if (frame != null) pool.offer(frame);
                }
            }
        }

        private void clearQueue() {
            PcmFrame frame;
            while ((frame = queue.poll()) != null) pool.offer(frame);
        }

        private void sendJson(JSONObject object) throws IOException {
            byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
            sendFrame(0x1, bytes, bytes.length);
        }

        private void sendClose(int code, String reason) throws IOException {
            byte[] reasonBytes = reason == null
                    ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            int reasonLength = Math.min(reasonBytes.length, 123);
            byte[] payload = new byte[2 + reasonLength];
            payload[0] = (byte) ((code >>> 8) & 0xFF);
            payload[1] = (byte) (code & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonLength);
            sendFrame(0x8, payload, payload.length);
        }

        private void sendFrame(int opcode, byte[] payload, int length)
                throws IOException {
            synchronized (outputLock) {
                output.write(0x80 | (opcode & 0x0F));
                if (length <= 125) {
                    output.write(length);
                } else {
                    output.write(126);
                    output.write((length >>> 8) & 0xFF);
                    output.write(length & 0xFF);
                }
                output.write(payload, 0, length);
                output.flush();
            }
        }
    }

    private static final class PcmFrame {
        final byte[] data;
        int length;

        PcmFrame(int capacity) {
            data = new byte[capacity];
        }
    }

    private static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private static final class IdleReadTimeout extends SocketTimeoutException {
        IdleReadTimeout() {
            super("No WebSocket frame started before the idle timeout");
        }
    }

    private static Frame readFrame(InputStream input) throws IOException {
        int first;
        try {
            first = input.read();
        } catch (SocketTimeoutException timeout) {
            throw new IdleReadTimeout();
        }
        if (first < 0) return null;
        int second = input.read();
        if (second < 0) throw new EOFException();
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (!fin) throw new IOException("Fragmented client frames are unsupported");
        if (!masked) throw new IOException("Client WebSocket frame was not masked");
        if (length == 126) {
            length = ((long) readRequired(input) << 8) | readRequired(input);
        } else if (length == 127) {
            length = 0L;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readRequired(input);
            }
        }
        if (length < 0 || length > 8 * 1024) {
            throw new IOException("WebSocket frame is too large");
        }
        byte[] mask = new byte[4];
        readFully(input, mask);
        byte[] payload = new byte[(int) length];
        readFully(input, payload);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return new Frame(opcode, payload);
    }

    private static int readRequired(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static void readFully(InputStream input, byte[] target)
            throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) throw new EOFException();
            offset += read;
        }
    }

    private static String websocketAccept(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(
                (key + WS_MAGIC).getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }
}
