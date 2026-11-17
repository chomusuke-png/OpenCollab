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
    fun `advertised addresses exclude loopback and link-local`() {
        NetworkHints.lanAddresses().forEach { a ->
            assertFalse(a.startsWith("127.") || a.startsWith("169.254."), a)
        }
    }
}
