package com.overdrive.app.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;

import org.junit.Test;

public class RemoteCommunicationWebSocketFrameTest {

    @Test
    public void idleTimeoutIsDistinguishedFromPartialFrameTimeout()
            throws Exception {
        Throwable idle = invokeFailure(new InputStream() {
            @Override public int read() throws SocketTimeoutException {
                throw new SocketTimeoutException("idle");
            }
        });
        assertEquals("IdleReadTimeout", idle.getClass().getSimpleName());

        Throwable partial = invokeFailure(new InputStream() {
            private boolean headerRead;

            @Override public int read() throws SocketTimeoutException {
                if (!headerRead) {
                    headerRead = true;
                    return 0x82;
                }
                throw new SocketTimeoutException("partial");
            }
        });
        assertEquals(SocketTimeoutException.class, partial.getClass());
    }

    @Test
    public void decodesMaskedBinaryFrames() throws Exception {
        byte[] mask = new byte[]{1, 2, 3, 4};
        byte[] payload = new byte[]{10, 20, 30, 40};
        byte[] frame = new byte[2 + mask.length + payload.length];
        frame[0] = (byte) 0x82;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, frame, 2, mask.length);
        for (int index = 0; index < payload.length; index++) {
            frame[2 + mask.length + index] =
                    (byte) (payload[index] ^ mask[index & 3]);
        }

        Object decoded = readFrame(new ByteArrayInputStream(frame));
        Field opcode = decoded.getClass().getDeclaredField("opcode");
        Field decodedPayload = decoded.getClass().getDeclaredField("payload");
        opcode.setAccessible(true);
        decodedPayload.setAccessible(true);

        assertEquals(0x2, opcode.getInt(decoded));
        assertArrayEquals(payload, (byte[]) decodedPayload.get(decoded));
    }

    private static Throwable invokeFailure(InputStream input) throws Exception {
        try {
            readFrame(input);
            fail("Expected WebSocket frame parsing to fail");
            return null;
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            assertTrue(cause instanceof SocketTimeoutException);
            return cause;
        }
    }

    private static Object readFrame(InputStream input) throws Exception {
        Method method = RemoteCommunicationWebSocket.class.getDeclaredMethod(
                "readFrame", InputStream.class);
        method.setAccessible(true);
        return method.invoke(null, input);
    }
}
