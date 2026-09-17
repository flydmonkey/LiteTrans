package com.videoconverter.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.NetworkInterface

class LanShareBindTest {
    @Test
    fun parseRequestLineSplitsPathAndQuery() {
        val req = parseHttpRequestLine("GET /d/v?k=pw HTTP/1.1")!!
        assertEquals("GET", req.method)
        assertEquals("/d/v", req.path)
        assertEquals("pw", req.query["k"])
        assertNull(parseHttpRequestLine("GET"))
    }

    @Test
    fun collectLanIfacesMarksLoopback() {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val mapped = collectLanIfaces(ifaces)
        val loopbackLocal = mapped.filter { it.hostAddress == "127.0.0.1" }
        assertTrue(loopbackLocal.isNotEmpty())
        assertTrue(loopbackLocal.all { it.loopback })
        assertNull(pickLanIpv4(mapped.filter { it.loopback }))
    }

    @Test
    fun serverSocketBindsRequestedIpv4NotWildcard() {
        openLanServerSocket("127.0.0.1", 0).use { server ->
            assertEquals("127.0.0.1", server.inetAddress.hostAddress)
            assertTrue(server.localPort > 0)
        }
    }
}
