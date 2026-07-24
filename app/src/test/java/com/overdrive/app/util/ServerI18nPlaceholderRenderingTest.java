package com.overdrive.app.util;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every shipped server-i18n template must still substitute its placeholders.
 *
 * <p>This is the contract that actually matters to users, and it is the one a
 * raw {@link java.text.MessageFormat} call site silently breaks: a lone
 * apostrophe opens a quoted run that swallows everything after it — so
 * {@code "З'єднання не знайдено: {0}"} emits a literal {@code {0}} and the
 * connection name never reaches the user.
 *
 * <p>Unlike a rule about the shape of the raw string, this walks the real
 * catalogs and asserts on rendered output, so it covers every subtree — not
 * just {@code telegram.*} — and it keeps passing as translators write ordinary
 * prose, because production formats through {@link MessageFormatSafe}.
 */
public class ServerI18nPlaceholderRenderingTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)");
    /** {0,number}-style typed subformats — unsupported, see walk(). */
    private static final Pattern TYPED_PLACEHOLDER = Pattern.compile("\\{(\\d+)\\s*,");

    @Test
    public void everyCatalogTemplateSubstitutesAllPlaceholders() throws IOException, JSONException {
        Path dir = findCatalogDir();
        List<String> failures = new ArrayList<>();
        int checked = 0;

        try (DirectoryStream<Path> catalogs = Files.newDirectoryStream(dir, "*.json")) {
            for (Path catalog : catalogs) {
                String locale = catalog.getFileName().toString().replace(".json", "");
                JSONObject root = new JSONObject(
                        new String(Files.readAllBytes(catalog), StandardCharsets.UTF_8));
                checked += walk(locale, "", root, failures);
            }
        }

        assertTrue("No catalogs were walked — the fixture path is wrong", checked > 0);
        if (!failures.isEmpty()) {
            fail("Templates lost a placeholder when formatted (" + failures.size() + "):\n  "
                    + String.join("\n  ", failures));
        }
    }

    /** Recurse the catalog, formatting every string that declares a placeholder. */
    private int walk(String locale, String prefix, JSONObject node, List<String> failures) {
        int checked = 0;
        for (Iterator<String> keys = node.keys(); keys.hasNext(); ) {
            String key = keys.next();
            // opt() rather than get(): the Android org.json stubs declare a
            // checked JSONException on get(), and a key straight from keys()
            // cannot be absent anyway.
            Object value = node.opt(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof JSONObject) {
                checked += walk(locale, path, (JSONObject) value, failures);
                continue;
            }
            if (!(value instanceof String)) continue;

            String template = (String) value;
            SortedSet<Integer> declared = declaredPlaceholderIndices(template);
            if (declared.isEmpty()) continue;

            // Typed subformats ({0,number}, {0,choice}, ...) are unsupported:
            // this harness feeds String args (a number subformat would throw on
            // them and misreport as "not substituted"), and MessageFormatSafe's
            // escaping cannot protect apostrophes inside recursively re-parsed
            // choice branches. No catalog uses them today; fail loudly with the
            // real reason if one ever lands, instead of a misleading
            // substitution failure.
            Matcher typed = TYPED_PLACEHOLDER.matcher(template);
            if (typed.find()) {
                failures.add(locale + " " + path + " — uses a typed subformat (\""
                        + typed.group() + "...\"), which MessageFormatSafe does not"
                        + " support (see its class javadoc); use a plain {"
                        + typed.group(1) + "} and format the value in code");
                continue;
            }

            checked++;
            // Args are sized to the highest index, but only DECLARED indices
            // are asserted below: a translation may legitimately reference
            // {1} without {0}, and blaming the formatter for an index the
            // string never contained would misdirect whoever reads the
            // failure. (Cross-locale placeholder parity is a different
            // contract, owned by TelegramCatalogParityTest.)
            Object[] args = new Object[declared.last() + 1];
            for (int i = 0; i < args.length; i++) {
                args[i] = "ARG" + i;
            }

            String rendered = MessageFormatSafe.format(
                    template, Locale.forLanguageTag(locale), args);

            for (int i : declared) {
                if (!rendered.contains("ARG" + i)) {
                    failures.add(locale + " " + path + " — {" + i + "} not substituted: \""
                            + rendered.replace("\n", "\\n") + "\"");
                    break;
                }
            }
        }
        return checked;
    }

    private static SortedSet<Integer> declaredPlaceholderIndices(String template) {
        SortedSet<Integer> declared = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            declared.add(Integer.parseInt(matcher.group(1)));
        }
        return declared;
    }

    /** Locate server-i18n whether the test runs from the module or the repo root. */
    private static Path findCatalogDir() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 4 && current != null; depth++) {
            Path fromModule = current.resolve("src/main/assets/server-i18n");
            if (Files.isDirectory(fromModule)) return fromModule;
            Path fromRepository = current.resolve("app/src/main/assets/server-i18n");
            if (Files.isDirectory(fromRepository)) return fromRepository;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate app/src/main/assets/server-i18n");
    }
}
