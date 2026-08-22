package com.overdrive.app.telenav;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.telenav.app.external.IServiceInitCallback;
import com.telenav.app.external.IServiceManager;
import com.telenav.app.external.IUserDataService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Binds Telenav's exported OEM AIDL ({@code com.telenav.app.service.TnNaviService},
 * action {@code com.telenav.app.external.service.NAVI}) and hands back the
 * {@link IUserDataService} for the duration of one operation.
 *
 * <p>The service is exported with no permission and {@code onBind} returns
 * unconditionally (the only "gate" is a client-version major-number check), so no
 * PermissiveContext is needed here — a normal bind is enough.
 *
 * <p>Runs inside the uid-2000 daemon. We use the {@code bindService(Intent, int,
 * Executor, ServiceConnection)} overload (API 29+) so the connection callback is
 * delivered on our own executor rather than a main Looper the daemon isn't running.
 * The two-step handshake is: bind → {@code IServiceManager} → {@code
 * registerUserDataServiceCallback} → {@code onServiceInitSuccess} delivers the
 * {@code IUserDataService} on a Binder thread.
 */
public final class TelenavClient {

    private static final String TAG = "TelenavClient";

    private static final String TELENAV_PKG = "com.telenav.app.arp";
    private static final String NAVI_SERVICE = "com.telenav.app.service.TnNaviService";
    private static final String NAVI_ACTION = "com.telenav.app.external.service.NAVI";
    // isValidClient compares the major (before the first ".") to the service's "2.1.1".
    private static final String CLIENT_VERSION = "2.1.1";

    private TelenavClient() {}

    /** An operation to run against the bound user-data service. */
    public interface UserDataOp<T> {
        T run(IUserDataService svc) throws Exception;
    }

    /**
     * Bind, obtain {@link IUserDataService}, run {@code op}, then unbind. Blocking,
     * with an overall timeout. Must be called off the main thread.
     */
    public static <T> T withUserData(Context ctx, long timeoutMs, UserDataOp<T> op) throws Exception {
        if (ctx == null) throw new IllegalStateException("no context");

        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch serviceReady = new CountDownLatch(1);
        final AtomicReference<IServiceManager> manager = new AtomicReference<>();
        final AtomicReference<IUserDataService> userData = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();

        final IServiceInitCallback initCallback = new IServiceInitCallback.Stub() {
            @Override public void onServiceInitSuccess(IBinder binder) {
                userData.set(IUserDataService.Stub.asInterface(binder));
                serviceReady.countDown();
            }
            @Override public void onServiceInitFailed() {
                failure.set("Telenav reported onServiceInitFailed");
                serviceReady.countDown();
            }
        };

        final ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                IServiceManager sm = IServiceManager.Stub.asInterface(binder);
                manager.set(sm);
                connected.countDown();
                try {
                    boolean valid = sm.isValid(CLIENT_VERSION);
                    Log.i(TAG, "isValid(" + CLIENT_VERSION + ")=" + valid);
                    sm.registerUserDataServiceCallback(initCallback);
                } catch (RemoteException e) {
                    failure.set("registerUserDataServiceCallback: " + e.getMessage());
                    serviceReady.countDown();
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "onServiceDisconnected");
            }
        };

        Intent intent = new Intent(NAVI_ACTION);
        intent.setComponent(new ComponentName(TELENAV_PKG, NAVI_SERVICE));

        boolean bindRequested = ctx.bindService(
                intent, Context.BIND_AUTO_CREATE, Executors.newSingleThreadExecutor(), conn);
        if (!bindRequested) {
            try { ctx.unbindService(conn); } catch (Throwable ignore) {}
            throw new IllegalStateException("bindService returned false for " + NAVI_SERVICE);
        }

        try {
            if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("timed out binding TnNaviService");
            }
            if (!serviceReady.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("timed out waiting for IUserDataService");
            }
            IUserDataService svc = userData.get();
            if (svc == null) {
                throw new IllegalStateException(failure.get() != null ? failure.get()
                        : "IUserDataService not delivered");
            }
            return op.run(svc);
        } finally {
            try { ctx.unbindService(conn); } catch (Throwable ignore) {}
        }
    }
}
