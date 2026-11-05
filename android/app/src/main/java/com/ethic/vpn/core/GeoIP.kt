package com.ethic.vpn.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where an IP address is: the phone's own address before the VPN, the server's after. Asks
 * ipwho.is and falls back to ipapi.co, since a service may refuse requests from a VPN server.
 */
object GeoIP {
    data class Location(
        val ip: String,
        val country: String,
        val countryCode: String,
        val city: String,
        val latitude: Double,
        val longitude: Double,
    ) {
        val flag: String get() = flag(countryCode)
        val place: String get() = listOf(city, country).filter { it.isNotEmpty() }.distinct().joinToString(", ")
    }

    /** Lookup services in the order they are tried; [ip] null asks about the caller's address. */
    fun urls(ip: String?): List<String> = listOf(
        "https://ipwho.is/" + (ip ?: ""),
        "https://ipapi.co/" + (ip?.let { "$it/" } ?: "") + "json/",
    )

    /** Reads an ipwho.is or ipapi.co answer; null for an error or a page that is not JSON. */
    fun parse(json: String): Location? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.has("success") && !o.optBoolean("success")) return null
        if (o.optBoolean("error")) return null
        return Location(
            ip = o.optString("ip"),
            country = o.optString("country_name").ifEmpty { o.optString("country") },
            countryCode = o.optString("country_code"),
            city = o.optString("city"),
            latitude = o.optDouble("latitude", Double.NaN).takeUnless { it.isNaN() } ?: return null,
            longitude = o.optDouble("longitude", Double.NaN).takeUnless { it.isNaN() } ?: return null,
        )
    }

    /** "NL" -> 🇳🇱; anything else -> 🌐. */
    fun flag(countryCode: String): String {
        val cc = countryCode.uppercase()
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌐"
        return cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** Blocking; call from a background thread. Returns null when every service fails. */
    fun lookup(ip: String? = null): Location? = urls(ip).firstNotNullOfOrNull { url ->
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "EthicVPN")
                parse(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
