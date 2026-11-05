package com.ethic.vpn.core

/**
 * IPv4 routes for the tunnel that cover everything except the home network, so the phone still
 * reaches a PC, printer or console on Wi-Fi while connected (VpnService.Builder.excludeRoute
 * exists only on Android 13+, so the complement is built by hand for every version).
 */
object LanRoutes {
    /** Private and link-local ranges: home networks, hotspots, a PC sharing its VPN. */
    val EXCLUDED = listOf("10.0.0.0/8", "169.254.0.0/16", "192.168.0.0/16")

    /** The smallest set of prefixes that covers 0.0.0.0/0 minus [excluded], as "a.b.c.d" to length. */
    fun routes(excluded: List<String> = EXCLUDED): List<Pair<String, Int>> {
        val ex = excluded.map { parse(it) }
        val out = mutableListOf<Pair<String, Int>>()
        fun walk(base: Long, len: Int) {
            val size = 1L shl (32 - len)
            val end = base + size - 1
            val overlaps = ex.filter { (b, l) -> b <= end && b + (1L shl (32 - l)) - 1 >= base }
            when {
                overlaps.isEmpty() -> out += format(base) to len
                overlaps.any { (b, l) -> l <= len && b <= base && b + (1L shl (32 - l)) - 1 >= end } -> Unit
                else -> {
                    walk(base, len + 1)
                    walk(base + size / 2, len + 1)
                }
            }
        }
        walk(0, 0)
        return out
    }

    private fun parse(cidr: String): Pair<Long, Int> {
        val (ip, len) = cidr.split("/")
        val v = ip.split(".").fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        return v to len.toInt()
    }

    private fun format(v: Long) = (3 downTo 0).joinToString(".") { ((v shr (it * 8)) and 255).toString() }
}
