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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every shipped server-i18n template must still substitute its placeholders.
 *
 * <p>This is the contract that actually matters to users, and it is the one a
 * raw {@link java.text.MessageFormat} call site silently breaks: a lone
 * apostrophe opens a quoted run, so {@code "L'enregistrement ... {0}"} emits a
 * literal {@code {0}} and the filename never reaches the user.
 *
 * <p>Unlike a rule about the shape of the raw string, this walks the real
 * catalogs and asserts on rendered output, so it covers every subtree — not
 * just {@code telegram.*} — and it keeps passing as translators write ordinary
 * prose, because production formats through {@link MessageFormatSafe}.
 */
public class ServerI18nPlaceholderRenderingTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)");

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
            int argCount = highestPlaceholderIndex(template) + 1;
            if (argCount == 0) continue;

            checked++;
            Object[] args = new Object[argCount];
            for (int i = 0; i < argCount; i++) {
                args[i] = "ARG" + i;
            }

            String rendered = MessageFormatSafe.format(
                    template, Locale.forLanguageTag(locale), args);

            for (int i = 0; i < argCount; i++) {
                if (!rendered.contains("ARG" + i)) {
                    failures.add(locale + " " + path + " — {" + i + "} not substituted: \""
                            + rendered.replace("\n", "\\n") + "\"");
                    break;
                }
            }
        }
        return checked;
    }

    private static int highestPlaceholderIndex(String template) {
        int highest = -1;
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        return highest;
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
