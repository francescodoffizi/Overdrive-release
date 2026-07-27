package com.overdrive.app.mqtt;

import com.overdrive.app.logging.DaemonLogger;

import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * MQTT publisher for a single broker connection.
 *
 * Manages the Paho MQTT client lifecycle: connect, publish, reconnect, disconnect.
 * Each instance is bound to one MqttConnectionConfig and publishes telemetry
 * JSON payloads to the configured topic.
 *
 * Proxy-aware: uses ProxyHelper to route through sing-box when available.
 * Reconnection: automatic with exponential backoff (5s → 10s → 20s → ... → 300s).
 *
 * Thread safety: all public methods are synchronized on the instance.
 */
public class MqttPublisherService implements MqttCallback {

    private static final String TAG = "MqttPublisher";
    private final DaemonLogger logger;

    // Backoff constants
    private static final int BACKOFF_BASE_SECONDS = 5;
    private static final int BACKOFF_CAP_SECONDS = 300;

    // How long to wait for an enabled-but-not-yet-up Tailscale proxy before falling back to a
    // direct dial. Covers the boot window (tailscaled still binding) without permanently refusing
    // a directly-reachable broker if the proxy is genuinely dead.
    private static final long PROXY_WARMUP_GRACE_MS = 60_000L;

    // Connection config
    private final MqttConnectionConfig config;
    private final String deviceId;

    // Paho MQTT client
    private MqttClient client;
    private volatile boolean running = false;
    private volatile boolean connected = false;

    // Stats
    private volatile long totalPublishes = 0;
    private volatile long failedPublishes = 0;
    private volatile long lastPublishTime = 0;
    private volatile int consecutiveFailures = 0;
    private volatile String lastError = null;
    // Proxy warm-up tracking (issue #182): when the first defer started, and whether we've already
    // logged this warm-up so the health loop doesn't spam an identical warning every cycle.
    private volatile long proxyWaitStartMs = 0L;
    private volatile boolean loggedProxyWait = false;

    // Change detection (report-by-exception) + Home Assistant discovery state.
    // TelemetryDiffer is documented single-thread-owned: ALL access to it must
    // happen on the scheduler thread inside the synchronized publishTelemetry().
    // The Paho callback thread (messageArrived) must NOT touch it directly —
    // it sets forceFullResend instead, and the publish thread performs reset().
    private final TelemetryDiffer differ = new TelemetryDiffer();
    private volatile boolean forceFullResend = false;
    private volatile boolean discoveryAnnounced = false;
    // Discoverable keys already covered by the announced bundle. Grows as fields appear so a
    // late-populating field (e.g. hv_pack_v, which needs cells + pack capacity, so it shows up
    // after the first publish) triggers a re-announce instead of never getting a component.
    private final java.util.Set<String> announcedKeys = new java.util.HashSet<>();
    // State-transition flush. On any mode edge (ACC on/off, charging start/stop) we flush a
    // full snapshot for a few cycles so the new state survives a single lost publish. Owned
    // by the publish thread, same as the differ.
    private boolean stateInit = false;
    private boolean prevAccOn = false;
    private boolean prevCharging = false;
    private int stateFlushCycles = 0;
    private volatile MqttCommandRouter commandRouter;
    private volatile String haVin = null;
    private volatile String haModel = null;
    private volatile String haSwVersion = null;

    public MqttPublisherService(MqttConnectionConfig config, String deviceId) {
        this.config = config;
        this.deviceId = deviceId;
        this.logger = DaemonLogger.getInstance(TAG + "-" + config.id);
    }

    // ==================== LIFECYCLE ====================

