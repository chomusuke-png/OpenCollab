package dev.opencollab.relay

import dev.opencollab.core.session.randomToken
import dev.opencollab.transport.MAX_WS_FRAME_BYTES
import dev.opencollab.transport.RelayFrame
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.IProtocol
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext

data class RelayConfig(
    val bind: String = "0.0.0.0",
    val port: Int = 8443,
    /** TLS context; null only when explicitly running plain behind a local TLS-terminating proxy. */
    val tls: SSLContext?,
    /** Shared secret hosts must present to open rooms. Empty disables the check (not recommended). */
    val hostSecret: String,
    val maxRooms: Int = 200,
    val maxPeersPerRoom: Int = 16,
    val roomGraceMillis: Long = 5 * 60_000,
    val maxFramesPerSecondPerPeer: Int = 2_000,
)

/**
 * Relay/signaling server. It never interprets session traffic: guests' frames are wrapped
 * in [RelayFrame.Data] for their room's host and vice versa. With OpenCollab clients those
 * frames are TLS records end-to-end between guest and host, so the relay sees only
 * ciphertext, room ids and connection metadata.
 */
class RelayServer(private val config: RelayConfig, private val log: (String) -> Unit = ::println) {
    private class Room(val id: String, val key: String) {
        @Volatile var host: WebSocket? = null
        val peers = ConcurrentHashMap<String, WebSocket>()
        @Volatile var orphanedAt: Long = 0
    }

    private sealed interface Attachment
    private class HostAttachment : Attachment { @Volatile var room: Room? = null }
    private class PeerAttachment(val room: Room, val peerId: String) : Attachment {
        var windowStart = System.currentTimeMillis()
        var frames = 0
    }

    private val rooms = ConcurrentHashMap<String, Room>()
    private val peerCounter = AtomicLong()
    private val janitor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay-janitor").apply { isDaemon = true } }
    private val started = CountDownLatch(1)
    @Volatile private var startError: Exception? = null

