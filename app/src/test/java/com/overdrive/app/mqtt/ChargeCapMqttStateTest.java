package com.overdrive.app.mqtt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Regression coverage for verified MQTT charge-cap state and retained HA cleanup. */
public class ChargeCapMqttStateTest {

    @Test
    public void normalTelemetryRequiresVerifiedCompleteChargeCapPair() {
        assertTrue(MqttConnectionManager.isVerifiedChargeCapState(Boolean.TRUE, 80, 1));
        assertFalse(MqttConnectionManager.isVerifiedChargeCapState(null, 80, 1));
        assertFalse(MqttConnectionManager.isVerifiedChargeCapState(Boolean.FALSE, 80, 1));
        assertFalse(MqttConnectionManager.isVerifiedChargeCapState(Boolean.TRUE, 80, -1));
        assertFalse(MqttConnectionManager.isVerifiedChargeCapState(Boolean.TRUE, 49, 1));
    }

    @Test
    public void publisherAcceptsOnlyIntegralCompleteTelemetryState() throws Exception {
        JSONObject valid = new JSONObject()
                .put("charge_cap_percent", 80)
                .put("charge_cap_enabled", 1);
        assertTrue(MqttPublisherService.hasVerifiedChargeCapState(valid));

        assertFalse(MqttPublisherService.hasVerifiedChargeCapState(
                new JSONObject().put("charge_cap_percent", 80)));
        assertFalse(MqttPublisherService.hasVerifiedChargeCapState(
                new JSONObject().put("charge_cap_percent", "80").put("charge_cap_enabled", 1)));
        assertFalse(MqttPublisherService.hasVerifiedChargeCapState(
                new JSONObject().put("charge_cap_percent", 80.5).put("charge_cap_enabled", 1)));
    }

    @Test
    public void explicitNullRegistersAsAChangedRetainedState() throws Exception {
        TelemetryDiffer differ = new TelemetryDiffer();
        JSONObject dc = new JSONObject().put("is_dcfc", 1);
        assertTrue(differ.changedKeys(dc).contains("is_dcfc"));
        differ.markAllSent(dc, 1L);

        JSONObject unknown = new JSONObject().put("is_dcfc", JSONObject.NULL);
        assertTrue(differ.changedKeys(unknown).contains("is_dcfc"));
    }

    @Test
    public void unavailableTransitionClearsOnlyPreviouslyPublishedChargeCapTopics() {
        assertArrayEquals(new String[]{"charge_cap_percent", "charge_cap_enabled"},
                MqttPublisherService.chargeCapTombstoneKeys(true, true, false));
        assertArrayEquals(new String[]{"charge_cap_percent"},
                MqttPublisherService.chargeCapTombstoneKeys(true, false, false));
        assertArrayEquals(new String[0],
                MqttPublisherService.chargeCapTombstoneKeys(true, true, true));
        assertArrayEquals(new String[0],
                MqttPublisherService.chargeCapTombstoneKeys(false, false, false));
    }

    @Test
    public void firstPublishClearsStaleRetainedChargeCapStateAfterRestart() {
        assertArrayEquals(new String[]{"charge_cap_percent", "charge_cap_enabled"},
                MqttPublisherService.chargeCapTombstoneKeys(false, false, false, true));
        assertArrayEquals(new String[0],
                MqttPublisherService.chargeCapTombstoneKeys(false, false, false, false));
        assertArrayEquals(new String[0],
                MqttPublisherService.chargeCapTombstoneKeys(false, false, true, true));
    }

    @Test
    public void capabilityTransitionForcesHaDiscoveryReplacement() {
        assertTrue(MqttPublisherService.chargeCapDiscoveryNeedsRefresh(true, false, true));
        assertTrue(MqttPublisherService.chargeCapDiscoveryNeedsRefresh(true, true, false));
        assertFalse(MqttPublisherService.chargeCapDiscoveryNeedsRefresh(true, true, true));
        assertFalse(MqttPublisherService.chargeCapDiscoveryNeedsRefresh(false, false, true));
    }

    @Test
    public void commandEchoRejectsUnsupportedOrInvalidReadback() {
        assertTrue(MqttCommandRouter.isVerifiedChargeCapEcho(
                "charge_cap_percent", Boolean.TRUE, 85));
        assertFalse(MqttCommandRouter.isVerifiedChargeCapEcho(
                "charge_cap_percent", null, 85));
        assertFalse(MqttCommandRouter.isVerifiedChargeCapEcho(
                "charge_cap_percent", Boolean.FALSE, 85));
        assertFalse(MqttCommandRouter.isVerifiedChargeCapEcho(
                "charge_cap_enabled", Boolean.TRUE, -1));
    }

    @Test
    public void genericChargeCapCommandsRequireACompleteVerifiedBackend() {
        assertTrue(MqttCommandRouter.hasVerifiedChargeCapBackend(Boolean.TRUE, 85, 1));
        assertFalse(MqttCommandRouter.hasVerifiedChargeCapBackend(null, 85, 1));
        assertFalse(MqttCommandRouter.hasVerifiedChargeCapBackend(Boolean.FALSE, 85, 1));
        assertFalse(MqttCommandRouter.hasVerifiedChargeCapBackend(Boolean.TRUE, 49, 1));
        assertFalse(MqttCommandRouter.hasVerifiedChargeCapBackend(Boolean.TRUE, 85, -1));
    }
}
