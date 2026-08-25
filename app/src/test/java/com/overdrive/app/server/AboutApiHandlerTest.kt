package com.overdrive.app.server

import com.overdrive.app.ui.about.VehicleVersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AboutApiHandlerTest {
    @Test
    fun responseContainsOnlyBrowserSafeVersionMetadata() {
        val response = AboutApiHandler.responseFor(
            VehicleVersionInfo(
                firmware = "2602",
                dsp = "2507223",
                mcu = "13.5.5",
                android = "10 (API 29)",
                securityPatch = "2023-02-05",
                headUnit = "BYD AUTO / DiLink3.0",
                vin = "TESTV1N1234567890"
            )
        )

        assertEquals(
            setOf("success", "firmware", "dsp", "mcu", "android", "securityPatch", "headUnit"),
            response.keySet()
        )
        assertFalse(response.keys().asSequence().any { it.contains("vin", ignoreCase = true) })
        assertFalse(response.toString().contains("TESTV1N1234567890"))
    }
}
