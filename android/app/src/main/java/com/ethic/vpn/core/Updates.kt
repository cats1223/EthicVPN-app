package com.ethic.vpn.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub for a newer release of the app. */
object Updates {
    const val LATEST_URL = "https://api.github.com/repos/cats1223/EthicVPN-app/releases/latest"
    /** All releases, betas included; used when the user opted into betas. GitHub may sort them by tag name. */
    const val ALL_URL = "https://api.github.com/repos/cats1223/EthicVPN-app/releases?per_page=100"

    /** [downloadUrl] is the APK itself when the release has one, otherwise the release page. */
    data class Release(val version: String, val pageUrl: String, val downloadUrl: String)

    fun parse(json: String): Release = parse(JSONObject(json))

    /**
     * The newest published release in a list, beta or not, by version: GitHub may list tags as
     * text, which puts beta.10 after beta.9.
     */
    fun parseNewest(json: String): Release? {
        val list = JSONArray(json)
        return (0 until list.length()).map { list.getJSONObject(it) }.filter { !it.optBoolean("draft") }.map(::parse)
            .reduceOrNull { best, r -> if (isNewer(r.version, best.version)) r else best }
    }

    private fun parse(o: JSONObject): Release {
        val page = o.getString("html_url")
        val assets = o.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0))
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url")
        return Release(o.getString("tag_name").removePrefix("v"), page, apk ?: page)
    }

    /**
     * Compares dotted versions number by number: 1.10.0 is newer than 1.9.2. A beta
     * (1.2.0-beta.1) is older than its release, so beta testers are offered 1.2.0, and betas of
     * one version compare by their number: beta.6 is newer than beta.5.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return when {
            isBeta(current) && !isBeta(latest) -> true
            isBeta(current) && isBeta(latest) -> betaNumber(latest) > betaNumber(current)
            else -> false
        }
    }

    fun isBeta(version: String): Boolean = '-' in version

    private fun numbers(version: String): List<Int> =
        version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    /** The number at the end of a beta suffix: "1.2.0-beta.6" -> 6. */
    private fun betaNumber(version: String): Int =
        Regex("""(\d+)$""").find(version.substringAfter('-'))?.value?.toIntOrNull() ?: 0

    /** Blocking; call from a background thread. With [includeBeta] the newest beta counts too. */
    fun fetchLatest(includeBeta: Boolean = false): Release {
        val connection = URL(if (includeBeta) ALL_URL else LATEST_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != 200) throw IllegalStateException("GitHub: HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return if (includeBeta) parseNewest(body) ?: throw IllegalStateException("GitHub: релизов нет") else parse(body)
        } finally {
            connection.disconnect()
        }
    }
}
