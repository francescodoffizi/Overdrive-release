package com.overdrive.app.services;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * App-process Bluetooth-connection watcher. The daemon (UID 2000, synthetic Context)
 * cannot reliably read Bluetooth — the framework {@link BluetoothAdapter} proxies and a
 * {@code dumpsys bluetooth_manager} parse are both flaky from a shell UID; Bluetooth is
 * only reliably readable from a normal app process. So this runs in the real app process
 * (started from {@link KeepAliveAccessibilityService}, which is always alive) and RELAYS
 * each connection transition to the daemon via {@link DaemonHttpClient} →
 * {@code POST /api/automations/event}, exactly like {@link CallStateMonitor}. The daemon
 * publishes the whitelisted {@code btState} / {@code btDeviceName} automation events (see
 * {@code Automations.publishExternalEvent}), enabling "when my phone connects" / "only
 * while <name> is connected" rules.
 *
 * <p><b>Detection.</b> Ground truth is resolved by reflecting the hidden
 * {@code BluetoothAdapter.getConnectDevices()} (note the spelling — no "e") and treating a
 * non-empty result as connected, reading the device's {@code getName()} (falling back to
 * {@code getAddress()}). When that hidden method is absent we fall back to iterating
 * {@code getBondedDevices()} + the hidden {@code BluetoothDevice.isConnected()}. The
 * connect/disconnect broadcasts ({@code ACL_CONNECTED} / {@code ACL_DISCONNECTED} /
 * adapter {@code STATE_CHANGED}) are only used as a cheap wake signal — the authoritative
 * state is always re-resolved by the reflection read.
 *
 * <p>Singleton + idempotent {@link #start}: the a11y service can re-bind and call
 * start() repeatedly; a second call is a no-op while already registered. Every read is
 * best-effort and never throws, so a Bluetooth-stack hiccup can't disrupt the process.
 */
public final class BluetoothStateMonitor {

    private static final String TAG = "BluetoothStateMonitor";
    private static BluetoothStateMonitor instance;

    private final Context appContext;
    private BroadcastReceiver receiver;
    // Last values relayed to the daemon, to dedupe identical consecutive relays (the
    // daemon's Automations.update is edge-gated anyway, but this saves the HTTP round-trip).
    private volatile String lastState;      // "on" / "off"
    private volatile String lastName;       // device name or ""
    // Cached hidden Method handles (resolve once). null after a failed probe.
    private volatile Method getConnectDevices;
    private volatile boolean getConnectDevicesProbed;
    private volatile Method deviceIsConnected;
    private volatile boolean deviceIsConnectedProbed;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bt-state-relay");
        t.setDaemon(true);
        return t;
    });

    // Low-cadence re-assert: the daemon holds BT state only in an in-memory map that a
    // daemon-only restart wipes; the broadcast receiver won't re-fire without a physical BT
    // edge, and relay() dedups on 2xx — so without this a post-restart daemon could sit with
    // no BT state until the next connect/disconnect. This periodically re-publishes current
    // truth with the dedup BYPASSED. It's cheap and safe: the daemon's update() is edge-gated,
    // so a same-value re-assert fires no trigger — it only reseeds the map.
    private static final long REASSERT_SECONDS = 60;
    private final ScheduledExecutorService reassert = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bt-state-reassert");
        t.setDaemon(true);
        return t;
    });

    // ── BT-automation gate ────────────────────────────────────────────────────
    // Whether any ENABLED automation references a bluetooth trigger. Answered by
    // the daemon over the local HTTP API (GET /api/automations/list) because the
    // automations live in /data/local/tmp/.automations, which is daemon-owned and
    // not reliably readable from the app UID. Cached with a TTL so the gate costs
    // at most one small loopback GET per TTL, not one per tick.
    private static final long REASSERT_GATE_TTL_MS = 5L * 60_000L;
    private volatile long gateCheckedAtMs = 0L;
    // FAIL OPEN: default true so that until we have a definitive answer (or if the
    // daemon is unreachable) the re-assert behaves exactly as it did before this
    // gate existed. Losing the self-heal would be a silent correctness regression;
    // an extra 60s POST is merely wasteful.
    private volatile boolean gateBtInUse = true;

    /**
     * True when the periodic re-assert is worth doing. Re-queries the daemon at
     * most once per {@link #REASSERT_GATE_TTL_MS}; on any error the cached value
     * is kept (and the initial value is {@code true}), so this can only ever
     * reduce work when we are CERTAIN no bluetooth automation is configured.
     */
    private boolean btAutomationInUse() {
        long now = System.currentTimeMillis();
        if (now - gateCheckedAtMs < REASSERT_GATE_TTL_MS) return gateBtInUse;
        gateCheckedAtMs = now;
        HttpURLConnection conn = null;
        try {
            conn = DaemonHttpClient.open("/api/automations/list", "GET", 2000, 3000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return gateBtInUse;  // keep prior answer
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            // Substring test rather than a full schema walk: the condition keys are
            // the literal strings "btState" / "btDeviceName" (BydEvent.java:115-116)
            // and they appear in the serialized automation only when a rule actually
            // references them. Erring toward "in use" on any ambiguity is safe.
            String body = sb.toString();
            boolean inUse = body.contains("btState") || body.contains("btDeviceName");
            if (gateBtInUse != inUse) {
                Log.i(TAG, "BT re-assert gate: bluetoothAutomationInUse=" + inUse);
            }
            gateBtInUse = inUse;
            return inUse;
        } catch (Throwable t) {
            // Daemon not up / transport hiccup — keep the previous (fail-open) answer.
            return gateBtInUse;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private BluetoothStateMonitor(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    /** Start the monitor (idempotent). Safe to call from onServiceConnected each bind. */
    public static synchronized void start(Context ctx) {
        if (instance != null) return;
        if (ctx == null) return;
        BluetoothStateMonitor m = new BluetoothStateMonitor(ctx);
        if (m.begin()) {
            instance = m;
        }
    }

    private boolean begin() {
        try {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    // Any BT edge — re-resolve ground truth and relay if it changed.
                    publish(false);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            appContext.registerReceiver(receiver, filter);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver failed: " + t.getMessage());
            return false;
        }
        // Seed the daemon with the current state now (the receiver only fires on future
        // edges, so without this a phone already connected at start would never publish).
        // force=true so the initial seed always reaches the daemon even if a prior instance
        // had latched the same values.
        publish(true);
        // Periodic re-assert (force-resend) as the daemon-restart safety net —
        // but ONLY while an enabled automation actually uses a Bluetooth trigger.
        //
        // BT state feeds nothing except automation conditions (see
        // Automations.publishExternalEvent / BydEvent.BT_STATE), so on a head unit
        // with no BT rule this timer was pure waste: reflection into
        // BluetoothAdapter + the hidden getConnectDevices(), then TWO HTTP POSTs
        // with dedup deliberately bypassed, every 60s forever — 1,440 wakeups and
        // 2,880 POSTs a day for a value nothing reads.
        //
        // The gate is re-evaluated on every tick (cheap: a cached answer refreshed
        // at most once per REASSERT_GATE_TTL_MS), so enabling a BT automation later
        // starts the re-assert within one tick — no app restart, no lost self-heal.
        // The BroadcastReceiver above stays registered unconditionally, so a real
        // BT connect/disconnect still relays immediately even while the timer is
        // gated off; the timer only covers the daemon-restart-wiped-its-map case.
        try {
            reassert.scheduleWithFixedDelay(() -> {
                if (btAutomationInUse()) publish(true);
            }, REASSERT_SECONDS, REASSERT_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "reassert schedule failed: " + t.getMessage());
        }
        Log.i(TAG, "bluetooth-state monitor started");
        return true;
    }

    /**
     * Re-resolve connection state + name and relay to the daemon.
     * @param force when true, bypass the local dedup and re-send even if the values are
     *     unchanged (used by the initial seed + the periodic re-assert so a wiped daemon
     *     state map is re-seeded); a broadcast-driven publish passes false to skip no-ops.
     */
    private void publish(boolean force) {
        boolean connected;
        String name;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                connected = false;
                name = "";
            } else {
                BluetoothDevice dev = firstConnectedDevice(adapter);
                connected = dev != null;
                name = connected ? deviceName(dev) : "";
            }
        } catch (Throwable t) {
            // Never let a stack hiccup crash the always-alive a11y process; skip this edge.
            Log.w(TAG, "publish read failed: " + t.getMessage());
            return;
        }
        // ORDER MATTERS on connect: relay the device NAME before the connection STATE.
        // btState is the edge that triggers rule evaluation on the daemon; if it arrived
        // first, a zero-delay "connects AND name == X" rule could evaluate the name against
        // the stale/empty value in the gap before btDeviceName lands. Pushing the name first
        // means it's already in the daemon state map when the state edge fires. (The two
        // relays run on the single-threaded io executor, so this ordering is preserved.)
        relay("btDeviceName", name, force);
        relay("btState", connected ? "on" : "off", force);
    }

    /**
     * The first actually-connected device, via the hidden {@code getConnectDevices()}
     * (the primary path), falling back to a {@code getBondedDevices()}
     * scan gated on the hidden {@code BluetoothDevice.isConnected()}. Returns null when
     * nothing is connected or nothing can be resolved.
     */
    private BluetoothDevice firstConnectedDevice(BluetoothAdapter adapter) {
        // Primary: hidden BluetoothAdapter.getConnectDevices() → Set<BluetoothDevice>.
        Method m = resolveGetConnectDevices(adapter);
        if (m != null) {
            try {
                Object r = m.invoke(adapter);
                if (r instanceof Set) {
                    for (Object o : (Set<?>) r) {
                        if (o instanceof BluetoothDevice) return (BluetoothDevice) o;
                    }
                    return null; // resolvable and empty → definitively nothing connected
                }
            } catch (Throwable ignored) {
                // fall through to the bonded-scan fallback
            }
        }
        // Fallback: bonded devices + hidden BluetoothDevice.isConnected().
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                Method isConn = resolveDeviceIsConnected();
                if (isConn != null) {
                    for (BluetoothDevice d : bonded) {
                        try {
                            if (Boolean.TRUE.equals(isConn.invoke(d))) return d;
                        } catch (Throwable ignored) { /* try next */ }
                    }
                }
            }
        } catch (Throwable ignored) {
            // getBondedDevices may throw — treat as "unknown → nothing connected".
        }
        return null;
    }

    /** Device friendly name, or its MAC address when the name is unavailable (never null). */
    private static String deviceName(BluetoothDevice dev) {
        try {
            String n = dev.getName();
            if (n != null && !n.isEmpty()) return n;
            String a = dev.getAddress();
            return a != null ? a : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private Method resolveGetConnectDevices(BluetoothAdapter adapter) {
        if (getConnectDevicesProbed) return getConnectDevices;
        try {
            // Hidden AOSP/BYD adapter method — spelling verified against the reference
            // apps: getConnectDevices (no trailing "e" on "Connect").
            getConnectDevices = adapter.getClass().getMethod("getConnectDevices");
        } catch (Throwable t) {
            getConnectDevices = null;
        }
        getConnectDevicesProbed = true;
        return getConnectDevices;
    }

    private Method resolveDeviceIsConnected() {
        if (deviceIsConnectedProbed) return deviceIsConnected;
        try {
            deviceIsConnected = BluetoothDevice.class.getMethod("isConnected");
        } catch (Throwable t) {
            deviceIsConnected = null;
        }
        deviceIsConnectedProbed = true;
        return deviceIsConnected;
    }

    /**
     * Relay one whitelisted event to the daemon, deduping identical consecutive values.
     * The value is JSON-encoded (a device name may contain quotes / backslashes / unicode),
     * so — unlike {@link CallStateMonitor}'s fixed-enum body — this must not concatenate.
     *
     * <p>The dedup check AND the "last relayed" memory update both run on the single-threaded
     * {@link #io} executor, and the memory is committed ONLY after a confirmed 2xx. This
     * matters at startup: this monitor is started by the same a11y service that also kicks
     * daemon startup, so the first {@code publish()} can race ahead of the daemon listening on
     * the HTTP port. Committing the dedup memory before the POST (as a naive relay would) would
     * suppress every future retry of a seed the daemon never actually received; committing only
     * on success means a failed relay is naturally re-attempted on the next edge until it lands.
     *
     * @param force when true, skip the dedup and re-send even if the value is unchanged (the
     *     initial seed + periodic re-assert, to re-seed a daemon whose in-memory state was
     *     wiped by a restart). The daemon's update() is edge-gated, so a same-value re-assert
     *     reseeds the map without firing any trigger.
     */
    private void relay(String event, String value, boolean force) {
        final String v = (value == null) ? "" : value;
        io.submit(() -> {
            // Dedup on the io thread (single writer → no race with the success-commit below).
            // Skipped when force=true so a re-assert always reaches a possibly-wiped daemon.
            if (!force) {
                if ("btState".equals(event)) { if (v.equals(lastState)) return; }
                else if ("btDeviceName".equals(event)) { if (v.equals(lastName)) return; }
            }

            final String body;
            try {
                body = new JSONObject().put("event", event).put("value", v).toString();
            } catch (Throwable t) {
                Log.w(TAG, "relay body build failed: " + t.getMessage());
                return;
            }
            HttpURLConnection conn = null;
            try {
                conn = DaemonHttpClient.open("/api/automations/event", "POST", 2000, 3000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                // Commit the dedup memory only once the daemon has actually accepted it, so a
                // startup-race failure retries on the next edge instead of being latched.
                if (code >= 200 && code < 300) {
                    if ("btState".equals(event)) lastState = v;
                    else if ("btDeviceName".equals(event)) lastName = v;
                }
                Log.i(TAG, "relayed " + event + "=" + v + " -> HTTP " + code);
            } catch (Throwable t) {
                Log.w(TAG, "relay failed (" + event + "): " + t.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
