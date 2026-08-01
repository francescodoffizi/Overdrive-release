package com.overdrive.app.ui.view

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

/**
 * App-process ([uid 1000]) client for the daemon's cluster-view mirror service.
 *
 * Hands the Projection screen's [Surface] (from its `TextureView`) to the daemon over a
 * Binder transaction so SurfaceFlinger can composite the driver cluster INTO that view. HTTP
 * (the normal app↔daemon channel) cannot carry a live `Surface` — only a kernel Binder can —
 * so this is the one path that goes direct to the daemon-registered service. Everything else
 * (open/close cast, status polling) stays on HTTP.
 *
 * The daemon service is resolved by name from `ServiceManager`; if it isn't up yet (daemon
 * still starting) every call is a graceful no-op returning [STATE_STOPPED] and the caller
 * retries on the next surface/size event.
 */
object ClusterViewMirrorClient {

    private const val TAG = "ClusterViewMirror"

    // MUST match ClusterViewMirrorService.
    private const val SERVICE_NAME = "overdrive_cluster_view"
    private const val DESCRIPTOR = "com.overdrive.app.cluster.IClusterViewMirror"
    private const val FIRST = IBinder.FIRST_CALL_TRANSACTION
    private const val TXN_ATTACH = FIRST
    private const val TXN_REPROJECT = FIRST + 1
    private const val TXN_DETACH = FIRST + 2

    const val STATE_STOPPED = 0
    const val STATE_ACTIVE = 1
    const val STATE_NO_PROJECTION = 2
    const val STATE_UNSUPPORTED = 3
    // App-side only: the daemon mirror service couldn't be resolved from ServiceManager
    // (daemon still starting, an OLD daemon from before an in-place update that never
    // registered it, or addService unsupported on this firmware). Distinct so the UI can
    // tell the user to restart the app/daemon rather than showing a generic failure.
    const val STATE_SERVICE_DOWN = 5

    const val SCALE_FIT = 0
    const val SCALE_FILL = 1
    const val SCALE_ZOOM = 2

    @Volatile private var cached: IBinder? = null
    @Volatile private var cachedDeathRecipient: IBinder.DeathRecipient? = null
    private val nextSessionId = AtomicLong(1L)
    private val newestSessionId = AtomicLong(0L)
    @Volatile private var activeSessionId = 0L
    // The daemon links this process-owned token to death. If the UI crashes or is killed while
    // a Surface is attached, it can tear down the mirror instead of retaining a dead producer.
    // The cast itself has an independent daemon-owned lifecycle and is not owned by this token.
    private val ownerToken = Binder()

    fun newSessionId(): Long {
        val id = nextSessionId.getAndIncrement()
        while (true) {
            val seen = newestSessionId.get()
            if (id <= seen || newestSessionId.compareAndSet(seen, id)) break
        }
        return id
    }

