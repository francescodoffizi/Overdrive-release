package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.byd.routing.VehicleCommandRouter.VehicleCommand;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of <b>controllable</b> vehicle entities exposed over MQTT / Home Assistant.
 *
 * Each entry knows two things:
 *   1. how to render itself as a Home Assistant discovery component (with its
 *      command/state topics, value domain, icon, …); and
 *   2. how to turn an inbound MQTT command payload into a
 *      {@link VehicleCommand} (always dispatched <b>SDK-only</b> — never the BYD cloud)
 *      plus an optional optimistic state echo.
 *
 * Read-back state topics reuse the existing per-field telemetry topics where the value
 * is already published ({@code ac_fan}, {@code light_drl}, {@code speed_limit_warning},
 * …). Where no telemetry field exists (e.g. the climate setpoint), the command echoes
 * the commanded value to a synthetic state topic so HA stays in sync.
 *
 * Only Tier-1 (already-implemented, SDK-backed) commands are registered here initially;
 * Tier 2/3 add entries following the same factories.
 */
public final class VehicleControlCatalog {

    private VehicleControlCatalog() {}

    /** Result of turning a payload into an action: the SDK command + optional optimistic echo. */
    public static final class ControlAction {
        public final VehicleCommand command;
        public final String echoKey;    // state-topic suffix to publish optimistically (nullable)
        public final String echoValue;  // value to publish (nullable)
        /** Deferred "remember what we commanded" hook for blind-toggle entities that have no
         *  readback (see {@link ControlEntity#toAction}). Null when there is nothing to
         *  remember. Callers MUST invoke {@link #commitToggleState()} only after the command
         *  actually succeeded — committing at build time made a REFUSED write still advance
         *  the cache, so the next blind "toggle" flipped away from a value the car never
         *  reached and the button appeared to need two presses. */
        private final Runnable toggleCommit;
        ControlAction(VehicleCommand c, String k, String v) { this(c, k, v, null); }
        ControlAction(VehicleCommand c, String k, String v, Runnable commit) {
            command = c; echoKey = k; echoValue = v; toggleCommit = commit;
        }
        public static ControlAction of(VehicleCommand c) { return new ControlAction(c, null, null); }
        public static ControlAction echo(VehicleCommand c, String k, String v) { return new ControlAction(c, k, v); }
        /** Record the just-commanded value so the next blind toggle flips from it. Idempotent
         *  and a no-op for entities with live readback.
         *
         *  <p>Call this when the command was actually ATTEMPTED against the vehicle — see
         *  {@link #commitIfAttempted}, which is what all callers use. Committing only on a
         *  confirmed SUCCESS sounds safer but breaks the feature outright on any HAL that
         *  cannot confirm: the cache never advances, so every "toggle" press re-sends the
         *  identical value and the opposite action becomes unreachable. A blind toggle has no
         *  readback by definition, so tracking "what we last commanded" is the only coherent
         *  semantics — the alternative is a control that only ever does one thing. */
        public void commitToggleState() {
            if (toggleCommit != null) toggleCommit.run();
        }
        /**
         * Commit the blind-toggle value iff the command actually reached the vehicle.
         *
         * <p>{@code SUCCESS} and {@code FAILED} both mean "we asked the car" — for a set-only
         * switch with no readback the resulting state is unknown either way, so the next press
         * must still alternate, otherwise the control freezes on one value forever on any HAL
         * that cannot confirm a write.
         *
         * <p>Everything else means nothing was commanded, so committing would silently swap
         * what the NEXT press does: {@code BLOCKED_DRIVING} (refused by the motion-safety
         * gate), {@code AUTH_REQUIRED}, {@code RATE_LIMITED}, and — importantly —
         * {@code NOT_SUPPORTED}, which {@code VehicleCommandRouter} returns from its
         * capability guards BEFORE invoking any leg (no SDK path / no cloud path / no legs at
         * all), not only after a real attempt.
         */
        public void commitIfAttempted(VehicleCommandRouter.Outcome outcome) {
            if (outcome == null) return;
            if (outcome == VehicleCommandRouter.Outcome.SUCCESS
                    || outcome == VehicleCommandRouter.Outcome.FAILED) {
                commitToggleState();
            }
        }
        ControlAction withToggleCommit(Runnable commit) {
            return new ControlAction(command, echoKey, echoValue, commit);
        }
    }

    /** Builds a command from (sub-key, payload, current snapshot). Return null to ignore. */
    public interface CommandFn {
        ControlAction build(String sub, String payload, BydVehicleData snap);
    }

    /** Optional capability gate — only advertise the entity when this returns true. */
    public interface AvailableFn {
        boolean available(BydVehicleData snap);
    }

    /**
     * Optional reader of an entity's current ON/OFF state from a live snapshot, used
     * to resolve a {@code "toggle"} payload into a concrete on/off before the command
     * is built. Returns null when the state is unknown (never reported) so the toggle
     * can fall back to a safe default. Only meaningful for on/off switch entities.
     */
    public interface StateFn {
        Boolean isOn(BydVehicleData snap);
    }

    /**
     * Optional reader of a SELECT entity's current option INDEX (0-based, into its
     * {@code options} list), used to resolve a {@code "toggle"}/cycle press by reading
     * the live mode and advancing from it — the same read-then-flip the OEM firmware
     * does. Returns -1 when the state is unavailable (getter absent on this trim), so
     * the cycle falls back to the last-commanded cache / default. Distinct from
     * {@link StateFn} (which is on/off for switches).
     */
    public interface SelectStateFn {
        int currentIndex();
    }

