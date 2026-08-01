package com.overdrive.app.automation;

/**
 * Arithmetic evaluator for automation expressions: {@code + - * / %}, parentheses, unary
 * sign, and a small function set, over operands that may be live references
 * ({@code ${var:NAME}} / {@code ${signal:TYPE[:k=v]}}) resolved through
 * {@link AutomationCondition#resolveDynamicToString}.
 *
 * <p>Recursive descent over expression → term → factor, so precedence and associativity
 * are structural rather than a regex pass. Every failure path returns null (unparseable
 * input, unresolved reference, non-numeric operand, division by zero, depth/length limit)
 * — callers treat null as "no result" and skip their write, matching the fail-safe
 * contract the condition engine already uses.
 *
 * <p>Deliberately NOT a general expression language: no strings, no assignment, no side
 * effects. It reads state and returns a number.
 */
public final class ExpressionEvaluator {

    /** Longest accepted expression. Bounds a hand-edited/imported config. */
    public static final int MAX_LENGTH = 500;
    /** Parenthesis/function nesting ceiling — guards against stack exhaustion on the queue thread. */
    private static final int MAX_DEPTH = 32;

    private final String input;
    private int index;
    private int depth;

    private ExpressionEvaluator(String input) {
        this.input = input;
    }

    /**
     * Evaluate an expression against current automation state.
     *
     * @param expression e.g. {@code 500 + (${signal:batteryLevel} - 20) * 3.75}
     * @return the value, or null when it can't be evaluated (see class docs)
     */
    public static Double evaluate(String expression) {
        if (expression == null) return null;
        String s = expression.trim();
        if (s.isEmpty() || s.length() > MAX_LENGTH) return null;
        try {
            ExpressionEvaluator p = new ExpressionEvaluator(s);
            Double result = p.parseExpression();
            if (result == null) return null;
            p.skipWhitespace();
            // Trailing garbage means we misread the input; refuse rather than use a
            // prefix ("5 +" must not evaluate to 5).
            if (p.index != p.input.length()) return null;
            if (result.isNaN() || result.isInfinite()) return null;
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Format a result the way the variable store expects: whole numbers without a
     * trailing ".0" so a downstream string {@code eq} against "5" still matches (same
     * normalisation {@link com.overdrive.app.automation.action.IncrementVariableAction}
     * applies).
     */
    public static String format(double value) {
        // Guard the long range: a (long) cast SATURATES at Long.MAX_VALUE, so 1.6e19 would
        // silently print as 9223372036854775807. Outside the range fall through to the
        // double form, which stays a faithful (if exponential) representation.
        if (value == Math.rint(value) && !Double.isInfinite(value)
                && Math.abs(value) <= 9.007199254740992E15) {   // 2^53: exact-integer limit
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    // ── Grammar ──────────────────────────────────────────────────────────────────

    /** expression := term (('+' | '-') term)* */
    private Double parseExpression() {
        Double left = parseTerm();
        if (left == null) return null;
        double acc = left;
        while (true) {
            skipWhitespace();
            if (match('+')) {
                Double right = parseTerm();
                if (right == null) return null;
                acc += right;
            } else if (match('-')) {
                Double right = parseTerm();
                if (right == null) return null;
                acc -= right;
            } else {
                return acc;
            }
        }
    }

    /** term := factor (('*' | '/' | '%') factor)* */
    private Double parseTerm() {
        Double left = parseFactor();
        if (left == null) return null;
        double acc = left;
        while (true) {
            skipWhitespace();
            if (match('*')) {
                Double right = parseFactor();
                if (right == null) return null;
                acc *= right;
            } else if (match('/')) {
                Double right = parseFactor();
                if (right == null) return null;
                if (right == 0d) return null;    // divide-by-zero → no result
                acc /= right;
            } else if (match('%')) {
                Double right = parseFactor();
                if (right == null) return null;
                if (right == 0d) return null;
                acc %= right;
            } else {
                return acc;
            }
        }
    }

    /** factor := ('+' | '-') factor | '(' expression ')' | reference | function | number */
    private Double parseFactor() {
        skipWhitespace();
        if (match('+')) return parseFactor();
        if (match('-')) {
            Double f = parseFactor();
            return f == null ? null : -f;
        }
        if (match('(')) {
            if (++depth > MAX_DEPTH) return null;
            Double inner = parseExpression();
            depth--;
            if (inner == null) return null;
            skipWhitespace();
            if (!match(')')) return null;
            return inner;
        }
        if (index >= input.length()) return null;
        char c = input.charAt(index);
        // ${var:…} / ${signal:…} — a live reference.
        if (c == '$') return parseReference();
        if (Character.isDigit(c) || c == '.') return parseNumber();
        if (Character.isLetter(c) || c == '_') return parseFunction();
        return null;
    }

    /**
     * Parse a {@code ${…}} token and resolve it to a number through the SAME resolver the
     * condition RHS uses, so signal addressing has one grammar. A never-fired signal,
     * unset variable, or non-numeric value yields null (→ the whole expression fails, and
     * the caller skips its write rather than storing a wrong number).
     */
    private Double parseReference() {
        int start = index;
        if (!input.startsWith("${", index)) return null;
        int close = input.indexOf('}', index + 2);
        if (close < 0) return null;
        index = close + 1;
        String token = input.substring(start, index);
        String resolved = AutomationCondition.resolveDynamicToString(token);
        if (resolved == null) return null;
        try {
            return Double.valueOf(resolved.trim());
        } catch (NumberFormatException e) {
            return null;   // e.g. a variable holding "true" used in arithmetic
        }
    }

    private Double parseNumber() {
        int start = index;
        boolean dot = false;
        while (index < input.length()) {
            char c = input.charAt(index);
            if (c == '.') {
                if (dot) break;
                dot = true;
                index++;
            } else if (Character.isDigit(c)) {
                index++;
            } else {
                break;
            }
        }
        if (start == index) return null;
        try {
            return Double.valueOf(input.substring(start, index));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A bare identifier is only meaningful as a function call here — a variable must be
     * written {@code ${var:NAME}} so the expression syntax stays unambiguous with the
     * rest of the engine (no second addressing scheme to learn or maintain).
     */
    private Double parseFunction() {
        int start = index;
        while (index < input.length()
                && (Character.isLetterOrDigit(input.charAt(index)) || input.charAt(index) == '_')) {
            index++;
        }
        String name = input.substring(start, index);
        skipWhitespace();
        if (!match('(')) return null;
        if (++depth > MAX_DEPTH) return null;
        java.util.List<Double> args = new java.util.ArrayList<>(4);
        skipWhitespace();
        if (!match(')')) {
            while (true) {
                Double arg = parseExpression();
                if (arg == null) { depth--; return null; }
                args.add(arg);
                skipWhitespace();
                if (match(')')) break;
                if (!match(',')) { depth--; return null; }
                // Bound the argument list so a pathological input can't allocate freely.
                if (args.size() > 8) { depth--; return null; }
            }
        }
        depth--;
        return applyFunction(name, args);
    }

    /** The function set. An unknown name or wrong arity returns null (expression fails). */
    private static Double applyFunction(String name, java.util.List<Double> args) {
        String fn = name.toUpperCase(java.util.Locale.ROOT);
        switch (fn) {
            case "MIN":
                if (args.size() < 2) return null;
                return Math.min(args.get(0), args.get(1));
            case "MAX":
                if (args.size() < 2) return null;
                return Math.max(args.get(0), args.get(1));
            case "CLAMP":
                // clamp(value, lo, hi) — tolerate reversed bounds like the reference impl.
                if (args.size() < 3) return null;
                double lo = Math.min(args.get(1), args.get(2));
                double hi = Math.max(args.get(1), args.get(2));
                return Math.max(lo, Math.min(hi, args.get(0)));
            case "ROUND":
                if (args.isEmpty()) return null;
                return Math.rint(args.get(0));
            case "FLOOR":
                if (args.isEmpty()) return null;
                return Math.floor(args.get(0));
            case "CEIL":
                if (args.isEmpty()) return null;
                return Math.ceil(args.get(0));
            case "ABS":
                if (args.isEmpty()) return null;
                return Math.abs(args.get(0));
            case "SQRT":
                if (args.isEmpty() || args.get(0) < 0) return null;
                return Math.sqrt(args.get(0));
            case "POW":
                if (args.size() < 2) return null;
                return Math.pow(args.get(0), args.get(1));
            default:
                return null;
        }
    }

    // ── Scanner helpers ──────────────────────────────────────────────────────────

    private boolean match(char expected) {
        if (index < input.length() && input.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
    }
}
