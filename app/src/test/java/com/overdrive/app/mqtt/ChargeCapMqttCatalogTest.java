package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

public class ChargeCapMqttCatalogTest {

    @Test
    public void chargeCapMqttInputIsStrictAndUsesOnlyGenericRange() {
        VehicleControlCatalog.ControlEntity percent =
                VehicleControlCatalog.get("charge_cap_percent");
        VehicleControlCatalog.ControlEntity enabled =
                VehicleControlCatalog.get("charge_cap_enabled");

        assertEquals(50.0, percent.min, 0.0);
        assertEquals(100.0, percent.max, 0.0);
        assertNull(percent.toAction(null, "49", null));
        assertNull(percent.toAction(null, "bad", null));
        assertTrue(percent.toAction(null, "85", null).command
                instanceof com.overdrive.app.byd.routing.VehicleCommandRouter.ChargeCapPercentCommand);
        assertNull(enabled.toAction(null, "enable", null));
        assertTrue(enabled.toAction(null, "on", null).command
                instanceof com.overdrive.app.byd.routing.VehicleCommandRouter.ChargeCapToggleCommand);
    }

    @Test
    public void discoveryExposesChargeCapControlsOnlyWithVerifiedCompleteState() throws Exception {
        JSONObject unverified = new JSONObject()
                .put("charge_cap_percent", 80);
        JSONObject verified = new JSONObject()
                .put("charge_cap_percent", 80)
                .put("charge_cap_enabled", 1);

        JSONObject unverifiedComponents = bundleComponents(unverified);
        assertFalse(unverifiedComponents.has("ctl_charge_cap_percent"));
        assertFalse(unverifiedComponents.has("ctl_charge_cap_enabled"));

        JSONObject verifiedComponents = bundleComponents(verified);
        assertTrue(verifiedComponents.has("ctl_charge_cap_percent"));
        assertTrue(verifiedComponents.has("ctl_charge_cap_enabled"));
    }

    @Test
    public void chargingEnergyPublishesExplicitQualityMetadata() {
        TelemetryFieldCatalog.Field incomplete =
                TelemetryFieldCatalog.get(
                        "charging_capacity_incomplete");
        TelemetryFieldCatalog.Field estimated =
                TelemetryFieldCatalog.get(
                        "charging_capacity_estimated");
        TelemetryFieldCatalog.Field source =
                TelemetryFieldCatalog.get(
                        "charging_capacity_source");

        assertTrue(incomplete.isBinary());
        assertTrue(estimated.isBinary());
        assertEquals("enum", source.deviceClass);
        assertTrue(source.isDiscoverable());
    }

    private static JSONObject bundleComponents(JSONObject snapshot) throws Exception {
        String bundle = HomeAssistantDiscovery.buildBundle(
                "test-device", null, null, null, "overdrive/test", snapshot,
                Collections.<String>emptySet(), true);
        return new JSONObject(bundle).getJSONObject("cmps");
    }
}
