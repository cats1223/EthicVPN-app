package com.ethic.vpn.core

/** Reads CHANGELOG.md (bundled with the app) for the "what's new" screen after an update. */
object Changelog {
    /** The lines under "## v<version>" up to the next version, or null if there is no such section. */
    fun section(text: String, version: String): String? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == "## v$version" }
        if (start < 0) return null
        val body = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        return body.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }
}
