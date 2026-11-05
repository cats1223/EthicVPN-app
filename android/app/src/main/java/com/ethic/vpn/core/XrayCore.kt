package com.ethic.vpn.core

import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject

/** Thin wrapper over libXray's JSON `invoke` API (apiVersion 3). */
object XrayCore {
    private const val API_VERSION = 3

    class XrayException(message: String) : Exception(message)

    /** [protect] must call VpnService.protect so Xray's own sockets do not loop back into the tunnel. */
    fun start(configJson: String, dns: String, protect: (Int) -> Boolean) {
        val controller = object : DialerController {
            override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
        }
        LibXray.registerDialerController(controller)
        LibXray.registerListenerController(controller)
        LibXray.setDNS(controller, dns)
        call("runXray", JSONObject().put("xrayJson", configJson))
    }

    fun stop() {
        try {
            call("stopXray", null)
        } finally {
            LibXray.resetDNS()
        }
    }

    fun isRunning(): Boolean = call("getXrayState", null)?.optBoolean("running") ?: false

    fun version(): String = runCatching { call("xrayVersion", null)?.optString("version") }.getOrNull().orEmpty()

    private fun call(method: String, payload: JSONObject?): JSONObject? {
        val request = JSONObject().put("apiVersion", API_VERSION).put("method", method)
        if (payload != null) request.put("payload", payload)
        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success")) {
            throw XrayException(response.optString("error").ifEmpty { "Xray: $method failed" })
        }
        return response.optJSONObject("data")
    }
}
