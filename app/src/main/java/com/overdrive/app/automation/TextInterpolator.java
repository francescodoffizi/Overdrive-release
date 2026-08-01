package com.overdrive.app.automation;

import com.overdrive.app.automation.action.SetVariableAction;
import com.overdrive.app.automation.value.Value;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${…}} references in an automation's FREE-TEXT fields (notification
 * body, toast / dialog message, spoken text, MQTT topic + payload) against live
 * automation state.
 *
 * <p>Three accepted forms, all read through the SAME state map a condition reads, so a
 * message shows exactly what a condition would have compared:
 * <ul>
 *   <li>{@code ${var:NAME}} — a user variable (Set Variable / Calculate Variable)
 *   <li>{@code ${signal:TYPE[:k=v,…]}} — a live vehicle signal, attribute-addressed
 *       exactly as a condition's right-hand side ({@code ${signal:windowOpenPercent:area=lf}})
 *   <li>{@code ${NAME}} — bare user-variable shorthand, the form {@code ApiAction} bodies
 *       and MQTT publish already accepted; kept so existing automations are unchanged
 * </ul>
 *
 * <p>An unresolved reference is left as the LITERAL placeholder rather than blanked —
 * the pre-existing behaviour, and it keeps a half-configured message debuggable.
 *
 * <p>Free text is safe here in a way it is NOT for {@link com.overdrive.app.automation.action.ShellAction},
 * whose numeric-only restriction is a security boundary: these values land in a UI string
 * or a notification body, never on a privileged command line. Callers that splice into
 * JSON must still escape each substituted value, so {@link #resolve} deliberately resolves
 * ONE token at a time and leaves escaping to the caller (see {@code ApiAction}).
 */
public final class TextInterpolator {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final String VAR_PREFIX = "var:";
    private static final String SIGNAL_PREFIX = "signal:";

    private TextInterpolator() { }

    /**
     * Resolve ONE {@code ${…}} token (wrapper included) to its current text value.
     *
     * @param token the full placeholder text, e.g. {@code ${signal:batteryLevel}}
     * @return the live value, or null when it can't be resolved (unknown variable,
     *         never-fired signal, malformed token) — callers keep the literal placeholder
     */
    public static String resolve(String token) {
        if (token == null || token.length() < 4) return null;   // shortest form is ${x}
        try {
            String inner = token.substring(2, token.length() - 1).trim();
            if (inner.isEmpty()) return null;
            if (inner.startsWith(VAR_PREFIX) || inner.startsWith(SIGNAL_PREFIX)) {
                // Shared resolver, so signal addressing can't drift from a condition's.
                return AutomationCondition.resolveDynamicToString("${" + inner + "}");
            }
            return variableValue(inner);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Interpolate every reference in {@code input}, leaving unresolved ones literal.
     * For a caller that splices into JSON, resolve token-by-token via {@link #resolve}
     * instead so each value can be escaped.
     *
     * @param input the raw text; null / reference-free input is returned unchanged
     * @return the text with every resolvable reference replaced
     */
    public static String interpolate(String input) {
        if (input == null || input.indexOf("${") < 0) return input;
        try {
            Matcher m = TOKEN.matcher(input);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String value = resolve(m.group(0));
                m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
            }
            m.appendTail(out);
            return out.toString();
        } catch (Throwable t) {
            return input;
        }
    }

    /** A user variable's current value, or null when it was never set. */
    private static String variableValue(String name) {
        try {
            Value v = Automations.getStateValue(SetVariableAction.variableEvent(name));
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
