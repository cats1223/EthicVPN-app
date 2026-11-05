package com.ethic.vpn.core

import java.net.URLDecoder

/**
 * ethicvpn:// links that a Telegram bot opens (through the redirect page in server/link-page):
 *   ethicvpn://add?url=<url-encoded subscription or vless:// key>
 *   ethicvpn://add/<subscription or key>
 *   ethicvpn://add  — no link: take it from the clipboard (the bot's "copy, then open" flow)
 */
object DeepLink {
    const val SCHEME = "ethicvpn"

    /** True for ethicvpn://add without a link: the subscription is waiting in the clipboard. */
    fun wantsClipboard(link: String): Boolean =
        link.trim().startsWith("$SCHEME://add", ignoreCase = true) && unwrap(link).isEmpty()

    /** What to import from [link], or [link] itself when it is not one of our links. */
    fun unwrap(link: String): String {
        val text = link.trim()
        val prefix = "$SCHEME://add"
        if (!text.startsWith(prefix, ignoreCase = true)) return text
        val rest = text.substring(prefix.length)
        return when {
            rest.startsWith("?") -> rest.removePrefix("?").split('&')
                .firstOrNull { it.startsWith("url=") }
                ?.let { URLDecoder.decode(it.removePrefix("url="), "UTF-8") }
                .orEmpty()
            // A raw link keeps its "://"; an encoded one has it as %3A%2F%2F.
            rest.startsWith("/") -> rest.removePrefix("/").let { if ("://" in it) it else URLDecoder.decode(it, "UTF-8") }
            else -> ""
        }.trim()
    }
}
