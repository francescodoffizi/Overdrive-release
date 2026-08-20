package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Ensures the new catalog controls are selectable in automations and key mapping. */
public class InfotainmentAutomationActionTest {

    @Test
    public void automationPickerContainsRotationAndNativeCameraView() {
        Actions actions = new Actions();

        assertVehicleControlAction(actions, "infotainment_rotation", 1);
        assertVehicleControlAction(actions, "native_camera_view", 1);
    }

    @Test
    public void keyMappingPickerContainsBothCatalogControls() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/assets/web/shared/key-mapping.js");

        assertTrue(source.contains(
                "id: 'infotainment_rotation', i18n: 'keymap.act_infotainment_rotation'"));
        assertTrue(source.contains(
                "kind: 'catalog', key: 'infotainment_rotation'"));
        assertTrue(source.contains(
                "id: 'native_camera_view', i18n: 'keymap.act_native_camera_view'"));
        assertTrue(source.contains(
                "kind: 'catalog', key: 'native_camera_view'"));
    }

    @Test
    public void englishLabelsExist() throws Exception {
        JSONObject server = new JSONObject(readRepositoryFile(
                "app/src/main/assets/server-i18n/en.json")).getJSONObject("automation");
        JSONObject web = new JSONObject(readRepositoryFile(
                "app/src/main/assets/web/i18n/en.json")).getJSONObject("keymap");

        for (String key : new String[] {
                "rotate_infotainment",
                "rotate_infotainment_description",
                "orientation_horizontal",
                "orientation_vertical",
                "native_camera_view",
                "native_camera_view_description",
                "camera_front_wide",
                "camera_rear_wide",
                "camera_left_right"
        }) {
            assertTrue("Missing automation." + key, server.has(key));
        }
        for (String key : new String[] {
                "act_infotainment_rotation",
                "orientation_horizontal",
                "orientation_vertical",
                "act_native_camera_view",
                "camera_front_wide",
                "camera_rear_wide",
                "camera_left_right"
        }) {
            assertTrue("Missing keymap." + key, web.has(key));
        }
    }

    private static void assertVehicleControlAction(
            Actions actions, String id, int variableCount) {
        Action action = actions.getAction(id);
        assertTrue(id + " must be a VehicleControlAction",
                action instanceof VehicleControlAction);
        assertEquals(variableCount,
                ((VehicleControlAction) action).getVariables().size());
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 6 && current != null;
                depth++, current = current.getParent()) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
