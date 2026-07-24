package com.overdrive.app.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Locale;

/**
 * Translator-written apostrophes must survive MessageFormat.
 *
 * <p>{@code java.text.MessageFormat} treats a lone {@code '} as the start of a
 * quoted literal run. Translations for languages with ordinary elision (fr
 * {@code l'}, {@code d'}, {@code n'}; uk {@code з'}; nl {@code {0}'s}; tr
 * {@code {0}'de}) therefore lose the apostrophe, and — when it appears before a
 * placeholder — lose the substitution entirely.
 *
 * <p>Cases below are taken verbatim from {@code app/src/main/assets/server-i18n}.
 */
public class MessageFormatSafeTest {

    private static final Locale FR = Locale.forLanguageTag("fr");
    private static final Locale UK = Locale.forLanguageTag("uk");
    private static final Locale NL = Locale.forLanguageTag("nl");

    // ── The severe case: an ODD apostrophe count before a placeholder ─────
    // The unclosed quote run swallows the substitution entirely; without the
    // escape these render the literal "{0}".

    @Test
    public void oddApostropheRunNoLongerSwallowsThePlaceholder() {
        // uk errors.connection_not_found — one lone apostrophe, so the raw
        // quote run extends to end-of-string and {0} is inside it.
        assertEquals("З'єднання не знайдено: conn-4",
                MessageFormatSafe.format("З'єднання не знайдено: {0}", UK, "conn-4"));
    }

    @Test
    public void ukrainianConnectionCountIsNotLost() {
        // uk errors.max_connections_reached
        assertEquals("Максимальна кількість з'єднань досягнута (8)",
                MessageFormatSafe.format(
                        "Максимальна кількість з'єднань досягнута ({0})", UK, 8));
    }

    // ── The mild case: an EVEN count closes its run before the placeholder ─
    // Raw MessageFormat drops the apostrophes but still substitutes; the fix
    // must keep the substitution AND restore the apostrophes.

    @Test
    public void evenApostropheRunKeepsBothApostrophesAndTheSubstitution() {
        // fr errors.recordings_not_found_with_filename — two lone apostrophes
        // (L', n'a), so raw MessageFormat renders "Lenregistrement na pas été
        // trouvé: clip.mp4": placeholder fine, prose mangled.
        assertEquals("L'enregistrement n'a pas été trouvé: clip.mp4",
                MessageFormatSafe.format(
                        "L'enregistrement n'a pas été trouvé: {0}", FR, "clip.mp4"));
    }

    @Test
    public void dutchPossessiveApostropheIsPreserved() {
        // nl telegram.recording_caption_no_label
        assertEquals(" Opname - 14:32's",
                MessageFormatSafe.format(" Opname - {0}'s", NL, "14:32"));
    }

    @Test
    public void nullLocaleStillEscapesAndSubstitutes() {
        // SrtWriter passes null to keep the old MessageFormat.format(template,
        // args) default-locale semantics — the escaping must apply there too.
        assertEquals(" Opname - 14:32's",
                MessageFormatSafe.format(" Opname - {0}'s", null, "14:32"));
        assertEquals("З'єднання не знайдено: conn-4",
                MessageFormatSafe.format("З'єднання не знайдено: {0}", null, "conn-4"));
    }

    // ── The safety-net contract: broken translations must not throw ───────
    // This catch path is the sole crash guard for every Messages.get call
    // site; a refactor that narrows the try block must fail here.

    @Test
    public void malformedTemplateReturnsTheRawTemplateInsteadOfThrowing() {
        // Unbalanced brace — MessageFormat.applyPattern throws internally.
        assertEquals("Enregistrements supprimés: {0 fichiers",
                MessageFormatSafe.format("Enregistrements supprimés: {0 fichiers", FR, 3));
    }

    // ── Escaping rules ───────────────────────────────────────────────────

    @Test
    public void alreadyEscapedPairIsLeftAlone() {
        // A translator (or a previous fix) may have written the MessageFormat
        // escape by hand. Doubling it again would emit two apostrophes.
        assertEquals("L'enregistrement 14:32",
                MessageFormatSafe.format("L''enregistrement {0}", FR, "14:32"));
    }

    @Test
    public void textWithoutApostrophesIsUnchanged() {
        assertEquals("Recording started at 14:32",
                MessageFormatSafe.format("Recording started at {0}", FR, "14:32"));
    }

    @Test
    public void templateWithoutArgumentsIsReturnedVerbatim() {
        // No args means no MessageFormat pass at all, so the raw apostrophe
        // must survive untouched rather than being escape-processed.
        assertEquals("L'enregistrement a commencé",
                MessageFormatSafe.format("L'enregistrement a commencé", FR));
    }

    @Test
    public void multipleApostrophesAndPlaceholdersAllSurvive() {
        // fr notifications.soh_frame_mismatch_body shape: two placeholders,
        // an apostrophe before the second one.
        assertEquals("Le véhicule rapporte 60 kWh, mais la capacité de l'emballage est 57 kWh.",
                MessageFormatSafe.format(
                        "Le véhicule rapporte {0} kWh, mais la capacité de l'emballage est {1} kWh.",
                        FR, 60, 57));
    }
}
