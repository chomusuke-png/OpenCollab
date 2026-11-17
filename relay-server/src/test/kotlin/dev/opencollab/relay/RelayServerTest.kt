package dev.opencollab.relay

import dev.opencollab.core.transport.Connection
import dev.opencollab.core.transport.ConnectionListener
import dev.opencollab.transport.RelayConnector
import dev.opencollab.transport.RelayHostTransport
import dev.opencollab.transport.TlsIdentity
import dev.opencollab.transport.TrustMode
import dev.opencollab.transport.WebSocketConnector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayServerTest {
    private val relayId = TlsIdentity.ephemeral("relay")
    private val secret = "correct-horse-battery-staple"
    private val observed = CopyOnWriteArrayList<String>()
    private lateinit var relay: RelayServer
    private val relayUri get() = URI("wss://127.0.0.1:${relay.port}")
    private val relayTrust get() = TrustMode.PinnedRelay(relayId.fingerprint)

    private class Collector : ConnectionListener {
        val messages = LinkedBlockingQueue<String>()
        @Volatile var closed: String? = null
        override fun onMessage(text: String) { messages += text }
        override fun onClosed(reason: String) { closed = reason }
    }

    @BeforeEach
    fun setUp() {
        relay = RelayServer(RelayConfig(bind = "127.0.0.1", port = 0, tls = relayId.serverContext(), hostSecret = secret, maxPeersPerRoom = 2)) {
            observed += it
        }
        relay.start()
    }

    @AfterEach
    fun tearDown() = relay.stop()

    private fun eventually(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(10) }
        throw AssertionError("Condition not met")
    }

    @Test
    fun `guest and host exchange messages end-to-end encrypted through the relay`() {
        val hostIdentity = TlsIdentity.ephemeral()
        val accepted = LinkedBlockingQueue<Connection>()
        val host = RelayHostTransport(relayUri, secret, relayTrust, hostIdentity)
        host.start { accepted += it }
        try {
            val room = host.room!!
            val guest = RelayConnector(relayUri, room, hostIdentity.fingerprint, relayTrust).connect()
            val guestIn = Collector().also(guest::setListener)
            val hostConn = accepted.poll(5, TimeUnit.SECONDS)!!
            val hostIn = Collector().also(hostConn::setListener)
            guest.send("""{"t":"hello","secret":"source code"}""")
            hostConn.send("welcome")
            assertEquals("""{"t":"hello","secret":"source code"}""", hostIn.messages.poll(5, TimeUnit.SECONDS))
            assertEquals("welcome", guestIn.messages.poll(5, TimeUnit.SECONDS))
            // The relay's own logs never contain session content.
            assertFalse(observed.any { it.contains("source code") })
            guest.close("bye")
            eventually { hostIn.closed != null }
        } finally {
            host.close()
        }
    }

    @Test
    fun `relay refuses hosts without the secret and unknown rooms`() {
        val bad = RelayHostTransport(relayUri, "wrong-secret-value", relayTrust, TlsIdentity.ephemeral())
        val ex = assertThrows(IOException::class.java) { bad.start { } }
        assertTrue(ex.message!!.contains("invalid relay secret"), ex.message)
        bad.close()
        assertThrows(IOException::class.java) {
            RelayConnector(relayUri, "AAAAAAAAAAAAAAAAAAAA", TlsIdentity.ephemeral().fingerprint, relayTrust).connect()
        }
    }

    @Test
    fun `a malicious relay or impostor host cannot pass the pinned host certificate`() {
        val realHost = TlsIdentity.ephemeral()
        val impostor = TlsIdentity.ephemeral()
        val host = RelayHostTransport(relayUri, secret, relayTrust, impostor)
        host.start { }
        try {
            assertThrows(IOException::class.java) {
                RelayConnector(relayUri, host.room!!, realHost.fingerprint, relayTrust).connect()
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun `guests must verify the relay certificate`() {
        assertThrows(IOException::class.java) {
            WebSocketConnector(URI("wss://127.0.0.1:${relay.port}/join/x"), TrustMode.PinnedRelay(TlsIdentity.ephemeral().fingerprint)).connect()
        }
    }

    @Test
    fun `room capacity is enforced and host disconnect closes guests`() {
        val hostIdentity = TlsIdentity.ephemeral()
        val host = RelayHostTransport(relayUri, secret, relayTrust, hostIdentity)
        host.start { }
        val room = host.room!!
        val g1 = RelayConnector(relayUri, room, hostIdentity.fingerprint, relayTrust).connect()
        val g2 = RelayConnector(relayUri, room, hostIdentity.fingerprint, relayTrust).connect()
        assertThrows(IOException::class.java) { RelayConnector(relayUri, room, hostIdentity.fingerprint, relayTrust).connect() }
        val c1 = Collector().also(g1::setListener)
        Collector().also(g2::setListener)
        host.close()
        eventually { c1.closed != null }
        assertEquals(1, relay.roomCount()) // reserved for the grace period so the host may come back
    }
}
