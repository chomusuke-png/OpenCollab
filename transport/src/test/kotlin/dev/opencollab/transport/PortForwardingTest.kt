package dev.opencollab.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress

class PortForwardingTest {
    @Test
    fun `public endpoint uses the external port or the listen port`() {
        assertEquals("wss://203.0.113.7:47800", PortForwarding.endpoint("203.0.113.7", 0, 47800))
        assertEquals("wss://myhome.example.net:50000", PortForwarding.endpoint(" myhome.example.net ", 50000, 47800))
        assertEquals("wss://[2001:db8::5]:47800", PortForwarding.endpoint("2001:db8::5", 0, 47800))
    }

    @Test
    fun `invalid public addresses are rejected`() {
        for (bad in listOf("", "wss://1.2.3.4", "host/path", "bad host", "-x.com", "a..b")) {
            assertThrows<IllegalArgumentException>(bad) { PortForwarding.endpoint(bad, 0, 47800) }
        }
        assertThrows<IllegalArgumentException> { PortForwarding.endpoint("1.2.3.4", 70000, 47800) }
    }

    @Test
    fun `public IP service answers are accepted only as IP literals`() {
        assertEquals("203.0.113.7", PortForwarding.parseIpLiteral("203.0.113.7"))
        assertEquals("2001:db8:0:0:0:0:0:5", PortForwarding.parseIpLiteral("2001:db8::5"))
        assertNull(PortForwarding.parseIpLiteral("<html>error</html>"))
        assertNull(PortForwarding.parseIpLiteral("example.com"))
        assertThrows<IllegalArgumentException> { PortForwarding.detectPublicIp("http://insecure.example") }
    }

    @Test
    fun `guests try their own network first, then public endpoints, then other networks`() {
        val guest = listOf(NetworkHints.LocalNetwork(InetAddress.getByName("192.168.1.12"), 24))
        val ordered = NetworkHints.orderEndpoints(
            listOf("wss://192.168.4.54:47800", "wss://172.24.112.1:47800", "wss://203.0.113.7:47800", "wss://192.168.1.30:47800"),
            guest,
        )
        assertEquals(
            listOf("wss://192.168.1.30:47800", "wss://203.0.113.7:47800", "wss://192.168.4.54:47800", "wss://172.24.112.1:47800"),
            ordered,
        )
    }
}
