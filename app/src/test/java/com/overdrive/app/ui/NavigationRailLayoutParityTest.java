package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class NavigationRailLayoutParityTest {
    private static final Pattern DESTINATION_ID =
            Pattern.compile("android:id=\"@\\+id/(railDest[A-Za-z0-9_]+)\"");

    @Test
    public void portraitAndLandscapeExposeTheSameDestinationRows() throws Exception {
        Set<String> portrait = destinationIds(
                readProjectFile("src/main/res/layout/activity_main_new.xml"));
        Set<String> landscape = destinationIds(
                readProjectFile("src/main/res/layout-land/activity_main_new.xml"));

        assertEquals(portrait, landscape);
        assertTrue(portrait.containsAll(Arrays.asList(
                "railDestDashboard",
                "railDestLive",
                "railDestRecordings",
                "railDestVehicle",
                "railDestProjection",
                "railDestTrips",
                "railDestCharging",
                "railDestAutomations",
                "railDestKeyMapping",
                "railDestIntegrations",
                "railDestRoadSense",
                "railDestMap",
                "railDestDiagnostics",
                "railDestSettings",
                "railDestAbout"
        )));
    }

    private static Set<String> destinationIds(String xml) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = DESTINATION_ID.matcher(xml);
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }

    private static String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path fromModule = current.resolve(relativePath);
        Path file = Files.exists(fromModule)
                ? fromModule
                : current.resolve("app").resolve(relativePath);
        if (!Files.exists(file)) {
            throw new AssertionError("Could not locate project file: " + relativePath);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