    private fun service(): IBinder? {
        val c = cached
        if (c != null && c.isBinderAlive) return c
        if (c != null) invalidateService(c)
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val get = sm.getMethod("getService", String::class.java)
            val b = get.invoke(null, SERVICE_NAME) as? IBinder
                ?: return null
            val death = IBinder.DeathRecipient { serviceDied(b) }
            try {
                b.linkToDeath(death, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "mirror service died while caching: ${t.message}")
                return null
            }
            cached = b
            cachedDeathRecipient = death
            b
        } catch (t: Throwable) {
            Log.w(TAG, "getService failed: ${t.message}")
            null
        }
    }

    private fun serviceDied(dead: IBinder) {
        synchronized(this) {
            if (cached !== dead) return
            Log.w(TAG, "mirror service binder died")
            cached = null
            cachedDeathRecipient = null
            activeSessionId = 0L
        }
    }

    private fun invalidateService(expected: IBinder) {
        synchronized(this) {
            val current = cached
            if (current !== expected) return
            cachedDeathRecipient?.let { death ->
                try { current.unlinkToDeath(death, 0) } catch (_: Throwable) {}
            }
            cached = null
            cachedDeathRecipient = null
            activeSessionId = 0L
        }
    }

    /**
     * Additive protocol trailer. Daemons predating session support ignore trailing Parcel
     * data; updated daemons use both values to reject delayed calls from an old Fragment or
     * app process without changing the stable transaction numbers or leading wire fields.
     */
    private fun writeSessionIdentity(data: Parcel, sessionId: Long) {
        data.writeStrongBinder(ownerToken)
        data.writeLong(sessionId)
    }

    /** Hand [surface] + the pane [box] (surface px) to the daemon; it (re)creates the mirror
     *  projecting the cluster into that box per [mode]. Returns a STATE_* code. */
    @Synchronized
    fun attach(
        sessionId: Long,
        surface: Surface?,
        bx: Int,
        by: Int,
        bw: Int,
        bh: Int,
        mode: Int
    ): Int {
        if (surface == null || !surface.isValid || bw <= 0 || bh <= 0) return STATE_STOPPED
        if (sessionId != newestSessionId.get()) return STATE_STOPPED
        val svc = service() ?: return STATE_SERVICE_DOWN
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(mode)
            data.writeInt(bx); data.writeInt(by); data.writeInt(bw); data.writeInt(bh)
            data.writeInt(1)
            surface.writeToParcel(data, 0)
            writeSessionIdentity(data, sessionId)
            check(svc.transact(TXN_ATTACH, data, reply, 0)) {
                "attach transaction not handled"
            }
            reply.readException()
            reply.readInt().also { state ->
                activeSessionId = if (state == STATE_ACTIVE) sessionId else 0L
            }
        } catch (t: Throwable) {
            Log.w(TAG, "attach transact failed: ${t.message}")
            invalidateService(svc)
            STATE_SERVICE_DOWN
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** Re-project the LIVE mirror into a new pane [box] (resize fast path — no teardown). */
    @Synchronized
    fun reproject(sessionId: Long, bx: Int, by: Int, bw: Int, bh: Int, mode: Int): Int {
        if (bw <= 0 || bh <= 0) return STATE_STOPPED
        if (sessionId != newestSessionId.get() || activeSessionId != sessionId) {
            return STATE_STOPPED
        }
        val svc = service() ?: return STATE_SERVICE_DOWN
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(mode)
            data.writeInt(bx); data.writeInt(by); data.writeInt(bw); data.writeInt(bh)
            writeSessionIdentity(data, sessionId)
            check(svc.transact(TXN_REPROJECT, data, reply, 0)) {
                "reproject transaction not handled"
            }
            reply.readException()
            reply.readInt().also { state ->
                if (state != STATE_ACTIVE && activeSessionId == sessionId) {
                    activeSessionId = 0L
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reproject transact failed: ${t.message}")
            invalidateService(svc)
            STATE_SERVICE_DOWN
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /** Tear the mirror down (view gone / cast stopped). Best-effort. */
    @Synchronized
    fun detach(sessionId: Long) {
        // The synchronous attach normally makes activeSessionId authoritative. Still send a
        // cleanup for the newest session when local state is uncertain (for example, a reply
        // failed after the daemon adopted the Surface). The daemon's scoped identity check
        // makes this harmless when it has no matching mirror. Older sessions remain suppressed
        // so a delayed Fragment cannot detach a replacement, including against a legacy daemon
        // that ignores the additive session trailer.
        if (activeSessionId != sessionId && newestSessionId.get() != sessionId) return
        val svc = service()
        if (svc == null) {
            activeSessionId = 0L
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            writeSessionIdentity(data, sessionId)
            check(svc.transact(TXN_DETACH, data, reply, 0)) {
                "detach transaction not handled"
            }
            reply.readException()
        } catch (t: Throwable) {
            Log.w(TAG, "detach transact failed: ${t.message}")
            invalidateService(svc)
        } finally {
            if (activeSessionId == sessionId) activeSessionId = 0L
            data.recycle(); reply.recycle()
        }
    }
}
