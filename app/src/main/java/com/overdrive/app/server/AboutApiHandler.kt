package com.overdrive.app.server

import com.overdrive.app.ui.about.VehicleVersionInfo
import com.overdrive.app.ui.about.VehicleVersionInfoProvider
import org.json.JSONObject
import java.io.OutputStream

/** Read-only metadata consumed by the authenticated web About page. */
object AboutApiHandler {
    private const val VEHICLE_INFO_PATH = "/api/about/vehicle-info"

    @JvmStatic
    @Throws(Exception::class)
    fun handle(method: String, path: String, body: String?, out: OutputStream): Boolean {
        if (path != VEHICLE_INFO_PATH) return false
        if (method != "GET") {
            HttpResponse.sendError(out, 405, "Method Not Allowed")
            return true
        }

        // Do not ask the daemon/bodywork collector for a VIN here. Unlike native About, this
        // response can travel over a LAN or tunnel and therefore exposes version metadata only.
        val info = VehicleVersionInfoProvider.read(vin = null)
        HttpResponse.sendJson(out, responseFor(info).toString())
        return true
    }

    internal fun responseFor(info: VehicleVersionInfo): JSONObject {
        val response = JSONObject().put("success", true)
        for ((key, value) in VehicleVersionInfoProvider.webValues(info)) {
            response.put(key, value ?: JSONObject.NULL)
        }
        return response
    }
}
