package dev.opencollab.transport

import dev.opencollab.core.transport.AbstractConnection
import dev.opencollab.core.transport.Connection
import dev.opencollab.core.transport.ConnectionListener
import dev.opencollab.core.transport.Connector
import dev.opencollab.core.transport.HostTransport
import dev.opencollab.core.util.SystemScheduler
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Control frames between a host and the relay (`/host` endpoint). Guests connect to
 * `/join/<room>` and exchange opaque text frames that the relay forwards to the host as
 * [Data]. With [TlsTunnel] those frames are TLS records, so the relay sees no plaintext.
 */
@Serializable
sealed class RelayFrame {
    @Serializable @SerialName("register")
    data class Register(val secret: String, val room: String? = null, val roomKey: String? = null) : RelayFrame()

    @Serializable @SerialName("registered")
    data class Registered(val room: String, val roomKey: String) : RelayFrame()

    @Serializable @SerialName("registerFailed")
    data class RegisterFailed(val reason: String) : RelayFrame()

    @Serializable @SerialName("peerOpen")
    data class PeerOpen(val peer: String) : RelayFrame()

    @Serializable @SerialName("peerClose")
    data class PeerClose(val peer: String, val reason: String = "") : RelayFrame()

    @Serializable @SerialName("data")
    data class Data(val peer: String, val d: String) : RelayFrame()

    @Serializable @SerialName("close")
    data class Close(val peer: String) : RelayFrame()

    companion object {
        val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true }
        fun encode(f: RelayFrame): String = json.encodeToString(serializer(), f)
        fun decode(s: String): RelayFrame = json.decodeFromString(serializer(), s)
    }
}

private fun URI.withPath(path: String): URI = URI(scheme, null, host, port, path, null, null)

/**
 * Hosts a session through a self-hosted relay. The host keeps one outbound WSS connection
 * to the relay; each guest becomes a virtual connection wrapped in a server-side
 * [TlsTunnel] using the host's session certificate. If the relay connection drops, the
 * transport re-registers the same room so existing invitations keep working.
 */
class RelayHostTransport(
    private val relayUri: URI,
    private val hostSecret: String,
    private val relayTrust: TrustMode,
    private val identity: TlsIdentity,
    private val onStatus: (String) -> Unit = {},
) : HostTransport {
    @Volatile var room: String? = null
        private set
    @Volatile private var roomKey: String? = null
    @Volatile private var relay: Connection? = null
    @Volatile private var closed = false
    private var onConnection: ((Connection) -> Unit)? = null
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val scheduler = SystemScheduler("opencollab-relay-host")
    private var attempts = 0

    override val endpoints: List<String> get() = listOfNotNull(room?.let { "relay $relayUri room $it" })

    private inner class PeerConnection(val peer: String) : AbstractConnection() {
        override val description = "relay:$peer"
        override fun send(text: String) {
            relay?.send(RelayFrame.encode(RelayFrame.Data(peer, text)))
        }
        override fun close(reason: String) {
            relay?.send(RelayFrame.encode(RelayFrame.Close(peer)))
            peers.remove(peer)
            deliverClosed(reason)
        }
        fun received(text: String) = deliver(text)
        fun closedByRelay(reason: String) = deliverClosed(reason)
    }

    override fun start(onConnection: (Connection) -> Unit) {
        this.onConnection = onConnection
        register()
    }

    private fun register() {
        val conn = WebSocketConnector(relayUri.withPath("/host"), relayTrust).connect()
        val registered = CountDownLatch(1)
        var error: String? = null
        conn.setListener(object : ConnectionListener {
            override fun onMessage(text: String) {
                val frame = try { RelayFrame.decode(text) } catch (e: Exception) { return }
                when (frame) {
                    is RelayFrame.Registered -> {
                        room = frame.room
                        roomKey = frame.roomKey
                        registered.countDown()
                    }
                    is RelayFrame.RegisterFailed -> { error = frame.reason; registered.countDown() }
                    is RelayFrame.PeerOpen -> {
                        val peer = PeerConnection(frame.peer)
                        peers[frame.peer] = peer
                        onConnection?.invoke(TlsTunnel.server(peer, identity))
                    }
                    is RelayFrame.Data -> peers[frame.peer]?.received(frame.d)
                    is RelayFrame.PeerClose -> peers.remove(frame.peer)?.closedByRelay(frame.reason.ifEmpty { "guest disconnected" })
                    else -> Unit
                }
            }
            override fun onClosed(reason: String) {
                if (relay !== conn) return
                relay = null
                peers.values.toList().forEach { it.closedByRelay("relay connection lost") }
                peers.clear()
                if (!closed) {
                    onStatus("Relay connection lost ($reason); reconnecting…")
                    scheduleReconnect()
                }
            }
        })
        relay = conn
        conn.send(RelayFrame.encode(RelayFrame.Register(hostSecret, room, roomKey)))
        if (!registered.await(10, TimeUnit.SECONDS)) {
            conn.close("timeout"); throw IOException("Relay did not answer the registration")
        }
        error?.let { conn.close("rejected"); throw IOException("Relay refused registration: $it") }
        attempts = 0
        onStatus("Registered with relay, room $room")
    }

    private fun scheduleReconnect() {
        val delay = minOf(30_000L, 1_000L shl minOf(attempts++, 5))
        scheduler.schedule(delay) {
            if (closed) return@schedule
            try {
                register()
            } catch (e: IOException) {
                onStatus("Relay reconnect failed: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    override fun close() {
        closed = true
        relay?.close("host stopped")
        relay = null
        scheduler.close()
    }
}

/** Guest side: connects through the relay and authenticates the host end-to-end. */
class RelayConnector(
    private val relayUri: URI,
    private val room: String,
    private val hostFingerprint: String,
    private val relayTrust: TrustMode,
) : Connector {
    init {
        require(room.matches(Regex("[A-Za-z0-9_-]{8,64}"))) { "Invalid room id" }
    }

    override fun connect(): Connection {
        val outer = WebSocketConnector(relayUri.withPath("/join/$room"), relayTrust).connect()
        return try {
            TlsTunnel.client(outer, TrustMode.Pinned(hostFingerprint))
        } catch (e: IOException) {
            outer.close("tls failed")
            throw e
        }
    }
}
