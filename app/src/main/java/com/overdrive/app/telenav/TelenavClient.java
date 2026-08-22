package com.overdrive.app.telenav;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.telenav.app.external.IServiceInitCallback;
import com.telenav.app.external.IServiceManager;
import com.telenav.app.external.IUserDataService;
import com.telenav.app.external.model.search.Place;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Binds Telenav's exported OEM AIDL ({@code com.telenav.app.service.TnNaviService},
 * action {@code com.telenav.app.external.service.NAVI}) and hands back a per-service
 * binder for one operation. The service is exported with no permission and
 * {@code onBind} returns unconditionally, so a normal bind is enough (no
 * PermissiveContext).
 *
 * <p>Must run in the APP process — the uid-2000 daemon can't {@code bindService}
 * (no AMS-registered app record). We use the {@code bindService(Intent, int,
 * Executor, ServiceConnection)} overload (API 29+) so the connection callback
 * lands on our executor, not a main Looper the daemon isn't running.
 */
public final class TelenavClient {

    private static final String TAG = "TelenavClient";

    private static final String TELENAV_PKG = "com.telenav.app.arp";
    private static final String NAVI_SERVICE = "com.telenav.app.service.TnNaviService";
    private static final String NAVI_ACTION = "com.telenav.app.external.service.NAVI";
    private static final String CLIENT_VERSION = "2.1.1"; // isValidClient checks major == "2"

    // From the decompiled INavigationService.Stub (Telenav v9.0.152): the AIDL
    // descriptor and startNavigation's transaction code. We raw-transact this one
    // method to avoid vendoring the whole navi/listener/model tree.
    private static final String NAV_DESCRIPTOR = "com.telenav.app.external.INavigationService";
    private static final int TRANSACTION_startNavigation = 8;

    private TelenavClient() {}

    /** Calls the specific "register …ServiceCallback" method on the manager. */
    public interface Registrar {
        void register(IServiceManager mgr, IServiceInitCallback cb) throws RemoteException;
    }

    /** Runs against the raw per-service binder delivered by onServiceInitSuccess. */
    public interface BinderOp<T> {
        T run(IBinder serviceBinder) throws Exception;
    }

    /** An operation against the typed user-data service. */
    public interface UserDataOp<T> {
        T run(IUserDataService svc) throws Exception;
    }

    public static <T> T withUserData(Context ctx, long timeoutMs, UserDataOp<T> op) throws Exception {
        return withService(ctx, timeoutMs,
                IServiceManager::registerUserDataServiceCallback,
                binder -> op.run(IUserDataService.Stub.asInterface(binder)));
    }

    /** Start turn-by-turn navigation to a place. Returns Telenav's boolean result. */
    public static boolean startNavigation(Context ctx, long timeoutMs, Place place) throws Exception {
        Boolean r = withService(ctx, timeoutMs,
                IServiceManager::registerNavigationServiceCallback,
                binder -> rawStartNavigation(binder, place));
        return Boolean.TRUE.equals(r);
    }

    private static boolean rawStartNavigation(IBinder binder, Place place) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(NAV_DESCRIPTOR);
            if (place != null) {
                data.writeInt(1);
                place.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            binder.transact(TRANSACTION_startNavigation, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Bind, obtain a service binder via {@code registrar}, run {@code op}, unbind.
     * Blocking with an overall timeout. Must be called off the main thread.
     */
    private static <T> T withService(Context ctx, long timeoutMs, Registrar registrar, BinderOp<T> op)
            throws Exception {
        if (ctx == null) throw new IllegalStateException("no context");

        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch serviceReady = new CountDownLatch(1);
        final AtomicReference<IBinder> serviceBinder = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();

        final IServiceInitCallback initCallback = new IServiceInitCallback.Stub() {
            @Override public void onServiceInitSuccess(IBinder binder) {
                serviceBinder.set(binder);
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
                connected.countDown();
                try {
                    boolean valid = sm.isValid(CLIENT_VERSION);
                    Log.i(TAG, "isValid(" + CLIENT_VERSION + ")=" + valid);
                    registrar.register(sm, initCallback);
                } catch (RemoteException e) {
                    failure.set("register callback: " + e.getMessage());
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
                throw new IllegalStateException("timed out waiting for service");
            }
            IBinder b = serviceBinder.get();
            if (b == null) {
                throw new IllegalStateException(failure.get() != null ? failure.get()
                        : "service binder not delivered");
            }
            return op.run(b);
        } finally {
            try { ctx.unbindService(conn); } catch (Throwable ignore) {}
        }
    }
}