    private val server = object : WebSocketServer(
        InetSocketAddress(config.bind, config.port),
        listOf(Draft_6455(emptyList(), listOf<IProtocol>(Protocol("")), MAX_WS_FRAME_BYTES)),
    ) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val path = handshake.resourceDescriptor.substringBefore('?')
            when {
                path == "/host" -> conn.setAttachment(HostAttachment())
                path.startsWith("/join/") -> joinRoom(conn, path.removePrefix("/join/"))
                path == "/health" -> conn.close(1000, "ok")
                else -> conn.close(4404, "unknown endpoint")
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            when (val a = conn.getAttachment<Attachment>()) {
                is HostAttachment -> onHostFrame(conn, a, message)
                is PeerAttachment -> onPeerFrame(conn, a, message)
                null -> conn.close(1008, "unexpected")
            }
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) = conn.close(1003, "binary frames are not supported")

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            when (val a = conn.getAttachment<Attachment>()) {
                is HostAttachment -> a.room?.let { room ->
                    if (room.host === conn) {
                        room.host = null
                        room.orphanedAt = System.currentTimeMillis()
                        room.peers.values.forEach { it.close(4410, "host disconnected") }
                        room.peers.clear()
                        log("room ${short(room.id)}: host disconnected")
                    }
                }
                is PeerAttachment -> {
                    if (a.room.peers.remove(a.peerId) != null) {
                        a.room.host?.sendSafely(RelayFrame.encode(RelayFrame.PeerClose(a.peerId, "guest disconnected")))
                    }
                }
                null -> Unit
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                startError = ex
                started.countDown()
            }
        }

        override fun onStart() {
            started.countDown()
        }
    }

    val port: Int get() = server.port

    fun start() {
        config.tls?.let { server.setWebSocketFactory(DefaultSSLWebSocketServerFactory(it)) }
        server.isReuseAddr = true
        server.connectionLostTimeout = 30
        server.start()
        started.await(10, TimeUnit.SECONDS)
        startError?.let { throw IllegalStateException("Cannot start relay: ${it.message}", it) }
        janitor.scheduleAtFixedRate(::expireRooms, 30, 30, TimeUnit.SECONDS)
        log("OpenCollab relay listening on ${if (config.tls != null) "wss" else "ws (plain, behind proxy)"}://${config.bind}:$port")
    }

    fun stop() {
        janitor.shutdownNow()
        server.stop(1000)
    }

    fun roomCount(): Int = rooms.size

    private fun onHostFrame(conn: WebSocket, a: HostAttachment, text: String) {
        val frame = try { RelayFrame.decode(text) } catch (e: Exception) { conn.close(1008, "malformed frame"); return }
        val room = a.room
        if (room == null) {
            if (frame !is RelayFrame.Register) { conn.close(1008, "register first"); return }
            if (config.hostSecret.isNotEmpty() && !MessageDigest.isEqual(frame.secret.toByteArray(), config.hostSecret.toByteArray())) {
                conn.sendSafely(RelayFrame.encode(RelayFrame.RegisterFailed("invalid relay secret")))
                conn.close(4401, "unauthorized")
                log("rejected host registration from ${conn.remoteSocketAddress}: invalid secret")
                return
            }
            val existing = frame.room?.let { rooms[it] }
            val presentedKey = frame.roomKey
            val target = if (existing != null && presentedKey != null &&
                MessageDigest.isEqual(existing.key.toByteArray(), presentedKey.toByteArray())
            ) {
                existing.host?.close(4409, "replaced by reconnecting host")
                existing
            } else {
                if (rooms.size >= config.maxRooms) {
                    conn.sendSafely(RelayFrame.encode(RelayFrame.RegisterFailed("relay is full")))
                    conn.close(4429, "full")
                    return
                }
                Room(randomToken(16), randomToken(24)).also { rooms[it.id] = it }
            }
            target.host = conn
            target.orphanedAt = 0
            a.room = target
            conn.sendSafely(RelayFrame.encode(RelayFrame.Registered(target.id, target.key)))
            log("room ${short(target.id)}: host registered (${rooms.size} rooms)")
            return
        }
        when (frame) {
            is RelayFrame.Data -> room.peers[frame.peer]?.sendSafely(frame.d)
            is RelayFrame.Close -> room.peers.remove(frame.peer)?.close(1000, "closed by host")
            else -> Unit
        }
    }

    private fun joinRoom(conn: WebSocket, roomId: String) {
        val room = rooms[roomId]
        val host = room?.host
        if (room == null || host == null || !host.isOpen) {
            conn.close(4404, "unknown room or host offline")
            return
        }
        if (room.peers.size >= config.maxPeersPerRoom) {
            conn.close(4429, "room is full")
            return
        }
        val peerId = "p${peerCounter.incrementAndGet()}"
        conn.setAttachment(PeerAttachment(room, peerId))
        room.peers[peerId] = conn
        host.sendSafely(RelayFrame.encode(RelayFrame.PeerOpen(peerId)))
        log("room ${short(room.id)}: guest $peerId joined from ${conn.remoteSocketAddress}")
    }

    private fun onPeerFrame(conn: WebSocket, a: PeerAttachment, text: String) {
        val now = System.currentTimeMillis()
        if (now - a.windowStart >= 1000) { a.windowStart = now; a.frames = 0 }
        if (++a.frames > config.maxFramesPerSecondPerPeer) {
            conn.close(4429, "rate limit")
            return
        }
        val host = a.room.host ?: run { conn.close(4410, "host offline"); return }
        host.sendSafely(RelayFrame.encode(RelayFrame.Data(a.peerId, text)))
    }

    private fun expireRooms() {
        val now = System.currentTimeMillis()
        rooms.values.filter { it.host == null && it.orphanedAt > 0 && now - it.orphanedAt > config.roomGraceMillis }.forEach {
            rooms.remove(it.id)
            log("room ${short(it.id)}: expired")
        }
    }

    private fun WebSocket.sendSafely(text: String) {
        try {
            if (isOpen) send(text)
        } catch (_: Exception) {
        }
    }

    private fun short(id: String) = id.take(6) + "…"
}
