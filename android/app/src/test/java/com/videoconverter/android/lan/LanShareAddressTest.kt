package com.videoconverter.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareAddressTest {
    @Test
    fun prefersWifiIpv4AndSkipsLoopback() {
        assertNull(pickLanIpv4(listOf(LanIface("lo", "127.0.0.1", true))))
        assertEquals(
            "10.0.0.8",
            pickLanIpv4(
                listOf(
                    LanIface("rmnet0", "10.20.30.40", false),
                    LanIface("wlan0", "10.0.0.8", false),
                ),
            ),
        )
        assertEquals("192.168.43.1", pickLanIpv4(listOf(LanIface("wlan1", "192.168.43.1", false))))
        assertNull(pickLanIpv4(listOf(LanIface("wlan0", "fe80::1", false))))
    }

    @Test
    fun portWalksForwardWhenBusy() {
        assertEquals(17890, chooseLanPort(occupied = emptySet()))
        assertEquals(17892, chooseLanPort(occupied = setOf(17890, 17891)))
        assertNull(chooseLanPort(occupied = (17890 until 17900).toSet()))
    }

    @Test
    fun publicUrlEncodesToken() {
        assertEquals("http://10.0.0.8:17890/", lanPublicUrl("10.0.0.8", 17890, ""))
        val url = lanPublicUrl("10.0.0.8", 17890, "a b")
        assertTrue(url.startsWith("http://10.0.0.8:17890/?"))
        assertTrue(url.contains("k="))
        assertTrue(!url.contains("a b"))
    }
}
