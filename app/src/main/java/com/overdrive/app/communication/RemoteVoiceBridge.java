package com.overdrive.app.communication;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One remote-voice session between the HTTP daemon and app-process receiver.
 */
public final class RemoteVoiceBridge implements Closeable {

    public interface Listener {
        void onControl(String control);
        void onReceiverLost(String reason);
    }

    public static final class RejectedException extends IOException {
        public RejectedException(String message) {
            super(message);
        }
    }

    private static final int ACCEPT_TIMEOUT_MS = 4_500;
    private static final int HANDSHAKE_TIMEOUT_MS = 2_000;
    private static final long SAFETY_REFRESH_MS = 100L;

    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final ServerSocket serverSocket;
    private Socket receiver;
    private DataInputStream controlInput;
    private DataOutputStream audioOutput;
    private Thread controlThread;
    private long lastSafetyCheckMs;
    private Boolean lastOverlaySafe;

    private RemoteVoiceBridge(ServerSocket serverSocket, Listener listener) {
        this.serverSocket = serverSocket;
        this.listener = listener;
    }

    public static RemoteVoiceBridge connect(int outputLevel, Listener listener)
            throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        server.setSoTimeout(ACCEPT_TIMEOUT_MS);

        RemoteVoiceBridge bridge = new RemoteVoiceBridge(server, listener);
        String token = UUID.randomUUID().toString();
        RemoteVoiceController.start(
                server.getLocalPort(), token,
                RemoteCommunicationPolicy.clampOutputLevel(outputLevel));
        try {
            bridge.acceptReceiver(token);
            return bridge;
        } catch (IOException error) {
            bridge.close();
            throw error;
        }
    }

    private void acceptReceiver(String expectedToken) throws IOException {
        try {
            receiver = serverSocket.accept();
        } catch (SocketTimeoutException timeout) {
            throw new RejectedException("The car audio receiver did not start");
        } finally {
            try { serverSocket.close(); } catch (Throwable ignored) {}
        }
        receiver.setTcpNoDelay(true);
        receiver.setKeepAlive(true);
        receiver.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        controlInput = new DataInputStream(receiver.getInputStream());
        audioOutput = new DataOutputStream(receiver.getOutputStream());

        String receivedToken = controlInput.readUTF();
        String status = controlInput.readUTF();
        String reason = controlInput.readUTF();
        if (!expectedToken.equals(receivedToken)) {
            throw new RejectedException("The car audio receiver handshake was rejected");
        }
        if (!"READY".equals(status)) {
            throw new RejectedException(
                    reason == null || reason.trim().isEmpty()
                            ? "The car audio receiver is unavailable" : reason);
        }
        receiver.setSoTimeout(0);
        sendOverlaySafety(true);
        startControlReader();
    }

    private void startControlReader() {
        controlThread = new Thread(() -> {
            try {
                while (!closed.get()) {
                    String control = controlInput.readUTF();
                    if (listener != null && control != null) listener.onControl(control);
                }
            } catch (IOException error) {
                if (!closed.get() && listener != null) {
                    listener.onReceiverLost("The car audio receiver disconnected");
                }
            }
        }, "RemoteVoiceControl");
        controlThread.setDaemon(true);
        controlThread.start();
    }

    public void sendPcm(byte[] pcm) throws IOException {
        if (closed.get()) throw new IOException("Remote voice bridge is closed");
        synchronized (writeLock) {
            RemoteVoiceProtocol.writePcm(audioOutput, pcm);
            sendOverlaySafety(false);
        }
    }

    private void sendOverlaySafety(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && now - lastSafetyCheckMs < SAFETY_REFRESH_MS) return;
        lastSafetyCheckMs = now;
        boolean safe = VehicleCommunicationSafety.isRemoteVoiceOverlaySafe();
        if (force || lastOverlaySafe == null || lastOverlaySafe != safe) {
            RemoteVoiceProtocol.writeOverlaySafe(audioOutput, safe);
            lastOverlaySafe = safe;
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (writeLock) {
            try {
                if (audioOutput != null) RemoteVoiceProtocol.writeEnd(audioOutput);
            } catch (Throwable ignored) {}
            try { if (receiver != null) receiver.close(); } catch (Throwable ignored) {}
            try { serverSocket.close(); } catch (Throwable ignored) {}
        }
        RemoteVoiceController.stop();
        if (controlThread != null && controlThread != Thread.currentThread()) {
            controlThread.interrupt();
        }
    }
}
