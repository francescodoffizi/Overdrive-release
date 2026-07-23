package com.overdrive.app.byd.routing;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.BydCloudDataProvider;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Routes vehicle control commands between BYD cloud REST and the local SDK
 * (BydDataCollector). Each {@link VehicleCommand} declares a capability
 * matrix — which paths it has, which is preferred, what handshake the cloud
 * leg needs — and the router resolves the effective policy from the
 * declaration plus a per-command override under the {@code bydCloud.routePolicy}
 * config section. The result of every dispatch is a structured
 * {@link CommandResult} so callers can render a transparent
 * "sent via cloud" / "sent via direct connection" badge to the UI.
 *
 * <p>Cloud calls run on a single-thread executor with a 12 s budget so a
 * stalled BYD round-trip never blocks the HTTP request beyond {@link
 * #CLOUD_TIMEOUT_MS}. A per-router lock guarantees only one cloud command is
 * in flight at a time — concurrent /control/remoteControl posts trip BYD's
 * rate-limit (response code 6024).
 */
public final class VehicleCommandRouter {

    private static final String TAG = "VehicleCommandRouter";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final long CLOUD_TIMEOUT_MS = 12_000L;
    private static final long CLOUD_TRUNK_UNLOCK_SETTLE_MS = 2_000L;

    /** BYD response code meaning "previous command in progress". */
    private static final String CLOUD_CODE_RATE_LIMITED = "6024";

    private static volatile VehicleCommandRouter instance;

    private final ExecutorService cloudExec;
    private final Object cloudLock = new Object();

    private VehicleCommandRouter() {
        cloudExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VehicleCommandRouter-Cloud");
            t.setDaemon(true);
            return t;
        });
    }

    public static VehicleCommandRouter getInstance() {
        if (instance == null) {
            synchronized (VehicleCommandRouter.class) {
                if (instance == null) instance = new VehicleCommandRouter();
            }
        }
        return instance;
    }

    // ── Public types ────────────────────────────────────────────────────

    /**
     * What a command's declared default preference is when both paths are
     * available. The router may degrade CLOUD_FIRST → SDK if cloud is
     * unavailable, and may upgrade SDK_FIRST → cloud-only when no SDK path
     * exists. CLOUD_ONLY / SDK_ONLY commands skip the alternate path entirely.
     */
    public enum RoutePreference { CLOUD_FIRST, SDK_FIRST, CLOUD_ONLY, SDK_ONLY }

    /**
     * Per-leg capability — does the command have this path, and is it
     * required (no fallback) or optional (router may substitute).
     */
    public enum Capability { NONE, AVAILABLE, REQUIRED }

    /**
     * What the cloud leg needs from the cloud session before it'll dispatch.
     * Kept conservative by default — see {@link VehicleCommand#cloudHandshake()}.
     */
    public enum CloudHandshake {
        /** Stateless config write — only valid creds + VIN. */
        SESSION,
        /** /control/remoteControl — also wants an active MQTT or REST poller. */
        LIVE_CHANNEL
    }

    public enum Outcome { SUCCESS, FAILED, NOT_SUPPORTED, RATE_LIMITED, AUTH_REQUIRED, BLOCKED_DRIVING }

    /**
     * Path actually executed. CLOUD_THEN_SDK = cloud tried, fell back to SDK.
     * SDK_THEN_CLOUD = SDK tried, fell back to cloud (rare; happens for
     * SDK_FIRST commands when the local primitive returns false).
     */
    public enum Path { CLOUD, SDK, CLOUD_THEN_SDK, SDK_THEN_CLOUD, NONE }

    public static final class CommandResult {
        public final Outcome outcome;
        public final Path path;
        public final String displayMessage;
        public final long latencyMs;
        public final Throwable error;

        private CommandResult(Outcome outcome, Path path, String displayMessage,
                              long latencyMs, Throwable error) {
            this.outcome = outcome;
            this.path = path;
            this.displayMessage = displayMessage != null ? displayMessage : "";
            this.latencyMs = latencyMs;
            this.error = error;
        }

        public static CommandResult success(Path path, String msg, long latencyMs) {
            return new CommandResult(Outcome.SUCCESS, path, msg, latencyMs, null);
        }
        public static CommandResult failed(Path path, String msg, long latencyMs, Throwable t) {
            return new CommandResult(Outcome.FAILED, path, msg, latencyMs, t);
        }
        public static CommandResult notSupported(String msg) {
            return new CommandResult(Outcome.NOT_SUPPORTED, Path.NONE, msg, 0, null);
        }
        public static CommandResult authRequired(String msg) {
            return new CommandResult(Outcome.AUTH_REQUIRED, Path.NONE, msg, 0, null);
        }
        public static CommandResult rateLimited(String msg, long latencyMs) {
            return new CommandResult(Outcome.RATE_LIMITED, Path.CLOUD, msg, latencyMs, null);
        }
        public static CommandResult blocked(String msg) {
            return new CommandResult(Outcome.BLOCKED_DRIVING, Path.NONE, msg, 0, null);
        }

        public String pathString() {
            switch (path) {
                case CLOUD: return "cloud";
                case SDK: return "local";
                case CLOUD_THEN_SDK: return "cloud-then-local";
                case SDK_THEN_CLOUD: return "local-then-cloud";
                default: return "none";
            }
        }
    }

    // ── Command base ────────────────────────────────────────────────────

    /**
     * Base class for vehicle commands. Subclasses declare the capability
     * matrix (cloud + SDK availability, default preference, cloud handshake)
     * and provide the per-path execution. The router never inspects subclass
     * types — everything flows through these declarations, so adding a new
     * command is "extend, declare capabilities, override the leg(s) you have."
     */
    public static abstract class VehicleCommand {
        public abstract String name();

        /**
         * Whether this command can use the cloud leg.
         * - NONE: no cloud path; SDK is the only option.
         * - AVAILABLE: cloud works; router may pick it or skip it.
         * - REQUIRED: cloud is the only path; missing cloud → AUTH_REQUIRED.
         */
        public Capability cloudCapability() { return Capability.NONE; }

        /**
         * Whether this command can use the SDK leg.
         * - NONE: no SDK path; cloud is the only option.
         * - AVAILABLE: SDK works; router may pick it or skip it.
         * - REQUIRED: SDK is the only path; missing SDK → NOT_SUPPORTED.
         */
        public Capability sdkCapability() { return Capability.NONE; }

        /**
         * What to try first when both legs are available. Ignored when one
         * leg is REQUIRED and the other is NONE.
         */
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }

        /**
         * Cloud handshake requirement.
         * /control/remoteControl posts (LOCKDOOR, OPENAIR, …) push their result
         * back through MQTT in the BYD app, but in this app we poll the result
         * over HTTP — so a credentials-only check is sufficient. Override to
         * LIVE_CHANNEL only if the command genuinely depends on an active push
         * channel (none of our current commands do).
         */
        public CloudHandshake cloudHandshake() { return CloudHandshake.SESSION; }

        /** /control/remoteControl needs the PIN; /control/smartCharge/* does not. */
        public boolean requiresControlPin() { return true; }

        /**
         * Latency-sensitive commands skip the cloud leg when the vehicle is
         * already awake (SDK is instant; cloud is 5–30 s) — find-car / flash.
         * Overridden for the find/flash commands; default is normal latency.
         */
        public boolean isLatencySensitive() { return false; }

        /** Whether this command actuates something driving-safety-relevant. */
        public enum MotionSafety { UNRESTRICTED, BLOCK_WHILE_MOVING }

        /**
         * Whether the router should refuse this command while the vehicle is
         * moving (see {@link DrivingSafetyGuard}). Safe-by-default: an
         * un-overridden command is treated as motion-sensitive, so a newly
         * added command never silently ships unrestricted.
         */
        public MotionSafety motionSafety() { return MotionSafety.BLOCK_WHILE_MOVING; }

        // ── Execution legs (override the ones you support) ──────────────

        /**
         * Run via cloud. Implementations either return {@link CloudOutcome}
         * with success/rateLimited, or throw if the path is unsupported.
         */
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return CloudOutcome.unsupported();
        }

        /**
         * Run via SDK. Returns true on success, false on failure.
         */
        public boolean executeViaSdk(BydDataCollector collector) {
            return false;
        }

        // ── Convenience accessors derived from capability declarations ──

        public final boolean hasCloudPath() { return cloudCapability() != Capability.NONE; }
        public final boolean hasSdkPath() { return sdkCapability() != Capability.NONE; }
        public final boolean cloudRequired() { return cloudCapability() == Capability.REQUIRED; }
        public final boolean sdkRequired() { return sdkCapability() == Capability.REQUIRED; }
    }

    /** Cloud execution outcome — success, rate-limited (don't fall back), or unsupported. */
    public static final class CloudOutcome {
        public final boolean success;
        public final boolean rateLimited;
        public final boolean unsupported;
        private CloudOutcome(boolean s, boolean r, boolean u) {
            success = s; rateLimited = r; unsupported = u;
        }
        public static CloudOutcome success() { return new CloudOutcome(true, false, false); }
        public static CloudOutcome failed() { return new CloudOutcome(false, false, false); }
        public static CloudOutcome rateLimited() { return new CloudOutcome(false, true, false); }
        public static CloudOutcome unsupported() { return new CloudOutcome(false, false, true); }
    }

    // ── Concrete commands ───────────────────────────────────────────────
    // Each command's capability declarations form the routing contract. The
    // router never special-cases a command (except TrunkOpenCommand, which is
    // a composite). To add a new control: extend, declare, override legs.

    public static final class LockCommand extends VehicleCommand {
        public String name() { return "lock"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public Capability sdkCapability() { return Capability.NONE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "LOCKDOOR", null, true);
        }
    }

    public static final class UnlockCommand extends VehicleCommand {
        public String name() { return "unlock"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public Capability sdkCapability() { return Capability.NONE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "OPENDOOR", null, true);
        }
    }

    /** Horn + lights — BYD cloud only (no SDK FINDCAR primitive on this gen). */
    public static final class FindCarCommand extends VehicleCommand {
        public String name() { return "find-car"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public Capability sdkCapability() { return Capability.NONE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public boolean isLatencySensitive() { return true; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "FINDCAR", null, false);
        }
    }

    /** Lights-only flash — BYD cloud only (no SDK flash primitive on this gen). */
    public static final class FlashLightsCommand extends VehicleCommand {
        public String name() { return "flash"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public Capability sdkCapability() { return Capability.NONE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public boolean isLatencySensitive() { return true; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "FLASHLIGHTNOWHISTLE", null, false);
        }
    }

    public static final class ClimateOnCommand extends VehicleCommand {
        public final double tempCelsius;
        public ClimateOnCommand(double t) { this.tempCelsius = t; }
        public String name() { return "climate-on"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            int t = (int) Math.round(Math.max(17, Math.min(33, tempCelsius)));
            JSONObject extra = new JSONObject();
            extra.put("temperature", String.valueOf(t));
            extra.put("copilot_temperature", String.valueOf(t));
            extra.put("cycle_mode", "2");
            extra.put("time_span", "3");
            extra.put("remote_mode", "4");
            return remoteCommand(client, vin, "OPENAIR", extra, true);
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcPower(true); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    public static final class ClimateOffCommand extends VehicleCommand {
        public String name() { return "climate-off"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "CLOSEAIR", null, true);
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcPower(false); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    public static final class CloseAllWindowsCommand extends VehicleCommand {
        public String name() { return "windows-close-all"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            return remoteCommand(client, vin, "CLOSEWINDOW", null, true);
        }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setAllWindowsCommand(2); // 2 = close
        }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    public static final class BatteryHeatCommand extends VehicleCommand {
        public final boolean enabled;
        public BatteryHeatCommand(boolean on) { this.enabled = on; }
        public String name() { return "battery-heat"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            JSONObject extra = new JSONObject();
            extra.put("batteryHeatSwitch", enabled ? "1" : "0");
            return remoteCommand(client, vin, "BATTERYHEAT", extra, true);
        }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    // ── Trunk: composite (cloud unlock + SDK tailgate) ──────────────────

    public static final class TrunkOpenCommand extends VehicleCommand {
        // Treated specially in execute() — see executeTrunkOpen().
        public String name() { return "trunk-open"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_FIRST; }
    }

    public static final class TrunkCloseCommand extends VehicleCommand {
        public String name() { return "trunk-close"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.closeTailgate(); }
    }

    /**
     * Local-only tailgate open. The cloud composite {@link TrunkOpenCommand} unlocks
     * via cloud first to avoid the alarm; this SDK-only variant fires the motor
     * directly for the MQTT/HA path (which never touches cloud). The body controller
     * still gates on vehicle state, and HA users gate it on "doors unlocked".
     */
    public static final class TrunkOpenSdkCommand extends VehicleCommand {
        public String name() { return "trunk-open-sdk"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.openTailgate(); }
    }

    // ── Tier 2: local body comfort (reuse verified SDK setters) ─────────

    /** Sunroof open/close/stop — BYDAutoBodyworkDevice.voiceCtlMoonRoof (area 5). */
    public static final class SunroofCommand extends VehicleCommand {
        public final int command; // 1=open, 2=close, 3=stop
        public SunroofCommand(int c) { this.command = c; }
        public String name() { return "sunroof"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSunWindowCommand(5, command); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /** Sunshade open/close/stop — BYDAutoBodyworkDevice.voiceCtlSunshadePanel (area 6). */
    public static final class SunshadeCommand extends VehicleCommand {
        public final int command;
        public SunshadeCommand(int c) { this.command = c; }
        public String name() { return "sunshade"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSunWindowCommand(6, command); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /** Rear-door child lock (both sides) — BYDAutoDoorLockDevice feature write. */
    public static final class ChildLockCommand extends VehicleCommand {
        public final boolean enabled;
        public ChildLockCommand(boolean e) { this.enabled = e; }
        public String name() { return "child-lock"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            // Both sides must move together; there's no telemetry readback for child
            // lock, so a half-applied state (one door locked, one not) would never
            // reconcile. Attempt both (non-short-circuit), then retry only the side
            // that missed once, so a transient HAL miss self-corrects instead of
            // leaving the doors inconsistent. Returns true only if both ended set.
            boolean left = c.setChildLock(true, enabled);
            boolean right = c.setChildLock(false, enabled);
            if (!left) left = c.setChildLock(true, enabled);
            if (!right) right = c.setChildLock(false, enabled);
            return left && right;
        }
        // Only disables a rear occupant's ability to open their own door from the
        // inside — doesn't itself unlatch or open anything. Matches how a physical
        // child-lock switch behaves in most vehicles (no gear interlock).
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /** Fold / unfold the exterior rear-view mirrors (BODYWORK_REARVIEW_MIRROR_SET).
     *  fold=true → mirrors fold in, false → unfold. No telemetry readback exists for
     *  mirror-fold state (confirmed against the OEM firmware — set-only), so this is
     *  a fire-and-set command; the keymap/automation "toggle" is a blind flip via the
     *  last-commanded cache, not a live read. */
    public static final class MirrorFoldCommand extends VehicleCommand {
        public final boolean fold;
        public MirrorFoldCommand(boolean fold) { this.fold = fold; }
        public String name() { return "mirror-fold"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return c.setMirrorsFolded(fold);
        }
    }

    /** Phone wireless-charger pad on/off — BYDAutoChargingDevice feature write. */
    public static final class WirelessChargingCommand extends VehicleCommand {
        public final boolean enabled;
        public WirelessChargingCommand(boolean e) { this.enabled = e; }
        public String name() { return "wireless-charging"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setWirelessCharging(enabled); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    public static final class TrunkStopCommand extends VehicleCommand {
        public String name() { return "trunk-stop"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.stopTailgate(); }
        // Never block a stop/abort action — you must always be able to halt an
        // in-progress motor movement, especially if the car starts moving mid-actuation.
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    // ── SDK-only commands ───────────────────────────────────────────────

    public static final class WindowMoveCommand extends VehicleCommand {
        public final int area; public final int action; public final Integer targetPercent;
        public WindowMoveCommand(int area, int action, Integer targetPercent) {
            this.area = area; this.action = action; this.targetPercent = targetPercent;
        }
        public String name() { return "window-move"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            if (targetPercent != null) return c.moveWindowToPercent(area, targetPercent);
            if (area == 0) return c.setAllWindowsCommand(action);
            return c.setWindowCommand(area, action);
        }
        // Ventilation is a legitimate, common in-motion use case; window glass
        // movement doesn't create the road/mechanical hazard trunk/door-unlock does.
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    public static final class ClimateSetTempCommand extends VehicleCommand {
        public final double tempCelsius; public final int zone;
        public ClimateSetTempCommand(int zone, double t) { this.zone = zone; this.tempCelsius = t; }
        public String name() { return "climate-temp"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcTemperature(zone, tempCelsius); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    public static final class ClimateSetFanCommand extends VehicleCommand {
        public final int level;
        public ClimateSetFanCommand(int l) { this.level = l; }
        public String name() { return "climate-fan"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcFanLevel(level); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /**
     * Seat heat / ventilation — cloud first (BYD VENTILATIONHEATING command),
     * with SDK fallback for cars that lack the cloud feature on their region/trim.
     *
     * <p>The cloud command is stateful: it requires the FULL snapshot of all
     * seat states. We track driver+passenger heat+vent locally; the constructor
     * captures the rest of the state at the moment the command is built so
     * unchanged seats retain their level.
     */
    public static final class SeatHeatCommand extends VehicleCommand {
        public final int position; public final int level;
        public final int driverHeat, driverVent, passengerHeat, passengerVent;
        public SeatHeatCommand(int p, int l, int dh, int dv, int ph, int pv) {
            this.position = p; this.level = l;
            this.driverHeat = dh; this.driverVent = dv;
            this.passengerHeat = ph; this.passengerVent = pv;
        }
        public String name() { return "seat-heat"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        // SDK_FIRST (not CLOUD_FIRST): the local setSeatHeatingState(position,level) is
        // genuinely PER-SEAT, whereas the cloud VENTILATIONHEATING command is COMPOSITE —
        // it writes mainHeat AND copilotHeat together from the (possibly stale) snapshot,
        // so a "driver heat on" cloud call would also drive the passenger seat. Preferring
        // the SDK path fixes the reported "driver seat heating turns on both seats" bug;
        // cloud stays as the fallback for when the local write is refused (e.g. parked).
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            String chairType = (position == 1) ? "1" : "2";
            boolean ok = client.setSeatClimate(vin, chairType,
                    driverHeat, driverVent, passengerHeat, passengerVent);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatHeating(position, level); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    public static final class SeatVentCommand extends VehicleCommand {
        public final int position; public final int level;
        public final int driverHeat, driverVent, passengerHeat, passengerVent;
        public SeatVentCommand(int p, int l, int dh, int dv, int ph, int pv) {
            this.position = p; this.level = l;
            this.driverHeat = dh; this.driverVent = dv;
            this.passengerHeat = ph; this.passengerVent = pv;
        }
        public String name() { return "seat-vent"; }
        public Capability cloudCapability() { return Capability.AVAILABLE; }
        public Capability sdkCapability() { return Capability.AVAILABLE; }
        // SDK_FIRST — same rationale as SeatHeatCommand: the local setSeatVentilation
        // (position,level) is per-seat; the cloud path is a composite that would drive
        // both seats. Prefer the per-seat SDK write; cloud is the fallback.
        public RoutePreference defaultPreference() { return RoutePreference.SDK_FIRST; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            String chairType = (position == 1) ? "1" : "2";
            boolean ok = client.setSeatClimate(vin, chairType,
                    driverHeat, driverVent, passengerHeat, passengerVent);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSeatVentilation(position, level); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /**
     * Driver-seat memory: recall (move to a stored slot) or save (store the current
     * position into a slot). Both slots are 1-2. SDK-only — the driver-seat memory
     * ids live on the setting HAL and have no BYD cloud remote-control equivalent.
     * {@code save=false} recalls (setSeatMemoryPosition / WAKE id); {@code save=true}
     * stores (setSeatMemorySave / SET id).
     */
    public static final class SeatMemoryCommand extends VehicleCommand {
        public final int position;
        public final boolean save;
        /** Recall a stored slot (backwards-compatible constructor). */
        public SeatMemoryCommand(int p) { this(p, false); }
        public SeatMemoryCommand(int p, boolean save) { this.position = p; this.save = save; }
        public String name() { return save ? "seat-memory-save" : "seat-memory-recall"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return save ? c.setSeatMemorySave(position) : c.setSeatMemoryPosition(position);
        }
        // save=true only writes the current position to memory — no motor movement.
        // save=false (recall) moves the seat with a driver in it — block while moving.
        public MotionSafety motionSafety() {
            return save ? MotionSafety.UNRESTRICTED : MotionSafety.BLOCK_WHILE_MOVING;
        }
    }

    public static final class LightsCommand extends VehicleCommand {
        public final boolean drlOn;
        public LightsCommand(boolean on) { this.drlOn = on; }
        public String name() { return "lights"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setDayTimeLight(drlOn); }
    }

    /**
     * Hazard (double-flash) lights on/off. Local SDK only — no cloud hazard command
     * exists (the cloud "flash" is the momentary locate-flash, not a sustained toggle).
     * Actuation is UNCONFIRMED on this platform (inferred feature id, no OEM precedent)
     * — {@link BydDataCollector#setHazardLights} returns false if the HAL rejects it, so
     * a failed write surfaces honestly rather than silently no-op'ing.
     */
    public static final class HazardCommand extends VehicleCommand {
        public final boolean on;
        public HazardCommand(boolean on) { this.on = on; }
        public String name() { return "hazard"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setHazardLights(on); }
    }

    public static final class AmbientColourCommand extends VehicleCommand {
        public final int colour;
        public AmbientColourCommand(int colour) { this.colour = colour; }
        public String name() { return "ambient"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAmbientLight(colour); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /** AC auto mode on/off (feature-id Ac.AUTO_MODE_SET). SDK-only. */
    public static final class AcAutoModeCommand extends VehicleCommand {
        public final boolean on;
        public AcAutoModeCommand(boolean on) { this.on = on; }
        public String name() { return "ac-auto"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcAutoMode(on); }
    }

    /** Air intake: recirculate cabin air vs draw fresh outside air (feature-id
     *  Ac.CYCLE_MODE_SET, FRESH_AIR=0 / RECIRCULATION=1). SDK-only. */
    public static final class AcRecirculationCommand extends VehicleCommand {
        public final boolean recirculate;
        public AcRecirculationCommand(boolean recirculate) { this.recirculate = recirculate; }
        public String name() { return "ac-recirculation"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAcRecirculation(recirculate); }
    }

    /** Front windscreen defrost/demist on/off (feature-id Ac.DEFROST_FRONT_SET). SDK-only —
     *  no BYD cloud remote-control equivalent; same AC-device routing as recirculation. */
    public static final class FrontDefrostCommand extends VehicleCommand {
        public final boolean on;
        public FrontDefrostCommand(boolean on) { this.on = on; }
        public String name() { return "defrost-front"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFrontDefrost(on); }
    }

    /** Rear windscreen defrost/demist on/off (feature-id Ac.DEFROST_REAR_SET). SDK-only. */
    public static final class RearDefrostCommand extends VehicleCommand {
        public final boolean on;
        public RearDefrostCommand(boolean on) { this.on = on; }
        public String name() { return "defrost-rear"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setRearDefrost(on); }
    }

    /** Fan-only (ventilation, no compressor) on/off. SDK-only, named-method HAL call. */
    public static final class FanOnlyModeCommand extends VehicleCommand {
        public final boolean on;
        public FanOnlyModeCommand(boolean on) { this.on = on; }
        public String name() { return "fan-only"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFanOnlyMode(on); }
    }

    /** Steering-wheel heating on/off (setting HAL, on=2/off=1). SDK-only. */
    public static final class SteeringWheelHeatCommand extends VehicleCommand {
        public final boolean on;
        public SteeringWheelHeatCommand(boolean on) { this.on = on; }
        public String name() { return "steering-heat"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSteeringWheelHeating(on); }
    }

    /** Smart welcome-light on/off (setting HAL, on=1/off=2). SDK-only. */
    public static final class WelcomeLightCommand extends VehicleCommand {
        public final boolean on;
        public WelcomeLightCommand(boolean on) { this.on = on; }
        public String name() { return "welcome-light"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setWelcomeLight(on); }
    }

    /** Interior reading light on/off (body feature-id, on=1/off=2). SDK-only. */
    public static final class ReadingLightCommand extends VehicleCommand {
        public final boolean on;
        public ReadingLightCommand(boolean on) { this.on = on; }
        public String name() { return "reading-light"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setReadingLight(on); }
    }

    /** Ambient-light music mode on/off (body feature-id, on=1/off=2). SDK-only. */
    public static final class AmbientMusicModeCommand extends VehicleCommand {
        public final boolean on;
        public AmbientMusicModeCommand(boolean on) { this.on = on; }
        public String name() { return "ambient-music"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setAmbientMusicMode(on); }
    }

    /** Headlight (headlamp) level / height (setting HAL, clamped 1..11). SDK-only. */
    public static final class HeadlightLevelCommand extends VehicleCommand {
        public final int level;
        public HeadlightLevelCommand(int level) { this.level = level; }
        public String name() { return "headlight-level"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setHeadlightLevel(level); }
    }

    public static final class AdasSpeedLimitWarningCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasSpeedLimitWarningCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-slw"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSpeedLimitWarning(enabled); }
    }

    /**
     * Electronic Stability Program (ESP / ESC) on/off. SDK-only — the ESP feature
     * id lives on the setting HAL (family-consistent with DOW/RCW) and has no BYD
     * cloud remote-control equivalent. SAFETY control: enabled=true restores
     * stability control, false disables it. On many vehicles the HAL/ECU re-enables
     * ESP at the next ignition cycle regardless of this write.
     */
    public static final class AdasEspCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasEspCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-esp"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEspState(enabled); }
    }

    /**
     * iTAC (Intelligent Torque Adaption Control) on/off. SDK-only — the iTAC feature
     * id lives on the setting HAL and has no BYD cloud remote-control equivalent.
     * Performance/traction feature, distinct from the ESP stability interlock.
     */
    public static final class AdasItacCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasItacCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-itac"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setItacState(enabled); }
    }

    /**
     * Lane-assist mode (Lane Departure Warning / Prevention) via
     * BYDAutoADASDevice.setLKSMode. SDK-only — a dedicated ADAS-device method, no BYD
     * cloud equivalent. This is a MULTI-mode control (not on/off): app-level mode
     * 0=Off, 1=LDW, 2=LDP, 3=LDW+LDP (BydDataCollector maps to the MCU values).
     */
    public static final class AdasLaneAssistCommand extends VehicleCommand {
        public final int mode;
        public AdasLaneAssistCommand(int mode) { this.mode = mode; }
        public String name() { return "adas-lane-assist"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setLaneAssistMode(mode); }
    }

    public static final class SettingChildPresenceDetectionCommand extends VehicleCommand {
        public final int value;
        public SettingChildPresenceDetectionCommand(int value) { this.value = value; }
        public String name() { return "setting-cpd"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChildPresenceDetection(value); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    // ── Expanded ADAS matrix (all SDK-only, on adasDevice via BydDataCollector) ──
    // Warning/info toggles are low-risk; the auto-brake / lane-keep ones are SAFETY
    // controls (disabling reduces protection) and are labelled as such at the action
    // layer. AEB is exposed ENABLE-ONLY (its action never sends a disable).

    public static final class AdasBlindSpotCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasBlindSpotCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-bsd"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setBlindSpotDetection(enabled); }
    }

    public static final class AdasTrafficSignCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasTrafficSignCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-tsr"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setTrafficSignRecognition(enabled); }
    }

    public static final class AdasRearCrossTrafficCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasRearCrossTrafficCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-rcta"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setRearCrossTrafficAlert(enabled); }
    }

    public static final class AdasFrontCrossTrafficCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasFrontCrossTrafficCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-fcta"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFrontCrossTrafficAlert(enabled); }
    }

    public static final class AdasTrafficLightAttentionCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasTrafficLightAttentionCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-tla"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setTrafficLightAttention(enabled); }
    }

    public static final class AdasOpenDoorWarningCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasOpenDoorWarningCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-dow"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setOpenDoorWarning(enabled); }
    }

    public static final class AdasRearCollisionWarningCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasRearCollisionWarningCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-rcw"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setRearCollisionWarning(enabled); }
    }

    public static final class AdasSpeedLimitControlCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasSpeedLimitControlCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-islc"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSpeedLimitControl(enabled); }
    }

    /** Emergency/urgent lane keeping — SAFETY (autonomous steering intervention). */
    public static final class AdasEmergencyLaneKeepCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasEmergencyLaneKeepCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-elka"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEmergencyLaneKeeping(enabled); }
    }

    /** Rear cross-traffic BRAKE — SAFETY (autonomous braking). */
    public static final class AdasRearCrossBrakeCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasRearCrossBrakeCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-rctb"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setRearCrossTrafficBraking(enabled); }
    }

    /** Front cross-traffic BRAKE — SAFETY (autonomous braking). */
    public static final class AdasFrontCrossBrakeCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasFrontCrossBrakeCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-fctb"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFrontCrossTrafficBraking(enabled); }
    }

    /**
     * Forward Collision Warning sensitivity LEVEL (multi-mode, not on/off): app-level
     * 0=Off/1=Low/2=Med/3=High. SAFETY — lowering delays collision warnings.
     */
    public static final class AdasFcwLevelCommand extends VehicleCommand {
        public final int level;
        public AdasFcwLevelCommand(int level) { this.level = level; }
        public String name() { return "adas-fcw"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setFcwLevel(level); }
    }

    /** Automatic Emergency Braking — SAFETY-CRITICAL, ENABLE-ONLY at the action layer. */
    public static final class AdasEmergencyBrakingCommand extends VehicleCommand {
        public final boolean enabled;
        public AdasEmergencyBrakingCommand(boolean on) { this.enabled = on; }
        public String name() { return "adas-aeb"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEmergencyBraking(enabled); }
    }

    /**
     * Smart-charging schedule — BYD cloud /control/smartCharge/saveOrUpdate.
     * Wire-compatible with pyBYD's trigger_save_charging_schedule.
     */
    public static final class ChargeScheduleCommand extends VehicleCommand {
        public final String startChargeTime;
        public final String endChargeTime;
        public final String chargeWay;
        public final boolean enabled;
        public ChargeScheduleCommand(String start, String end, String chargeWay, boolean enabled) {
            this.startChargeTime = start;
            this.endChargeTime = end;
            this.chargeWay = chargeWay;
            this.enabled = enabled;
        }
        public String name() { return "charge-schedule"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public boolean requiresControlPin() { return false; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            boolean ok = client.saveChargingSchedule(vin, startChargeTime, endChargeTime, chargeWay, enabled);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /**
     * BEV charge cap. Collector writes the SOC-target (setSOCTarget +
     * setSocSaveSwitch) first — the path that actually applies on these trims,
     * clamped to [15/25 .. 70] — and falls back to the legacy
     * setChargeStopCapacityState (50..100%, probed for no-op) when absent.
     */
    public static final class ChargeCapPercentCommand extends VehicleCommand {
        public final int percent;
        public ChargeCapPercentCommand(int p) { this.percent = p; }
        public String name() { return "charge-cap-percent"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChargeCapPercent(percent); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /** BEV charge cap on/off — setSocSaveSwitch, falling back to setChargeStopSwitchState. */
    public static final class ChargeCapToggleCommand extends VehicleCommand {
        public final boolean enabled;
        public ChargeCapToggleCommand(boolean on) { this.enabled = on; }
        public String name() { return "charge-cap-toggle"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setChargeCapEnabled(enabled); }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /** Smart-charge master switch — BYD cloud /control/smartCharge/changeChargeStatue. */
    public static final class SmartChargingToggleCommand extends VehicleCommand {
        public final boolean enabled;
        public SmartChargingToggleCommand(boolean on) { this.enabled = on; }
        public String name() { return "smart-charging-toggle"; }
        public Capability cloudCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.CLOUD_ONLY; }
        public boolean requiresControlPin() { return false; }
        public CloudOutcome executeViaCloud(BydCloudClient client, String vin) throws Exception {
            boolean ok = client.toggleSmartCharging(vin, enabled);
            return ok ? CloudOutcome.success() : CloudOutcome.failed();
        }
        public MotionSafety motionSafety() { return MotionSafety.UNRESTRICTED; }
    }

    /**
     * Local CAN-backed in-car setting write via the BYD carsettings provider
     * ({@link com.overdrive.app.byd.BydCarSettings}). SDK/local-only — never cloud.
     * Only allowlisted keys with in-domain values are accepted (validated downstream).
     */
    public static final class CarSettingCommand extends VehicleCommand {
        // Registry keys (see BydCarSettings.REGISTRY) that write a driving-safety-
        // relevant setting via this generic path — a second, independent route to
        // the same hazards as the dedicated Adas*/Lights/EnergyFeedback/etc. commands.
        // Any key NOT in this set (including one added to the registry later without
        // updating this set) falls through to the class default (BLOCK_WHILE_MOVING)
        // rather than silently shipping unrestricted — see motionSafety() below.
        private static final java.util.Set<String> UNRESTRICTED_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
                "children_lock", "shut_window_after_locking", "auto_mirror_for_lock",
                "rain_close_window", "auto_lock_time", "avh_assist", "auto_wipe",
                "charge_limit", "unit_temperature", "lighting_ambient_brightness"));

        public final String key; public final int value;
        public CarSettingCommand(String key, int value) { this.key = key; this.value = value; }
        public String name() { return "car-setting:" + key; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) {
            return com.overdrive.app.byd.BydCarSettings.getInstance().writeInt(key, value);
        }
        public MotionSafety motionSafety() {
            return UNRESTRICTED_KEYS.contains(key) ? MotionSafety.UNRESTRICTED : MotionSafety.BLOCK_WHILE_MOVING;
        }
    }

    /** Drive mode on the setting-device "drive config" axis — 1=NORMAL, 2=ECO,
     *  3=SPORT, 4=SNOW. Routed via {@link BydDataCollector#setDriveConfigMode(int)},
     *  which falls back through setDriveConfig → target-driving-mode feature ids →
     *  (for eco/sport only) the energy-device operation mode. SDK-only. */
    public static final class OperationModeCommand extends VehicleCommand {
        public final int mode;
        public OperationModeCommand(int mode) { this.mode = mode; }
        public String name() { return "drive-config-mode"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setDriveConfigMode(mode); }
    }

    /** Powertrain mode: EV vs HEV (DM/PHEV only). SDK-only. */
    public static final class EnergyModeCommand extends VehicleCommand {
        public final int mode;
        public EnergyModeCommand(int mode) { this.mode = mode; }
        public String name() { return "energy-mode"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEnergyMode(mode); }
    }

    /** Energy recuperation / regen strength. SDK-only. */
    public static final class EnergyFeedbackCommand extends VehicleCommand {
        public final int level;
        public EnergyFeedbackCommand(int level) { this.level = level; }
        public String name() { return "energy-feedback"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setEnergyFeedback(level); }
    }

    /** Steering-assist weighting: comfort vs sport. SDK-only. */
    public static final class SteerAssistCommand extends VehicleCommand {
        public final int mode;
        public SteerAssistCommand(int mode) { this.mode = mode; }
        public String name() { return "steer-assist"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setSteerAssist(mode); }
    }

    /**
     * Brake-pedal feel: comfort vs sport/strong. SDK-only (BYDAutoADASDevice
     * setBrakeFootSenseState). {@code level} is the app-level convention
     * 0=comfort/1=sport; the collector maps it to the HAL value.
     */
    public static final class BrakeFeelCommand extends VehicleCommand {
        public final int level;
        public BrakeFeelCommand(int level) { this.level = level; }
        public String name() { return "brake-feel"; }
        public Capability sdkCapability() { return Capability.REQUIRED; }
        public RoutePreference defaultPreference() { return RoutePreference.SDK_ONLY; }
        public boolean executeViaSdk(BydDataCollector c) { return c.setBrakeFootSense(level); }
    }

    // ── Routing ─────────────────────────────────────────────────────────

    public CommandResult execute(VehicleCommand cmd) {
        CommandResult blocked = checkDrivingSafety(cmd);
        if (blocked != null) return blocked;

        if (cmd instanceof TrunkOpenCommand) {
            return executeTrunkOpen();
        }

        // No legs at all — nothing to do.
        if (!cmd.hasCloudPath() && !cmd.hasSdkPath()) {
            return CommandResult.notSupported(msg("not_supported"));
        }

        RoutePreference pref = resolveEffectivePreference(cmd);
        switch (pref) {
            case SDK_ONLY:
                return runSdkOnly(cmd);
            case CLOUD_ONLY:
                return runCloudOnly(cmd);
            case SDK_FIRST:
                return runSdkFirst(cmd);
            case CLOUD_FIRST:
            default:
                return runCloudFirst(cmd);
        }
    }

    /**
     * Resolve the command's effective preference, applying any per-command
     * config override under {@code bydCloud.routePolicy.<name>}, and clamping
     * the result to the command's declared capabilities (e.g., a CLOUD_FIRST
     * command with SDK NONE collapses to CLOUD_ONLY automatically).
     *
     * <p>Valid override values: {@code cloud_first}, {@code sdk_first},
     * {@code cloud_only}, {@code sdk_only}. Anything else is ignored.
     */
    private RoutePreference resolveEffectivePreference(VehicleCommand cmd) {
        RoutePreference pref = cmd.defaultPreference();
        RoutePreference override = readPolicyOverride(cmd.name());
        if (override != null) pref = override;

        // Clamp to the command's actual capabilities so a misconfigured override
        // (e.g., cloud_first on a SDK-only command) doesn't break dispatch.
        RoutePreference clamped;
        if (!cmd.hasCloudPath()) {
            clamped = cmd.hasSdkPath() ? RoutePreference.SDK_ONLY : pref;
        } else if (!cmd.hasSdkPath()) {
            clamped = RoutePreference.CLOUD_ONLY;
        } else if (cmd.cloudRequired() && !cmd.sdkRequired()) {
            clamped = RoutePreference.CLOUD_ONLY;
        } else if (cmd.sdkRequired() && !cmd.cloudRequired()) {
            clamped = RoutePreference.SDK_ONLY;
        } else {
            clamped = pref;
        }

        // Log when an override was actually rejected by the clamp — silent
        // rewrites would leave admins wondering why their override "didn't take".
        if (override != null && clamped != override) {
            logger.info("routePolicy['" + cmd.name() + "']=" + override
                    + " clamped to " + clamped + " (capabilities: cloud="
                    + cmd.cloudCapability() + " sdk=" + cmd.sdkCapability() + ")");
        }
        return clamped;
    }

    private RoutePreference readPolicyOverride(String commandName) {
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            if (root == null) return null;
            JSONObject byd = root.optJSONObject("bydCloud");
            if (byd == null) return null;
            JSONObject policy = byd.optJSONObject("routePolicy");
            if (policy == null) return null;
            String raw = policy.optString(commandName, "");
            if (raw.isEmpty()) return null;
            switch (raw.toLowerCase()) {
                case "cloud_first": return RoutePreference.CLOUD_FIRST;
                case "sdk_first": return RoutePreference.SDK_FIRST;
                case "cloud_only": return RoutePreference.CLOUD_ONLY;
                case "sdk_only": return RoutePreference.SDK_ONLY;
                default:
                    logger.warn("Ignoring unknown routePolicy['" + commandName + "']='" + raw + "'");
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ── Per-policy executors ────────────────────────────────────────────

    private CommandResult runSdkOnly(VehicleCommand cmd) {
        if (!cmd.hasSdkPath()) return CommandResult.notSupported(msg("not_supported"));
        long start = System.currentTimeMillis();
        SdkLeg leg = invokeSdk(cmd);
        long elapsed = System.currentTimeMillis() - start;
        if (leg.success) return CommandResult.success(Path.SDK, msg("local_sent"), elapsed);
        return CommandResult.failed(Path.SDK, msg("not_supported"), elapsed, leg.error);
    }

    /**
     * Local-only dispatch for the MQTT / Home Assistant control path.
     *
     * <p>Runs <b>only</b> the SDK leg — it never touches the cloud leg, the cloud
     * handshake, the control PIN, or VIN lookup, and never constructs a cloud
     * client. This is the structural guarantee behind "MQTT control is fully
     * local with zero BYD-cloud dependency": even a command that declares a cloud
     * capability will only have its {@link VehicleCommand#executeViaSdk} leg run
     * here. Commands with no SDK path return {@code NOT_SUPPORTED} (the control
     * catalog should not have offered them in the first place).
     */
    public CommandResult executeSdkOnly(VehicleCommand cmd) {
        CommandResult blocked = checkDrivingSafety(cmd);
        if (blocked != null) return blocked;
        return runSdkOnly(cmd);
    }

    private CommandResult runCloudOnly(VehicleCommand cmd) {
        if (!cmd.hasCloudPath()) return CommandResult.notSupported(msg("not_supported"));
        if (!cloudHandshakeSatisfied(cmd)) {
            return CommandResult.authRequired(msg("cloud_required"));
        }
        long start = System.currentTimeMillis();
        CloudCallResult cr = runCloudCall(cmd);
        long elapsed = System.currentTimeMillis() - start;
        return mapCloudOnlyResult(cr, elapsed);
    }

    private CommandResult runCloudFirst(VehicleCommand cmd) {
        long start = System.currentTimeMillis();

        // Latency-sensitive override: SDK is instant when the car is awake.
        if (cmd.isLatencySensitive() && isVehicleAwake() && cmd.hasSdkPath()) {
            SdkLeg leg = invokeSdk(cmd);
            if (leg.success) {
                return CommandResult.success(Path.SDK, msg("local_sent"),
                        System.currentTimeMillis() - start);
            }
            // SDK failed despite being awake — fall through to cloud.
        }

        if (cloudHandshakeSatisfied(cmd)) {
            CloudCallResult cr = runCloudCall(cmd);
            long elapsed = System.currentTimeMillis() - start;
            if (cr.outcome == CloudOutcomeKind.SUCCESS) {
                return CommandResult.success(Path.CLOUD, msg("cloud_sent"), elapsed);
            }
            // Rate-limit: don't fall back; the previous command is still
            // executing and the SDK would race it.
            if (cr.outcome == CloudOutcomeKind.RATE_LIMITED) {
                return CommandResult.rateLimited(msg("rate_limited"), elapsed);
            }
            // Cloud failed (timeout, HTTP, controlState=2). Try SDK.
            if (cmd.hasSdkPath()) {
                SdkLeg leg = invokeSdk(cmd);
                long elapsed2 = System.currentTimeMillis() - start;
                if (leg.success) return CommandResult.success(Path.CLOUD_THEN_SDK,
                        msg("cloud_unavailable_used_local"), elapsed2);
                return CommandResult.failed(Path.CLOUD_THEN_SDK,
                        msg("cloud_failed"), elapsed2, cr.error != null ? cr.error : leg.error);
            }
            return CommandResult.failed(Path.CLOUD, msg("cloud_failed"), elapsed, cr.error);
        }

        // Cloud unavailable; SDK fallback if possible.
        if (cmd.hasSdkPath()) {
            SdkLeg leg = invokeSdk(cmd);
            long elapsed = System.currentTimeMillis() - start;
            if (leg.success) return CommandResult.success(Path.SDK,
                    msg("cloud_offline_used_local"), elapsed);
            return CommandResult.failed(Path.SDK, msg("cloud_failed"), elapsed, leg.error);
        }
        return CommandResult.authRequired(msg("cloud_required"));
    }

    /**
     * SDK_FIRST mirror of CLOUD_FIRST — try the local primitive first; if it
     * fails (or the car is asleep and the call would no-op), fall through to
     * cloud. Reserved for commands where SDK is the canonical path but cloud
     * is a viable fallback (no current commands declare this; included for
     * the config-driven override path).
     */
    private CommandResult runSdkFirst(VehicleCommand cmd) {
        long start = System.currentTimeMillis();
        SdkLeg sdkLeg = null;
        if (cmd.hasSdkPath()) {
            sdkLeg = invokeSdk(cmd);
            if (sdkLeg.success) {
                return CommandResult.success(Path.SDK, msg("local_sent"),
                        System.currentTimeMillis() - start);
            }
        }
        // SDK failed or absent — try cloud.
        if (!cmd.hasCloudPath()) {
            // No cloud path. Whether SDK was attempted matters for the message:
            // if it ran and failed, surface that; otherwise it's truly unsupported.
            if (sdkLeg != null) {
                return CommandResult.failed(Path.SDK, msg("cloud_failed"),
                        System.currentTimeMillis() - start, sdkLeg.error);
            }
            return CommandResult.notSupported(msg("not_supported"));
        }
        if (!cloudHandshakeSatisfied(cmd)) {
            // SDK actually ran and returned false — don't blame missing cloud
            // creds for a local primitive that had its turn.
            if (sdkLeg != null) {
                return CommandResult.failed(Path.SDK, msg("cloud_failed"),
                        System.currentTimeMillis() - start, sdkLeg.error);
            }
            return CommandResult.authRequired(msg("cloud_required"));
        }
        CloudCallResult cr = runCloudCall(cmd);
        long elapsed = System.currentTimeMillis() - start;
        if (cr.outcome == CloudOutcomeKind.SUCCESS) {
            return CommandResult.success(Path.SDK_THEN_CLOUD,
                    msg("local_unavailable_used_cloud"), elapsed);
        }
        if (cr.outcome == CloudOutcomeKind.RATE_LIMITED) {
            return CommandResult.rateLimited(msg("rate_limited"), elapsed);
        }
        // BYD endpoint rejected the command shape — distinct from a transient
        // failure. Mirror runCloudOnly's UNSUPPORTED handling.
        if (cr.outcome == CloudOutcomeKind.UNSUPPORTED) {
            return CommandResult.notSupported(msg("not_supported"));
        }
        return CommandResult.failed(Path.SDK_THEN_CLOUD, msg("cloud_failed"), elapsed, cr.error);
    }

    private CommandResult mapCloudOnlyResult(CloudCallResult cr, long elapsed) {
        switch (cr.outcome) {
            case SUCCESS:      return CommandResult.success(Path.CLOUD, msg("cloud_sent"), elapsed);
            case RATE_LIMITED: return CommandResult.rateLimited(msg("rate_limited"), elapsed);
            case UNSUPPORTED:  return CommandResult.notSupported(msg("not_supported"));
            default:           return CommandResult.failed(Path.CLOUD, msg("cloud_failed"), elapsed, cr.error);
        }
    }

    /**
     * Trunk open: fire the SDK tailgate motor, but only once the car is unlocked
     * — the body controller trips the alarm if the tailgate opens while locked.
     *
     * <p>Lock state comes from the local OTA rail
     * ({@link BydDataCollector#readDoorLockState}, the same signal AccSentry
     * uses), so an already-unlocked car opens directly with no cloud round-trip.
     * Only when the car reports LOCKED do we send the cloud unlock, validate its
     * response, and then fire the motor. If lock state can't be read (INVALID),
     * we fall back to the unlock-then-open path so we never risk the alarm.
     */
    private CommandResult executeTrunkOpen() {
        long start = System.currentTimeMillis();

        int lockState = BydDataCollector.getInstance().readDoorLockState();
        if (lockState != BydDataCollector.DOOR_STATE_UNLOCK) {
            // LOCKED (or INVALID — treat conservatively as locked): unlock via
            // cloud and confirm success before firing the motor.
            UnlockCommand unlock = new UnlockCommand();
            CommandResult unlockResult = execute(unlock);
            if (unlockResult.outcome != Outcome.SUCCESS) {
                return unlockResult;
            }
            try { Thread.sleep(CLOUD_TRUNK_UNLOCK_SETTLE_MS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        try {
            boolean ok = BydDataCollector.getInstance().openTailgate();
            long elapsed = System.currentTimeMillis() - start;
            Path path = lockState == BydDataCollector.DOOR_STATE_UNLOCK
                    ? Path.SDK : Path.CLOUD_THEN_SDK;
            if (ok) return CommandResult.success(path, msg("local_sent"), elapsed);
            return CommandResult.failed(path, msg("not_supported"), elapsed, null);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return CommandResult.failed(Path.CLOUD_THEN_SDK,
                    msg("not_supported"), elapsed, e);
        }
    }

    // ── Cloud helpers ───────────────────────────────────────────────────

    private enum CloudOutcomeKind { SUCCESS, FAILED, RATE_LIMITED, UNSUPPORTED }
    private static final class CloudCallResult {
        final CloudOutcomeKind outcome;
        final Throwable error;
        CloudCallResult(CloudOutcomeKind o, Throwable e) { outcome = o; error = e; }
    }

    private static final class SdkLeg {
        final boolean success;
        final Throwable error;
        SdkLeg(boolean s, Throwable e) { success = s; error = e; }
    }

    private SdkLeg invokeSdk(VehicleCommand cmd) {
        try {
            return new SdkLeg(cmd.executeViaSdk(BydDataCollector.getInstance()), null);
        } catch (Exception e) {
            logger.warn("SDK exec for " + cmd.name() + " threw: " + e.getMessage());
            return new SdkLeg(false, e);
        }
    }

    private CloudCallResult runCloudCall(final VehicleCommand cmd) {
        // Serialize cloud commands so we never race two simultaneous BYD
        // remote-control posts from different HTTP threads.
        synchronized (cloudLock) {
            try {
                final BydCloudClient client = BydCloudDataProvider.getInstance().getSharedClient();
                if (client == null) {
                    return new CloudCallResult(CloudOutcomeKind.FAILED,
                            new IllegalStateException("cloud client unavailable"));
                }
                final String vin = BydCloudConfig.fromUnifiedConfig().vin;
                if (vin == null || vin.isEmpty()) {
                    return new CloudCallResult(CloudOutcomeKind.FAILED,
                            new IllegalStateException("VIN missing"));
                }
                Future<CloudOutcome> f = cloudExec.submit(new Callable<CloudOutcome>() {
                    public CloudOutcome call() throws Exception {
                        // /control/remoteControl commands require the PIN handshake;
                        // /control/smartCharge/* and similar config writes do not.
                        if (cmd.requiresControlPin()) {
                            client.verifyControlPassword(vin);
                        }
                        return cmd.executeViaCloud(client, vin);
                    }
                });
                CloudOutcome out = f.get(CLOUD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (out.success) return new CloudCallResult(CloudOutcomeKind.SUCCESS, null);
                if (out.rateLimited) return new CloudCallResult(CloudOutcomeKind.RATE_LIMITED, null);
                if (out.unsupported) return new CloudCallResult(CloudOutcomeKind.UNSUPPORTED, null);
                return new CloudCallResult(CloudOutcomeKind.FAILED, null);
            } catch (TimeoutException te) {
                return new CloudCallResult(CloudOutcomeKind.FAILED, te);
            } catch (ExecutionException ee) {
                return new CloudCallResult(CloudOutcomeKind.FAILED, ee.getCause());
            } catch (Exception e) {
                return new CloudCallResult(CloudOutcomeKind.FAILED, e);
            }
        }
    }

    /**
     * Shared helper for /control/remoteControl commands — issues the POST and
     * maps the BYD response to a {@link CloudOutcome}, recognizing the 6024
     * rate-limit code so the caller can stop the cascade and not race a
     * still-executing command.
     */
    private static CloudOutcome remoteCommand(BydCloudClient client, String vin,
                                              String commandType, JSONObject extra,
                                              boolean waitForResult) throws Exception {
        BydCloudClient.CloudCommandResult r =
                client.executeRemoteCommandWithCode(vin, commandType, extra, waitForResult);
        if (r.success) return CloudOutcome.success();
        if (CLOUD_CODE_RATE_LIMITED.equals(r.code)) return CloudOutcome.rateLimited();
        return CloudOutcome.failed();
    }

    // ── Cloud handshake ─────────────────────────────────────────────────

    /**
     * Returns true iff the cloud leg has what it needs to dispatch. Honors
     * the per-command {@link CloudHandshake} declaration:
     * <ul>
     *   <li>{@code SESSION} — credentials + VIN verified (default).</li>
     *   <li>{@code LIVE_CHANNEL} — also need an active MQTT subscriber or
     *       REST poller (the {@code connected} bit on the data provider).</li>
     * </ul>
     * Most BYD remote-control commands work fine with just SESSION because
     * we poll {@code /control/remoteControlResult} over HTTP — there's no
     * live-channel dependency. Override LIVE_CHANNEL only if a command
     * genuinely needs a push subscription.
     */
    private boolean cloudHandshakeSatisfied(VehicleCommand cmd) {
        if (!cmd.hasCloudPath()) return false;
        try {
            BydCloudConfig cfg = BydCloudConfig.fromUnifiedConfig();
            if (!cfg.isVerified()) return false;
            if (cmd.cloudHandshake() == CloudHandshake.LIVE_CHANNEL) {
                JSONObject status = BydCloudDataProvider.getInstance().getStatusJson();
                return status != null && status.optBoolean("connected", false);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isVehicleAwake() {
        BydVehicleData d = BydDataCollector.getInstance().getData();
        return d != null && d.powerLevel != BydVehicleData.UNAVAILABLE && d.powerLevel >= 2;
    }

    /**
     * Refuses a motion-sensitive command while the vehicle is moving. Returns
     * {@code null} when the command may proceed (either it's not motion-sensitive,
     * or {@link DrivingSafetyGuard} confirms the vehicle is safely parked).
     *
     * <p>Called from both {@link #execute} and {@link #executeSdkOnly} — they are
     * independent public entry points (the MQTT/Home-Assistant path calls
     * executeSdkOnly directly, never execute), so each needs its own call to this
     * shared helper rather than relying on a single insertion point.
     */
    private CommandResult checkDrivingSafety(VehicleCommand cmd) {
        if (cmd.motionSafety() != VehicleCommand.MotionSafety.BLOCK_WHILE_MOVING) return null;
        if (!DrivingSafetyGuard.isMovementBlocked()) return null;
        logger.warn("Blocked '" + cmd.name() + "' — vehicle in motion");
        return CommandResult.blocked(msg("blocked_driving"));
    }

    // ── i18n key resolution ─────────────────────────────────────────────

    private static String msg(String key) {
        return com.overdrive.app.server.Messages.get("vehicle_control." + key);
    }
}