    /** One controllable entity. */
    public static final class ControlEntity {
        public final String key;            // topic key + unique_id suffix
        public final String platform;       // switch/number/select/button/lock/cover/climate
        public final String name;
        public final String icon;
        public final String category;       // "config"/"diagnostic"/null
        public final boolean sensitive;     // windows/sunroof/locks etc.
        public final String stateKey;       // existing telemetry key for state_topic; null = command-only/optimistic
        // select-only: Jinja value_template applied on the state topic to map an
        // enum int (as published by telemetry) onto an option word; null = none.
        public final String stateValueTemplate;
        // platform extras
        public final double min, max, step;
        public final String unit;
        public final List<String> options;
        public final String deviceClass;
        public final String onVal, offVal;
        public final CommandFn cmd;
        public final AvailableFn avail;
        // Optional current-state reader; when set, a "toggle" payload is resolved to
        // the opposite of this before the command is built. null → no toggle support.
        public final StateFn state;
        // Optional SELECT current-index reader; when set, a "toggle"/cycle press reads
        // the live option index and advances from it (parity with the OEM read-then-flip
        // toggle). null → cycle falls back to last-commanded cache / default.
        public final SelectStateFn selectState;

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail) {
            this(key, platform, name, icon, category, sensitive, stateKey, min, max, step,
                    unit, options, deviceClass, onVal, offVal, cmd, avail, null, null, null);
        }

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail, String stateValueTemplate) {
            this(key, platform, name, icon, category, sensitive, stateKey, min, max, step,
                    unit, options, deviceClass, onVal, offVal, cmd, avail, stateValueTemplate, null, null);
        }

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail, String stateValueTemplate, StateFn state) {
            this(key, platform, name, icon, category, sensitive, stateKey, min, max, step,
                    unit, options, deviceClass, onVal, offVal, cmd, avail, stateValueTemplate, state, null);
        }

        ControlEntity(String key, String platform, String name, String icon, String category,
                      boolean sensitive, String stateKey, double min, double max, double step,
                      String unit, List<String> options, String deviceClass, String onVal, String offVal,
                      CommandFn cmd, AvailableFn avail, String stateValueTemplate, StateFn state,
                      SelectStateFn selectState) {
            this.key = key; this.platform = platform; this.name = name; this.icon = icon;
            this.category = category; this.sensitive = sensitive; this.stateKey = stateKey;
            this.min = min; this.max = max; this.step = step; this.unit = unit; this.options = options;
            this.deviceClass = deviceClass; this.onVal = onVal; this.offVal = offVal;
            this.cmd = cmd; this.avail = avail; this.stateValueTemplate = stateValueTemplate;
            this.state = state;
            this.selectState = selectState;
        }

        public boolean isAvailable(BydVehicleData snap) {
            try { return avail == null || avail.available(snap); } catch (Exception e) { return true; }
        }

        /** Whether this entity supports a "toggle" payload (has a state reader). */
        public boolean supportsToggle() { return state != null; }

        public ControlAction toAction(String sub, String payload, BydVehicleData snap) {
            try {
                if (payload != null && "toggle".equalsIgnoreCase(payload.trim())) {
                    // Two toggle strategies:
                    //  (a) SWITCH with a live state reader → flip the reported on/off.
                    //  (b) SELECT with a fixed option list but NO telemetry readback
                    //      (regen/steering/brake feel — the HAL exposes no getter) →
                    //      CYCLE to the next option, tracked by a last-commanded cache
                    //      so repeated presses walk the list (comfort→sport→comfort…).
                    if (state != null) {
                        Boolean on = null;
                        try { on = state.isOn(snap); } catch (Exception ignored) {}
                        // Unknown current state (never reported) → default to turning ON,
                        // the more useful "make it happen" outcome for a single press.
                        boolean next = (on == null) ? true : !on;
                        payload = next ? "on" : "off";
                    } else if (options != null && !options.isEmpty()) {
                        payload = nextCyclePayload();
                    } else if (onVal != null) {
                        // (c) SET-ONLY SWITCH with no state reader and no option list (mirror
                        // fold). No live readback exists, so flip off the last-commanded value
                        // (default OFF → first press turns ON, i.e. folds). This is the "blind
                        // flip" the keymap/automation UI advertises; without it "toggle" fell
                        // through and truthy("toggle")==false made every press UNFOLD.
                        String last = LAST_SWITCH_PAYLOAD.get(key);
                        boolean wasOn = last != null && last.equalsIgnoreCase(onVal);
                        payload = wasOn ? offVal : onVal;
                    }
                    // Anything else: "toggle" falls through to the builder unchanged
                    // (which treats the unknown payload via its own default).
                }
                ControlAction action = cmd.build(sub, payload, snap);
                // Remember the last concrete payload we commanded for a cycle-capable
                // select, so the NEXT "toggle" advances from here even without readback.
                if (action != null && options != null && !options.isEmpty()
                        && payload != null && !"toggle".equalsIgnoreCase(payload.trim())) {
                    LAST_SELECT_PAYLOAD.put(key, payload);
                }
                // Same for a set-only switch (mirror fold): record the concrete on/off value
                // just commanded so the next "toggle" flips from it. DEFERRED, not applied
                // here: this runs at BUILD time, before the command is executed, so writing the
                // cache unconditionally would advance it even for a press the motion-safety
                // gate refuses outright — silently swapping what the NEXT press does. The
                // caller applies it via ControlAction.commitIfAttempted(outcome) once the
                // command has actually reached the vehicle (see that method for why "only on
                // SUCCESS" is the wrong bar for a control with no readback).
                if (action != null && state == null && (options == null || options.isEmpty())
                        && onVal != null && payload != null
                        && !"toggle".equalsIgnoreCase(payload.trim())) {
                    final String committed = truthy(payload) ? onVal : offVal;
                    final String k = key;
                    action = action.withToggleCommit(() -> LAST_SWITCH_PAYLOAD.put(k, committed));
                }
                return action;
            } catch (Exception e) { return null; }
        }

        /**
         * Advance a select entity to its next option for a "toggle"/cycle press.
         *
         * <p>Current-index resolution, best-to-worst — mirroring how the OEM firmware
         * toggles (it reads the live mode back):
         *   1. LIVE READBACK (selectState): the SDK getter for this mode
         *      (getBrakeFootSense / getEnergyFeedback / getSteerAssist). This is the
         *      robust source — it reflects a mode changed from the car's own menu and
         *      survives a daemon restart. Returns the app-level option index, or -1 if
         *      the getter is unavailable on this trim.
         *   2. LAST-COMMANDED cache: what we last set (process-lifetime).
         *   3. DEFAULT assumption: the HAL boots these at option[0] (standard/comfort),
         *      so with nothing known we assume idx 0 and advance to option[1] — the
         *      first press then visibly CHANGES the mode instead of re-commanding the
         *      default and appearing to do nothing.
         * Cycles option[i] → option[i+1] (wrapping), case-insensitive.
         */
        private String nextCyclePayload() {
            int idx = -1;
            // 1. Live readback (parity with the OEM firmware's read-then-flip toggle).
            if (selectState != null) {
                try {
                    int live = selectState.currentIndex();
                    if (live >= 0 && live < options.size()) idx = live;
                } catch (Exception ignored) {}
            }
            // 2. Last-commanded cache.
            if (idx < 0) {
                String last = LAST_SELECT_PAYLOAD.get(key);
                if (last != null) {
                    for (int i = 0; i < options.size(); i++) {
                        if (options.get(i).equalsIgnoreCase(last)) { idx = i; break; }
                    }
                }
            }
            // 3. Default: assume booted at option[0] so the first press flips to [1].
            if (idx < 0) idx = 0;
            int nextIdx = (idx + 1) % options.size();
            return options.get(nextIdx);
        }

        /** Build the Home Assistant discovery component (with topics injected). */
        public JSONObject component(String baseTopic, String node) {
            try {
                JSONObject c = new JSONObject();
                c.put("p", platform);
                c.put("name", name);
                c.put("unique_id", node + "_ctl_" + key);
                if (icon != null) c.put("icon", icon);
                if (category != null) c.put("entity_category", category);
                String cmdBase = baseTopic + "/" + key;

                switch (platform) {
                    case "climate": {
                        c.put("modes", new JSONArray(java.util.Arrays.asList("off", "auto")));
                        c.put("mode_command_topic", cmdBase + "/mode/set");
                        c.put("mode_state_topic", baseTopic + "/ac_on");
                        c.put("mode_state_template", "{% if (value | int(0)) > 0 %}auto{% else %}off{% endif %}");
                        c.put("temperature_command_topic", cmdBase + "/temperature/set");
                        c.put("temperature_state_topic", baseTopic + "/climate_setpoint");
                        c.put("min_temp", min); c.put("max_temp", max); c.put("temp_step", step);
                        JSONArray fans = new JSONArray();
                        for (int i = 0; i <= 7; i++) fans.put(String.valueOf(i));
                        c.put("fan_modes", fans);
                        c.put("fan_mode_command_topic", cmdBase + "/fan_mode/set");
                        c.put("fan_mode_state_topic", baseTopic + "/ac_fan");
                        c.put("current_temperature_topic", baseTopic + "/cabin_temp");
                        break;
                    }
                    case "cover": {
                        if (deviceClass != null) c.put("device_class", deviceClass);
                        c.put("command_topic", cmdBase + "/set");
                        c.put("payload_open", "OPEN");
                        c.put("payload_close", "CLOSE");
                        c.put("payload_stop", "STOP");
                        if (stateKey != null) {
                            c.put("position_topic", baseTopic + "/" + stateKey);
                            c.put("set_position_topic", cmdBase + "/position/set");
                            c.put("position_open", 100);
                            c.put("position_closed", 0);
                        } else {
                            c.put("optimistic", true);
                        }
                        break;
                    }
                    case "lock": {
                        c.put("command_topic", cmdBase + "/set");
                        c.put("payload_lock", "LOCK");
                        c.put("payload_unlock", "UNLOCK");
                        if (stateKey != null) {
                            c.put("state_topic", baseTopic + "/" + stateKey);
                            c.put("state_locked", onVal != null ? onVal : "2");
                            c.put("state_unlocked", offVal != null ? offVal : "1");
                        } else {
                            c.put("optimistic", true);
                        }
                        break;
                    }
                    case "number": {
                        c.put("command_topic", cmdBase + "/set");
                        if (stateKey != null) c.put("state_topic", baseTopic + "/" + stateKey);
                        c.put("min", min); c.put("max", max); c.put("step", step);
                        if (unit != null) c.put("unit_of_measurement", unit);
                        c.put("mode", "slider");
                        break;
                    }
                    case "select": {
                        c.put("command_topic", cmdBase + "/set");
                        if (stateKey != null) {
                            c.put("state_topic", baseTopic + "/" + stateKey);
                            // Telemetry publishes the enum as a raw int; map it onto the
                            // option word so the HA select accepts the state. The `else value`
                            // passthrough leaves an already-word-valued echo untouched.
                            if (stateValueTemplate != null) c.put("value_template", stateValueTemplate);
                        }
                        c.put("options", new JSONArray(options));
                        break;
                    }
                    case "button": {
                        c.put("command_topic", cmdBase + "/set");
                        c.put("payload_press", "PRESS");
                        break;
                    }
                    case "switch":
                    default: {
                        c.put("command_topic", cmdBase + "/set");
                        if (stateKey != null) c.put("state_topic", baseTopic + "/" + stateKey);
                        c.put("payload_on", onVal != null ? onVal : "1");
                        c.put("payload_off", offVal != null ? offVal : "0");
                        c.put("state_on", onVal != null ? onVal : "1");
                        c.put("state_off", offVal != null ? offVal : "0");
                        break;
                    }
                }
                return c;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ==================== registry ====================

    private static final Map<String, ControlEntity> ENTITIES = new LinkedHashMap<>();

    // Last concrete payload commanded per readback-less SELECT entity (regen/steering/
    // brake feel), so a "toggle"/cycle press can advance to the next option without any
    // telemetry getter. Written from key-map / MQTT / automation dispatch threads →
    // concurrent map. Process-lifetime cache; a daemon restart just resets to "start
    // from first option", which is fine (the first press picks a deterministic option).
    private static final Map<String, String> LAST_SELECT_PAYLOAD = new java.util.concurrent.ConcurrentHashMap<>();

    // Last concrete payload commanded per readback-less SWITCH entity (mirror fold — a
    // set-only on/off switch with no fold-state getter). Lets a "toggle" press flip off the
    // last-commanded value, which is the "blind flip" the keymap/automation UI promises.
    // Same lifecycle/concurrency notes as LAST_SELECT_PAYLOAD.
    private static final Map<String, String> LAST_SWITCH_PAYLOAD = new java.util.concurrent.ConcurrentHashMap<>();

    private static void register(ControlEntity e) { ENTITIES.put(e.key, e); }

    public static Collection<ControlEntity> all() { return ENTITIES.values(); }
    public static ControlEntity get(String key) { return ENTITIES.get(key); }

    // ── factories ───────────────────────────────────────────────────────
    static ControlEntity sw(String key, String name, String icon, String category, String stateKey,
                            String onVal, String offVal, CommandFn cmd) {
        return new ControlEntity(key, "switch", name, icon, category, false, stateKey,
                0, 0, 0, null, null, null, onVal, offVal, cmd, null);
    }
    /** Switch with a live state reader → supports a "toggle" payload (flip current). */
    static ControlEntity sw(String key, String name, String icon, String category, String stateKey,
                            String onVal, String offVal, CommandFn cmd, StateFn state) {
        return new ControlEntity(key, "switch", name, icon, category, false, stateKey,
                0, 0, 0, null, null, null, onVal, offVal, cmd, null, null, state);
    }
    static ControlEntity number(String key, String name, String icon, String category, String stateKey,
                                double min, double max, double step, String unit, CommandFn cmd) {
        return new ControlEntity(key, "number", name, icon, category, false, stateKey,
                min, max, step, unit, null, null, null, null, cmd, null);
    }
    static ControlEntity select(String key, String name, String icon, String category, String stateKey,
                                List<String> options, CommandFn cmd) {
        return new ControlEntity(key, "select", name, icon, category, false, stateKey,
                0, 0, 0, null, options, null, null, null, cmd, null);
    }
    static ControlEntity select(String key, String name, String icon, String category, String stateKey,
                                List<String> options, String stateValueTemplate, CommandFn cmd) {
        return new ControlEntity(key, "select", name, icon, category, false, stateKey,
                0, 0, 0, null, options, null, null, null, cmd, null, stateValueTemplate);
    }
    /** Select with a live current-index reader → a "toggle"/cycle press reads the live
     *  mode and advances from it (parity with the OEM read-then-flip toggle). */
    static ControlEntity select(String key, String name, String icon, String category, String stateKey,
                                List<String> options, CommandFn cmd, SelectStateFn selectState) {
        return new ControlEntity(key, "select", name, icon, category, false, stateKey,
                0, 0, 0, null, options, null, null, null, cmd, null, null, null, selectState);
    }
    static ControlEntity cover(String key, String name, String icon, String deviceClass, boolean sensitive,
                               String stateKey, CommandFn cmd) {
        return new ControlEntity(key, "cover", name, icon, null, sensitive, stateKey,
                0, 0, 0, null, null, deviceClass, null, null, cmd, null);
    }
    static ControlEntity climate(CommandFn cmd) {
        return new ControlEntity("climate", "climate", "Climate", "mdi:air-conditioner", null, false, null,
                17, 33, 1, null, null, null, null, null, cmd, null);
    }

    // ── helpers ─────────────────────────────────────────────────────────
    static int pInt(String s, int dflt) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; } }
    static double pDouble(String s, double dflt) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return dflt; } }
    static boolean truthy(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.equals("1") || t.equalsIgnoreCase("on") || t.equalsIgnoreCase("true") || t.equalsIgnoreCase("ON");
    }
    private static int seatHeat(BydVehicleData s, int idx) {
        return (s != null && s.seatHeat != null && s.seatHeat.length > idx) ? s.seatHeat[idx] : 0;
    }
    private static int seatCool(BydVehicleData s, int idx) {
        return (s != null && s.seatCool != null && s.seatCool.length > idx) ? s.seatCool[idx] : 0;
    }
    private static final List<String> LEVEL_4 = java.util.Arrays.asList("off", "low", "medium", "high");
    private static int levelIndex(String payload) {
        int i = LEVEL_4.indexOf(payload.trim().toLowerCase());
        return i >= 0 ? i : pInt(payload, 0);
    }
    // BYD operation-mode enum values from the SDK docs
    // (doc/android/hardware/bydauto/energy/BYDAutoEnergyDevice.html + constant-values):
    //   ENERGY_OPERATION_ECONOMY = 1, ENERGY_OPERATION_SPORT = 2.
    // These are the ONLY two operation modes the SDK defines — there is no
    // NORMAL and no SNOW here (SNOW is a *road-surface* value on a different
    // axis: ENERGY_ROAD_SURFACE_SNOW = 2). The earlier 0-based list
    // (eco=0/sport=1/normal=2/snow=3) sent setOperationMode(0) for ECO, which is
    // not a valid operation-mode value, so the HAL rejected the write
    // (ENERGY_COMMAND_INVALID) and the mode never changed. Map the words to the
    // real SDK ints; a raw int passes through.
    private static final List<String> DRIVE_MODES = java.util.Arrays.asList("normal", "eco", "sport");
    // Drive mode on the setting-device "drive config" axis (see
    // BydDataCollector.setDriveConfigMode): NORMAL=1, ECO=2, SPORT=3, SNOW=4.
    // NOTE: this is the config axis, NOT the energy-device operation-mode axis
    // (which is eco=1/sport=2 and has no NORMAL).
    private static int driveModeValue(String payload) {
        String p = payload.trim().toLowerCase();
        if ("normal".equals(p)) return 1;
        if ("eco".equals(p) || "economy".equals(p)) return 2;
        if ("sport".equals(p)) return 3;
        if ("snow".equals(p)) return 4;
        return pInt(payload, 1);
    }

    /** Config-axis drive-mode int → option word (inverse of driveModeValue). */
    private static String driveModeWord(int v) {
        switch (v) {
            case 1: return "normal";
            case 2: return "eco";
            case 3: return "sport";
            case 4: return "snow";
            default: return "normal";
        }
    }

    static {
        // ── Climate (composite) ─────────────────────────────────────────
        register(climate((sub, payload, snap) -> {
            if ("mode".equals(sub)) {
                if ("off".equalsIgnoreCase(payload.trim())) {
                    return ControlAction.echo(new VehicleCommandRouter.ClimateOffCommand(), "ac_on", "0");
                }
                double setpoint = 22;
                return ControlAction.echo(new VehicleCommandRouter.ClimateOnCommand(setpoint), "ac_on", "1");
            }
            if ("temperature".equals(sub)) {
                double t = pDouble(payload, 22);
                return ControlAction.echo(new VehicleCommandRouter.ClimateSetTempCommand(1, t),
                        "climate_setpoint", String.valueOf(t));
            }
            if ("fan_mode".equals(sub)) {
                int f = pInt(payload, 0);
                return ControlAction.echo(new VehicleCommandRouter.ClimateSetFanCommand(f), "ac_fan", String.valueOf(f));
            }
            return null;
        }));

        // ── Windows (all) — cover, command-only (per-window + position: Tier 2) ──
        register(cover("windows_all", "Windows", "mdi:car-door", "window", true, null, (sub, payload, snap) -> {
            // CLOSE-all routes to the dedicated CloseAllWindowsCommand (CLOUD_FIRST with
            // SDK fallback), NOT the bare local setAllWindowState(2,2,2,2). On this
            // generation the local all-windows CLOSE is unreliable (anti-pinch / the HAL
            // often ignores a simultaneous 4-window raise), which is exactly why the
            // composite cloud CLOSEWINDOW command exists — that's the path that actually
            // raises the windows. OPEN/STOP keep the direct local move (they work locally
            // and must stay instant/offline). This fixes "windows up (close) mapping does
            // nothing" while "down (open) works".
            if ("CLOSE".equalsIgnoreCase(payload)) {
                return ControlAction.of(new VehicleCommandRouter.CloseAllWindowsCommand());
            }
            int action = "OPEN".equalsIgnoreCase(payload) ? 1 : "STOP".equalsIgnoreCase(payload) ? 3 : 2;
            return ControlAction.of(new VehicleCommandRouter.WindowMoveCommand(0, action, null));
        }));

        // ── Tailgate — cover (open=SDK motor, close/stop) ───────────────
        register(cover("tailgate", "Tailgate", "mdi:car-back", "door", true, null, (sub, payload, snap) -> {
            if ("CLOSE".equalsIgnoreCase(payload)) return ControlAction.of(new VehicleCommandRouter.TrunkCloseCommand());
            if ("STOP".equalsIgnoreCase(payload)) return ControlAction.of(new VehicleCommandRouter.TrunkStopCommand());
            return ControlAction.of(new VehicleCommandRouter.TrunkOpenSdkCommand());
        }));

        // ── Seat heating (driver/passenger) — select off/low/medium/high ─
        register(select("seat_heat_driver", "Driver Seat Heating", "mdi:car-seat-heater", null,
                "seat_heat_driver", LEVEL_4, (sub, payload, snap) -> {
            int lvl = levelIndex(payload);
            VehicleCommand c = new VehicleCommandRouter.SeatHeatCommand(1, lvl,
                    lvl, seatCool(snap, 0), seatHeat(snap, 1), seatCool(snap, 1));
            return ControlAction.echo(c, "seat_heat_driver", LEVEL_4.get(Math.min(3, Math.max(0, lvl))));
        }));
        register(select("seat_heat_passenger", "Passenger Seat Heating", "mdi:car-seat-heater", null,
                "seat_heat_passenger", LEVEL_4, (sub, payload, snap) -> {
            int lvl = levelIndex(payload);
            VehicleCommand c = new VehicleCommandRouter.SeatHeatCommand(2, lvl,
                    seatHeat(snap, 0), seatCool(snap, 0), lvl, seatCool(snap, 1));
            return ControlAction.echo(c, "seat_heat_passenger", LEVEL_4.get(Math.min(3, Math.max(0, lvl))));
        }));

        // ── Seat ventilation (driver/passenger) — select ────────────────
        register(select("seat_vent_driver", "Driver Seat Ventilation", "mdi:car-seat-cooler", null,
                "seat_vent_driver", LEVEL_4, (sub, payload, snap) -> {
            int lvl = levelIndex(payload);
            VehicleCommand c = new VehicleCommandRouter.SeatVentCommand(1, lvl,
                    seatHeat(snap, 0), lvl, seatHeat(snap, 1), seatCool(snap, 1));
            return ControlAction.echo(c, "seat_vent_driver", LEVEL_4.get(Math.min(3, Math.max(0, lvl))));
        }));
        register(select("seat_vent_passenger", "Passenger Seat Ventilation", "mdi:car-seat-cooler", null,
                "seat_vent_passenger", LEVEL_4, (sub, payload, snap) -> {
            int lvl = levelIndex(payload);
            VehicleCommand c = new VehicleCommandRouter.SeatVentCommand(2, lvl,
                    seatHeat(snap, 0), seatCool(snap, 0), seatHeat(snap, 1), lvl);
            return ControlAction.echo(c, "seat_vent_passenger", LEVEL_4.get(Math.min(3, Math.max(0, lvl))));
        }));

        // ── Seat memory recall — buttons ────────────────────────────────
        register(new ControlEntity("seat_memory_driver", "button", "Recall Driver Seat", "mdi:seat-recline-extra",
                null, false, null, 0, 0, 0, null, null, null, null, null,
                (sub, payload, snap) -> ControlAction.of(new VehicleCommandRouter.SeatMemoryCommand(1)), null));

        // ── Daytime running lights — switch (real state, toggle-capable) ─
        register(sw("drl", "Daytime Running Lights", "mdi:car-light-dimmed", null, "light_drl", "1", "0",
                (sub, payload, snap) -> ControlAction.of(new VehicleCommandRouter.LightsCommand(truthy(payload))),
                snap -> snap == null ? null : snap.dayTimeLight));

        // ── Hazard (double-flash) lights — switch ────────────────────────────
        // State published to light_hazard (getLightStatus(8) → snap.hazard). The READBACK is
        // ALSO unverified, not just the write: the SDK's light-type table has no hazard entry
        // and position 8 is LIGHT_FOOT (the footwell lamp), so this switch's reported state may
        // actually track the courtesy lighting. See BydDataCollector.collectLight.
        // The SET (double-flash COMMAND feature) is UNCONFIRMED on this firmware — no
        // reference-app precedent and an inferred feature id — so the write may be refused by
        // the HAL; setHazardLights returns false in that case. Validate actuation via
        // GET /api/debug/light/fire?candidate=A before relying on it.
        register(sw("hazard", "Hazard Lights", "mdi:car-light-alert", null, "light_hazard", "1", "0",
                (sub, payload, snap) -> ControlAction.of(new VehicleCommandRouter.HazardCommand(truthy(payload))),
                snap -> snap == null ? null : snap.hazard));

        // ── Ambient lights colour — number (real state, 1-based palette index) ──
        register(number("ambient_colour", "Ambient Lights Colour", "mdi:format-color-fill", "config",
                "ambient_colour", 1, 31, 1, "", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.AmbientColourCommand(pInt(payload, 1)))));

        // ── Ambient lights main switch — switch (whole cabin) ────────────────
        // State published to ambient_enabled ONLY when the vehicle actually reports it (the
        // Light-device status feature or the atmosphere_lamp flag); a trim that reports neither
        // leaves this unavailable rather than showing a wrong "off". The write walks all three
        // tiers — see BydDataCollector.setAmbientLightEnabled.
        //
        // The live reader gives this switch a real "toggle" (flip the reported state). On a trim
        // that reports NO state the reader returns null, so "toggle" follows the documented
        // default and turns ON every press (see ControlEntity.toAction strategy (a)) — explicit
        // on/off payloads still work either way, which is what HA itself sends.
        register(sw("ambient_power", "Ambient Lights", "mdi:track-light", "config", "ambient_enabled",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.AmbientPowerCommand(truthy(payload))),
                snap -> snap == null || snap.ambientEnabled == com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                        ? null : snap.ambientEnabled == 1));

        // ── Ambient lights brightness — number, whole cabin, 0-100% ──────────
        // Optimistic (echo): the SDK exposes a 0..5 LEVEL per zone, not a whole-cabin percent,
        // so there is no single field to read back — the collector publishes no ambient
        // brightness telemetry. Echoing the commanded value keeps the HA slider in step.
        register(number("ambient_brightness", "Ambient Lights Brightness", "mdi:brightness-6", "config",
                "ambient_brightness", 0, 100, 1, "%", (sub, payload, snap) -> {
                    int v = Math.max(0, Math.min(100, pInt(payload, 0)));
                    return ControlAction.echo(
                            new VehicleCommandRouter.AmbientBrightnessCommand(v), "ambient_brightness",
                            String.valueOf(v));
                }));

        // ── ADAS speed-limit warning — switch (real state, toggle-capable) ─
        register(sw("adas_slw", "Speed Limit Warning", "mdi:speedometer-slow", "config", "speed_limit_warning",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.AdasSpeedLimitWarningCommand(truthy(payload))),
                snap -> snap == null ? null : snap.speedLimitWarning));

        // ── Electronic Stability Program (ESP/ESC) — switch ──────────────
        // SAFETY control. State published to esp_state (1=on/0=off); the ESP feature
        // id is a resolveOrFallback guess (unconfirmed on this firmware) — verify via
        // GET /api/vehicle/adas before relying on it. No "problem" device_class: ESP
        // ON is the desired/normal state.
        register(sw("esp_control", "Stability Control (ESP)", "mdi:car-traction-control", "config", "esp_state",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.AdasEspCommand(truthy(payload)))));

        // ── iTAC (Intelligent Torque Adaption Control) — switch ──────────
        // Performance/traction feature (NOT the ESP stability interlock). No telemetry
        // state field is published, so the state is optimistic (echo the commanded
        // value). The iTAC feature ids are decoded from the DiLink APK — verify via
        // GET /api/vehicle/adas (itac block) before relying on it.
        register(sw("itac", "iTAC (Torque Control)", "mdi:car-cog", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasItacCommand(truthy(payload)),
                        "itac", truthy(payload) ? "1" : "0")));

        // ── ADAS lane assist — select (Off/LDW/LDP/LDW+LDP), live readback ───
        // Multi-mode via BYDAutoADASDevice.setLKSMode. The payload IS the app-level
        // mode int ("0".."3"); a "toggle"/cycle press advances to the next option using
        // the live getLaneAssistMode readback (parity with the OEM read-then-flip).
        register(select("lane_assist", "Lane Assist", "mdi:road-variant", "config", null,
                java.util.Arrays.asList("0", "1", "2", "3"),
                (sub, payload, snap) -> ControlAction.of(
                        new VehicleCommandRouter.AdasLaneAssistCommand(pInt(payload, 0))),
                () -> com.overdrive.app.byd.BydDataCollector.getInstance().getLaneAssistMode()));

        // ── ADAS child presence detection — switch (real state) ──────────────
        // State is published as 1/0 to child_presence_detection (see MqttConnectionManager +
        // TelemetryFieldCatalog): the raw SDK value 1=on/2=off/3=delay is normalized there, so
        // state_on="1"/state_off="0" here match the wire value. Command maps on→1, off→2.
        register(sw("adas_cpd", "Child Presence Detection", "mdi:car-child-seat", "config", "child_presence_detection",
                "1", "0", (sub, payload, snap) ->
                        ControlAction.of(new VehicleCommandRouter.SettingChildPresenceDetectionCommand(truthy(payload) ? 1 : 2)),
                // Raw childPresenceDetection: 1=on, 2=off, 3=delay. "on" iff == 1.
                snap -> snap == null ? null : (snap.childPresenceDetection == 1)));

        // ── Expanded ADAS matrix ─────────────────────────────────────────────
        // All route to adasDevice via BydDataCollector (feature-id or reflection). No
        // telemetry state field is published for these, so state is optimistic (echo).
        // Feature ids / polarity are per the OEM SDK and UNVERIFIED on every trim —
        // verify via GET /api/vehicle/adas before relying on any given one. The
        // auto-brake / lane-keep entries are SAFETY controls (labelled at the action
        // layer); AEB is enable-only there.
        register(sw("adas_bsd", "Blind Spot Detection", "mdi:car-side", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasBlindSpotCommand(truthy(payload)),
                        "adas_bsd", truthy(payload) ? "1" : "0")));
        register(sw("adas_tsr", "Traffic Sign Recognition", "mdi:sign-real-estate", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasTrafficSignCommand(truthy(payload)),
                        "adas_tsr", truthy(payload) ? "1" : "0")));
        register(sw("adas_rcta", "Rear Cross Traffic Alert", "mdi:car-back", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasRearCrossTrafficCommand(truthy(payload)),
                        "adas_rcta", truthy(payload) ? "1" : "0")));
        register(sw("adas_fcta", "Front Cross Traffic Alert", "mdi:car", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasFrontCrossTrafficCommand(truthy(payload)),
                        "adas_fcta", truthy(payload) ? "1" : "0")));
        register(sw("adas_tla", "Traffic Light Attention", "mdi:traffic-light", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasTrafficLightAttentionCommand(truthy(payload)),
                        "adas_tla", truthy(payload) ? "1" : "0")));
        register(sw("adas_dow", "Door Open Warning", "mdi:car-door", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasOpenDoorWarningCommand(truthy(payload)),
                        "adas_dow", truthy(payload) ? "1" : "0")));
        register(sw("adas_rcw", "Rear Collision Warning", "mdi:car-back", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasRearCollisionWarningCommand(truthy(payload)),
                        "adas_rcw", truthy(payload) ? "1" : "0")));
        register(sw("adas_islc", "Speed Limit Control", "mdi:speedometer", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasSpeedLimitControlCommand(truthy(payload)),
                        "adas_islc", truthy(payload) ? "1" : "0")));
        register(sw("adas_elka", "Emergency Lane Keeping", "mdi:road-variant", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasEmergencyLaneKeepCommand(truthy(payload)),
                        "adas_elka", truthy(payload) ? "1" : "0")));
        register(sw("adas_rctb", "Rear Cross Traffic Brake", "mdi:car-brake-alert", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasRearCrossBrakeCommand(truthy(payload)),
                        "adas_rctb", truthy(payload) ? "1" : "0")));
        register(sw("adas_fctb", "Front Cross Traffic Brake", "mdi:car-brake-alert", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasFrontCrossBrakeCommand(truthy(payload)),
                        "adas_fctb", truthy(payload) ? "1" : "0")));
        register(sw("adas_aeb", "Automatic Emergency Braking", "mdi:car-brake-abs", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasEmergencyBrakingCommand(truthy(payload)),
                        "adas_aeb", truthy(payload) ? "1" : "0")));
        register(select("adas_fcw", "Forward Collision Warning", "mdi:car-emergency", "config", null,
                java.util.Arrays.asList("0", "1", "2", "3"),
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.AdasFcwLevelCommand(pInt(payload, 0)),
                        "adas_fcw", String.valueOf(pInt(payload, 0)))));

        // ── Charge cap (BEV) — switch + number, optimistic state ────────
        register(sw("charge_cap_enabled", "Charge Limit", "mdi:battery-charging-100", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.ChargeCapToggleCommand(truthy(payload)),
                        "charge_cap_enabled", truthy(payload) ? "1" : "0")));
        register(number("charge_cap_percent", "Charge Limit %", "mdi:battery-charging-80", "config",
                "charge_cap_percent", 15, 100, 5, "%", (sub, payload, snap) -> {
            int pct = Math.max(15, Math.min(100, pInt(payload, 70)));
            return ControlAction.echo(new VehicleCommandRouter.ChargeCapPercentCommand(pct),
                    "charge_cap_percent", String.valueOf(pct));
        }));

        // ── Tier 2: sunroof / sunshade (covers) + child lock + wireless charger ──
        register(cover("sunroof", "Sunroof", "mdi:window-shutter-open", "window", true, null, (sub, payload, snap) -> {
            int cmd = "OPEN".equalsIgnoreCase(payload) ? 1 : "STOP".equalsIgnoreCase(payload) ? 3 : 2;
            return ControlAction.of(new VehicleCommandRouter.SunroofCommand(cmd));
        }));
        register(cover("sunshade", "Sunshade", "mdi:blinds", "shade", true, null, (sub, payload, snap) -> {
            int cmd = "OPEN".equalsIgnoreCase(payload) ? 1 : "STOP".equalsIgnoreCase(payload) ? 3 : 2;
            return ControlAction.of(new VehicleCommandRouter.SunshadeCommand(cmd));
        }));
        register(sw("child_lock", "Child Lock", "mdi:car-door-lock", "config", null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.ChildLockCommand(truthy(payload)),
                        "child_lock", truthy(payload) ? "1" : "0")));
        // Mirror fold/unfold — set-only (no fold-state getter on this platform), so
        // like child_lock it echoes the commanded value to the last-command cache;
        // a "toggle" press flips off that cache (blind toggle). on=fold, off=unfold.
        register(sw("mirror_fold", "Fold Mirrors", "mdi:car-side", null, null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.MirrorFoldCommand(truthy(payload)),
                        "mirror_fold", truthy(payload) ? "1" : "0")));
        register(sw("wireless_charging", "Phone Wireless Charger", "mdi:battery-charging-wireless", null, null, "1", "0",
                (sub, payload, snap) -> ControlAction.echo(
                        new VehicleCommandRouter.WirelessChargingCommand(truthy(payload)),
                        "wireless_charging", truthy(payload) ? "1" : "0")));

        // ── Drive / energy modes (BYDAutoEnergyDevice / SettingDevice) ────
        // select entities: HA renders a dropdown, keymap binds one option, the
        // automation UI exposes the same option set. Payloads are readable words
        // mapped to the BYD SDK int enum; pInt() also accepts a raw int.
        // drive_mode: telemetry publishes operationMode under "op_mode" (not
        // "operation_mode") as a raw int; bind the state topic there and map the
        // int→word via value_template so the HA select accepts live telemetry.
        // The echo emits the option word directly (in-domain).
        // drive_mode: op_mode telemetry is published on the setting-device "drive
        // config" axis (NORMAL=1, ECO=2, SPORT=3, SNOW=4) — see
        // BydDataCollector.collectEnergy, which reads getDriveConfig (or maps the
        // energy-device getOperationMode up to this axis). Echo the word and map
        // int→word on the state topic using the SAME 1/2/3/4 values, so live
        // telemetry and the optimistic echo agree. Adds NORMAL, which the old
        // energy-device path could not represent.
        register(select("drive_mode", "Drive Mode", "mdi:car-shift-pattern", null, "op_mode",
                DRIVE_MODES,
                "{% set m = value | int(-1) %}{{ 'normal' if m == 1 else 'eco' if m == 2 else 'sport' if m == 3 else 'snow' if m == 4 else value }}",
                (sub, payload, snap) -> {
                    int m = driveModeValue(payload);
                    return ControlAction.echo(new VehicleCommandRouter.OperationModeCommand(m),
                            "op_mode", driveModeWord(m));
                }));
        // powertrain_mode: energy_mode telemetry is the raw SDK energy-mode int
        // (ENERGY_MODE_EV=1, ENERGY_MODE_HEV=3; NOTE 0=ENERGY_MODE_STOP, NOT ev).
        // The old code sent setEnergyMode(0) for EV — which is STOP — so EV never
        // engaged. Map the words to the real SDK ints and mirror them on the
        // state topic.
        final List<String> POWERTRAIN = java.util.Arrays.asList("ev", "hev");
        register(select("powertrain_mode", "Powertrain Mode", "mdi:engine", null, "energy_mode",
                POWERTRAIN,
                "{% set m = value | int(-1) %}{{ 'ev' if m == 1 else 'hev' if m == 3 else value }}",
                (sub, payload, snap) -> {
                    int m = "hev".equalsIgnoreCase(payload.trim()) ? 3   // ENERGY_MODE_HEV
                          : "ev".equalsIgnoreCase(payload.trim()) ? 1    // ENERGY_MODE_EV
                          : pInt(payload, 1);
                    return ControlAction.echo(new VehicleCommandRouter.EnergyModeCommand(m),
                            "energy_mode", m == 3 ? "hev" : "ev");
                }));
        // hold_battery: a friendly alias for "switch to HEV". NOTE this does NOT hold the
        // pack at its current level — field-reported on a SeaLion 6 DM-i: selecting HEV
        // starts the ICE and RECHARGES the battery. A real SOC-hold lever does exist
        // (BYDAutoSettingDevice.setSOCTarget + BYDAutoChargingDevice.setSocSaveSwitch,
        // wired in BydDataCollector as the "charge cap"); the reference app DiPlus drives
        // exactly that pair for its PHEV battery-hold modes. Prefer charge_cap_* for a
        // genuine hold. Any payload commands HEV (3); mirrors energy_mode state as hev.
        register(select("hold_battery", "Hold Battery Charge", "mdi:battery-lock", null, "energy_mode",
                java.util.Arrays.asList("on"),
                "{% set m = value | int(-1) %}{{ 'on' if m == 3 else 'off' }}",
                (sub, payload, snap) ->
                    ControlAction.echo(new VehicleCommandRouter.EnergyModeCommand(3), "energy_mode", "hev")));
        // regen_level: normalized user level fed to BydDataCollector.setEnergyFeedback,
        // which maps 0..2 -> MCU 2..4. standard = 0 (SETTING_ENERGY_FEEDBACK_STANDARD),
        // high = 1 (SETTING_ENERGY_FEEDBACK_LARGE) — per the OEM firmware convention.
        // (Previously sent 1/2, which the setter forwarded raw: 1 was below the valid
        // MCU range and 2 was the HAL's *standard*, so standard no-op'd and high set
        // standard.)
        register(select("regen_level", "Energy Recuperation", "mdi:battery-charging-medium", null, null,
                java.util.Arrays.asList("standard", "high"),
                (sub, payload, snap) -> {
                    int lvl = "high".equalsIgnoreCase(payload.trim()) ? 1   // SETTING_ENERGY_FEEDBACK_LARGE
                            : "standard".equalsIgnoreCase(payload.trim()) ? 0 // SETTING_ENERGY_FEEDBACK_STANDARD
                            : pInt(payload, 0);
                    return ControlAction.of(new VehicleCommandRouter.EnergyFeedbackCommand(lvl));
                },
                // Live readback for toggle: getEnergyFeedback returns app-level 0/1/2;
                // options are [standard(0), high(1)], so clamp a "max"(2) read to high(1).
                () -> {
                    int lvl = com.overdrive.app.byd.BydDataCollector.getInstance().getEnergyFeedback();
                    return lvl < 0 ? -1 : Math.min(lvl, 1);
                }));
        // steering_mode: SET_DR_ST_ASSIS_COMFORT = 1, SET_DR_ST_ASSIS_SPORT = 2
        // (there is no 0). Old code sent 0/1 → the HAL rejected 0.
        register(select("steering_mode", "Steering Assist", "mdi:steering", null, null,
                java.util.Arrays.asList("comfort", "sport"),
                (sub, payload, snap) -> {
                    int m = "sport".equalsIgnoreCase(payload.trim()) ? 2    // SET_DR_ST_ASSIS_SPORT
                          : "comfort".equalsIgnoreCase(payload.trim()) ? 1  // SET_DR_ST_ASSIS_COMFORT
                          : pInt(payload, 1);
                    return ControlAction.of(new VehicleCommandRouter.SteerAssistCommand(m));
                },
                // Live readback for toggle: getSteerAssist returns app-level 0=comfort/1=sport.
                () -> com.overdrive.app.byd.BydDataCollector.getInstance().getSteerAssist()));
        // brake_feel: brake-pedal feel comfort vs sport/strong (BYDAutoADASDevice
        // setBrakeFootSenseState). App-level 0=comfort/1=sport; the collector maps to
        // the HAL value (comfort→2, sport→0). No telemetry state field, so optimistic.
        register(select("brake_feel", "Brake Feel", "mdi:car-brake-alert", null, null,
                java.util.Arrays.asList("comfort", "sport"),
                (sub, payload, snap) -> {
                    int lvl = "sport".equalsIgnoreCase(payload.trim()) ? 1
                            : "comfort".equalsIgnoreCase(payload.trim()) ? 0
                            : pInt(payload, 0);
                    return ControlAction.of(new VehicleCommandRouter.BrakeFeelCommand(lvl));
                },
                // Live readback for toggle: getBrakeFootSense returns app-level 0=comfort/1=sport.
                () -> com.overdrive.app.byd.BydDataCollector.getInstance().getBrakeFootSense()));

        // ── Tier 3: curated CAN-backed car settings (local carsettings provider) ──
        for (com.overdrive.app.byd.BydCarSettings.CarSetting s : com.overdrive.app.byd.BydCarSettings.registry()) {
            final String key = s.key;
            final String stateKey = "setting_" + s.key;
            final int sMin = s.min, sMax = s.max, sStep = s.step;
            final int[] sOpts = s.options;
            switch (s.kind) {
                case BOOL: {
                    register(sw(stateKey, s.name, s.icon, "config", stateKey, "1", "0",
                            (sub, payload, snap) -> ControlAction.echo(
                                    new VehicleCommandRouter.CarSettingCommand(key, truthy(payload) ? 1 : 0),
                                    stateKey, truthy(payload) ? "1" : "0")));
                    break;
                }
                case INT_RANGE: {
                    register(number(stateKey, s.name, s.icon, "config", stateKey, sMin, sMax, sStep, s.unit,
                            (sub, payload, snap) -> {
                                int v = Math.max(sMin, Math.min(sMax, pInt(payload, sMin)));
                                return ControlAction.echo(new VehicleCommandRouter.CarSettingCommand(key, v),
                                        stateKey, String.valueOf(v));
                            }));
                    break;
                }
                case INT_ENUM: {
                    java.util.List<String> opts = new java.util.ArrayList<>();
                    for (int o : sOpts) opts.add(String.valueOf(o));
                    register(select(stateKey, s.name, s.icon, "config", stateKey, opts,
                            (sub, payload, snap) -> {
                                int v = pInt(payload, sOpts.length > 0 ? sOpts[0] : 0);
                                return ControlAction.echo(new VehicleCommandRouter.CarSettingCommand(key, v),
                                        stateKey, String.valueOf(v));
                            }));
                    break;
                }
            }
        }
    }
}
