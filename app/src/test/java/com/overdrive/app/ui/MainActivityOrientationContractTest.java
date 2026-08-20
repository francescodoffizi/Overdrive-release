package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class MainActivityOrientationContractTest {

    @Test
    public void mainActivityDoesNotConsumeLayoutChangingConfigurationEvents() throws Exception {
        String manifest = readProjectFile("src/main/AndroidManifest.xml");
        int activityStart = manifest.indexOf(
                "android:name=\"com.overdrive.app.ui.MainActivity\"");
        assertTrue("MainActivity declaration missing", activityStart >= 0);
        int activityEnd = manifest.indexOf("</activity>", activityStart);
        assertTrue("MainActivity declaration is not closed", activityEnd > activityStart);

        String declaration = manifest.substring(activityStart, activityEnd);
        assertFalse(declaration.contains("configChanges=\"orientation"));
        assertFalse(declaration.contains("|orientation"));
        assertFalse(declaration.contains("screenSize"));
    }

    @Test
    public void primaryAdaptiveScreensProvideLandscapeResources() throws Exception {
        assertTrue(projectFile("src/main/res/layout/fragment_dashboard.xml").toFile().isFile());
        assertTrue(projectFile("src/main/res/layout-land/fragment_dashboard.xml").toFile().isFile());
        assertTrue(projectFile("src/main/res/layout/fragment_recordings.xml").toFile().isFile());
        assertTrue(projectFile("src/main/res/layout-land/fragment_recordings.xml").toFile().isFile());
    }

    private static String readProjectFile(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(projectFile(relativePath)),
                StandardCharsets.UTF_8
        );
    }

    private static Path projectFile(String relativePath) {
        Path current = Paths.get("").toAbsolutePath();
        Path fromModule = current.resolve(relativePath);
        if (Files.exists(fromModule)) return fromModule;
        Path fromRepository = current.resolve("app").resolve(relativePath);
        if (Files.exists(fromRepository)) return fromRepository;
        throw new AssertionError("Could not locate project file: " + relativePath);
    }
}
