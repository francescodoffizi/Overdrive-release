package com.overdrive.app.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class KeyMapDispatcherNativeCameraGateTest {

    @Test
    public void nativeCameraBindingRequiresActiveNativeCameraPackage() throws Exception {
        JSONObject binding = binding(cameraAction());

        assertFalse(KeyMapDispatcher.isBindingEligibleForForeground(binding, null));
        assertFalse(KeyMapDispatcher.isBindingEligibleForForeground(
                binding, "com.overdrive.app"));
        assertTrue(KeyMapDispatcher.isBindingEligibleForForeground(
                binding, "com.byd.avc"));
    }

    @Test
    public void sequenceContainingNativeCameraViewUsesSameForegroundGate() throws Exception {
        JSONObject sequence = new JSONObject()
                .put("kind", "sequence")
                .put("steps", new JSONArray()
                        .put(new JSONObject()
                                .put("kind", "catalog")
                                .put("key", "lock"))
                        .put(cameraAction()));

        assertFalse(KeyMapDispatcher.isBindingEligibleForForeground(
                binding(sequence), "com.overdrive.app"));
        assertTrue(KeyMapDispatcher.isBindingEligibleForForeground(
                binding(sequence), "com.byd.avc"));
    }

    @Test
    public void ordinaryBindingRemainsEligibleForAnyForegroundPackage() throws Exception {
        JSONObject ordinary = binding(new JSONObject()
                .put("kind", "catalog")
                .put("key", "lock"));

        assertTrue(KeyMapDispatcher.isBindingEligibleForForeground(ordinary, null));
        assertTrue(KeyMapDispatcher.isBindingEligibleForForeground(
                ordinary, "com.overdrive.app"));
    }

    private static JSONObject binding(JSONObject action) throws Exception {
        return new JSONObject()
                .put("keycode", 302)
                .put("pressType", "single")
                .put("enabled", true)
                .put("action", action);
    }

    private static JSONObject cameraAction() throws Exception {
        return new JSONObject()
                .put("kind", "catalog")
                .put("key", "native_camera_view")
                .put("payload", "rear");
    }
}
