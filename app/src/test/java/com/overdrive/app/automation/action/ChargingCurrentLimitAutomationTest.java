package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.AutomationCategories;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Contract for the readback-verified AC inlet current automation. */
public class ChargingCurrentLimitAutomationTest {

    @Test
    public void actionUsesVerifiedEndpointAndAllFiveStates() throws Exception {
        Action action = new Actions().getAction("acChargeCurrentLimit");
        assertTrue(action instanceof ApiAction);

        ApiAction apiAction = (ApiAction) action;
        assertEquals("POST", field(apiAction, "method"));
        assertEquals("/api/vehicle/ac-charge-current-limit", field(apiAction, "path"));
        assertEquals("{\"state\":${state}}", field(apiAction, "body"));
        assertEquals(1, apiAction.getVariables().size());

        Type variable = apiAction.getVariables().get(0);
        assertTrue(variable instanceof EnumType);
        assertEquals("state", variable.getLabel().getId());
        List<String> optionIds = new ArrayList<>();
        for (Label option : ((EnumType) variable).getOptions()) {
            optionIds.add(option.getId());
        }
        assertEquals(Arrays.asList("1", "2", "3", "4", "5"), optionIds);
        assertEquals(AutomationCategories.DRIVE,
                AutomationCategories.forId("acChargeCurrentLimit"));
    }

    @Test
    public void englishLabelsExist() throws Exception {
        JSONObject automation = new JSONObject(readRepositoryFile(
                "app/src/main/assets/server-i18n/en.json")).getJSONObject("automation");
        for (String key : new String[] {
                "ac_charge_current_limit",
                "ac_charge_current_limit_description",
                "ac_charge_current",
                "ac_charge_current_6a",
                "ac_charge_current_8a",
                "ac_charge_current_10a",
                "ac_charge_current_16a",
                "ac_charge_current_max"
        }) {
            assertTrue("Missing automation." + key, automation.has(key));
        }
    }

    private static String field(ApiAction action, String name) throws Exception {
        Field field = ApiAction.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(action);
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
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
