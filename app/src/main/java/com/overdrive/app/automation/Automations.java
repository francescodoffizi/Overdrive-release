package com.overdrive.app.automation;

import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.action.Actions;
import com.overdrive.app.automation.condition.Conditions;
import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.condition.TimeEvent;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.automation.value.StringValue;
import com.overdrive.app.automation.value.Value;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Automations {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    private static final File AUTOMATION_HOME = new File("/data/local/tmp/.automations");
    private static final File AUTOMATION_CONFIG = new File(AUTOMATION_HOME, "config.json");
    // Last-known-good backup + scratch file for the atomic write. loadFromFile falls back to .bak when
    // the live file is truncated/corrupt (e.g. daemon killed mid-write on ACC-off), so a torn write can
    // never silently wipe every configured automation.
    private static final File AUTOMATION_BACKUP = new File(AUTOMATION_HOME, "config.json.bak");
    private static final File AUTOMATION_TMP = new File(AUTOMATION_HOME, "config.json.tmp");
    // Serializes the read-snapshot-write sequence so two concurrent HTTP request threads can't interleave
    // their FileOutputStreams and produce a mangled file.
    private static final Object SAVE_LOCK = new Object();
    private static final Map<EventData, Value> state = new ConcurrentHashMap<>();
    // The schema object graph (~200 Label/EnumType/EventCondition/Action objects) is built lazily on
    // first use rather than at class-load. A daemon with zero configured automations touches Automations
    // on every telemetry snapshot (via BydEvent.isDisabled()) but never needs the schema, so this keeps
    // the "no automation configured => no extra compute" property honest — the graph only materializes
    // when an automation is loaded from disk or the schema API is hit.
    private static volatile Conditions conditions;
    private static volatile Actions actions;
    private static volatile Type delay;
    private static final Object SCHEMA_LOCK = new Object();
    private static final Map<String, Automation> automations = new ConcurrentHashMap<>();
    // O(1) enabled-automation count so isDisabled() (called ~30x per telemetry snapshot on the daemon
    // hot path) is a field read instead of a full stream scan of the map. Maintained under SAVE_LOCK
    // alongside every mutation. volatile for cross-thread visibility from the telemetry thread.
    private static volatile int enabledCount = 0;

    static {
        // Load config from the file at startup
        loadFromFile();
        // Load reusable action groups too (separate file). Order doesn't matter — an
        // actionGroup action resolves its group by id lazily at run time — but loading
        // here makes them ready before the first automation fires.
        ActionGroups.loadFromFile();

        // Start publishing the current time-of-day / day-of-week into the state so
        // time and day conditions can be evaluated.
        TimeEvent.scheduleTimeEvent();

        // Start the low-cadence WiFi/system-boot poll (self-gating: does nothing while
        // no automation is enabled). Feeds wifiState / wifiSsid / boot events.
        com.overdrive.app.automation.condition.NetworkEvent.scheduleNetworkEvent();

        // NOTE: btState / btDeviceName are NOT polled here. Bluetooth can't be read
        // reliably from the daemon (UID 2000) — Bluetooth is only readable from a normal
        // app process, not the shell-UID daemon. So
        // the app-process BluetoothStateMonitor (started by KeepAliveAccessibilityService)
        // watches ACL connect/disconnect and relays those events to the daemon via
        // Automations.publishExternalEvent, exactly like callState.

        // Start the FAST turn-indicator poll. Self-gates one level tighter than the
        // others (isEventReferenced): it reads the lamps at 500ms ONLY while an enabled
        // automation actually triggers on a turn signal, otherwise it's a parked thread
        // ticking a cheap map check. Publishes turnSignal on/off far more promptly than
        // the ~5s stationary snapshot cadence used to.
        com.overdrive.app.automation.condition.TurnSignalEvent.scheduleTurnSignalEvent();

        // Start the FAST drive-mode poll. Same self-gating as TurnSignalEvent: reads the
        // drive-config axis at 1s ONLY while an enabled automation actually triggers on
        // driveMode, otherwise a parked thread ticking a cheap map check. Publishes the
        // drive mode far more promptly than the ~5s stationary snapshot cadence used to.
        com.overdrive.app.automation.condition.DriveModeEvent.scheduleDriveModeEvent();

        // Start the FAST blind-spot poll. Same self-gating as TurnSignalEvent: reads the
        // radar warning registers at 250ms ONLY while an enabled automation references
        // blindSpot. The ADAS event callback also pushes alerts instantly; this poll covers
        // trims that don't deliver those events, and expires the alert hold. See
        // BlindSpotEvent.
        com.overdrive.app.automation.condition.BlindSpotEvent.scheduleBlindSpotEvent();

        // Start the FAST seatbelt poll. Identical self-gating to TurnSignalEvent: reads the
        // belts at 500ms ONLY while an enabled automation actually triggers on a seatbelt,
        // otherwise a parked thread ticking a cheap map check. Cuts a buckle trigger's lag
        // from the ~5s stationary snapshot cadence (the reported "2-3s") to near-real-time.
        com.overdrive.app.automation.condition.SeatbeltEvent.scheduleSeatbeltEvent();

        // Start the FAST dynamic-input poll (accelerator / brake / steering). Same
        // self-gating as TurnSignalEvent — reads a signal at 250ms ONLY while an enabled
        // automation actually references it, otherwise a parked thread ticking cheap map
        // checks. Fixes the up-to-5s lag those triggers had on the stationary snapshot.
        com.overdrive.app.automation.condition.DynamicsEvent.scheduleDynamicsEvent();

        // Start the FAST energy-recuperation (regen) poll. Same self-gating: reads the
        // regen level at 1s ONLY while an enabled automation references energyRegen,
        // otherwise a parked thread. Fixes the 2-4s lag regen had on the ~5s snapshot.
        com.overdrive.app.automation.condition.EnergyRegenEvent.scheduleEnergyRegenEvent();

        // Start the FAST gear poll. Same self-gating as TurnSignalEvent: reads gear at 500ms
        // ONLY while an enabled automation triggers on gear, otherwise a parked thread ticking
        // a cheap map check. Gear otherwise rode the 5s (ACC-on) snapshot; the gearbox HAL
        // listener can't be used (its learningEPB() path crashes the daemon), so this fast poll
        // is how a gear trigger becomes prompt. See GearEvent.
        com.overdrive.app.automation.condition.GearEvent.scheduleGearEvent();

        // Start the FAST climate poll (seat heat/cool per seat + high/low beam). Same
        // self-gating: reads at 1s ONLY while an enabled automation references one of those
        // six signals, otherwise a parked thread. These otherwise rode the ~5s snapshot (their
        // HAL callbacks refresh only a subset — DRL / pushed seat events), so this closes the
        // lag for the reported seat-cooling ELSE and for beam triggers. See ClimateEvent.
        com.overdrive.app.automation.condition.ClimateEvent.scheduleClimateEvent();

        // Subscribe to raw door open/close edges (event-driven, no poll) so door-state
        // triggers fire the instant a door/lid opens. Self-gates on isDisabled().
        com.overdrive.app.automation.condition.DoorEvent.start();
    }

    /**
     * Lazily build and return the conditions schema.
     */
    private static Conditions conditions() {
        Conditions c = conditions;
        if (c == null) {
            synchronized (SCHEMA_LOCK) {
                c = conditions;
                if (c == null) c = conditions = new Conditions();
            }
        }
        return c;
    }

    /**
     * Lazily build and return the actions schema.
     */
    private static Actions actions() {
        Actions a = actions;
        if (a == null) {
            synchronized (SCHEMA_LOCK) {
                a = actions;
                if (a == null) a = actions = new Actions();
            }
        }
        return a;
    }

    /**
     * Lazily build and return the delay type.
     */
    private static Type delay() {
        Type d = delay;
        if (d == null) {
            synchronized (SCHEMA_LOCK) {
                d = delay;
                if (d == null) d = delay = new IntType(new Label("delay", Messages.get("automation.delay")), 0, 86400);
            }
        }
        return d;
    }

    /**
     * Recompute the cached enabled-automation count. MUST be called under {@link #SAVE_LOCK} after any
     * mutation of the automations map or an automation's disabled flag.
     */
    private static void refreshEnabledCount() {
        int n = 0;
        for (Automation a : automations.values()) if (!a.isDisabled()) n++;
        enabledCount = n;
        seedReferencedVariables();
    }

    /**
     * Seed every user VARIABLE referenced by any automation to "" (empty) if it has no
     * value yet, so a first-run comparison behaves intuitively:
     * {@code Parking_Mode != true} is TRUE before the flag is ever set (empty ≠ "true"),
     * and {@code Parking_Mode == true} is FALSE. Without this, an unseen variable reads
     * as null and {@link AutomationCondition#compare} returns false for BOTH — so a
     * {@code != true} guard would never pass on the first run and the automation could
     * never start. Idempotent: only seeds a variable that isn't already in the state
     * (a real set via SetVariableAction always wins), and empty-string is a distinct,
     * stable value so it never re-fires. Called under {@link #SAVE_LOCK} after any load
     * or mutation, so newly-referenced variables get seeded as automations change.
     */
    private static void seedReferencedVariables() {
        for (Automation a : automations.values()) {
            // Triggers + conditions that reference a variable event.
            for (EventData key : a.getTriggers()) seedIfVariable(key);
            // getAllConditions (not getConditions) so a variable referenced only inside
            // a nested condition group is still seeded — else its "!= true" guard would
            // read null and never pass on first run.
            for (AutomationCondition c : a.getAllConditions()) seedIfVariable(c.getEventData());
            // Also seed variables a "Set Variable" action DEFINES, so a condition on a
            // variable only ever set (never triggered/conditioned) elsewhere still reads
            // empty rather than null before its first set.
            seedVariablesDefinedByActions(a.getActions());
            seedVariablesDefinedByActions(a.getElseActions());
        }
    }

    private static void seedIfVariable(EventData key) {
        if (key == null) return;
        if (!com.overdrive.app.automation.condition.BydEvent.VARIABLE_TYPE.equals(key.getType())) return;
        // putIfAbsent semantics: never clobber a real value already set.
        state.putIfAbsent(key, new StringValue(""));
    }

    private static void seedVariablesDefinedByActions(java.util.List<AutomationAction> actions) {
        // Recurse into nested children so a setVariable INSIDE a loop/if/group is still
        // seeded — otherwise its variable would read null (not "") on first run and a
        // "!= true" guard would never pass. forEachAction walks the whole tree.
        forEachAction(actions, action -> {
            if (!"setVariable".equals(action.getType()) && !"incrementVariable".equals(action.getType())
                    && !"computeVariable".equals(action.getType())) return;
            Object name = action.getVariables() == null ? null : action.getVariables().get("name");
            if (name == null) return;
            String n = name.toString().trim();
            if (n.isEmpty()) return;
            seedIfVariable(com.overdrive.app.automation.action.SetVariableAction.variableEvent(n));
        });
    }

    /**
     * Depth-first walk of an action list AND every nested child/else-child list,
     * applying {@code visitor} to each action. The single place that knows the action
     * tree shape, so every walker (variable seeding, manual-clip sizing, shell scans)
     * recurses consistently and a control-flow-nested action is never missed.
     */
    private static void forEachAction(java.util.List<AutomationAction> actions,
                                      java.util.function.Consumer<AutomationAction> visitor) {
        if (actions == null) return;
        for (AutomationAction action : actions) {
            if (action == null) continue;
            visitor.accept(action);
            forEachAction(action.getChildActions(), visitor);
            forEachAction(action.getElseChildActions(), visitor);
        }
    }

    private Automations() {}

    /**
     * Largest manual-replay total window (beforeSeconds + afterSeconds) across
     * every ENABLED automation's actions and else-actions. Consumed by
     * {@link com.overdrive.app.recording.ManualClipService#getConfiguredRetentionSeconds()}
     * so the pre-record ring is sized for automation-triggered replays too — a
     * clip bound only in an automation (never in Key Mapping) must still fit.
     *
     * <p>Cheap and side-effect-free: iterates the in-memory automation map (a
     * disabled automation contributes 0). Bounded to the manual-clip max so a
     * hand-edited config can never request an oversized ring.
     */
    public static int getMaxManualClipRetentionSeconds() {
        int max = 0;
        for (Automation automation : automations.values()) {
            if (automation.isDisabled()) continue;
            max = Math.max(max, maxManualClipRetention(automation.getActions()));
            max = Math.max(max, maxManualClipRetention(automation.getElseActions()));
        }
        return Math.min(com.overdrive.app.recording.ManualClipWindow.MAX_SECONDS, max);
    }

    private static int maxManualClipRetention(List<AutomationAction> actions) {
        // int[] so the lambda can accumulate. Recurse (forEachAction) so a manualClip
        // nested in a loop/if is counted too — otherwise the pre-record ring would be
        // undersized and that replay would silently fail to capture its window.
        final int[] max = {0};
        forEachAction(actions, action -> {
            if (!"manualClip".equals(action.getType())) return;
            Map<String, Object> variables = action.getVariables();
            if (variables == null) return;
            max[0] = Math.max(max[0],
                    intVar(variables, "beforeSeconds") + intVar(variables, "afterSeconds"));
        });
        return max[0];
    }

    private static int intVar(Map<String, Object> variables, String key) {
        Object value = variables.get(key);
        int seconds = value instanceof Number ? ((Number) value).intValue() : 0;
        return Math.max(0, seconds);
    }

    /**
     * Push the current manual-replay retention to the live encoder after an
     * automation mutation, so a newly-saved (or removed) replay action changes
     * the pre-record window without waiting for the next camera cold start —
     * mirroring the Key Mapping save path. Best-effort and never throws: sizing
     * also happens on encoder init, so a transient failure here self-heals.
     */
    private static void applyManualClipRetention() {
        try {
            com.overdrive.app.recording.ManualClipService.getInstance()
                    .reapplyLiveRetention();
        } catch (Throwable ignored) {
            // Retention is re-derived on the next encoder init regardless.
        }
    }

    /**
     * Get a condition schema with a specific key
     *
     * @param key The key for a condition
     * @return The condition schema for that key
     */
    public static EventCondition getCondition(String key) {
        return conditions().getCondition(key);
    }

    /**
     * Get an action schema with a specific key
     *
     * @param key The key for an action
     * @return The action schema for that key
     */
    public static Action getAction(String key) {
        return actions().getAction(key);
    }

    // Runtime re-entrancy guard for nested action execution (loops / if / action groups
    // all run their children back through runActionList). Bounds a cyclic/over-deep tree
    // that slipped past the parse-time MAX_ACTION_DEPTH (e.g. an action group calling
    // itself). Per-thread because actions run on the single AutomationQueue worker (and
    // the /test executor); each independent run starts at 0.
    // Must sit ABOVE the parse-time control-flow cap (Automation.MAX_ACTION_DEPTH=8) with
    // headroom, so a LEGAL max-depth automation that ALSO runs action groups (each an extra
    // runActionList re-entry) executes fully without falsely tripping this guard; it only
    // fires on a genuine runaway/cycle. 16 = 8 (static cap) + generous action-group headroom.
    private static final int MAX_RUN_DEPTH = 16;
    private static final ThreadLocal<Integer> RUN_DEPTH = ThreadLocal.withInitial(() -> 0);

    // Set by a Wait Until whose condition never became true, to STOP the rest of the chain.
    // Previously a timed-out wait just fell through and every following action ran anyway, so
    // "wait until gear = P → fold mirrors" folded the mirrors even though the car never went
    // into P — the wait looked like it did nothing. A timeout is now a failed precondition:
    // the remaining actions (at every nesting level) are skipped and the run ends.
    // Per-thread for the same reason as RUN_DEPTH, and cleared at the top of each run.
    private static final ThreadLocal<Boolean> CHAIN_ABORTED = ThreadLocal.withInitial(() -> false);

    /** Called by a wait action when its timeout elapsed without the condition being met. */
    public static void abortChain() { CHAIN_ABORTED.set(true); }

    /** Clear the abort flag — at the start of every independent action run. */
    public static void resetChain() { CHAIN_ABORTED.set(false); }

    /** Whether the current chain has been aborted by a timed-out wait. */
    public static boolean chainAborted() { return CHAIN_ABORTED.get(); }

    /**
     * Run a list of actions in order, re-entrantly: a control-flow action's
     * {@code trigger} calls back here with its child list, so loops / if-branches /
     * action groups all execute through one path with a shared depth guard. A flat list
     * runs at depth 1, identical to the original inline loop. Null/unknown-type elements
     * are skipped (defense-in-depth for a hand-edited config), and the depth guard stops
     * runaway nesting without killing the worker.
     */
    public static void runActionList(java.util.List<AutomationAction> actionList) {
        if (actionList == null) return;
        int depth = RUN_DEPTH.get();
        if (depth >= MAX_RUN_DEPTH) {
            logger.warn("Action nesting depth cap (" + MAX_RUN_DEPTH + ") hit — stopping to avoid runaway/cycle");
            return;
        }
        // Depth 0 = the start of an independent run (the queue worker or /test). Clear any
        // abort left by a previous run on this same pooled/worker thread, so one timed-out
        // wait can never suppress the NEXT automation's actions.
        if (depth == 0) resetChain();
        RUN_DEPTH.set(depth + 1);
        try {
            for (AutomationAction automationAction : actionList) {
                // A timed-out wait aborts the rest of the chain, including the outer levels
                // it returns into (a wait inside an If/Loop body stops the whole run, not just
                // that body) — the precondition it was guarding never came true.
                if (chainAborted()) break;
                if (automationAction == null) continue;
                Action action = getAction(automationAction.getType());
                if (action == null) continue;
                action.trigger(automationAction);
            }
        } finally {
            RUN_DEPTH.set(depth);
        }
    }

    /**
     * Check whether the delay is an allowed value
     *
     * @param seconds The number of seconds to delay the actions
     * @return true if it is valid, false otherwise
     */
    public static boolean isValidDelay(int seconds) {
        return delay().isValid(seconds);
    }

    /**
     * Whether automations are disabled.
     * Disabled when there are no automations or all of them are disabled. Backed by an O(1) cached
     * count (not a map scan) because this is called ~30x per telemetry snapshot on the daemon hot path.
     *
     * @return Whether the automation feature is enabled
     */
    public static boolean isDisabled() {
        return enabledCount == 0;
    }

    /**
     * Whether an automation with this id currently exists.
     *
     * @param id The id to look up
     * @return true if an automation is stored under this id
     */
    public static boolean exists(String id) {
        return id != null && automations.containsKey(id);
    }

    /**
     * Whether any ENABLED automation references the given event — as a TRIGGER or as a
     * CONDITION. Lets a high-cadence event source (e.g. the turn-signal fast poll) gate
     * its expensive per-tick work on "is anyone actually listening for this?", so it
     * stays a true no-op until a relevant automation exists — the same
     * cost-when-disabled bar the rest of the subsystem holds, but per-event rather than
     * global. Conditions are included because a turn signal can gate a DIFFERENT
     * trigger (e.g. "when speed &gt; 60 AND left indicator on"); if we polled only for
     * triggers, that condition would evaluate against a stale/unseeded turn state.
     * Cheap: a short walk of the (typically tiny) automation map, only called from a
     * low/aperiodic scheduler, never the telemetry hot path.
     *
     * @param key the event to test
     * @return true if at least one enabled automation references it (trigger or condition)
     */
    public static boolean isEventReferenced(EventData key) {
        if (key == null || enabledCount == 0) return false;
        for (Automation a : automations.values()) {
            if (a.isDisabled()) continue;
            if (a.isTriggered(key)) return true;
            // getAllConditions so an event used only inside a nested group still marks
            // this event as "referenced" — otherwise its fast-poll (e.g. turn signal)
            // would stay parked and the group condition would read stale state.
            for (AutomationCondition c : a.getAllConditions()) {
                if (key.equals(c.getEventData())) return true;
            }
            // ACTION-side references too. The flow actions (if/else, wait-until,
            // wait-until-signal, loop) test a signal from their own variables, NOT via a
            // trigger or condition — so without this a self-gated signal's poller stays
            // parked and the action reads a permanently-null state: an "if seatbelt ==
            // unbuckled" silently never holds, and a wait-until burns its whole timeout.
            // Walks nested child/else lists because these actions nest.
            if (actionsReference(a.getActions(), key)) return true;
            if (actionsReference(a.getElseActions(), key)) return true;
        }
        return false;
    }

    /**
     * Does any action in this list (or nested beneath it) name {@code key} as an operand?
     *
     * <p>An action's operands are plain variable values, so this asks each value whether it
     * ADDRESSES the key — covering both a left-hand signal address ({@code speed:units=kmph},
     * and the legacy flat ids) and a dynamic right-hand {@code ${signal:…}} token, since both
     * make the action depend on that signal being polled.
     *
     * <p>Depth-bounded by the same constant the action runner uses, so a malformed/deep tree
     * can't make this walk unbounded on a scheduler thread.
     */
    /**
     * The action-variable ids that can name a signal: {@code event} is a flow action's
     * left-hand address ({@code SignalAddressType}) and {@code value} its dynamic right-hand
     * side, which may hold a {@code ${signal:…}} token. Any other variable is a plain operand
     * and is deliberately not treated as an address.
     */
    private static final String[] ADDRESS_FIELDS = { "event", "value" };

    private static boolean actionsReference(List<AutomationAction> actions, EventData key) {
        return actionsReference(actions, key, MAX_RUN_DEPTH);
    }

    /**
     * Could this stored variable value possibly address a signal of {@code type}? A pure
     * allocation-free pre-filter for {@link #actionsReference} — see the note there.
     *
     * <p>Conservative by construction: it may say "maybe" for a value that turns out not to
     * match (the caller then does the real parse), but it never says "no" to a value that
     * would. A real address is {@code type}, {@code type:…} or {@code ${signal:type…}}, so the
     * type must appear in the string; a legacy alias doesn't contain its target type, so those
     * are admitted by name.
     */
    private static boolean mightAddress(String value, String type) {
        if (value.isEmpty() || type == null || type.isEmpty()) return false;
        if (value.contains(type)) return true;
        return AutomationCondition.isLegacySignalId(value.trim());
    }

    private static boolean actionsReference(List<AutomationAction> actions, EventData key, int depthLeft) {
        if (actions == null || actions.isEmpty() || depthLeft <= 0) return false;
        for (AutomationAction a : actions) {
            if (a == null) continue;
            // ONLY the two fields that can hold a signal address are considered: "event" (a
            // flow action's LHS, a SignalAddressType) and "value" (the dynamic RHS, which may
            // hold a ${signal:…} token). Scanning EVERY variable would be both wasteful and
            // WRONG — a user variable or a free-text field could legitimately contain a word
            // that happens to be a legacy signal id ("hazard", "drl"), which would then keep an
            // unrelated signal's poller awake for no reason.
            Map<String, Object> vars = a.getVariables();
            for (String field : ADDRESS_FIELDS) {
                Object v = vars.get(field);
                if (!(v instanceof String)) continue;
                String s = (String) v;
                // Cheap reject before allocating. This runs on the fast pollers (the dynamics
                // poll calls isEventReferenced 3x every 250ms), and parsing an address builds a
                // fresh EventData plus a HashMap for the attributed case — avoid that for the
                // overwhelmingly common "this value can't name this key" case.
                if (!mightAddress(s, key.getType())) continue;
                if (key.equals(AutomationCondition.resolveSignalAddress(s))) return true;
            }
            if (actionsReference(a.getChildActions(), key, depthLeft - 1)) return true;
            if (actionsReference(a.getElseChildActions(), key, depthLeft - 1)) return true;
        }
        return false;
    }

    /**
     * Create or update an automation
     * Will use a UUID for new automations
     *
     * @param id         The id of an existing automation or null if a new automation is needed
     * @param automation The automation to add to the map
     */
    public static void updateAutomation(String id, Automation automation) {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        automations.put(id, automation);
        synchronized (SAVE_LOCK) { refreshEnabledCount(); }
        saveToFile();
        AutomationQueue.checkWorkerState();
        applyManualClipRetention();
        logger.info("Updated automation: " + id);
    }

    /**
     * Create or update an automation from a JSON representation
     *
     * @param id   The id for this automation or null if a new automation is needed
     * @param json The JSON representation of this automation
     * @return true if successfully created/updated, false otherwise
     */
    public static boolean updateAutomation(String id, JSONObject json) {
        Automation automation = Automation.fromJson(json);
        if (automation == null) return false;
        updateAutomation(id, automation);
        return true;
    }

    /**
     * Bulk-import automations from an exported map (id → automation JSON), the exact
     * shape {@link #toJson} produces. Each entry is validated via {@link Automation#fromJson}
     * (same gate as a single create), so a malformed entry is skipped, never persisted.
     *
     * @param json    the exported {id: automation} map
     * @param replace true = replace the whole set (clear first); false = merge (add/overwrite by id)
     * @return the number of automations successfully imported (validated + stored)
     */
    public static int importAutomations(JSONObject json, boolean replace) {
        if (json == null) return 0;
        // Validate ALL entries first so a partial/garbage file can't half-wipe the set:
        // we only clear (replace mode) once we know we have a valid parse to install.
        java.util.LinkedHashMap<String, Automation> parsed = new java.util.LinkedHashMap<>();
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Automation a = Automation.fromJson(json.optJSONObject(key));
            if (a != null) {
                // Mint a fresh id for a blank/duplicate-on-merge key so an import can't
                // collide with or silently overwrite an unrelated existing automation.
                String id = (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
                parsed.put(id, a);
            }
        }
        if (parsed.isEmpty()) return 0;
        synchronized (SAVE_LOCK) {
            if (replace) automations.clear();
            automations.putAll(parsed);
            refreshEnabledCount();
        }
        saveToFile();
        AutomationQueue.checkWorkerState();
        applyManualClipRetention();
        logger.info("Imported " + parsed.size() + " automations (replace=" + replace + ")");
        return parsed.size();
    }

    /**
     * Delete an automation with a specific id
     * Only persists and re-evaluates the worker state when a mapping actually existed, so a delete of
     * an unknown id is a true no-op (no needless file write / worker churn) and the caller can report
     * a 404 instead of a misleading success.
     *
     * @param id The id of the automation to delete
     * @return true if an automation was actually removed, false if no mapping existed for this id
     */
    public static boolean deleteAutomation(String id) {
        if (automations.remove(id) == null) return false;
        synchronized (SAVE_LOCK) { refreshEnabledCount(); }
        saveToFile();
        AutomationQueue.checkWorkerState();
        applyManualClipRetention();
        logger.info("Removed automation: " + id);
        return true;
    }

    /**
     * Disable an automation
     *
     * @param id       The id of the automation to disable
     * @param disabled true if it should be disabled, false otherwise
     * @return true if successfully disabled, false otherwise
     */
    public static boolean disableAutomation(String id, boolean disabled) {
        Automation automation = automations.get(id);
        if (automation == null) return false;
        automation.setDisabled(disabled);
        synchronized (SAVE_LOCK) { refreshEnabledCount(); }
        saveToFile();
        AutomationQueue.checkWorkerState();
        applyManualClipRetention();
        logger.info((disabled ? "Disabled" : "Enabled") + " automation: " + id);
        return true;
    }

    /**
     * Flip an automation's enabled state (for the AUTOMATION_CONTROL "toggle" action).
     * Returns false if the id is unknown.
     */
    public static boolean toggleAutomation(String id) {
        Automation automation = automations.get(id);
        if (automation == null) return false;
        return disableAutomation(id, !automation.isDisabled());
    }

    /**
     * Whether an automation with this id exists (for the automation-control action's
     * unknown-target guard).
     */
    public static boolean automationExists(String id) {
        return automations.containsKey(id);
    }

    /**
     * Lightweight [{id, name}] list for the automation-control target picker. {@code name}
     * is the user-given name, or a short generated fallback ("Automation <8-char id>")
     * when unnamed, so the picker is never blank. Excludes {@code selfId} (an automation
     * shouldn't target itself in the picker) when provided.
     */
    public static JSONArray listForPicker(String selfId) {
        JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, Automation> e : automations.entrySet()) {
                if (selfId != null && selfId.equals(e.getKey())) continue;
                String nm = e.getValue().getName();
                if (nm.isEmpty()) {
                    String k = e.getKey();
                    nm = "Automation " + (k.length() > 8 ? k.substring(0, 8) : k);
                }
                arr.put(new JSONObject().put("id", e.getKey()).put("name", nm));
            }
        } catch (Exception ignored) {}
        return arr;
    }

    /**
     * The schema containing allowed values and descriptions for an automation
     *
     * @return The JSON schema for an automation
     */
    public static JSONArray schemaJson() {
        JSONArray json = conditions().toJson();

        try {
            JSONObject delayJson = delay().toJson();
            delayJson.put(
                    "description", Messages.get("automation.delay_description"));
            json.put(delayJson);
            json.put(actions().toJson());
            // Optional "else" branch — same action catalog, required 0. Emitted as its
            // own schema section so the existing schema-driven form renders it with no
            // bespoke UI. Automations without an else branch simply leave it empty.
            json.put(actions().elseToJson());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * The JSON for all the stored automations
     * Can be stored to load later
     *
     * @return JSON for all the stored automations
     */
    public static JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            for (Map.Entry<String, Automation> automation : automations.entrySet()) {
                json.put(automation.getKey(), automation.getValue().toJson());
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * Persist the automations to a file.
     * <p>
     * Durable + concurrency-safe: the whole snapshot-then-write runs under {@link #SAVE_LOCK} so
     * concurrent API-thread saves can't interleave, and the bytes are written to a scratch
     * {@code .tmp} file then atomically {@code renameTo}'d over the live file (rename is atomic on the
     * same filesystem). Before promoting the scratch file, the current good live file is copied to a
     * {@code .bak} last-known-good. A crash therefore leaves at most a stale-but-valid live file or a
     * recoverable {@code .bak}; it can never leave a half-written live file that wipes all automations.
     */
    public static void saveToFile() {
        synchronized (SAVE_LOCK) {
            if (!AUTOMATION_HOME.exists()) AUTOMATION_HOME.mkdirs();
            // Snapshot to bytes under the lock so the persisted content is internally consistent.
            byte[] bytes = toJson().toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(AUTOMATION_TMP)) {
                fos.write(bytes);
                fos.getFD().sync();
            } catch (IOException e) {
                logger.error("Failed to write automations scratch file");
                return;
            }
            // Promote the existing good file to the backup before replacing it, so a failure while the
            // live file is momentarily gone still leaves a recoverable copy.
            if (AUTOMATION_CONFIG.exists()) copyFile(AUTOMATION_CONFIG, AUTOMATION_BACKUP);
            if (!AUTOMATION_TMP.renameTo(AUTOMATION_CONFIG)) {
                logger.error("Failed to promote automations scratch file to live config");
                return;
            }
            logger.info("Saved " + automations.size() + " Automations to " + AUTOMATION_CONFIG);
        }
    }

    /**
     * Copy a file's bytes. Best-effort; failures are logged but not fatal (the backup is a safety net,
     * not the source of truth).
     */
    private static void copyFile(File from, File to) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        } catch (IOException e) {
            logger.error("Failed to back up automations config");
        }
    }

    /**
     * Load persisted automations from the file, falling back to the last-known-good backup when the
     * live file is missing or corrupt. Runs under {@link #SAVE_LOCK} so it can't observe a live file
     * mid-rename.
     */
    public static void loadFromFile() {
        synchronized (SAVE_LOCK) {
            if (tryLoadFrom(AUTOMATION_CONFIG)) return;
            // Live file missing/corrupt — recover from the backup rather than silently starting empty.
            if (AUTOMATION_BACKUP.exists()) {
                if (tryLoadFrom(AUTOMATION_BACKUP)) {
                    logger.info("Recovered automations from backup after live config was unreadable");
                    return;
                }
                logger.error("Both live and backup automation configs were unreadable");
            }
        }
    }

    /**
     * Attempt to load automations from a specific file.
     *
     * @param file The file to read
     * @return true if the file existed and parsed (automations populated), false if missing/corrupt
     */
    private static boolean tryLoadFrom(File file) {
        if (!file.exists()) return false;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(content);
            // Build into a scratch map first so a parse failure part-way can't leave the live map
            // half-populated on top of what was already loaded.
            Map<String, Automation> loaded = new java.util.HashMap<>();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Automation automation = Automation.fromJson(json.optJSONObject(key));
                if (automation != null) loaded.put(key, automation);
            }
            // Replace, don't merge: the file is the source of truth. Merging would resurrect an
            // automation deleted since the last load if this is ever wired to a runtime reload
            // (config-restore / OTA); replacement keeps the in-memory map exactly matching the file.
            automations.keySet().retainAll(loaded.keySet());
            automations.putAll(loaded);
            refreshEnabledCount();
            logger.info("Loaded " + loaded.size() + " Automations from " + file);
            return true;
        } catch (Exception e) {
            logger.error("Failed to load automations from " + file);
            return false;
        }
    }

    /**
     * Method to call when an event caused a value in the state to change (the new value has already been
     * committed to the state map by {@link #update}).
     * Will check all automations which contain this event as a trigger.
     * If the previous value is unknown, the event will not be triggered as the value may not have changed.
     * For this reason, events should fire at least once at startup to fill unknown values in the state.
     *
     * @param key      The event key
     * @param oldValue The value of the event before this change
     */
    private static void stateChanged(EventData key, Value oldValue) {
        // Don't trigger events when we don't know the previous value
        if (oldValue != null) {
            for (Map.Entry<String, Automation> automation : automations.entrySet()) {
                Automation a = automation.getValue();
                if (!a.isDisabled() && a.isTriggered(key)) {
                    // Enqueue when the conditions are met, OR when they aren't but an
                    // else branch exists — the else branch must fire after the same
                    // delay. The final decision (primary vs else) is re-made at fire
                    // time in triggerActions, so a condition that flips during the
                    // delay window is honoured. Only when conditions currently fail
                    // AND there is no else branch do we remove any pending item.
                    if (a.conditionsMet(state) || a.hasElseActions()) {
                        logger.info("Adding automation to queue: " + automation.getKey());
                        AutomationQueue.addToQueue(automation.getKey(), a.getDelay());
                    } else {
                        logger.info("Removing automation from queue: " + automation.getKey());
                        AutomationQueue.removeFromQueue(automation.getKey());
                    }
                }
            }
        }
    }

    /**
     * Read the current value of an event from the live automation state, or null if
     * the event has never fired since boot. Exposed for the {@code waitUntil} action,
     * which polls a signal's current value while running (on the single automation
     * worker thread) — it must see the SAME committed state the trigger/condition
     * evaluation sees, so it reads this shared map rather than a private copy.
     *
     * @param key the event to read
     * @return the current {@link Value}, or null if unseen
     */
    public static Value getStateValue(EventData key) {
        return key == null ? null : state.get(key);
    }

    /**
     * Update the value in the state with a new value
     * Uses the Not Equal To comparator to see if the value has changed.
     * The commit is atomic on the state map: only the single thread that actually transitions the stored
     * value proceeds to evaluate automations, so two telemetry callback threads observing the same old
     * value for one logical change cannot both fire (which would double-run a vehicle-control action).
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, Value value) {
        update(key, value, false);
    }

    /**
     * Core state update.
     *
     * @param forceStore when true, the value is STORED into the state map even while no
     *     automation is enabled ({@link #isDisabled()}). This is used ONLY for externally
     *     relayed signals (btState / btDeviceName / callState — see
     *     {@link #publishExternalEvent}): the app-process relay dedups on its side and stops
     *     re-sending once it has pushed a value, so if the daemon dropped that value while
     *     disabled, a CONDITION evaluated after the user later enables their first rule (or
     *     after a daemon-only restart clears this map) would read {@code null} instead of the
     *     real connection state. Forcing the store keeps the map truthful; triggers are still
     *     only fired when enabled (below), and since triggers are edge-based, seeding a value
     *     while disabled can never retroactively misfire a rule. Telemetry updates pass
     *     {@code false} so the hot-path {@link #isDisabled()} short-circuit is preserved.
     */
    public static void update(EventData key, Value value, boolean forceStore) {
        if (key == null || value == null) return;
        boolean disabled = isDisabled();
        // Hot path: a telemetry update with nothing listening does no work at all.
        if (disabled && !forceStore) return;

        // Atomic compare-and-set: replace only if the current value differs; the winning thread gets the
        // previous value back and is the only one that runs stateChanged for this transition.
        Value[] previous = new Value[1];
        Value committed = state.compute(key, (k, current) -> {
            previous[0] = current;
            if (current == null || Boolean.TRUE.equals(current.compare(value, "neq"))) {
                return value; // transition — store the new value
            }
            return current; // unchanged — leave as-is
        });
        // We transitioned iff the stored value is now the new value AND it differs from what was there.
        // Only EVALUATE triggers/conditions when automations are enabled — a forced store while
        // disabled just seeds the map (there is nothing enabled to run, and triggers are edge-based
        // so the seed can't misfire once a rule is later enabled).
        if (!disabled && committed == value && previous[0] != value) {
            stateChanged(key, previous[0]);
        }
    }

    /**
     * Method to call the update method with a primitive value
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, String value) {
        update(key, new StringValue(value));
    }

    /** String update that also seeds the state map while disabled (external relays only). */
    public static void update(EventData key, String value, boolean forceStore) {
        update(key, new StringValue(value), forceStore);
    }

    /**
     * Method to call the update method with a primitive value
     *
     * @param key   The key for the event
     * @param value The new value of the event
     */
    public static void update(EventData key, Integer value) {
        update(key, new IntValue(value));
    }

    /**
     * Publish an EXTERNAL event relayed from the app process (signals the daemon can't
     * observe itself). WHITELISTED: only the keys below are honoured, and each is
     * mapped to its curated {@link BydEvent} EventData with a validated value — so the
     * app→daemon bridge can never inject arbitrary automation state.
     *
     * @param event the external event key (e.g. "callState")
     * @param value the string value (validated per event)
     * @return true if the event was recognised and published
     */
    public static boolean publishExternalEvent(String event, String value) {
        if (event == null) return false;
        switch (event) {
            case "callState":
                // Only accept the three real telephony states; anything else is dropped
                // rather than published (no spurious edge on a garbled relay). forceStore:
                // the relay dedups on its side, so seed the map even while disabled (see
                // update(..., forceStore)) — otherwise a condition read after the user
                // enables their first call-state rule would see null.
                if ("idle".equals(value) || "ringing".equals(value) || "offhook".equals(value)) {
                    update(com.overdrive.app.automation.condition.BydEvent.CALL_STATE, value, true);
                    return true;
                }
                return false;
            case "btState":
                // Bluetooth connection edge, relayed from the app-process
                // BluetoothStateMonitor (the daemon can't reliably read BT from UID 2000).
                // Only the two real states; anything else is dropped (no spurious edge).
                // forceStore so the map is seeded even before the first BT rule is enabled
                // and survives a daemon-only restart (the relay won't re-send otherwise).
                if ("on".equals(value) || "off".equals(value)) {
                    update(com.overdrive.app.automation.condition.BydEvent.BT_STATE, value, true);
                    return true;
                }
                return false;
            case "btDeviceName":
                // Connected-device friendly name from the same relay ("" when nothing is
                // connected). Free-text (bounded) — a name can be any string, so the only
                // validation is a length cap; null is coerced to "" so a name-match
                // condition sees a stable value rather than "unseen". forceStore: same
                // rationale as btState.
                if (value == null) value = "";
                if (value.length() > 64) value = value.substring(0, 64);
                update(com.overdrive.app.automation.condition.BydEvent.BT_DEVICE_NAME, value, true);
                return true;
            default:
                return false;
        }
    }

    /**
     * Publish an INBOUND MQTT message as an automation signal. The daemon's MQTT
     * subscriber calls this when a broker message lands on {@code <base>/automation/<channel>}
     * (see MqttPublisherService), letting an external system (Home Assistant, Node-RED, …)
     * trigger an OverDrive automation. Distinct from {@link #publishExternalEvent}: the
     * channel is caller-defined, so this is its own guarded seam rather than a fixed
     * whitelist. The channel is validated (bounded, safe charset) so a malformed/hostile
     * topic can't inject arbitrary automation state; the value is bounded in length. The
     * value flows through {@link #update} (level-triggered: fires on a value transition),
     * so publishing distinct values (or toggling) drives repeated triggers.
     *
     * @param channel the channel segment from the topic (validated)
     * @param value   the message payload as a string (bounded)
     * @return true if accepted and published, false if the channel/value was rejected
     */
    public static boolean publishMqttTrigger(String channel, String value) {
        if (!isValidMqttChannel(channel)) return false;
        if (value == null) value = "";
        if (value.length() > 256) value = value.substring(0, 256);
        // Bound distinct-channel growth: update() inserts a permanent state entry per
        // channel, and the channel is external (broker-writable). A chatty/hostile broker
        // publishing to <base>/automation/<random-N> would otherwise grow the state map
        // unbounded. Cap the number of DISTINCT channels we'll ever seed; once at the cap,
        // only already-known channels continue to publish (a real automation watches a
        // fixed, small set of channels, so this never limits legitimate use). The set only
        // grows, mirroring update()'s own permanence — no pruning needed for a ≤cap set.
        if (mqttChannelsSeen.size() >= MAX_MQTT_CHANNELS && !mqttChannelsSeen.contains(channel)) {
            return false;
        }
        mqttChannelsSeen.add(channel);
        update(com.overdrive.app.automation.condition.BydEvent.mqttTrigger(channel), value);
        return true;
    }

    // Distinct inbound-MQTT channels ever seeded, capped so a broker can't grow the state
    // map without bound (see publishMqttTrigger). A concurrent set — publishMqttTrigger runs
    // on the Paho callback thread.
    private static final int MAX_MQTT_CHANNELS = 64;
    private static final java.util.Set<String> mqttChannelsSeen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A safe channel id: non-empty, &le;64 chars, [A-Za-z0-9._-] only (a single MQTT
     *  topic segment). Rejects wildcards/slashes so it maps to exactly one channel. */
    private static boolean isValidMqttChannel(String channel) {
        if (channel == null || channel.isEmpty() || channel.length() > 64) return false;
        for (int i = 0; i < channel.length(); i++) {
            char c = channel.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Run the actions for a specific automation
     * Can run the actions without checking the conditions for testing the actions
     * A disabled automation never fires from this choke point: both the queue worker
     * (checkConditions=true) and the /test endpoint (checkConditions=false) flow through here, and a
     * disabled automation that was queued with a delay before being disabled must not auto-fire when
     * the delay elapses. The disabled guard is therefore applied regardless of checkConditions.
     * The returned boolean reports only whether the automation EXISTS (so the API can answer 404 for
     * unknown ids); a known automation that was skipped because it is disabled or its conditions are
     * no longer met still returns true — testing a disabled automation is an accepted edge case that
     * reports success without firing. A known automation whose action throws also returns true (it
     * exists): the throw is swallowed here so the API never confuses an action failure with a 404.
     *
     * @param id              The id of the automation to run the actions for
     * @param checkConditions Whether to check that the conditions match before running the actions
     * @return true if an automation with this id exists, false if the id is unknown
     */
    public static boolean triggerActions(String id, boolean checkConditions) {
        Automation automation = automations.get(id);
        if (automation == null) return false;

        // A disabled automation must never run its actions, even if it was queued before being disabled
        if (automation.isDisabled()) {
            logger.info("Skipping disabled automation actions: " + id);
            return true;
        }

        // Decide which branch to run. The /test path (checkConditions=false) always
        // runs the PRIMARY actions so "test" exercises the happy path. The queue
        // worker (checkConditions=true) runs the primary branch when conditions are
        // met, otherwise the else branch (if any). Conditions are re-checked HERE, at
        // fire time, so a value that changed during the delay window is honoured.
        boolean met = !checkConditions || automation.conditionsMet(state);
        boolean runElse = checkConditions && !met && automation.hasElseActions();
        if (met || runElse) {
            logger.info("Triggering automation " + (runElse ? "else-" : "") + "actions: " + id);
            // Guard the action run at the shared choke point that BOTH callers flow through: the
            // autonomous queue worker (AutomationQueue.java:135-139) already wraps its call in
            // catch(Throwable), but the /test endpoint (AutomationApiHandler.testAutomation) does not.
            // A misbehaving or null action element (reachable via the unchecked
            // actions.add(action.fromJson(...)) in Automation.fromJson) would otherwise let an NPE/Error
            // propagate out of the test call, up through handle() to HttpServer's catch(Exception) which
            // only logs and closes the socket in its finally block — leaving the client with no HTTP
            // response at all. Swallowing it here (mirroring the queue worker) means the caller still
            // gets a proper response and the daemon stays up. We return true (the automation EXISTS) so
            // the failure is never misreported as a 404 by callers that map false -> 404.
            // Record the fire (real triggers only — not the /test path). Bumps the
            // in-memory count + timestamp for the list "last fired / N times"; the value
            // is persisted lazily (below) rather than on every fire to avoid a disk
            // write per trigger on a hot automation.
            if (checkConditions) automation.recordTriggered(System.currentTimeMillis());
            try {
                if (runElse) automation.triggerElseActions();
                else automation.triggerActions();
            } catch (Throwable t) {
                logger.error("Automation action threw while triggering: " + id);
            }
            // Persist the bumped stats opportunistically: only every STATS_PERSIST_EVERY
            // fires (per automation) so a busy rule doesn't hammer the disk, while the
            // count still survives a restart with at most a few lost increments.
            if (checkConditions && (automation.getTriggerCount() % STATS_PERSIST_EVERY) == 0) {
                saveToFile();
            }
        }
        return true;
    }

    // Persist run-stats to disk every N fires per automation (not every fire — that
    // would be a disk write per trigger on a hot rule). A restart loses at most N-1
    // increments of the display counter, which is acceptable for a cosmetic stat.
    private static final long STATS_PERSIST_EVERY = 10L;
}
