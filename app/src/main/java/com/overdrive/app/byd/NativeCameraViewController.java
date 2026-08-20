package com.overdrive.app.byd;

import android.content.Context;
import android.content.Intent;

import com.overdrive.app.logging.DaemonLogger;

/** Selects a supported view in an already-running OEM panorama application. */
final class NativeCameraViewController {
    static final String ACTION = "android.intent.action.AUTO_VIDEO_BUTTON";
    static final String EVENT_EXTRA = "android.intent.extra.KEY_EVENT";

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("NativeCameraView");

    interface Broadcaster {
        void send(String action, String extra, int viewCode) throws Exception;
    }

    private NativeCameraViewController() {}

    static boolean show(Context context, int viewCode) {
        if (context == null) return false;
        return show(
                viewCode,
                (action, extra, code) -> {
                    Intent intent = new Intent(action);
                    intent.putExtra(extra, code);
                    context.sendBroadcast(intent);
                });
    }

    static boolean show(int viewCode, Broadcaster broadcaster) {
        if (!isSupportedViewCode(viewCode) || broadcaster == null) {
            return false;
        }

        try {
            broadcaster.send(ACTION, EVENT_EXTRA, viewCode);
            logger.info("native panorama view=" + viewCode + " broadcast=true");
            return true;
        } catch (Throwable failed) {
            logger.warn("native panorama view=" + viewCode
                    + " broadcast failed: " + failed.getMessage());
            return false;
        }
    }

    static boolean isSupportedViewCode(int viewCode) {
        switch (viewCode) {
            case BydDataCollector.NATIVE_CAMERA_VIEW_FRONT:
            case BydDataCollector.NATIVE_CAMERA_VIEW_REAR:
            case BydDataCollector.NATIVE_CAMERA_VIEW_LEFT:
            case BydDataCollector.NATIVE_CAMERA_VIEW_RIGHT:
            case BydDataCollector.NATIVE_CAMERA_VIEW_FRONT_WIDE:
            case BydDataCollector.NATIVE_CAMERA_VIEW_REAR_WIDE:
            case BydDataCollector.NATIVE_CAMERA_VIEW_LEFT_RIGHT:
                return true;
            default:
                return false;
        }
    }
}
