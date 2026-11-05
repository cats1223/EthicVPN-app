package com.ethic.vpn.core

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * A server parsed from a share link. Supported:
 * - vless://<uuid>@<host>:<port>?type=tcp&security=reality&pbk=...&sid=...&sni=...&fp=chrome&flow=xtls-rprx-vision#Name
 * - trojan://<password>@<host>:<port>?security=tls&sni=...&type=ws&path=...#Name (TLS unless told otherwise)
 */
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int,
    /** "vless" or "trojan". */
    val protocol: String = VLESS,
    val uuid: String = "",
    /** Trojan password. */
    val password: String = "",
    val encryption: String = "none",
    val flow: String = "",
    val network: String = "tcp",
    val security: String = "none",
    val sni: String = "",
    val fingerprint: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val mldsa65Verify: String = "",
    val alpn: List<String> = emptyList(),
    val pinnedPeerCertSha256: String = "",
    val verifyPeerCertByName: String = "",
    val echConfigList: String = "",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val authority: String = "",
    val mode: String = "",
    val headerType: String = "",
    val extra: String = "",
    /** The original link, kept so the profile can be shared or re-parsed. */
    val link: String = "",
) {
    /** Connection parameters without the uuid, passwords and keys, safe to show in the log. */
    val summary: String
        get() {
            // VLESS encryption strings carry a key after the first dot; only the scheme is shown.
            val enc = if (protocol == VLESS) encryption.substringBefore('.') else ""
            val parts = mutableListOf(protocol, "$address:$port", "type=$network", "security=$security")
            listOf(
                "enc" to enc, "sni" to sni, "fp" to fingerprint, "flow" to flow, "mode" to mode, "spx" to spiderX,
                "path" to path, "host" to host, "sid" to if (shortId.isEmpty()) "" else "set",
                "pbk" to if (publicKey.isEmpty()) "" else "set", "extra" to if (extra.isEmpty()) "" else "set",
            ).filter { it.second.isNotEmpty() }.forEach { parts += "${it.first}=${it.second}" }
            return parts.joinToString(" ")
        }

    companion object {
        const val VLESS = "vless"
        const val TROJAN = "trojan"

        private val SCHEMES = mapOf("vless" to VLESS, "trojan" to TROJAN)

        /** Whether [text] starts like a server link this app can read. */
        fun isShareLink(text: String): Boolean = SCHEMES.keys.any { text.startsWith("$it://", ignoreCase = true) }

        /** Returns null for anything that is not a well-formed link of a supported protocol. */
        fun parse(raw: String): ServerProfile? {
            val text = raw.trim()
            val scheme = text.substringBefore("://", "").lowercase()
            val protocol = SCHEMES[scheme] ?: return null
            // Names often hold raw spaces and emoji that java.net.URI rejects, so cut the fragment off first.
            val hash = text.indexOf('#')
            val body = if (hash >= 0) text.substring(0, hash) else text
            val fragment = if (hash >= 0) text.substring(hash + 1) else null
            val uri = try {
                URI(body.replace(" ", "%20"))
            } catch (_: Exception) {
                return null
            }
            val user = uri.rawUserInfo?.let(::decode)?.takeIf { it.isNotBlank() } ?: return null
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            val q = parseQuery(uri.rawQuery)
            val name = fragment?.let { runCatching { decode(it) }.getOrDefault(it) }?.trim()?.takeIf { it.isNotBlank() } ?: "$host:$port"
            val base = ServerProfile(name = name, address = host, port = port, protocol = protocol, link = text)

            return when (protocol) {
                TROJAN -> withStream(base.copy(password = user), q, defaultSecurity = "tls")
                else -> withStream(base.copy(uuid = user, encryption = q["encryption"]?.takeIf { it.isNotBlank() } ?: "none"), q, "none")
            }
        }

        /** The transport and security parameters VLESS and Trojan links share. */
        private fun withStream(p: ServerProfile, q: Map<String, String>, defaultSecurity: String): ServerProfile {
            val network = when (val t = q["type"].orEmpty().lowercase()) {
                "", "raw" -> "tcp"
                "splithttp" -> "xhttp"
                else -> t
            }
            return p.copy(
                flow = q["flow"].orEmpty(),
                network = network,
                security = q["security"]?.lowercase()?.takeIf { it.isNotBlank() } ?: defaultSecurity,
                sni = q["sni"] ?: q["peer"].orEmpty(),
                fingerprint = q["fp"].orEmpty(),
                publicKey = q["pbk"].orEmpty(),
                shortId = q["sid"].orEmpty(),
                spiderX = q["spx"].orEmpty(),
                mldsa65Verify = q["pqv"].orEmpty(),
                alpn = list(q["alpn"]),
                pinnedPeerCertSha256 = q["pcs"].orEmpty(),
                verifyPeerCertByName = q["vcn"].orEmpty(),
                echConfigList = q["ech"].orEmpty(),
                host = q["host"].orEmpty(),
                path = q["path"].orEmpty(),
                serviceName = q["serviceName"].orEmpty(),
                authority = q["authority"].orEmpty(),
                mode = q["mode"].orEmpty(),
                headerType = q["headerType"].orEmpty(),
                extra = q["extra"].orEmpty(),
            )
        }

        private fun list(value: String?): List<String> =
            value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun parseQuery(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val i = pair.indexOf('=')
                if (i < 0) decode(pair) to "" else decode(pair.substring(0, i)) to decode(pair.substring(i + 1))
            }
        }

        // URLDecoder turns '+' into a space, which breaks base64 values such as pbk; keep it literal.
        private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }
}
