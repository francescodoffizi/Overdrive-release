package com.overdrive.app.server;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins category-local sorting and BEV filtering in the two schema-driven pickers. */
public class AutomationPickerGroupingAssetTest {

    @Test
    public void groupedOptionsAreSortedByLocalizedLabel() throws IOException {
        String automations = readRepositoryFile(
                "app/src/main/assets/web/shared/automations.js");
        String keyMapping = readRepositoryFile(
                "app/src/main/assets/web/shared/key-mapping.js");
        String automationsPage = readRepositoryFile(
                "app/src/main/assets/web/local/automations.html");
        String keyMappingPage = readRepositoryFile(
                "app/src/main/assets/web/local/key-mapping.html");

        assertTrue(automations.contains(
                "const items = buckets.get(cat).slice().sort"));
        assertTrue(automations.contains("this._collator().compare("));
        assertTrue(keyMapping.contains(
                "var items = buckets[cats[i]].slice().sort"));
        assertTrue(keyMapping.contains("String(tr(a.i18n)).localeCompare("));
        assertTrue(automationsPage.contains("automations.js?v=av62"));
        assertTrue(keyMappingPage.contains("key-mapping.js?v=32"));
    }

    @Test
    public void keyMappingFiltersHybridOnlyActionsOnConfirmedBev() throws IOException {
        String keyMapping = readRepositoryFile(
                "app/src/main/assets/web/shared/key-mapping.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/KeymapApiHandler.java");

        assertTrue(keyMapping.contains("var HYBRID_ONLY_CURATED = {"));
        assertTrue(keyMapping.contains(
                "state.fuelCapableHybrid === false"));
        assertTrue(keyMapping.contains("HYBRID_ONLY_CURATED[CURATED[i].id]"));
        assertTrue(api.contains(
                "resp.put(\"fuelCapableHybrid\", collector.isFuelCapableHybridPublic());"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
