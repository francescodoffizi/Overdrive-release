package com.overdrive.app.automation;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class AutomationSignalPickerSortingTest {

    @Test
    public void dynamicSignalPickersUseCategorizedAlphabeticalOrdering() throws Exception {
        String source = new String(
                Files.readAllBytes(Path.of("src/main/assets/web/shared/automations.js")),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("appendSortedCatalogOptions(selector, options)"));
        assertTrue(source.contains("buckets.get(cat).slice().sort(compare)"));
        assertTrue(source.contains("this.appendSortedCatalogOptions(sel, pickable)"));
        assertTrue(source.contains("this.appendSortedCatalogOptions(sel, signalPickable)"));
    }
}
