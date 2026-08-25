package com.overdrive.app.ui.about

import android.os.Build
import java.util.Locale

/** Values shown in About's vehicle/head-unit card. Null means the host did not expose a value. */
internal data class VehicleVersionInfo(
    val firmware: String?,
    val dsp: String?,
    val mcu: String?,
    val android: String,
    val securityPatch: String?,
    val headUnit: String?,
    val vin: String?
)

/** Public Android build fields captured separately so the resolver remains a pure unit-test target. */
internal data class AndroidBuildSnapshot(
    val incremental: String?,
    val display: String?,
    val release: String?,
    val sdk: Int,
    val securityPatch: String?,
    val manufacturer: String?,
    val model: String?,
    val device: String?
)

/**
 * Reads stable Android/BYD version properties without blocking the UI thread.
 *
 * BYD exposes DSP and MCU versions as system properties rather than public Android APIs. Property
 * names differ between DiLink generations, so the resolver uses a small ordered set of observed
 * names and ignores offline/unknown sentinels. The standard Android fields remain available on a
 * non-BYD host, making the About card useful even when the OEM properties do not exist.
 */
internal object VehicleVersionInfoProvider {
    private val firmwareDate = Regex("(?:^|\\D)20(\\d{2})(0[1-9]|1[0-2])\\d{2}(?:\\D|$)")
    private val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")

    fun read(vin: String?): VehicleVersionInfo = resolve(
        build = AndroidBuildSnapshot(
            incremental = Build.VERSION.INCREMENTAL,
            display = Build.DISPLAY,
            release = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE
        ),
        propertyReader = ::readSystemProperty,
        vin = vin
    )

    internal fun resolve(
        build: AndroidBuildSnapshot,
        propertyReader: (String) -> String?,
        vin: String?
    ): VehicleVersionInfo {
        val rawFirmware = firstUseful(
            build.incremental,
            propertyReader("ro.build.version.incremental"),
            propertyReader("ro.product.build.version.incremental"),
            build.display,
            propertyReader("ro.build.display.id")
        )
        val release = useful(build.release) ?: "?"
        val android = if (build.sdk > 0) "$release (API ${build.sdk})" else release

        return VehicleVersionInfo(
            firmware = rawFirmware?.let(::formatFirmware),
            dsp = firstUseful(
                propertyReader("dsp_version"),
                propertyReader("persist.sys.dsp_version"),
                propertyReader("sys.ivi.dsp_sw_ver")
            ),
            mcu = firstUseful(
                propertyReader("mcu_version"),
                propertyReader("persist.sys.mcu_version"),
                propertyReader("sys.ivi.mcu_sw_ver")
            ),
            android = android,
            securityPatch = useful(build.securityPatch),
            headUnit = distinctUseful(
                build.manufacturer,
                build.model,
                build.device
            ).joinToString(" / ").ifEmpty { null },
            vin = normalizeVin(vin)
        )
    }

    /** Convert an OEM incremental build such as 20260204 into its familiar YYMM label (2602). */
    internal fun formatFirmware(raw: String): String {
        val value = raw.trim()
        val match = firmwareDate.find(value) ?: return value
        val shortVersion = match.groupValues[1] + match.groupValues[2]
        val alreadyLabelled = Regex("^${Regex.escape(shortVersion)}(?:$|[._ -])")
            .containsMatchIn(value)
        return if (alreadyLabelled) value else "$shortVersion ($value)"
    }

    /** Only a standards-shaped real VIN is displayable; hashed/wrapper SDK fallbacks are rejected. */
    internal fun normalizeVin(raw: String?): String? {
        val normalized = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return normalized.takeIf { vinPattern.matches(it) && it.any(Char::isDigit) }
    }

    internal fun maskVin(vin: String): String {
        val normalized = normalizeVin(vin) ?: return ""
        return "\u2022".repeat(normalized.length - 4) + normalized.takeLast(4)
    }

    /**
     * Browser-safe subset used by the authenticated web About endpoint.
     *
     * VIN is intentionally absent rather than merely masked. A web session can be reached through
     * a LAN or remote tunnel, so the identifier never crosses the HTTP boundary at all.
     */
    internal fun webValues(info: VehicleVersionInfo): Map<String, String?> = linkedMapOf(
        "firmware" to info.firmware,
        "dsp" to info.dsp,
        "mcu" to info.mcu,
        "android" to info.android,
        "securityPatch" to info.securityPatch,
        "headUnit" to info.headUnit
    )

    private fun firstUseful(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull(::useful)

    private fun distinctUseful(vararg candidates: String?): List<String> {
        val result = ArrayList<String>()
        for (candidate in candidates) {
            val value = useful(candidate) ?: continue
            if (result.none { it.equals(value, ignoreCase = true) }) result.add(value)
        }
        return result
    }

    private fun useful(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val upper = value.uppercase(Locale.ROOT)
        if (upper == "UNKNOWN" || upper == "NULL" || upper == "N/A" ||
            upper == "UNAVAILABLE" || upper.contains("OFFLINE")) {
            return null
        }
        return value
    }

    private fun readSystemProperty(key: String): String? = try {
        val properties = Class.forName("android.os.SystemProperties")
        val get = properties.getMethod("get", String::class.java, String::class.java)
        get.invoke(null, key, "") as? String
    } catch (_: Throwable) {
        null
    }
}
