package com.overdrive.app.communication;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.Test;

public class RemoteVoiceProtocolTest {

    @Test
    public void roundTripsPcmSafetyAndEndPackets() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        byte[] pcm = new byte[]{1, 2, 3, 4};

        RemoteVoiceProtocol.writePcm(output, pcm);
        RemoteVoiceProtocol.writeOverlaySafe(output, false);
        RemoteVoiceProtocol.writeEnd(output);

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray()));
        RemoteVoiceProtocol.Packet audio = RemoteVoiceProtocol.read(input);
        assertEquals(RemoteVoiceProtocol.TYPE_PCM, audio.type);
        assertArrayEquals(pcm, audio.payload);

        RemoteVoiceProtocol.Packet safety = RemoteVoiceProtocol.read(input);
        assertEquals(RemoteVoiceProtocol.TYPE_OVERLAY_SAFE, safety.type);
        assertArrayEquals(new byte[]{0}, safety.payload);

        RemoteVoiceProtocol.Packet end = RemoteVoiceProtocol.read(input);
        assertEquals(RemoteVoiceProtocol.TYPE_END, end.type);
        assertEquals(0, end.payload.length);
        assertNull(RemoteVoiceProtocol.read(input));
    }

    @Test
    public void rejectsOddAndOversizedPcmFrames() throws Exception {
        expectWriteFailure(new byte[]{1});
        expectWriteFailure(new byte[
                RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES + 2]);
    }

    @Test
    public void rejectsInvalidInboundFrameLengths() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES + 2);
        output.flush();

        try {
            RemoteVoiceProtocol.read(new DataInputStream(
                    new ByteArrayInputStream(bytes.toByteArray())));
            fail("Expected invalid frame length to be rejected");
        } catch (IOException expected) {
            assertEquals(
                    "Invalid remote voice frame length: "
                            + (RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES + 2),
                    expected.getMessage());
        }
    }

    private static void expectWriteFailure(byte[] pcm) throws Exception {
        try {
            RemoteVoiceProtocol.writePcm(
                    new DataOutputStream(new ByteArrayOutputStream()), pcm);
            fail("Expected invalid PCM frame to be rejected");
        } catch (IOException expected) {
            assertEquals("Invalid PCM frame", expected.getMessage());
        }
    }
}
