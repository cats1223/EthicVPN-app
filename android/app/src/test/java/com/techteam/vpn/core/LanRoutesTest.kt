package com.techteam.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanRoutesTest {
    private fun ip(s: String) = s.split(".").fold(0L) { a, p -> (a shl 8) or p.toLong() }
    private fun covered(routes: List<Pair<String, Int>>, addr: String) =
        routes.any { (b, l) -> ip(addr) >= ip(b) && ip(addr) <= ip(b) + (1L shl (32 - l)) - 1 }

    @Test
    fun coversInternetButNotHomeNetwork() {
        val r = LanRoutes.routes()
        for (a in listOf("1.1.1.1", "8.8.8.8", "213.108.23.40", "172.19.0.1", "223.255.255.255", "0.0.0.1")) {
            assertTrue(a, covered(r, a))
        }
        for (a in listOf("192.168.1.49", "10.0.0.5", "169.254.1.1", "192.168.255.255")) {
            assertFalse(a, covered(r, a))
        }
        // Everything but the three ranges: 2^32 - 2^24 - 2^16 - 2^16 addresses, no overlaps.
        val total = r.sumOf { (_, l) -> 1L shl (32 - l) }
        assertEquals((1L shl 32) - (1L shl 24) - 2 * (1L shl 16), total)
    }
}
