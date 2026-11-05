package com.ethic.vpn.core

import java.time.LocalDate

/**
 * Traffic per day for the statistics screen: day -> down and up bytes, kept for 60 days.
 * Stored as text, "2026-10-25:1234:567:12;2026-10-26:…": down, up and blocked ads.
 */
class TrafficHistory(private val days: MutableMap<String, LongArray> = mutableMapOf()) {
    data class Day(val day: LocalDate, val down: Long, val up: Long) {
        val total get() = down + up
    }

    fun add(down: Long, up: Long, today: LocalDate = LocalDate.now()) {
        if (down <= 0 && up <= 0) return
        val old = days[today.toString()] ?: LongArray(3)
        days[today.toString()] = longArrayOf(old[0] + down.coerceAtLeast(0), old[1] + up.coerceAtLeast(0), old[2])
        prune(today)
    }

    fun addBlocked(count: Int, today: LocalDate = LocalDate.now()) {
        if (count <= 0) return
        val old = days[today.toString()] ?: LongArray(3)
        days[today.toString()] = longArrayOf(old[0], old[1], old[2] + count)
        prune(today)
    }

    /** Blocked ads of the last [count] days, today included. */
    fun blocked(count: Int, today: LocalDate = LocalDate.now()): Long =
        (0 until count).sumOf { days[today.minusDays(it.toLong()).toString()]?.get(2) ?: 0L }

    /** ISO dates sort as text, so anything below the cut-off is older than 60 days. */
    private fun prune(today: LocalDate) {
        val oldest = today.minusDays(KEEP_DAYS).toString()
        days.keys.removeAll { it <= oldest }
    }

    /** Each of the last [count] days, oldest first, today last. */
    fun daily(count: Int, today: LocalDate = LocalDate.now()): List<Day> = (count - 1 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        val v = days[day.toString()] ?: LongArray(3)
        Day(day, v[0], v[1])
    }

    /** Down and up bytes of the last [count] days, today included. */
    fun total(count: Int, today: LocalDate = LocalDate.now()): Pair<Long, Long> =
        daily(count, today).fold(0L to 0L) { acc, d -> (acc.first + d.down) to (acc.second + d.up) }

    fun encode(): String = days.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value[0]}:${it.value[1]}:${it.value[2]}" }

    companion object {
        const val KEEP_DAYS = 60L

        fun decode(text: String?): TrafficHistory = TrafficHistory(
            text.orEmpty().split(';').mapNotNull { entry ->
                val p = entry.split(':')
                if (p.size !in 3..4) return@mapNotNull null
                val down = p[1].toLongOrNull() ?: return@mapNotNull null
                val up = p[2].toLongOrNull() ?: return@mapNotNull null
                p[0] to longArrayOf(down, up, p.getOrNull(3)?.toLongOrNull() ?: 0)
            }.toMap().toMutableMap(),
        )
    }
}
