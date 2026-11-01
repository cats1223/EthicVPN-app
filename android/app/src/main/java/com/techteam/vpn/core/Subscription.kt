package com.techteam.vpn.core

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** Traffic and expiry the panel reports in the `subscription-userinfo` header. */
data class SubscriptionInfo(
    val title: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    /** Unix seconds; 0 means no expiry. */
    val expire: Long = 0,
    val updatedAt: Long = 0,
    /** A message from the panel (Remnawave's `announce` header), shown on the subscription card. */
    val announce: String = "",
)

data class SubscriptionResult(val profiles: List<ServerProfile>, val info: SubscriptionInfo)

object Subscription {
    const val USER_AGENT = "TechTeamVPN/1.0"

    /**
     * Blocking; call off the main thread.
     * @param headers device headers (`x-hwid` and friends) for panels that bind keys to devices.
     */
    fun fetch(url: String, headers: Map<String, String> = emptyMap()): SubscriptionResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val profiles = check(code, conn::getHeaderField, body)
            val info = parseUserInfo(conn.getHeaderField("subscription-userinfo"))
                .copy(
                    title = decodeTitle(conn.getHeaderField("profile-title")),
                    updatedAt = System.currentTimeMillis() / 1000,
                    announce = decodeTitle(conn.getHeaderField("announce")),
                )
            return SubscriptionResult(profiles, info)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The servers in a panel's answer, or an error a person understands: Remnawave's device limit
     * (`x-hwid-max-devices-reached`, with its `announce` message), a missing device id, an HTTP
     * error, or a web page where the subscription should be.
     */
    fun check(code: Int, header: (String) -> String?, body: String): List<ServerProfile> {
        if (header("x-hwid-max-devices-reached") == "true") {
            val announce = decodeTitle(header("announce"))
            throw IllegalStateException(
                "В подписке достигнут лимит устройств. Удалите старое устройство в боте или на странице подписки" +
                    if (announce.isNotBlank()) ". $announce" else "",
            )
        }
        if (header("x-hwid-not-supported") == "true") throw IllegalStateException("Панель не получила идентификатор устройства")
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        if (body.trimStart().startsWith("<")) {
            throw IllegalStateException("По ссылке открывается страница, а не подписка. Скопируйте ссылку подписки в боте ещё раз")
        }
        return parseLinks(body).ifEmpty { throw IllegalStateException("В подписке нет серверов VLESS или Trojan") }
    }

    /** Accepts plain text or base64 with one share link per line. */
    fun parseLinks(body: String): List<ServerProfile> {
        val text = body.trim()
        val plain = if (text.contains("://")) text else decodeBase64(text) ?: text
        return plain.lineSequence().mapNotNull { ServerProfile.parse(it) }.toList()
    }

    fun parseUserInfo(header: String?): SubscriptionInfo {
        if (header.isNullOrBlank()) return SubscriptionInfo()
        val values = header.split(';').mapNotNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size == 2) kv[0].trim().lowercase() to (kv[1].trim().toDoubleOrNull()?.toLong() ?: 0L) else null
        }.toMap()
        return SubscriptionInfo(
            upload = values["upload"] ?: 0,
            download = values["download"] ?: 0,
            total = values["total"] ?: 0,
            expire = values["expire"] ?: 0,
        )
    }

    fun decodeTitle(header: String?): String {
        if (header.isNullOrBlank()) return ""
        return if (header.startsWith("base64:")) decodeBase64(header.removePrefix("base64:")) ?: "" else header
    }

    private fun decodeBase64(s: String): String? {
        val clean = s.filterNot { it.isWhitespace() }
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(padded)) }
            .getOrNull()
    }
}
