package com.overdrive.app.automation;

import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.Value;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Automation {
    // Condition-combining logic. "AND" (default) = every condition must match;
    // "OR" = any one condition matching is enough. Stored as a String so the JSON
    // schema stays open and an unknown/absent value degrades safely to AND — which
    // is exactly the pre-existing behaviour, so saved automations are unaffected.
    public static final String LOGIC_AND = "AND";
    public static final String LOGIC_OR = "OR";

    // Max nesting depth for condition groups when parsing. The UI only builds 2 levels
    // (top operator + one group layer), but the engine is recursive; this bounds a
    // hand-edited / imported config so it can't build an unbounded tree that blows the
    // stack on evaluate(). Generous enough to never limit a real rule.
    // (public so the web editor can read it from the schema and never drift.)
    public static final int MAX_GROUP_DEPTH = 8;

    private final LinkedHashSet<EventData> triggers;
    private final List<AutomationCondition> conditions;
    private final String conditionLogic;
    // Optional nested condition groups, combined with the flat `conditions` as PEER
    // terms under `conditionLogic`. Empty for every automation created before this
    // feature (and for simple ones), in which case conditionsMet reduces to the exact
    // flat evaluation below — so old automations are byte-identical and unaffected.
    private final List<ConditionGroup> conditionGroups;
    private final int delay;
    private final List<AutomationAction> actions;
    // Optional "else" branch: actions run when the automation is triggered but its
    // conditions are NOT met. Empty when unused, so an automation with no else
    // branch behaves exactly as before. Never null.
    private final List<AutomationAction> elseActions;

    // volatile: written from HTTP request threads (setDisabled) and read from the telemetry thread
    // (stateChanged) and the queue worker thread (triggerActions) with no shared lock, so a plain field
    // could let those threads observe a stale enabled/disabled state and fire a just-disabled automation.
    private volatile boolean disabled;

    // Optional user-given name (a label shown in the list + the automation-control
    // target picker). Empty for automations created before this feature — the UI then
    // falls back to the generated description, so nothing regresses. Mutable (set from
    // fromJson / an edit); not part of the constructor chain to keep it stable.
    private volatile String name = "";
    // Lightweight run stats surfaced in the list ("last fired", "N times"). Written on
    // the queue worker thread when the actions actually run; volatile for cross-thread
    // visibility to the HTTP list read. Persisted so the count survives a restart.
    private volatile long lastTriggered = 0L; // epoch ms, 0 = never
    private volatile long triggerCount = 0L;

    /**
     * Backward-compatible constructor: AND logic, no else branch. Kept so any
     * existing caller / test that built an Automation the old way still compiles
     * and behaves identically.
     */
    public Automation(
            List<EventData> triggers,
            List<AutomationCondition> conditions,
            Integer delay,
            List<AutomationAction> actions,
            boolean disabled) {
        this(triggers, conditions, LOGIC_AND, delay, actions, null, disabled);
    }

    /**
     * A representation of a single automation
     * Contains all the fields which build up an automation
     * Can be stored and loaded when needed
     *
     * @param triggers       The events which would cause this automation to be checked
     * @param conditions     The conditions to check before applying the actions of this automation
     * @param conditionLogic How conditions combine: {@link #LOGIC_AND} or {@link #LOGIC_OR}
     *                       (null/unknown → AND)
     * @param delay          The amount of time to wait before running the actions in seconds
     * @param actions        The actions which will be run in order when this automation is triggered
     * @param elseActions    Actions to run when triggered but conditions are NOT met (null → none)
     * @param disabled       Whether this automation is disabled. This can be mutated to disable and enable later
     */
    public Automation(
            List<EventData> triggers,
            List<AutomationCondition> conditions,
            String conditionLogic,
            Integer delay,
            List<AutomationAction> actions,
            List<AutomationAction> elseActions,
            boolean disabled) {
        this(triggers, conditions, conditionLogic, null, delay, actions, elseActions, disabled);
    }

    /**
     * Full constructor including optional nested {@code conditionGroups}. The 7-arg
     * constructor delegates here with no groups, so every existing caller is unchanged.
     *
     * @param conditionGroups nested AND/OR groups combined with {@code conditions} as
     *                        peer terms under {@code conditionLogic} (null → none)
     */
    public Automation(
            List<EventData> triggers,
            List<AutomationCondition> conditions,
            String conditionLogic,
            List<ConditionGroup> conditionGroups,
            Integer delay,
            List<AutomationAction> actions,
            List<AutomationAction> elseActions,
            boolean disabled) {
        this.triggers = new LinkedHashSet<>(triggers);
        this.conditions = conditions;
        // Normalize: only "OR" (case-insensitive) enables OR; everything else is AND.
        this.conditionLogic = LOGIC_OR.equalsIgnoreCase(conditionLogic) ? LOGIC_OR : LOGIC_AND;
        this.conditionGroups = conditionGroups != null ? conditionGroups : new ArrayList<>();
        this.delay = Objects.requireNonNullElse(delay, 0);
        this.actions = actions;
        this.elseActions = elseActions != null ? elseActions : new ArrayList<>();
        this.disabled = disabled;
    }

    /**
     * The events which would cause this automation to be checked
     *
     * @return The events which would cause this automation to be checked
     */
    public LinkedHashSet<EventData> getTriggers() {
        return triggers;
    }

    /**
     * The conditions to check before applying the actions of this automation
     *
     * @return The conditions to check before applying the actions of this automation
     */
    public List<AutomationCondition> getConditions() {
        return conditions;
    }

    /**
     * How the conditions combine: {@link #LOGIC_AND} or {@link #LOGIC_OR}.
     *
     * @return the normalized condition logic
     */
    public String getConditionLogic() {
        return conditionLogic;
    }

    /**
     * Actions to run when the automation is triggered but its conditions are NOT
     * met. Never null; empty when there is no else branch.
     *
     * @return the else-branch actions
     */
    public List<AutomationAction> getElseActions() {
        return elseActions;
    }

    /**
     * Whether this automation has a non-empty else branch.
     *
     * @return true if there are else actions to run when conditions fail
     */
    public boolean hasElseActions() {
        return elseActions != null && !elseActions.isEmpty();
    }

    /**
     * The amount of time to wait before running the actions in seconds
     *
     * @return The amount of time to wait before running the actions in seconds
     */
    public int getDelay() {
        return delay;
    }

    /**
     * The actions which will be run in order when this automation is triggered
     *
     * @return The actions which will be run in order when this automation is triggered
     */
    public List<AutomationAction> getActions() {
        return actions;
    }

    /**
     * Whether this automation is currently disabled
     *
     * @return Whether this automation is currently disabled
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Check if the triggered contains an event
     * Will be looked up from a set so is fast to check
     *
     * @param trigger The event to check
     * @return true if this automation should be checked, false otherwise
     */
    public boolean isTriggered(EventData trigger) {
        return triggers.contains(trigger);
    }

    /** Optional user-given name (never null; empty when unnamed). */
    public String getName() { return name == null ? "" : name; }
    public void setName(String n) { this.name = (n == null) ? "" : n.trim(); }

    /** Epoch-ms of the last time this automation's actions ran (0 = never). */
    public long getLastTriggered() { return lastTriggered; }
    public long getTriggerCount() { return triggerCount; }
    /** Restore persisted stats on load. */
    public void setStats(long lastTriggered, long triggerCount) {
        this.lastTriggered = lastTriggered;
        this.triggerCount = triggerCount;
    }
    /**
     * Record that this automation just fired: bump the count and stamp the time. Called
     * from the queue worker right before the actions run. {@code nowMs} is passed in
     * (the daemon has a real clock) so this stays testable and clock-source-agnostic.
     */
    public void recordTriggered(long nowMs) {
        this.triggerCount++;
        this.lastTriggered = nowMs;
    }

    /**
     * Whether all the conditions specified in this automation are met
     *
     * @param state The current event state
     * @return true if all conditions match the state, false otherwise
     */
    public boolean conditionsMet(Map<EventData, Value> state) {
        // FAST PATH — no nested groups (every automation created before this feature,
        // and every simple one). Behaviour is byte-identical to the original flat
        // evaluation, so nothing regresses.
        if (conditionGroups.isEmpty()) {
            // No conditions → always met (unchanged), regardless of logic. Also keeps
            // OR from vacuously returning false on an empty list.
            if (conditions.isEmpty()) return true;
            if (LOGIC_OR.equals(conditionLogic)) {
                return conditions.stream().anyMatch(c -> c.compare(state.get(c.getEventData())));
            }
            return conditions.stream().allMatch(c -> c.compare(state.get(c.getEventData())));
        }
        // NESTED PATH — flat conditions and each group are PEER terms combined under
        // conditionLogic. Empty flat list + present groups is fine (the flat terms just
        // don't contribute). "No terms at all" can't happen here (groups is non-empty).
        boolean isOr = LOGIC_OR.equals(conditionLogic);
        boolean any = false, all = true;
        for (AutomationCondition c : conditions) {
            boolean r = c.compare(state.get(c.getEventData()));
            any = any || r;
            all = all && r;
        }
        for (ConditionGroup g : conditionGroups) {
            boolean r = g.evaluate(state);
            any = any || r;
            all = all && r;
        }
        return isOr ? any : all;
    }

    /** The nested condition groups (never null; empty when unused). */
    public List<ConditionGroup> getConditionGroups() {
        return conditionGroups;
    }

    /**
     * ALL conditions referenced by this automation — the flat list PLUS every condition
     * nested in any group (recursively). Callers that walk conditions to seed variables
     * or decide which events to observe MUST use this, not {@link #getConditions}, or a
     * condition living only inside a group would be missed (never seeded / its event
     * never watched). {@link #getConditions} stays flat-only so {@link #toJson} still
     * emits just the top-level conditions.
     */
    public List<AutomationCondition> getAllConditions() {
        if (conditionGroups.isEmpty()) return conditions; // fast path — no copy
        List<AutomationCondition> all = new ArrayList<>(conditions);
        for (ConditionGroup g : conditionGroups) {
            g.collectConditions(all);
        }
        return all;
    }

    /**
     * Mutate this automation to disable the actions
     *
     * @param disabled Whether this should be disabled
     */
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    /**
     * Run all the actions for this automation
     * Will not check whether the conditions are met so that should be checked first if needed
     */
    public void triggerActions() {
        runActions(getActions());
    }

    /**
     * Run the else-branch actions (the ones that fire when the automation is
     * triggered but its conditions are NOT met). No-op when there is no else branch.
     */
    public void triggerElseActions() {
        runActions(getElseActions());
    }

    /**
     * Run a list of actions in order. Delegates to the static, re-entrant
     * {@link Automations#runActionList} so a control-flow action (loop/if) can run its
     * nested children through the same runner with a shared depth guard. Behaviour for
     * a flat list is identical to before (single level, depth 1).
     */
    private void runActions(List<AutomationAction> actionList) {
        Automations.runActionList(actionList);
    }

    /**
     * Create a JSON object which can be stored and loaded for this automation
     *
     * @return JSON representation of this automation
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();

        try {
            JSONArray triggers = new JSONArray();
            for (EventData trigger : getTriggers()) {
                triggers.put(trigger.toJson());
            }
            json.put("triggers", triggers);
            JSONArray conditions = new JSONArray();
            for (AutomationCondition condition : getConditions()) {
                conditions.put(condition.toJson());
            }
            json.put("conditions", conditions);
            json.put("conditionLogic", getConditionLogic());
            // Emit nested groups ONLY when present, so an automation without them
            // serializes byte-identically to before this feature (no new key appears).
            if (!conditionGroups.isEmpty()) {
                JSONArray groupsJson = new JSONArray();
                for (ConditionGroup g : conditionGroups) {
                    groupsJson.put(g.toJson());
                }
                json.put("conditionGroups", groupsJson);
            }
            json.put("delay", getDelay());
            JSONArray actions = new JSONArray();
            for (AutomationAction action : getActions()) {
                actions.put(action.toJson());
            }
            json.put("actions", actions);
            // Always emit elseActions (possibly empty) so a round-trip is stable;
            // an empty array is equivalent to "no else branch".
            JSONArray elseActionsJson = new JSONArray();
            for (AutomationAction action : getElseActions()) {
                elseActionsJson.put(action.toJson());
            }
            json.put("elseActions", elseActionsJson);
            json.put("disabled", isDisabled());
            // Optional name + run stats — emitted only when set, so an automation
            // created before this feature serializes with no new keys (byte-identical).
            if (!getName().isEmpty()) json.put("name", getName());
            if (triggerCount > 0) {
                json.put("triggerCount", triggerCount);
                json.put("lastTriggered", lastTriggered);
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * Create an instance of this class from some JSON
     * Will validate that the automation is valid
     *
     * @param input The JSON for this automation
     * @return An automation instance if the JSON is valid, null otherwise
     */
    /**
     * Rewrite stored option values that a later build renamed or withdrew, so an automation the
     * user created against an older option set still loads.
     *
     * <p>Without this, an unknown option makes {@code EventCondition.eventData} return null and
     * {@link #fromJson} drop the WHOLE automation — losing its name, conditions and actions, and
     * (once any later save rewrites the file) erasing it from disk. Migrating is strictly better
     * than dropping whenever the old value has a faithful successor.
     *
     * <p><b>RETIRED: occupant / seat=driver → passenger.</b> This rule rewrote a stored
     * driver-occupancy rule to the passenger seat, because the driver option had been withdrawn
     * (no {@code getPassengerStatus} area exists for that seat) and the old driver event was in
     * fact fed from area 1 = the FRONT PASSENGER, so "passenger" was the behaviour the user had
     * actually been observing.
     *
     * <p>The driver seat is now a supported option again, inferred from the seatbelt-reminder
     * mask + the driver belt ({@code BydDataCollector.readDriverOccupancyNow}), so
     * {@code seat=driver} is a VALID option and must NOT be rewritten — rewriting it would send
     * a freshly-authored driver rule to the wrong seat. The rule is therefore removed rather
     * than kept.
     *
     * <p><b>One-way loss, accepted and unavoidable:</b> a driver rule that was already rewritten
     * by a build carrying this migration was persisted as {@code seat=passenger} on the next
     * save, so its original intent is gone from disk and cannot be recovered here — a stored
     * "passenger" entry is now indistinguishable from one the user authored deliberately.
     * Guessing which ones to convert back would silently re-point working passenger rules at the
     * driver seat, which is worse. Affected users re-pick the driver seat once; noted in the
     * release notes.
     *
     * <p>Kept as an identity hook (rather than deleted) so the call sites in {@link #fromJson}
     * and {@code ConditionGroup.fromJson} stay in place for the next option withdrawal, and so
     * the collision-dropping wrapper below keeps its single documented seam.
     *
     * @return the entry unchanged; no option currently needs migrating
     */
    static JSONObject migrateStaleOptions(JSONObject entry) {
        return entry;
    }

    /**
     * Array-aware form of {@link #migrateStaleOptions}: migrates each entry, preserving order.
     *
     * <p>The collision-dropping this wrapper used to do existed only for the retired
     * driver→passenger rewrite: rewriting BOTH halves of a "driving alone" rule
     * ({@code driver==occupied AND passenger==empty}) collapsed them onto one seat, producing
     * {@code passenger==occupied AND passenger==empty} — never satisfiable, so the automation
     * loaded, looked healthy and silently never fired. With that rewrite gone no entry changes
     * seat, so no migration can manufacture a duplicate, and dropping entries here would now
     * DELETE a legitimate driver term. Kept as the single seam a future withdrawal would
     * re-use; reinstate the drop logic together with any rule that rewrites a variable.
     *
     * @param arr the stored triggers or conditions array; may be null
     * @return the entries to parse, in order
     */
    static List<JSONObject> migrateStaleOptions(JSONArray arr) throws org.json.JSONException {
        List<JSONObject> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            out.add(migrateStaleOptions(arr.getJSONObject(i)));
        }
        return out;
    }

    public static Automation fromJson(JSONObject input) {
        try {
            List<EventData> triggers = new ArrayList<>();
            JSONArray triggersJson = input.getJSONArray("triggers");
            if (triggersJson.length() == 0) return null;
            for (JSONObject triggerJson : migrateStaleOptions(triggersJson)) {
                String key = triggerJson.getString("type");
                EventCondition condition = Automations.getCondition(key);
                if (condition == null) return null;
                // eventData() returns null when a stored variable value is no longer a valid
                // option (EventCondition.eventData → EnumType.isValid), e.g. an option that a
                // later build removed. That null MUST NOT be added: it survives into the trigger
                // set, and toJson() then NPEs on trigger.toJson() BEFORE json.put("triggers"),
                // where the catch swallows it and returns an empty {} — which the next
                // saveToFile() persists, silently destroying the whole automation (name,
                // conditions, actions). Dropping just this automation keeps the damage contained
                // and leaves the .bak recoverable, matching how an unknown type is handled above.
                EventData triggerData = condition.eventData(triggerJson);
                if (triggerData == null) return null;
                triggers.add(triggerData);
            }
            // Belt-and-braces: the migration can DROP a collided entry, so re-assert the
            // non-empty invariant it was checked for above. It cannot currently empty the list
            // (a driver entry is only dropped when a passenger entry it would duplicate is
            // present, and that one is never dropped), but a future migration rule could, and a
            // trigger-less automation would load and silently never fire.
            if (triggers.isEmpty()) return null;

            List<AutomationCondition> conditions = new ArrayList<>();
            JSONArray conditionsJson = input.optJSONArray("conditions");
            if (conditionsJson != null) {
                for (JSONObject conditionJson : migrateStaleOptions(conditionsJson)) {
                    String key = conditionJson.getString("type");
                    EventCondition condition = Automations.getCondition(key);
                    if (condition == null) return null;
                    // Same null contract as the triggers above (automationCondition() returns
                    // null on a stale option / comparator / value). A null here propagates to
                    // toJson()'s condition loop and to Automations.isEventReferenced, which
                    // dereferences each condition's event data — so drop the automation instead.
                    AutomationCondition parsed = condition.automationCondition(conditionJson);
                    if (parsed == null) return null;
                    conditions.add(parsed);
                }
                // Same rule as ConditionGroup.fromJson: if the migration emptied a NON-empty
                // stored condition list, reject. conditionsMet treats an empty list as "always
                // met", so keeping it would turn the automation's gate into no gate and let it
                // fire unconditionally — worse than not firing, since actions actuate the car.
                if (conditionsJson.length() > 0 && conditions.isEmpty()) return null;
            }

            // Optional; absent/unknown → AND, which is the pre-existing behaviour, so
            // automations saved before this field existed load and run unchanged.
            String conditionLogic = input.optString("conditionLogic", LOGIC_AND);

            // Optional nested condition groups. Absent → empty list → conditionsMet
            // takes the identical flat path (no behaviour change for old automations).
            // Depth-capped so a hand-edited/imported config can't build an unbounded
            // tree; an invalid leaf inside any group rejects the whole automation
            // (mirrors flat-condition validation above).
            List<ConditionGroup> conditionGroups = new ArrayList<>();
            JSONArray groupsJson = input.optJSONArray("conditionGroups");
            if (groupsJson != null) {
                for (int i = 0; i < groupsJson.length(); i++) {
                    ConditionGroup g = ConditionGroup.fromJson(groupsJson.getJSONObject(i), MAX_GROUP_DEPTH);
                    if (g == null) return null;
                    conditionGroups.add(g);
                }
            }

            int delay = input.optInt("delay", 0);
            if (!Automations.isValidDelay(delay)) return null;

            // "actions" is required and must be non-empty (the primary branch).
            List<AutomationAction> actions = parseActions(input.getJSONArray("actions"));
            if (actions == null || actions.isEmpty()) return null;

            // "elseActions" is optional. Absent → no else branch. Present-but-invalid
            // (an unknown action type) → reject the whole automation rather than
            // silently dropping the branch, matching how the primary actions are
            // validated. An explicitly empty array is fine (== no else branch).
            List<AutomationAction> elseActions = new ArrayList<>();
            JSONArray elseActionsJson = input.optJSONArray("elseActions");
            if (elseActionsJson != null && elseActionsJson.length() > 0) {
                elseActions = parseActions(elseActionsJson);
                if (elseActions == null) return null;
            }

            boolean disabled = input.optBoolean("disabled", false);

            Automation a = new Automation(triggers, conditions, conditionLogic, conditionGroups,
                    delay, actions, elseActions, disabled);
            // Optional name + run stats (absent on pre-feature automations → defaults).
            a.setName(input.optString("name", ""));
            a.setStats(input.optLong("lastTriggered", 0L), input.optLong("triggerCount", 0L));
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse an actions JSON array into a list of {@link AutomationAction}. Shared by
     * the primary "actions" and optional "elseActions" parse so both validate an
     * unknown action type identically.
     *
     * @param actionsJson the JSON array of action objects
     * @return the parsed list, or null if any element is missing/unknown-type/invalid
     * @throws org.json.JSONException if the array shape is malformed (caller catches)
     */
    private static List<AutomationAction> parseActions(JSONArray actionsJson) throws org.json.JSONException {
        return parseActions(actionsJson, MAX_ACTION_DEPTH);
    }

    /**
     * Public entry to the same validated action-list parser {@link #fromJson} uses, so
     * {@link ActionGroups} validates a group's actions through the identical gate
     * (unknown/invalid action → null → the group is rejected). Returns null (not throw)
     * on malformed JSON.
     */
    public static List<AutomationAction> parseActionsPublic(JSONArray actionsJson) {
        try {
            return parseActions(actionsJson, MAX_ACTION_DEPTH);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse an actions JSON array, recursing into nested {@code childActions}/
     * {@code elseActions} for control-flow actions (loop/if) up to {@code depthLeft}
     * levels. A flat (non-control-flow) action never carries child keys, so an old
     * automation takes the identical single-level path. Returns null if any element is
     * missing/unknown-type/invalid, or if a control-flow action nests past the depth
     * cap — so a malformed/over-deep config is rejected whole, matching the flat path.
     */
    private static List<AutomationAction> parseActions(JSONArray actionsJson, int depthLeft) throws org.json.JSONException {
        List<AutomationAction> actions = new ArrayList<>();
        for (int i = 0; i < actionsJson.length(); i++) {
            JSONObject actionJson = actionsJson.getJSONObject(i);
            String key = actionJson.getString("type");
            Action action = Automations.getAction(key);
            if (action == null) return null;
            AutomationAction automationAction = action.fromJson(actionJson);
            if (automationAction == null) return null;
            // Only control-flow actions carry nested lists; everything else is flat.
            if (action.hasChildActions()) {
                if (depthLeft <= 0) return null; // over-deep tree → reject the automation
                List<AutomationAction> children = new ArrayList<>();
                JSONArray childJson = actionJson.optJSONArray("childActions");
                if (childJson != null) {
                    children = parseActions(childJson, depthLeft - 1);
                    if (children == null) return null;
                }
                List<AutomationAction> elseChildren = new ArrayList<>();
                JSONArray elseJson = actionJson.optJSONArray("elseActions");
                if (elseJson != null) {
                    elseChildren = parseActions(elseJson, depthLeft - 1);
                    if (elseChildren == null) return null;
                }
                automationAction = automationAction.withChildren(children, elseChildren);
            }
            actions.add(automationAction);
        }
        return actions;
    }

    // Max nesting depth for control-flow child actions (loop/if bodies). Bounds a
    // hand-edited/imported config so a pathological tree can't blow the stack; the UI now
    // reads this from the schema and stops offering If/Loop at the cap, so a user can't
    // build an unsaveable automation. Set generously (8) — deeper than any realistic rule
    // yet bounded. The runtime cycle guard (Automations.MAX_RUN_DEPTH) sits above this so a
    // legal max-depth tree that also expands action groups still executes without tripping.
    // (public so the web editor reads the SAME value and the two never drift.)
    public static final int MAX_ACTION_DEPTH = 8;
}
