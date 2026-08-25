package com.overdrive.app.ui.about

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleVersionInfoWebAssetTest {
    @Test
    fun webAboutRendersAllPublicFieldsWithoutVin() {
        val html = readRepositoryFile("app/src/main/assets/web/local/about.html")
        val server = readRepositoryFile("app/src/main/java/com/overdrive/app/server/HttpServer.java")

        assertTrue(html.contains("fetch('/api/about/vehicle-info'"))
        for (key in listOf("firmware", "dsp", "mcu", "android", "securityPatch", "headUnit")) {
            assertTrue("Missing web About field: $key", html.contains("data-vehicle-info=\"$key\""))
        }
        assertFalse(html.contains("data-vehicle-info=\"vin\""))
        assertTrue(html.contains("data-i18n=\"about.vehicle_vin_private\""))
        assertTrue(server.contains("path.startsWith(\"/api/about/\")"))
    }

    private fun readRepositoryFile(relative: String): String {
        var current: Path? = Paths.get("").toAbsolutePath()
        while (current != null) {
            val direct = current.resolve(relative)
            if (Files.exists(direct)) {
                return String(Files.readAllBytes(direct), StandardCharsets.UTF_8)
            }
            val fromModule = current.resolve(relative.removePrefix("app/"))
            if (Files.exists(fromModule)) {
                return String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8)
            }
            current = current.parent
        }
        throw AssertionError("Could not locate $relative")
    }
}
