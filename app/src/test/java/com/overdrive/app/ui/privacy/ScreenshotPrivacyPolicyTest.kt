package com.overdrive.app.ui.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPrivacyPolicyTest {
    @Test
    fun masksNetworkAddressesUrlsAndCoordinates() {
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveText("192.168.50.251:5555"))
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveText("https://private.example.test/dashboard"))
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveText("51.507351, -0.127758"))
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveText("Latitude: 51.507351"))
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveText("owner@example.test"))
    }

    @Test
    fun doesNotMaskOrdinaryVehicleMetrics() {
        assertFalse(ScreenshotPrivacyPolicy.isSensitiveText("100% state of charge"))
        assertFalse(ScreenshotPrivacyPolicy.isSensitiveText("244 mi · SOH 98%"))
        assertFalse(ScreenshotPrivacyPolicy.isSensitiveText("Version 36.6"))
    }

    @Test
    fun masksKnownPrivateViewRolesWithoutPageSpecificWiring() {
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveResourceName("tvCurrentUrl"))
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveResourceName("ivQrCode"))
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveResourceName("ivThumbnail"))
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveResourceName("tvLocation"))
        assertTrue(ScreenshotPrivacyPolicy.isSensitiveResourceName("cameraPreview"))
        assertFalse(ScreenshotPrivacyPolicy.isSensitiveResourceName("tvBatteryPercent"))
    }
}
