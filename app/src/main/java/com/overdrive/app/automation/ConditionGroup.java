package com.overdrive.app.automation;

import com.overdrive.app.automation.condition.EventCondition;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.Value;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One nested condition group: a set of leaf {@link AutomationCondition}s plus optional
 * child {@link ConditionGroup}s, combined by this group's own AND/OR {@code logic}.
 * Enables boolean trees like {@code (A AND B) OR (C AND D)} that the flat
 * conditions+conditionLogic on {@link Automation} can't express.
 *
 * <p><b>Purely additive.</b> An automation with no {@code conditionGroups} never
 * constructs one of these, and {@link Automation#conditionsMet} falls back to the exact
 * pre-existing flat evaluation — so every saved automation is byte-identical and behaves
 * identically. Depth is bounded by the parser ({@link Automation}) so a hand-edited or
 * community-imported config can't build an unbounded tree.
 */
public final class ConditionGroup {

    private final String logic; // Automation.LOGIC_AND / LOGIC_OR
    private final List<AutomationCondition> conditions;
    private final List<ConditionGroup> groups;

    public ConditionGroup(String logic, List<AutomationCondition> conditions, List<ConditionGroup> groups) {
        this.logic = Automation.LOGIC_OR.equalsIgnoreCase(logic) ? Automation.LOGIC_OR : Automation.LOGIC_AND;
        this.conditions = conditions != null ? conditions : new ArrayList<>();
        this.groups = groups != null ? groups : new ArrayList<>();
    }

    public String getLogic() { return logic; }
    public List<AutomationCondition> getConditions() { return conditions; }
    public List<ConditionGroup> getGroups() { return groups; }

    /**
     * Evaluate this group against the current state: combine every leaf condition AND
     * every child group (as peer terms) using this group's logic. An empty group (no
     * leaves, no children) evaluates to true, matching {@link Automation}'s
     * "no conditions → met" rule so an empty group never vacuously fails an OR.
     */
    public boolean evaluate(Map<EventData, Value> state) {
        boolean isOr = Automation.LOGIC_OR.equals(logic);
        boolean any = false, all = true, sawTerm = false;
        for (AutomationCondition c : conditions) {
            if (c == null) continue;
            sawTerm = true;
            boolean r = c.compare(state.get(c.getEventData()));
            any = any || r;
            all = all && r;
        }
        for (ConditionGroup g : groups) {
            if (g == null) continue;
            sawTerm = true;
            boolean r = g.evaluate(state);
            any = any || r;
            all = all && r;
        }
        if (!sawTerm) return true;
        return isOr ? any : all;
    }

    /** Collect this group's leaves + all descendants' leaves (flat). For seeding/reference walks. */
    public void collectConditions(List<AutomationCondition> out) {
        out.addAll(conditions);
        for (ConditionGroup g : groups) {
            if (g != null) g.collectConditions(out);
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("logic", logic);
            JSONArray conds = new JSONArray();
            for (AutomationCondition c : conditions) {
                if (c != null) conds.put(c.toJson());
            }
            json.put("conditions", conds);
            JSONArray subs = new JSONArray();
            for (ConditionGroup g : groups) {
                if (g != null) subs.put(g.toJson());
            }
            json.put("groups", subs);
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }

    /**
     * Parse one group from JSON, recursing into child groups up to {@code depthLeft}
     * levels. Returns null if any leaf condition is invalid (unknown type / bad value)
     * so the whole automation is rejected, matching flat-condition parsing. depthLeft
     * &lt;= 0 stops recursion (children beyond the cap are dropped, not an error).
     */
    public static ConditionGroup fromJson(JSONObject input, int depthLeft) {
        try {
            String logic = input.optString("logic", Automation.LOGIC_AND);
            List<AutomationCondition> conds = new ArrayList<>();
            JSONArray condsJson = input.optJSONArray("conditions");
            if (condsJson != null) {
                // Migrate stale stored options here too — a nested group's condition rows come
                // from the SAME condition catalog as the flat ones, so a withdrawn option would
                // otherwise reject this group and drop the whole automation (see
                // Automation.migrateStaleOptions for why dropping loses user data). The
                // array-aware form is used so a group holding BOTH seats can't be migrated into
                // a contradictory AND.
                for (JSONObject cj : Automation.migrateStaleOptions(condsJson)) {
                    String key = cj.getString("type");
                    EventCondition ec = Automations.getCondition(key);
                    if (ec == null) return null;
                    AutomationCondition ac = ec.automationCondition(cj);
                    if (ac == null) return null;
                    conds.add(ac);
                }
                // A group the migration emptied must REJECT, not become a no-op. evaluate()
                // returns true for a group with no terms, so silently keeping an emptied group
                // turns a gate into no gate — the automation would then FIRE unconditionally,
                // which is worse than not firing (it can actuate the vehicle). This can only
                // happen when every stored condition in the group was dropped as a collided
                // duplicate; rejecting drops just this automation and leaves the .bak intact.
                if (condsJson.length() > 0 && conds.isEmpty()) return null;
            }
            List<ConditionGroup> subs = new ArrayList<>();
            JSONArray groupsJson = input.optJSONArray("groups");
            if (groupsJson != null && groupsJson.length() > 0) {
                // Over-depth is a REJECTION (return null → whole automation rejected),
                // matching the action-tree parser (Automation.parseActions). The old code
                // silently DROPPED over-deep child groups while still returning this group as
                // valid, so an imported/hand-edited automation lost condition terms without
                // warning (and its AND/OR logic silently changed). depthLeft is generous
                // (Automation.MAX_GROUP_DEPTH); no editor-built automation reaches it.
                if (depthLeft <= 0) return null;
                for (int i = 0; i < groupsJson.length(); i++) {
                    ConditionGroup g = fromJson(groupsJson.getJSONObject(i), depthLeft - 1);
                    if (g == null) return null;
                    // Same rule as Automation.fromJson: a term-less child group evaluates to true
                    // and would vacuously satisfy an OR at this level. Drop it rather than keep a
                    // gate that isn't one.
                    if (g.conditions.isEmpty() && g.groups.isEmpty()) continue;
                    subs.add(g);
                }
            }
            return new ConditionGroup(logic, conds, subs);
        } catch (Exception e) {
            return null;
        }
    }
}
