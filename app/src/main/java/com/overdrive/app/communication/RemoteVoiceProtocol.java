package com.overdrive.app.communication;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Framing between the shell daemon and the app-process AudioTrack receiver.
 *
 * Frame: {@code int32 payloadLengthIncludingType, uint8 type, payload...}.
 */
public final class RemoteVoiceProtocol {

    public static final int TYPE_PCM = 1;
    public static final int TYPE_OVERLAY_SAFE = 2;
    public static final int TYPE_END = 3;

    private static final int MAX_FRAME_BYTES =
            RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES + 1;

    public static final class Packet {
        public final int type;
        public final byte[] payload;

        Packet(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private RemoteVoiceProtocol() {}

    public static void writePcm(DataOutputStream out, byte[] pcm) throws IOException {
        if (pcm == null || pcm.length == 0
                || pcm.length > RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES
                || (pcm.length & 1) != 0) {
            throw new IOException("Invalid PCM frame");
        }
        write(out, TYPE_PCM, pcm);
    }

    public static void writeOverlaySafe(DataOutputStream out, boolean safe)
            throws IOException {
        write(out, TYPE_OVERLAY_SAFE, new byte[]{(byte) (safe ? 1 : 0)});
    }

    public static void writeEnd(DataOutputStream out) throws IOException {
        write(out, TYPE_END, new byte[0]);
    }

    private static void write(DataOutputStream out, int type, byte[] payload)
            throws IOException {
        out.writeInt(1 + payload.length);
        out.writeByte(type);
        out.write(payload);
        out.flush();
    }

    public static Packet read(DataInputStream in) throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException eof) {
            return null;
        }
        if (length < 1 || length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid remote voice frame length: " + length);
        }
        int type = in.readUnsignedByte();
        byte[] payload = new byte[length - 1];
        in.readFully(payload);
        return new Packet(type, payload);
    }
}
