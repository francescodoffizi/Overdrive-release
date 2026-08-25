package com.overdrive.app.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleVersionInfoProviderTest {
    @Test
    fun resolvesObservedDiLinkVersionProperties() {
        val properties = mapOf(
            "dsp_version" to "2507223",
            "mcu_version" to "13.5.5.2601300.2"
        )

        val info = VehicleVersionInfoProvider.resolve(
            build = AndroidBuildSnapshot(
                incremental = "eng.build.20260204.033434",
                display = "QKQ1.210910.001 release-keys",
                release = "10",
                sdk = 29,
                securityPatch = "2023-02-05",
                manufacturer = "BYD AUTO",
                model = "BYD AUTO",
                device = "DiLink3.0"
            ),
            propertyReader = properties::get,
            vin = "testv1n1234567890"
        )

        assertEquals("2602 (eng.build.20260204.033434)", info.firmware)
        assertEquals("2507223", info.dsp)
        assertEquals("13.5.5.2601300.2", info.mcu)
        assertEquals("10 (API 29)", info.android)
        assertEquals("2023-02-05", info.securityPatch)
        assertEquals("BYD AUTO / DiLink3.0", info.headUnit)
        assertEquals("TESTV1N1234567890", info.vin)
    }

    @Test
    fun ignoresOfflineSentinelsAndUsesPropertyFallbacks() {
        val properties = mapOf(
            "persist.sys.dsp_version" to "2411001",
            "mcu_version" to "MCU_OFFLINE",
            "persist.sys.mcu_version" to "12.4.1"
        )

        val info = VehicleVersionInfoProvider.resolve(
            build = AndroidBuildSnapshot(null, "display-1", "12", 31, null, null, null, null),
            propertyReader = properties::get,
            vin = null
        )

        assertEquals("display-1", info.firmware)
        assertEquals("2411001", info.dsp)
        assertEquals("12.4.1", info.mcu)
        assertNull(info.securityPatch)
        assertNull(info.headUnit)
        assertNull(info.vin)
    }

    @Test
    fun rejectsSdkVinWrappersAndMasksARealVinByDefault() {
        assertNull(VehicleVersionInfoProvider.normalizeVin("hashed-vin-wrapper"))
        assertNull(VehicleVersionInfoProvider.normalizeVin("LGXCH6CD0I2085367"))

        val vin = "TESTV1N1234567890"
        val masked = VehicleVersionInfoProvider.maskVin(vin)
        assertEquals(vin.length, masked.length)
        assertTrue(masked.endsWith("7890"))
        assertFalse(masked.contains(vin.dropLast(4)))
    }

    @Test
    fun webValuesNeverExposeVin() {
        val info = VehicleVersionInfo(
            firmware = "2602",
            dsp = "2507223",
            mcu = "13.5.5",
            android = "10 (API 29)",
            securityPatch = "2023-02-05",
            headUnit = "BYD AUTO / DiLink3.0",
            vin = "TESTV1N1234567890"
        )

        val values = VehicleVersionInfoProvider.webValues(info)

        assertEquals(
            setOf("firmware", "dsp", "mcu", "android", "securityPatch", "headUnit"),
            values.keys
        )
        assertFalse(values.keys.any { it.contains("vin", ignoreCase = true) })
        assertFalse(values.values.contains(info.vin))
    }
}
