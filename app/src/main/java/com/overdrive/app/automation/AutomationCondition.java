package com.overdrive.app.automation;

import com.overdrive.app.automation.action.SetVariableAction;
import com.overdrive.app.automation.condition.BydEvent;
import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.BaseValue;
import com.overdrive.app.automation.value.IntValue;
import com.overdrive.app.automation.value.StringValue;
import com.overdrive.app.automation.value.Value;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class AutomationCondition {
    private final EventData eventData;
    private final String comparator;
    private final Object value;

    /**
     * A condition representation for a specific automation
     * The value can have any type as it is compared from an event with a specific type
     *
     * @param eventData  The variables for an event which would be compared to this condition
     * @param comparator The id of a comparator to use to compare the event and this value
     * @param value      The value to compare to an event
     */
    public AutomationCondition(EventData eventData, String comparator, Object value) {
        this.eventData = eventData;
        this.comparator = comparator;
        this.value = value;
    }

    /**
     * The variables for an event which would be compared to this condition
     *
     * @return The variables for an event which would be compared to this condition
     */
    public EventData getEventData() {
        return eventData;
    }

    /**
     * The id of a comparator to use to compare the event and this value
     * See the getComparators() method of values or types
     *
     * @return The id of a comparator to use to compare the event and this value
     */
    public String getComparator() {
        return comparator;
    }

    /**
     * The value to compare to an event
     *
     * @return The value to compare to an event
     */
    public Object getValue() {
        return value;
    }

    /**
     * Compare this value using the stored comparator
     * Checks for null response from the compare method to ensure the comparator and values are valid
     * A null value means the referenced event has never fired since boot, so its state is unknown.
     * In that case the condition is treated as not met (returns false) rather than dereferencing null,
     * which would throw an NPE on the telemetry/queue threads that call conditionsMet.
     *
     * @param value The value to compare with this condition
     * @return true if the comparison was successful, false otherwise
     */
    public boolean compare(Value value) {
        // An absent (never-fired) event value can never satisfy a condition
        if (value == null) return false;
        // DYNAMIC RIGHT-HAND SIDE (expression engine). When the stored value is a
        // reference token — ${var:NAME} (a user variable) or ${signal:TYPE[:k=v,…]}
        // (another live signal) — resolve it against the CURRENT shared state at compare
        // time and compare the LHS signal against that. This is fully additive: only a
        // String value beginning with the "${" token triggers it, and no pre-existing
        // automation ever stored such a value (numeric conditions stored ints; enum/
        // string conditions stored plain option words), so every existing rule takes the
        // identical constant path below. Fail-safe: an unresolved/absent/non-comparable
        // reference yields "not met" (returns false) rather than throwing on the
        // telemetry/queue threads — matching the null-LHS contract above.
        Object rhs = this.value;
        if (rhs instanceof String) {
            String ref = ((String) rhs).trim();
            if (ref.startsWith("${") && ref.endsWith("}")) {
                Value resolved = resolveDynamic(ref);
                if (resolved == null) return false; // unresolved reference → not met
                // Coerce the resolved value to the LHS's type so a numeric signal can be
                // compared against a variable/signal whose live value is string-backed
                // (a user variable is always a StringValue; "speed > ${var:limit}" where
                // limit holds "60" must compare numerically, not lexically). If the LHS
                // is an IntValue we parse the resolved value's string form to an int;
                // non-numeric → not met (fail-safe). For a string/enum LHS the raw
                // resolved value is compared as-is (lexical eq/neq).
                Object coerced = coerceForLhs(value, resolved);
                if (coerced == null) return false;
                return Boolean.TRUE.equals(value.compare(coerced, comparator));
            }
        }
        // Coerce a plain CONSTANT rhs to the LHS's type before comparing. A user VARIABLE
        // LHS is always a StringValue, but the numeric value picker stores its constant as a
        // bare JSON number → an Integer on the daemon (and DynamicIntType.isValid accepts
        // both). StringValue.compareValue is typed to String, so BaseValue.compare then does
        // (String) Integer → ClassCastException → null → the condition silently evaluates
        // false. That is exactly why "if variable == 0 then … else …" never took the THEN
        // branch (e.g. a 0/1 toggle stuck at 0). Coercing here (mirrors coerceForLhs on the
        // dynamic path) makes the constant comparison type-correct in both directions.
        Object constant = coerceConstantForLhs(value, this.value);
        if (constant == null && this.value != null) return false; // non-coercible → not met
        return Boolean.TRUE.equals(value.compare(constant, comparator));
    }

    /**
     * Coerce a plain constant to be comparable against the LHS value's type. A
     * {@link StringValue} LHS gets the constant as its String form (so a numeric-picker
     * Integer like {@code 0} compares against a variable's {@code "0"}); an {@link IntValue}
     * LHS gets it parsed to an Integer (so a string {@code "60"} compares numerically),
     * returning null when it isn't a whole number (fail-safe → not met). Any other LHS type
     * (or a null constant) is returned unchanged, so every existing same-type comparison
     * takes the identical path it did before.
     */
    private static Object coerceConstantForLhs(Value lhs, Object constant) {
        if (constant == null) return null;
        if (lhs instanceof StringValue) return constant.toString();
        if (lhs instanceof IntValue) {
            if (constant instanceof Integer) return constant;
            if (constant instanceof Number) return ((Number) constant).intValue();
            try {
                return Integer.valueOf(constant.toString().trim());
            } catch (Exception e) {
                return null; // non-numeric constant vs a numeric signal → not met
            }
        }
        return constant;
    }

    /**
     * Coerce a resolved dynamic value to be comparable against the LHS value's type.
     * When the LHS is numeric ({@link IntValue}) the resolved value is parsed to an
     * Integer (from its string/Integer form), so a numeric signal compares numerically
     * against a string-backed variable — returns null when it isn't a whole number
     * (fail-safe → not met). For any other LHS type the resolved value is returned
     * unchanged (string/enum eq/neq compares lexically, as today).
     *
     * @param lhs      the live left-hand value (the signal being tested)
     * @param resolved the value the dynamic reference resolved to
     * @return a value comparable against {@code lhs}, or null if not coercible
     */
    private static Object coerceForLhs(Value lhs, Value resolved) {
        if (lhs instanceof IntValue) {
            Object raw = (resolved instanceof BaseValue<?>) ? ((BaseValue<?>) resolved).getValue() : resolved;
            if (raw instanceof Integer) return raw;
            if (raw instanceof Number) return ((Number) raw).intValue();
            try {
                return Integer.valueOf(raw.toString().trim());
            } catch (Exception e) {
                return null; // non-numeric variable/signal vs a numeric condition → not met
            }
        }
        return resolved;
    }

    /**
     * One-shot evaluation of "&lt;live signal&gt; &lt;comparator&gt; &lt;value&gt;" against the CURRENT
     * shared automation state, reusing this class's full compare path — including the
     * dynamic right-hand side ({@code ${var:NAME}} / {@code ${signal:TYPE[:k=v]}}) and the
     * numeric coercion in {@link #coerceForLhs}. Exposed so the inline flow actions
     * (If / Loop / Wait Until) evaluate a scalar test IDENTICALLY to a real condition,
     * rather than each re-implementing a constant-only compare. Fail-safe: an unknown
     * signal, never-fired LHS, or unresolved reference yields {@code false} (not met).
     *
     * @param lhs        the live signal to test (its state is read here)
     * @param comparator eq/neq/gt/lt/gte/lte
     * @param value      a constant (Integer/String) OR a dynamic {@code ${…}} token
     * @return true iff the comparison holds against current state
     */
    public static boolean evaluate(EventData lhs, String comparator, Object value) {
        if (lhs == null || comparator == null) return false;
        Value current = Automations.getStateValue(lhs);
        if (current == null) return false;
        return new AutomationCondition(lhs, comparator, value).compare(current);
    }

    /** True if a stored condition value is a dynamic reference token ${…}. */
    public static boolean isDynamicRef(Object value) {
        if (!(value instanceof String)) return false;
        String s = ((String) value).trim();
        return s.startsWith("${") && s.endsWith("}") && s.length() > 3;
    }

    /**
     * Parse a signal ADDRESS into the {@link EventData} state key it names, without reading
     * its value. Accepts the same grammar {@link #resolveDynamic} does, with or without the
     * {@code ${signal:…}} wrapper:
     *
     * <ul>
     *   <li>{@code TYPE} → {@code EventData(TYPE)} — e.g. {@code gear}, {@code batteryLevel}</li>
     *   <li>{@code TYPE:k1=v1,k2=v2} → an ATTRIBUTED key — e.g. {@code speed:units=kmph},
     *       {@code lights:area=lowBeam}, {@code turnSignal:side=left}</li>
     *   <li>{@code ${signal:…}} → the wrapped form, so a value emitted by the editor's
     *       signal picker can be stored directly as an address</li>
     * </ul>
     *
     * <p>This is the LEFT-hand-side counterpart of the RHS resolver: the flow actions
     * (if/else, wait-until, wait-until-signal, loop) use it so their comparable-signal list
     * is the full condition catalog rather than a hardcoded enum per action, addressing every
     * attributed signal by the one grammar the RHS already speaks. Keeping both in this class
     * keeps that grammar in a single place.
     *
     * @return the addressed key, or null when the address is blank/malformed (callers treat
     *         null as "no LHS" and fail safe rather than comparing against a wrong signal)
     */
    /**
     * Legacy flow-action signal ids → their equivalent address in the shared grammar.
     *
     * <p>Before the flow actions shared the condition catalog, each declared its own enum and
     * these ids named signals the catalog models as ATTRIBUTED conditions. They are still on
     * disk in saved automations, and {@link com.overdrive.app.automation.action.BaseAction}
     * rejects an action whose stored variable no longer validates — which would drop the whole
     * automation. Translating them here keeps every saved automation working AND keeps the
     * translation in one place instead of a per-action switch.
     *
     * <p>Every other legacy id (brake, batteryLevel, gear, …) is already a catalog id and
     * needs no entry.
     */
    private static final Map<String, String> LEGACY_SIGNAL_IDS = Map.of(
            "speedKmph", "speed:units=kmph",
            "speedMph",  "speed:units=mph",
            "turnLeft",  "turnSignal:side=left",
            "turnRight", "turnSignal:side=right",
            "lowBeam",   "lights:area=lowBeam",
            "highBeam",  "lights:area=highBeam",
            "hazard",    "lights:area=hazard",
            "drl",       "lights:area=drl");

    /**
     * Is this exactly one of the pre-catalog flow-action signal ids? Lets a caller pre-filter
     * without parsing (a legacy alias does not contain the type it maps to, so a substring
     * test alone would miss it).
     */
    public static boolean isLegacySignalId(String id) {
        return id != null && LEGACY_SIGNAL_IDS.containsKey(id);
    }

    public static EventData resolveSignalAddress(String address) {
        if (address == null) return null;
        try {
            String s = address.trim();
            if (s.isEmpty()) return null;
            // Saved automations still hold the pre-catalog ids; map them to the shared grammar
            // before parsing so they resolve to exactly the signal they always did.
            String legacy = LEGACY_SIGNAL_IDS.get(s);
            if (legacy != null) s = legacy;
            // Unwrap ${signal:…} / ${var:…} so the editor's picker output works as-is.
            if (isDynamicRef(s)) {
                String inner = s.substring(2, s.length() - 1).trim();
                int kindColon = inner.indexOf(':');
                if (kindColon < 0) return null;
                String kind = inner.substring(0, kindColon).trim();
                String rest = inner.substring(kindColon + 1).trim();
                if (rest.isEmpty()) return null;
                if ("var".equals(kind)) {
                    return SetVariableAction.variableEvent(rest);
                }
                if (!"signal".equals(kind)) return null;
                s = rest;
            }
            int attrColon = s.indexOf(':');
            if (attrColon < 0) return new EventData(s);
            String type = s.substring(0, attrColon).trim();
            if (type.isEmpty()) return null;
            Map<String, String> attrs = new HashMap<>();
            // A user VARIABLE is addressed as "variable:name=<free text>", and that text is the
            // only attribute value here that isn't a fixed token — it may legitimately contain
            // a comma ("Shopping,List"). Splitting on "," would then produce a fragment with no
            // "=" and reject the whole address, so the rule would save and silently never fire.
            // Take the remainder verbatim as the single name attribute instead.
            if (BydEvent.VARIABLE_TYPE.equals(type)) {
                String rest = s.substring(attrColon + 1).trim();
                if (!rest.startsWith("name=")) return null;
                String name = rest.substring(5).trim();
                return name.isEmpty() ? null : SetVariableAction.variableEvent(name);
            }
            for (String pair : s.substring(attrColon + 1).trim().split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) return null;   // malformed attribute → fail safe, never guess
                attrs.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
            return attrs.isEmpty() ? new EventData(type) : new EventData(type, attrs);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolve a dynamic reference token ({@code ${var:NAME}} / {@code ${signal:TYPE[:k=v]}})
     * to its live value as a plain String, or null if it can't be resolved (unknown kind,
     * never-fired signal, malformed token). Exposed for {@link
     * com.overdrive.app.automation.action.SetVariableAction} so "set variable = &lt;live
     * signal&gt;" captures the CURRENT value of any signal into a user variable, reusing the
     * SAME resolver the condition RHS uses (one source of truth for signal addressing).
     *
     * @param token a {@code ${…}} reference (caller should check {@link #isDynamicRef})
     * @return the resolved value as a String, or null when unresolved
     */
    public static String resolveDynamicToString(String token) {
        Value v = resolveDynamic(token);
        if (v == null) return null;
        Object raw = (v instanceof BaseValue<?>) ? ((BaseValue<?>) v).getValue() : v;
        return raw == null ? null : raw.toString();
    }

    /**
     * Resolve a dynamic reference token to the live {@link Value} it points at, or null
     * when it can't be resolved (unknown kind, never-fired signal, malformed token).
     * Reads the SAME shared automation state the LHS is read from
     * ({@link Automations#getStateValue}), so a cross-signal compare sees a consistent
     * snapshot. Two forms:
     *   ${var:NAME}                      → the user variable NAME (see SetVariableAction)
     *   ${signal:TYPE}                   → the live signal of that type (no attributes)
     *   ${signal:TYPE:k1=v1,k2=v2}       → a live signal differentiated by attributes
     *                                      (e.g. windowOpenPercent with area=lf)
     */
    private static Value resolveDynamic(String token) {
        try {
            // Strip the ${ … } wrapper.
            String inner = token.substring(2, token.length() - 1).trim();
            int colon = inner.indexOf(':');
            if (colon < 0) return null;
            String kind = inner.substring(0, colon).trim();
            String rest = inner.substring(colon + 1).trim();
            if (rest.isEmpty()) return null;

            if ("var".equals(kind)) {
                // User variable → its current state value (a StringValue), or null if unset.
                return Automations.getStateValue(SetVariableAction.variableEvent(rest));
            }
            if ("signal".equals(kind)) {
                // signal:TYPE  or  signal:TYPE:attrKey=val,attrKey2=val2
                String type;
                Map<String, String> attrs = new HashMap<>();
                int attrColon = rest.indexOf(':');
                if (attrColon < 0) {
                    type = rest;
                } else {
                    type = rest.substring(0, attrColon).trim();
                    String attrStr = rest.substring(attrColon + 1).trim();
                    for (String pair : attrStr.split(",")) {
                        int eq = pair.indexOf('=');
                        if (eq <= 0) return null; // malformed attribute → fail safe
                        attrs.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                    }
                }
                if (type.isEmpty()) return null;
                EventData key = attrs.isEmpty() ? new EventData(type) : new EventData(type, attrs);
                return Automations.getStateValue(key);
            }
            return null;
        } catch (Throwable t) {
            return null; // any parse error → not met (fail-safe on the fire gate)
        }
    }

    /**
     * Create a JSON object which can be stored and loaded for this condition
     *
     * @return JSON representation of this condition
     */
    public JSONObject toJson() {
        JSONObject json = getEventData().toJson();

        try {
            json.put("comparator", getComparator());
            json.put("value", getValue());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }
}
