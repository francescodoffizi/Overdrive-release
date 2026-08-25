package com.overdrive.app.ui.privacy

/** Pure matching policy shared by the native privacy overlay and unit tests. */
object ScreenshotPrivacyPolicy {
    private val ipv4 = Regex(
        """(?<!\d)(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?::\d{1,5})?(?!\d)"""
    )
    private val coordinates = Regex(
        """(?<![\d.])[+-]?(?:\d{1,2}|1[0-7]\d|180)\.\d{4,}\s*[,;/]\s*[+-]?(?:\d{1,2}|1[0-7]\d|180)\.\d{4,}(?![\d.])"""
    )
    private val labelledCoordinate = Regex(
        """(?i)\b(?:lat(?:itude)?|lon(?:gitude)?|lng)\s*[:=]\s*[+-]?\d{1,3}\.\d{3,}"""
    )
    private val url = Regex("""(?i)\b(?:https?|wss?)://\S+""")
    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")

    private val sensitiveResourceTokens = listOf(
        "qrcode",
        "currenturl",
        "tunnelurl",
        "remoteurl",
        "deviceid",
        "ipaddress",
        "latitude",
        "longitude",
        "coordinates",
        "location",
        "address",
        "thumbnail",
        "videoplayer",
        "camerapreview",
        "cameraimage",
        "snapshot",
        "vehiclevin",
        "platenumber",
        "licenseplate"
    )

    fun isSensitiveText(value: CharSequence?): Boolean {
        val text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return false
        return ipv4.containsMatchIn(text) ||
            coordinates.containsMatchIn(text) ||
            labelledCoordinate.containsMatchIn(text) ||
            url.containsMatchIn(text) ||
            email.containsMatchIn(text)
    }

    fun isSensitiveResourceName(resourceName: String?): Boolean {
        val normalized = resourceName
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() }
            .orEmpty()
        if (normalized.isEmpty()) return false
        return normalized.contains("qr") || sensitiveResourceTokens.any(normalized::contains)
    }
}
