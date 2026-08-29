package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.action.Actions;
import com.overdrive.app.automation.action.VehicleControlAction;
import com.overdrive.app.automation.AutomationCategories;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydFeatureIds;
import com.overdrive.app.byd.routing.VehicleCommandRouter;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Contracts for the OEM four-position headlight selector across every action surface. */
public class HeadlightModeControlTest {

    @Test
    public void featureIdsAndModeValuesMatchTheOemReference() {
        assertEquals(1276153912, BydFeatureIds.INSTRUMENT_HEADLIGHT_CONTROL_SET);
        assertEquals(1011875880, BydFeatureIds.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK);
        assertEquals(1, BydDataCollector.HEADLIGHT_MODE_OFF);
        assertEquals(2, BydDataCollector.HEADLIGHT_MODE_AUTO);
        assertEquals(3, BydDataCollector.HEADLIGHT_MODE_PARKING);
        assertEquals(4, BydDataCollector.HEADLIGHT_MODE_LOW_BEAM);
    }

    @Test
    public void catalogMapsOnlyTheFourOemSelectorModes() {
        VehicleControlCatalog.ControlEntity entity =
                VehicleControlCatalog.get("headlight_mode");

        assertNotNull(entity);
        assertEquals("select", entity.platform);
        assertEquals("headlight_mode", entity.stateKey);
        assertEquals(Arrays.asList("off", "auto", "parking", "low_beam"), entity.options);
        assertMode(entity, "off", BydDataCollector.HEADLIGHT_MODE_OFF);
        assertMode(entity, "auto", BydDataCollector.HEADLIGHT_MODE_AUTO);
        assertMode(entity, "parking", BydDataCollector.HEADLIGHT_MODE_PARKING);
        assertMode(entity, "low_beam", BydDataCollector.HEADLIGHT_MODE_LOW_BEAM);
        assertNull(entity.toAction(null, "on", null));
        assertNull(entity.toAction(null, "high_beam", null));
        assertNull(entity.toAction(null, "", null));
    }

    @Test
    public void automationPickerUsesTheSharedCatalogModes() {
        Action action = new Actions().getAction("headlight_mode");

        assertTrue(action instanceof VehicleControlAction);
        assertEquals(AutomationCategories.LIGHTING,
                AutomationCategories.forId("headlight_mode"));
        List<Type> variables = ((VehicleControlAction) action).getVariables();
        assertEquals(1, variables.size());
        assertTrue(variables.get(0) instanceof EnumType);

        List<String> optionIds = new ArrayList<>();
        for (Label option : ((EnumType) variables.get(0)).getOptions()) {
            optionIds.add(option.getId());
        }
        assertEquals(Arrays.asList("off", "auto", "parking", "low_beam"), optionIds);
    }

    @Test
    public void keyMappingPickerOffersEachFixedModeWithoutAnUnsafeCycle() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/assets/web/shared/key-mapping.js");

        assertTrue(source.contains(
                "id: 'headlight_mode',  i18n: 'keymap.act_headlight_mode'"));
        assertTrue(source.contains(
                "kind: 'catalog', key: 'headlight_mode'"));
        assertTrue(source.contains(
                "{ v: 'off', i18n: 'keymap.off' }"));
        assertTrue(source.contains(
                "{ v: 'auto', i18n: 'keymap.headlight_auto' }"));
        assertTrue(source.contains(
                "{ v: 'parking', i18n: 'keymap.parking_lights' }"));
        assertTrue(source.contains(
                "{ v: 'low_beam', i18n: 'keymap.headlight_on_low_beam' }"));

        int start = source.indexOf("id: 'headlight_mode'");
        int end = source.indexOf("] },", start);
        assertTrue(start >= 0 && end > start);
        assertFalse(source.substring(start, end).contains("toggle"));
    }

    @Test
    public void englishLabelsExistForBothEditors() throws Exception {
        JSONObject automation = new JSONObject(readRepositoryFile(
                "app/src/main/assets/server-i18n/en.json")).getJSONObject("automation");
        JSONObject keymap = new JSONObject(readRepositoryFile(
                "app/src/main/assets/web/i18n/en.json")).getJSONObject("keymap");

        for (String key : new String[] {
                "set_headlight_mode",
                "set_headlight_mode_description",
                "headlight_mode",
                "headlight_auto",
                "parking_lights",
                "headlight_on_low_beam"
        }) {
            assertTrue("Missing automation." + key, automation.has(key));
        }
        for (String key : new String[] {
                "act_headlight_mode",
                "headlight_auto",
                "parking_lights",
                "headlight_on_low_beam"
        }) {
            assertTrue("Missing keymap." + key, keymap.has(key));
        }
    }

    private static void assertMode(
            VehicleControlCatalog.ControlEntity entity, String payload, int expectedMode) {
        VehicleControlCatalog.ControlAction action =
                entity.toAction(null, payload, null);
        assertNotNull(action);
        assertTrue(action.command instanceof VehicleCommandRouter.HeadlightModeCommand);
        assertEquals(expectedMode,
                ((VehicleCommandRouter.HeadlightModeCommand) action.command).mode);
        assertEquals("headlight_mode", action.echoKey);
        assertEquals(payload, action.echoValue);
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
