package com.overdrive.app.util;

import java.text.MessageFormat;
import java.util.Locale;

/**
 * {@link MessageFormat} formatting that tolerates translator-written apostrophes.
 *
 * <p>{@code MessageFormat} treats a lone {@code '} as the opening of a quoted
 * literal run: the apostrophe itself is consumed, and everything up to the next
 * {@code '} (or end of string) is emitted verbatim — including any {@code {0}}
 * inside it, which is then never substituted.
 *
 * <p>That is a poor fit for a Crowdin-fed catalog. Elision is ordinary in many
 * of the languages we ship: French {@code l'}, {@code d'}, {@code n'};
 * Ukrainian {@code з'}; Dutch {@code {0}'s}; Turkish {@code {0}'de}. Translators
 * write these naturally and cannot reasonably be asked to type {@code ''}, so
 * the catalogs accumulate templates that silently drop an apostrophe — or, when
 * it precedes a placeholder, drop the substitution:
 *
 * <pre>
 *   "L'enregistrement n'a pas été trouvé: {0}"
 *      → "Lenregistrement na pas été trouvé: {0}"   // filename never shown
 * </pre>
 *
 * <p>This helper escapes lone apostrophes immediately before formatting, so the
 * catalog can hold ordinary prose and the placeholders still resolve. Existing
 * {@code ''} pairs are left alone, making the transform safe to apply to a
 * template that was already escaped by hand.
 */
public final class MessageFormatSafe {

    private MessageFormatSafe() {
    }

    /**
     * Format {@code template} with {@code args}, escaping lone apostrophes first.
     *
     * <p>With no arguments the template is returned verbatim: there is no
     * placeholder to protect, so running it through {@code MessageFormat} would
     * only risk mangling text that is already correct.
     *
     * <p>On a malformed template (unbalanced braces, bad argument index) the raw
     * template is returned rather than throwing — matching the pre-existing
     * behaviour of the call sites this replaces, where a broken translation must
     * never take down a recording or an API response.
     */
    public static String format(String template, Locale locale, Object... args) {
        if (template == null) return null;
        if (args == null || args.length == 0) return template;
        try {
            MessageFormat format = (locale != null)
                    ? new MessageFormat(escapeLoneApostrophes(template), locale)
                    : new MessageFormat(escapeLoneApostrophes(template));
            return format.format(args);
        } catch (Exception e) {
            return template;
        }
    }

    /**
     * Double every apostrophe that is not already part of a {@code ''} pair.
     *
     * <p>Runs of apostrophes are handled pairwise so the transform is idempotent:
     * an already-escaped {@code ''} stays {@code ''} rather than growing to four
     * characters on each pass.
     */
    static String escapeLoneApostrophes(String template) {
        if (template.indexOf('\'') < 0) return template;

        int length = template.length();
        StringBuilder out = new StringBuilder(length + 8);
        int i = 0;
        while (i < length) {
            char c = template.charAt(i);
            if (c != '\'') {
                out.append(c);
                i++;
                continue;
            }
            // Consume the whole run so pairs are counted, not re-escaped.
            int runStart = i;
            while (i < length && template.charAt(i) == '\'') {
                i++;
            }
            int run = i - runStart;
            // NOTE: deliberately a manual loop, not String.repeat — that API is
            // Java 11 but only reached Android at API 34, and this ships to
            // minSdk 25 head units.
            for (int pair = 0; pair < run / 2; pair++) {
                out.append("''");
            }
            if ((run & 1) == 1) {
                out.append("''");
            }
        }
        return out.toString();
    }
}
