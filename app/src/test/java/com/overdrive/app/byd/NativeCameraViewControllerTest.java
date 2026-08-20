package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NativeCameraViewControllerTest {

    private DaemonLogger.Config previousLogConfig;

    @Before
    public void disableAndroidAndFileLogging() {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @After
    public void restoreLogging() {
        DaemonLogger.configure(previousLogConfig);
    }

    @Test
    public void selectsDirectionWithoutLaunchingAnApplication() {
        List<String> calls = new ArrayList<>();

        boolean dispatched = NativeCameraViewController.show(
                BydDataCollector.NATIVE_CAMERA_VIEW_REAR,
                (action, extra, viewCode) ->
                        calls.add("broadcast:" + action + ":" + extra + ":" + viewCode));

        assertTrue(dispatched);
        assertEquals(Arrays.asList(
                "broadcast:android.intent.action.AUTO_VIDEO_BUTTON:"
                        + "android.intent.extra.KEY_EVENT:3002"), calls);
    }

    @Test
    public void broadcastFailureIsReported() {
        boolean dispatched = NativeCameraViewController.show(
                BydDataCollector.NATIVE_CAMERA_VIEW_LEFT,
                (action, extra, viewCode) -> {
                    throw new IllegalStateException("receiver unavailable");
                });

        assertFalse(dispatched);
    }

    @Test
    public void invalidDirectionDoesNotBroadcast() {
        int[] calls = {0};

        boolean dispatched = NativeCameraViewController.show(
                9999,
                (action, extra, viewCode) -> calls[0]++);

        assertFalse(dispatched);
        assertEquals(0, calls[0]);
    }
}
