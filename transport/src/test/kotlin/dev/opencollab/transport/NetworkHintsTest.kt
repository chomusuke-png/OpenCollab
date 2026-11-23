package dev.opencollab.transport

import dev.opencollab.core.protocol.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class NetworkHintsTest {
    // Guest on 192.168.1.12/24 (home Wi-Fi).
    private val guestNetworks = listOf(NetworkHints.LocalNetwork(InetAddress.getByName("192.168.1.12"), 24))

    @Test
    fun `host address from another private network is flagged`() {
        assertTrue(NetworkHints.isPrivateAddressOnAnotherNetwork("192.168.4.54", guestNetworks))
        assertTrue(NetworkHints.isPrivateAddressOnAnotherNetwork("172.24.112.1", guestNetworks))
        assertTrue(NetworkHints.isPrivateAddressOnAnotherNetwork("10.0.0.7", guestNetworks))
    }

    @Test
    fun `same subnet, public and VPN addresses are not flagged`() {
        assertFalse(NetworkHints.isPrivateAddressOnAnotherNetwork("192.168.1.50", guestNetworks))
        assertFalse(NetworkHints.isPrivateAddressOnAnotherNetwork("203.0.113.9", guestNetworks))
        assertFalse(NetworkHints.isPrivateAddressOnAnotherNetwork("100.101.7.3", guestNetworks)) // Tailscale
        assertFalse(NetworkHints.isPrivateAddressOnAnotherNetwork("host.example.com", guestNetworks))
        val wide = listOf(NetworkHints.LocalNetwork(InetAddress.getByName("192.168.5.10"), 22)) // 192.168.4.0/22
        assertFalse(NetworkHints.isPrivateAddressOnAnotherNetwork("192.168.4.54", wide))
    }

    @Test
    fun `invitation endpoints on another network are reported`() {
        val invite = Invite(
            project = "p", role = Role.EDITOR, token = "t", fingerprint = TlsIdentity.ephemeral().fingerprint,
            direct = listOf("wss://192.168.4.54:47800", "wss://172.24.112.1:47800"),
        )
        assertEquals(invite.direct, NetworkHints.unreachableDirectEndpoints(invite, guestNetworks))
    }

    @Test
    fun `reach of an address`() {
        val nobody = NetworkHints.Reach.NOBODY
        val self = NetworkHints.Reach.THIS_COMPUTER
        val local = NetworkHints.Reach.LOCAL_NETWORK
        val internet = NetworkHints.Reach.INTERNET
        mapOf(
            "0.0.0.0" to nobody, "::" to nobody, "[::]" to nobody, "" to nobody,
            "127.0.0.1" to self, "127.5.0.1" to self, "localhost" to self, "[::1]" to self,
            "192.168.4.54" to local, "10.0.0.7" to local, "172.16.3.2" to local, "169.254.1.1" to local,
            "100.101.7.3" to local, "[fd12::1]" to local, "[fe80::1]" to local, "nas.local" to local,
            "203.0.113.9" to internet, "100.128.0.1" to internet, "relay.example.com" to internet, "[2001:db8::1]" to internet,
        ).forEach { (host, expected) -> assertEquals(expected, NetworkHints.reachOf(host), host) }
    }

    @Test
    fun `reach of an invitation is its widest address`() {
        val local = NetworkHints.Reach.LOCAL_NETWORK
        assertEquals(local, NetworkHints.inviteReach(listOf("wss://192.168.4.54:25565"), null))
        assertEquals(NetworkHints.Reach.INTERNET, NetworkHints.inviteReach(listOf("wss://192.168.4.54:25565"), "wss://relay.example.com:8443"))
        assertEquals(NetworkHints.Reach.INTERNET, NetworkHints.inviteReach(listOf("wss://192.168.4.54:1", "wss://81.2.69.160:1"), null))
        assertEquals(local, NetworkHints.inviteReach(listOf("wss://192.168.4.54:1"), "wss://0.0.0.0:25566"))
        assertEquals(NetworkHints.Reach.THIS_COMPUTER, NetworkHints.inviteReach(listOf("wss://127.0.0.1:1"), null))
        assertEquals(NetworkHints.Reach.NOBODY, NetworkHints.inviteReach(emptyList(), "not a url"))
        assertEquals(NetworkHints.Reach.NOBODY, NetworkHints.reachOfUrl("wss://0.0.0.0:25566"))
    }

    @Test
    fun `advertised addresses exclude loopback and link-local`() {
        NetworkHints.lanAddresses().forEach { a ->
            assertFalse(a.startsWith("127.") || a.startsWith("169.254."), a)
        }
    }
}