    /**
     * Connect to the MQTT broker.
     * @return true if connected successfully
     */
    public synchronized boolean connect() {
        if (connected && client != null && client.isConnected()) {
            return true;
        }

        String brokerUri = config.getBrokerUri();
        if (brokerUri.isEmpty()) {
            lastError = "No broker URL configured";
            logger.error(lastError);
            return false;
        }

        // Mark the connection active as soon as it has a broker to attempt, independent of whether
        // this first connect succeeds. Otherwise a failed OR deferred initial connect leaves
        // running=false, and MqttConnectionManager's health loop bails on !isRunning() and never
        // reschedules — the connection stays dead until a daemon restart. That gate (not the direct
        // dial alone) is the real "never recovers" root cause behind #182, and the reason the
        // "will retry on first publish" contract at the call site never actually held.
        running = true;

        // issue #182: when the Tailscale SOCKS proxy is ENABLED the broker is normally reachable
        // ONLY through it (e.g. a LAN broker behind a subnet router while the car is on cellular).
        // A DIRECT dial in that state can never succeed off Wi-Fi and strands the connection, so
        // hold off while the proxy is warming up (tailscaled still binding at boot / after a link
        // change) instead of falling through to the direct-socket path below. We do NOT bump
        // consecutiveFailures — the health loop re-probes at the min-interval floor and connects the
        // instant the proxy binds. Bounded by PROXY_WARMUP_GRACE_MS so a genuinely dead proxy can't
        // permanently refuse a directly-reachable broker: after the grace window we fall through and
        // try a direct dial as a last resort.
        if (ProxyHelper.isProxyExpected() && !ProxyHelper.isProxyAvailable()) {
            long now = System.currentTimeMillis();
            if (proxyWaitStartMs == 0L) proxyWaitStartMs = now;
            long waitedMs = now - proxyWaitStartMs;
            if (waitedMs < PROXY_WARMUP_GRACE_MS) {
                connected = false;
                lastError = "Tailscale proxy enabled but not reachable yet (127.0.0.1:"
                        + ProxyHelper.getTailscaleProxyPort() + ") — deferring connect until proxy is up";
                if (!loggedProxyWait) {
                    logger.warn(lastError);
                    loggedProxyWait = true;
                }
                return false;
            }
            logger.warn("Tailscale proxy still unreachable after " + (waitedMs / 1000)
                    + "s — attempting a direct connect as a fallback");
        }
        // Proxy is up (or the grace window elapsed) — clear the warm-up state and proceed to connect.
        proxyWaitStartMs = 0L;
        loggedProxyWait = false;

        String effectiveClientId = config.getEffectiveClientId(deviceId);

        // Tear down any stale client before opening a new one. Without this, a reconnect after a
        // dropped/half-open connection leaves the old Paho client (same clientId) abandoned but not
        // closed — the broker then sees two clients with the same id and the new connect can fail
        // with "Client is connected" (Paho reason 32100). Closing it first releases the clientId.
        if (client != null) {
            try { if (client.isConnected()) client.disconnect(1000); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
            client = null;
        }

        MqttClient newClient = null;
        try {
            // Create client with in-memory persistence (no filesystem needed)
            newClient = new MqttClient(brokerUri, effectiveClientId, new MemoryPersistence());
            newClient.setCallback(this);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);
            options.setAutomaticReconnect(false); // We handle reconnect ourselves for proxy awareness

            // Auth
            if (config.username != null && !config.username.isEmpty()) {
                options.setUserName(config.username);
            }
            if (config.password != null && !config.password.isEmpty()) {
                options.setPassword(config.password.toCharArray());
            }

            // --- Protocol-Aware Socket Routing ---
            // SSL URIs (ssl://, wss://) require an SSLSocketFactory for the TLS handshake.
            // Applying a raw SocketFactory to an SSL connection causes Paho to send
            // unencrypted MQTT packets to a port expecting a TLS Client Hello → instant drop.
            boolean isSsl = config.isSsl();
            boolean isWebSocket = brokerUri.startsWith("ws://") || brokerUri.startsWith("wss://");

            if (ProxyHelper.isProxyAvailable()) {
                if (isWebSocket && isSsl) {
                    // WSS + Proxy: Paho 1.2.0+ has a bug (eclipse/paho.mqtt.java#573) where
                    // WebSocketSecureNetworkModule bypasses the SocketFactory and calls
                    // new Socket() directly for the initial TCP connection. Our ProxiedSslSocketFactory
                    // never gets invoked, so the SOCKS tunnel is never established.
                    //
                    // Workaround: set JVM-level SOCKS proxy properties so that ALL sockets
                    // (including Paho's internal new Socket()) route through sing-box.
                    // Then provide the appropriate SSLSocketFactory for the TLS layer only.
                    System.setProperty("socksProxyHost", "127.0.0.1");
                    System.setProperty("socksProxyPort", String.valueOf(ProxyHelper.getProxyPort()));
                    if (config.trustAllCerts) {
                        options.setSocketFactory(ProxyHelper.getTrustAllSslFactory());
                    } else {
                        options.setSocketFactory((javax.net.ssl.SSLSocketFactory)
                                javax.net.ssl.SSLSocketFactory.getDefault());
                    }
                } else if (isSsl) {
                    // SSL (non-WebSocket) + Proxy: our ProxiedSslSocketFactory works correctly
                    // because Paho's SSLNetworkModule calls factory.createSocket(host, port).
                    options.setSocketFactory(ProxyHelper.getProxiedSslSocketFactory(config.trustAllCerts));
                } else if (isWebSocket) {
                    // WS (plain) + Proxy: same Paho bug applies — use system SOCKS properties.
                    System.setProperty("socksProxyHost", "127.0.0.1");
                    System.setProperty("socksProxyPort", String.valueOf(ProxyHelper.getProxyPort()));
                } else {
                    // Plain TCP + Proxy: ProxiedSocketFactory works fine.
                    options.setSocketFactory(ProxyHelper.getMqttSocketFactory());
                }
            } else {
                // No proxy — clear any leftover system SOCKS properties from a previous
                // connection attempt where the proxy was active.
                System.clearProperty("socksProxyHost");
                System.clearProperty("socksProxyPort");

                if (isSsl) {
                    if (config.trustAllCerts) {
                        // Direct SSL with blind trust (Home Assistant self-signed certs)
                        options.setSocketFactory(ProxyHelper.getTrustAllSslFactory());
                    } else {
                        // Direct SSL with system trust store (public CAs)
                        options.setSocketFactory(javax.net.ssl.SSLSocketFactory.getDefault());
                    }
                }
            }
            // else: plain TCP, no proxy — Paho uses its default SocketFactory

            // --- Last Will and Testament (LWT) ---
            // When the broker detects an ungraceful disconnect (car drives into a tunnel,
            // head unit loses power), it publishes "offline" on our behalf so consumers
            // don't show stale telemetry.
            String lwtTopic = config.topic + "/availability";
            byte[] lwtPayload = "offline".getBytes();
            options.setWill(lwtTopic, lwtPayload, 1, true);

            logger.info("Connecting to " + brokerUri + " as " + effectiveClientId
                    + " (proxy=" + ProxyHelper.isProxyAvailable()
                    + ", ssl=" + isSsl
                    + ", ws=" + isWebSocket
                    + ", trustAll=" + config.trustAllCerts + ")");

            newClient.connect(options);
            client = newClient;
            connected = true;
            running = true;
            consecutiveFailures = 0;
            lastError = null;

            // Publish "online" availability immediately after successful connect.
            // This pairs with the LWT "offline" — consumers can subscribe to
            // <topic>/availability to track connection state.
            try {
                client.publish(lwtTopic, "online".getBytes(), 1, true);
            } catch (MqttException e) {
                logger.warn("Failed to publish availability online: " + e.getMessage());
            }

            // Home Assistant: re-announce discovery after every (re)connect, and listen for
            // HA's birth message so we re-announce + resend state when HA restarts.
            if (config.isHomeAssistant()) {
                discoveryAnnounced = false;
                try {
                    client.subscribe(HomeAssistantDiscovery.statusTopic(config.discoveryPrefix), 0);
                } catch (MqttException e) {
                    logger.warn("HA status subscribe failed: " + e.getMessage());
                }
                // Vehicle control (local SDK only): subscribe to inbound command topics
                // <base>/<key>/set and <base>/<key>/<sub>/set (composite climate/cover).
                if (config.isControlEnabled()) {
                    try {
                        client.subscribe(config.topic + "/+/set", config.qos);
                        client.subscribe(config.topic + "/+/+/set", config.qos);
                        logger.info("Subscribed to vehicle-control command topics under " + config.topic);
                    } catch (MqttException e) {
                        logger.warn("Control command subscribe failed: " + e.getMessage());
                    }
                }
            }

            // Inbound automation triggers: <base>/automation/<channel>. Independent of the
            // HA/control gates above — an external broker message on this subtree fires an
            // OverDrive automation (see messageArrived → Automations.publishMqttTrigger).
            // Re-subscribed on every (re)connect since connect() is the reconnect path.
            // Cheap and inert when no automation watches the channel.
            try {
                client.subscribe(config.topic + "/automation/+", config.qos);
                logger.info("Subscribed to inbound automation-trigger topics under " + config.topic + "/automation/");
            } catch (MqttException e) {
                logger.warn("Automation-trigger subscribe failed: " + e.getMessage());
            }

            logger.info("Connected to " + brokerUri);
            return true;

        } catch (MqttException e) {
            connected = false;
            consecutiveFailures++;

            // Paho's error 32103 (SERVER_CONNECT_ERROR) is a black hole — it hides the
            // real cause (SSL cert rejection, socket timeout, etc.) behind a generic message.
            // Walk the cause chain to extract the actual underlying exception.
            String rootCause = extractRootCause(e);
            lastError = "Connect failed (reason=" + e.getReasonCode() + ") Cause: " + rootCause;
            logger.error(lastError);

            // Invalidate proxy cache on connection failure — proxy state may have changed
            ProxyHelper.invalidateCache();

            // Close the client that failed to connect to avoid resource leak
            if (newClient != null) {
                try { newClient.close(); } catch (MqttException ignored) {}
            }
            return false;
        } catch (Throwable t) {
            // Catch ExceptionInInitializerError (Paho logging class not found) and any other errors
            connected = false;
            consecutiveFailures++;
            lastError = "Connect error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            if (t.getCause() != null) {
                lastError += " (caused by: " + t.getCause().getMessage() + ")";
            }
            logger.error(lastError);

            if (newClient != null) {
                try { newClient.close(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    /**
     * Disconnect from the MQTT broker.
     */
    public synchronized void disconnect() {
        running = false;
        connected = false;

        if (commandRouter != null) {
            commandRouter.shutdown();
            commandRouter = null;
        }

        if (client != null) {
            try {
                if (client.isConnected()) {
                    // Publish graceful "offline" before disconnect.
                    // The LWT only fires on ungraceful drops — this covers clean shutdowns.
                    try {
                        String lwtTopic = config.topic + "/availability";
                        client.publish(lwtTopic, "offline".getBytes(), 1, true);
                    } catch (MqttException e) {
                        logger.warn("Failed to publish availability offline: " + e.getMessage());
                    }
                    client.disconnect(5000);
                }
            } catch (MqttException e) {
                logger.warn("Disconnect error: " + e.getMessage());
            } finally {
                try { client.close(); } catch (MqttException ignored) {}
                client = null;
            }
        }

        // NOTE: the JVM-level socksProxyHost/Port properties are process-global and
        // shared by every connection (all route through the same sing-box). We must
        // NOT clear them here — a sibling WS+proxy connection may still be relying on
        // them, and one connection's disconnect would silently break the others'
        // sockets. connect() re-asserts or clears them authoritatively from the
        // current (global) proxy state on each (re)connect, and the manager clears
        // them once on full shutdown (stopAll). See MqttConnectionManager.stopAll().

        logger.info("Disconnected from " + config.name + " (" + config.getBrokerUri() + ")");
    }

    /** Latest vehicle identity used to build the HA discovery device block. */
    public void setHaMeta(String vin, String model, String swVersion) {
        this.haVin = vin;
        this.haModel = model;
        this.haSwVersion = swVersion;
    }

    /**
     * Publish a telemetry snapshot, applying change detection and the min/max
     * interval window. Behaviour depends on the connection mode:
     *
     *  - Home Assistant mode: per-field retained topics (only changed fields, or
     *    everything on heartbeat/first), plus a one-time device-bundle discovery
     *    announce. No aggregate JSON.
     *  - Aggregate mode: the full JSON blob to the configured topic, but only when
     *    a backing value changed (or on heartbeat) — retain stays valid because we
     *    always send a complete snapshot.
     *
     * @return true if nothing went wrong (a deliberate skip also returns true)
     */
    public synchronized boolean publishTelemetry(JSONObject snapshot) {
        if (!running) return false;

        // Apply a deferred reset requested by the Paho callback thread (HA birth).
        // Done here, on the publish thread, so the differ's HashMap is only ever
        // mutated by one thread — avoids the ConcurrentModificationException /
        // lost-update race that a direct differ.reset() in messageArrived caused.
        if (forceFullResend) {
            forceFullResend = false;
            differ.reset();
        }

        long now = System.currentTimeMillis();

        // Read the vehicle state up front — it drives both the state-transition full-sync AND the
        // per-state heartbeat ceiling (parked / charging overrides). Cheap in-memory reads; default
        // to the previous cycle's values if a monitor isn't ready so the interval stays stable.
        boolean carOn = prevAccOn, charging = prevCharging;
        try { carOn = com.overdrive.app.monitor.AccMonitor.isAccOn(); } catch (Throwable ignored) {}
        try { charging = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging(); } catch (Throwable ignored) {}

        long minMs = Math.max(1, config.minIntervalSeconds) * 1000L;
        // Heartbeat ceiling is state-aware: while charging (or, failing that, while parked) the
        // config's per-state override replaces maxIntervalSeconds. effectiveMaxIntervalSeconds
        // floors the result at minIntervalSeconds, so maxMs >= minMs always holds.
        long maxMs = config.effectiveMaxIntervalSeconds(carOn, charging) * 1000L;

        Set<String> changed = differ.changedKeys(snapshot);
        boolean first = differ.lastSendTimeMs() == 0;
        // Heartbeat: with heartbeatSendAll, fire on a fixed cadence since the last FULL sync
        // (immune to change-only partial publishes resetting the clock — the starvation bug).
        boolean heartbeat = (config.heartbeatSendAll ? differ.fullSyncElapsedMs(now)
                                                     : differ.elapsedMs(now)) >= maxMs;

        // Full-sync on every state-mode transition (ACC on↔off, charging start↔stop) so the new
        // state survives even if a single change-publish is lost at a network handoff. Cheap:
        // fires only on edges, a few full sends each. Inert until the monitors first report.
        if (config.flushOnStateChange && stateInit && (carOn != prevAccOn || charging != prevCharging)) {
            stateFlushCycles = 5;
        }
        prevAccOn = carOn; prevCharging = charging; stateInit = true;
        boolean flushNow = stateFlushCycles > 0;

        // Rate-limit floor: never transmit more often than the min interval, unless this is the
        // first publish, a heartbeat, or a state-transition flush.
        if (!first && !heartbeat && !flushNow && differ.elapsedMs(now) < minMs) {
            return true;
        }

        if (config.isHomeAssistant()) {
            if (!ensureConnected()) return false;
            // Re-announce if a discoverable field has appeared that the last bundle didn't cover
            // (fields populate at different times; the first publish doesn't have them all yet).
            if (discoveryAnnounced && !announcedKeys.containsAll(discoverableKeys(snapshot))) {
                discoveryAnnounced = false;
            }
            if (!discoveryAnnounced) announceDiscovery(snapshot);

            boolean sendAll = first || heartbeat || !config.changeOnly || flushNow;
            Set<String> keys = sendAll ? discoverableKeys(snapshot) : changed;
            if (flushNow && stateFlushCycles > 0) stateFlushCycles--;
            if (!sendAll && keys.isEmpty()) return true;

            boolean ok = true;
            for (String k : keys) {
                if (!TelemetryFieldCatalog.isPublishable(k)) continue;
                Object v = snapshot.opt(k);
                if (v == null || v == JSONObject.NULL || v instanceof JSONArray) continue;
                if (!publishString(HomeAssistantDiscovery.stateTopic(config.topic, k),
                        String.valueOf(v), true, config.qos)) {
                    ok = false;
                    break;
                }
            }
            if (ok && snapshot.has("lat") && snapshot.has("lon")
                    && (sendAll || changed.contains("lat") || changed.contains("lon"))) {
                publishLocation(snapshot);
            }
            if (ok) {
                differ.markKeysSent(snapshot, keys, now);
                if (sendAll) differ.markFullSync(now);
            }
            return ok;
        }

        // Aggregate mode — full snapshot. Honour the same full-sync triggers (heartbeat /
        // state-flush) so a parked snapshot still goes out even under changeOnly.
        boolean shouldSend = first || heartbeat || flushNow
                || differ.shouldPublish(!changed.isEmpty(), config.changeOnly, now, minMs, maxMs);
        if (flushNow && stateFlushCycles > 0) stateFlushCycles--;
        if (!shouldSend) return true;
        if (!publishString(config.topic, snapshot.toString(), config.retainMessages, config.qos)) {
            return false;
        }
        differ.markAllSent(snapshot, now);
        differ.markFullSync(now);
        return true;
    }

    /**
     * Publish a JSON payload to the configured topic (backward-compatible helper —
     * no change gating). Prefer {@link #publishTelemetry(JSONObject)}.
     */
    public synchronized boolean publish(JSONObject payload) {
        if (!running) return false;
        return publishString(config.topic, payload.toString(), config.retainMessages, config.qos);
    }

    /**
     * Publish an arbitrary payload to an arbitrary topic — the seam the automation
     * "Publish MQTT" action uses to notify Home Assistant (or any broker consumer). A
     * relative topic (no leading '/') is scoped under this connection's base topic so a
     * user needn't know the full prefix; an absolute topic (leading '/') is used as-is
     * minus the leading slash. Returns false when the connection isn't running so a
     * disabled/absent MQTT setup makes the action a clean no-op. Uses this connection's
     * QoS; retain is caller-chosen (HA state topics want retain=true).
     */
    public synchronized boolean publishToTopic(String topic, String payload, boolean retain) {
        if (!running) return false;
        if (topic == null || topic.isEmpty() || payload == null) return false;
        String full;
        if (topic.startsWith("/")) {
            full = topic.substring(1);
        } else {
            full = config.topic + "/" + topic;
        }
        return publishString(full, payload, retain, config.qos);
    }

    /** Low-level single-message publish with reconnect + stats handling. */
    private boolean publishString(String topic, String payload, boolean retain, int qos) {
        if (!ensureConnected()) {
            failedPublishes++;
            return false;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(qos);
            message.setRetained(retain);
            client.publish(topic, message);

            totalPublishes++;
            lastPublishTime = System.currentTimeMillis();
            consecutiveFailures = 0;
            lastError = null;
            return true;
        } catch (MqttException e) {
            failedPublishes++;
            consecutiveFailures++;
            lastError = "Publish failed: " + e.getMessage();
            logger.warn(lastError);
            connected = false;
            ProxyHelper.invalidateCache();
            return false;
        }
    }

    private boolean ensureConnected() {
        if (!running) return false;
        if (connected && client != null && client.isConnected()) return true;
        return connect();
    }

    /**
     * Active connection health check, run every scheduler cycle independent of
     * whether a telemetry publish is actually due.
     *
     * Why this is needed: reconnect is otherwise only attempted as a side effect
     * of {@link #publishString} throwing. But the change-gated publish loop skips
     * idle cycles for up to {@code maxIntervalSeconds} (default 300s) while parked,
     * so a silently-dropped link (NAT/firewall idle-timeout, the ACC-OFF data
     * blackout) is never noticed — and with QoS 0 even the eventual heartbeat
     * publish can succeed into a half-open socket without throwing. The result is
     * a connection that reports "running" but transmits nothing until a manual
     * restart.
     *
     * Paho's keep-alive (30s) flips {@code client.isConnected()} to false / fires
     * {@code connectionLost} within ~keep-alive seconds of a real drop. Polling
     * that here lets the scheduler reconnect promptly instead of waiting for the
     * next heartbeat. A failed reconnect leaves {@code consecutiveFailures}
     * incremented (by {@link #connect}), so the scheduler's backoff spaces out
     * retries rather than hammering a dead broker every cycle.
     *
     * @return true if connected (already, or after a successful reconnect)
     */
    public synchronized boolean ensureAlive() {
        if (!running) return false;
        if (client != null && client.isConnected()) {
            connected = true;
            return true;
        }
        connected = false;
        return connect();
    }

    private Set<String> discoverableKeys(JSONObject snap) {
        Set<String> keys = new HashSet<>();
        Iterator<String> it = snap.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!TelemetryFieldCatalog.isPublishable(k)) continue;
            Object v = snap.opt(k);
            if (v == null || v == JSONObject.NULL || v instanceof JSONArray) continue;
            keys.add(k);
        }
        return keys;
    }

    private void publishLocation(JSONObject snap) {
        try {
            JSONObject loc = new JSONObject();
            loc.put("latitude", snap.optDouble("lat"));
            loc.put("longitude", snap.optDouble("lon"));
            publishString(HomeAssistantDiscovery.locationTopic(config.topic), loc.toString(), true, config.qos);
        } catch (Exception ignored) {}
    }

    /** Publish the retained device-bundle discovery config (HA mode). */
    private void announceDiscovery(JSONObject snapshot) {
        try {
            String topic = HomeAssistantDiscovery.deviceConfigTopic(config.discoveryPrefix, deviceId);
            // Pass announcedKeys as sticky so a field that's momentarily absent from this snapshot
            // (e.g. the derived hv_pack_v) isn't dropped from the bundle on a re-announce — that
            // drop is what left fields Unavailable in HA after a restart.
            String bundle = HomeAssistantDiscovery.buildBundle(deviceId, haVin, haModel, haSwVersion,
                    config.topic, snapshot, announcedKeys, config.isControlEnabled());
            if (publishString(topic, bundle, true, 1)) {
                discoveryAnnounced = true;
                announcedKeys.addAll(discoverableKeys(snapshot));
                logger.info("Published HA discovery bundle to " + topic
                        + " (" + announcedKeys.size() + " keys)");
            }
        } catch (Exception e) {
            logger.warn("HA discovery announce failed: " + e.getMessage());
        }
    }

    /**
     * Remove the HA device (empty retained payload) so toggling discovery off or
     * deleting a connection doesn't orphan entities. Best-effort; needs a live client.
     */
    public synchronized void removeDiscovery(String discoveryPrefix) {
        if (client == null || !client.isConnected()) return;
        try {
            String topic = HomeAssistantDiscovery.deviceConfigTopic(discoveryPrefix, deviceId);
            MqttMessage message = new MqttMessage(new byte[0]);
            message.setRetained(true);
            message.setQos(1);
            client.publish(topic, message);
            logger.info("Removed HA discovery at " + topic);
        } catch (MqttException e) {
            logger.warn("HA discovery remove failed: " + e.getMessage());
        }
    }

    // ==================== MQTT CALLBACK ====================

    @Override
    public void connectionLost(Throwable cause) {
        connected = false;
        lastError = "Connection lost: " + (cause != null ? extractRootCause(cause) : "unknown");
        logger.warn(lastError);
        ProxyHelper.invalidateCache();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // The only thing we subscribe to is HA's birth/status topic. When HA comes
        // back online, re-announce discovery and force a full state resend so its
        // entities repopulate immediately instead of waiting for the next change.
        if (config.isHomeAssistant() && topic != null
                && topic.equals(HomeAssistantDiscovery.statusTopic(config.discoveryPrefix))) {
            String payload = new String(message.getPayload()).trim();
            if ("online".equalsIgnoreCase(payload)) {
                logger.info("Home Assistant birth received — re-announcing discovery");
                discoveryAnnounced = false;
                // Defer the differ.reset() to the publish thread — do NOT touch
                // the differ from this Paho callback thread (it isn't thread-safe).
                forceFullResend = true;
            }
            return;
        }

        // Inbound automation trigger: <base>/automation/<channel>. An external broker
        // message (Home Assistant, Node-RED, …) becomes an automation signal keyed by
        // channel, so a rule can fire on "HA published X to channel Y". Enqueue-only —
        // Automations.publishMqttTrigger does an atomic map CAS + queues to the automation
        // worker, so it's safe and non-blocking on this Paho callback thread (never runs
        // action logic here). Inert at zero cost when no automation watches the channel.
        String autoPrefix = config.topic + "/automation/";
        if (topic != null && topic.startsWith(autoPrefix)) {
            String channel = topic.substring(autoPrefix.length());
            String payload = new String(message.getPayload());
            try {
                com.overdrive.app.automation.Automations.publishMqttTrigger(channel, payload);
            } catch (Throwable t) {
                logger.warn("Inbound MQTT automation trigger failed: " + t.getMessage());
            }
            return;
        }

        // Inbound vehicle-control command: <base>/<key>/set or <base>/<key>/<sub>/set.
        if (config.isControlEnabled() && topic != null
                && topic.startsWith(config.topic + "/") && topic.endsWith("/set")) {
            String inner = topic.substring(config.topic.length() + 1, topic.length() - "/set".length());
            String key, sub;
            int slash = inner.indexOf('/');
            if (slash >= 0) { key = inner.substring(0, slash); sub = inner.substring(slash + 1); }
            else { key = inner; sub = null; }
            String payload = new String(message.getPayload());
            // ensureCommandRouter() is synchronized and returns the (volatile)
            // router so we don't race on the field read against disconnect().
            MqttCommandRouter router = ensureCommandRouter();
            if (router != null) router.handle(key, sub, payload);
        }
    }

    private synchronized MqttCommandRouter ensureCommandRouter() {
        // Don't resurrect a router for a connection that's shutting down — a late
        // inbound message racing disconnect() would otherwise leak a new executor.
        if (!running) return null;
        if (commandRouter == null) {
            commandRouter = new MqttCommandRouter(config.id,
                    (k, v) -> publishString(config.topic + "/" + k, v, true, config.qos));
        }
        return commandRouter;
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Delivery confirmed (QoS 1)
    }

    // ==================== STATUS ====================

    /**
     * Get connection status as JSON for API responses.
     */
    public JSONObject getStatus() {
        JSONObject status = new JSONObject();
        try {
            status.put("id", config.id);
            status.put("name", config.name);
            status.put("connected", connected && client != null && client.isConnected());
            status.put("running", running);
            status.put("totalPublishes", totalPublishes);
            status.put("failedPublishes", failedPublishes);
            status.put("lastPublishTime", lastPublishTime);
            status.put("consecutiveFailures", consecutiveFailures);
            status.put("lastError", lastError != null ? lastError : "");
            status.put("brokerUri", config.getBrokerUri());
            status.put("topic", config.topic);
            status.put("ssl", config.isSsl());
            status.put("trustAllCerts", config.trustAllCerts);
            status.put("proxyActive", ProxyHelper.isProxyAvailable());
            status.put("proxyPort", ProxyHelper.getProxyPort());
        } catch (Exception ignored) {}
        return status;
    }

    /**
     * Calculate backoff delay for reconnection.
     */
    public long getBackoffSeconds() {
        if (consecutiveFailures <= 0) return 0;
        long backoff = BACKOFF_BASE_SECONDS * (1L << Math.min(consecutiveFailures - 1, 10));
        return Math.min(backoff, BACKOFF_CAP_SECONDS);
    }

    // ==================== DIAGNOSTICS ====================

    /**
     * Walk the exception cause chain to find the real underlying error.
     *
     * Paho wraps the actual failure (SSLHandshakeException, CertPathValidatorException,
     * SocketTimeoutException, etc.) inside layers of MqttException. Error 32103
     * (SERVER_CONNECT_ERROR) is especially opaque — the getMessage() just says
     * "Unable to connect to server" with zero detail about WHY.
     *
     * This method digs through the chain and returns a human-readable string
     * showing each layer, so the log actually tells you what happened.
     */
    private static String extractRootCause(Throwable t) {
        if (t == null) return "Unknown";

        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());

        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < 5) {
            sb.append(" → ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
            depth++;
        }

        return sb.toString();
    }

    // ==================== GETTERS ====================

    public MqttConnectionConfig getConfig() { return config; }
    public boolean isConnected() { return connected && client != null && client.isConnected(); }
    public boolean isRunning() { return running; }
    public long getTotalPublishes() { return totalPublishes; }
    public long getFailedPublishes() { return failedPublishes; }
    public long getLastPublishTime() { return lastPublishTime; }
    public String getLastError() { return lastError; }
}
