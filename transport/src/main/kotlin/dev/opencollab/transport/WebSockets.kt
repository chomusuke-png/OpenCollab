package dev.opencollab.transport

import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.transport.AbstractConnection
import dev.opencollab.core.transport.Connection
import dev.opencollab.core.transport.Connector
import dev.opencollab.core.transport.HostTransport
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLParameters

/** Max WebSocket frame accepted from a peer (protocol frames are capped well below this). */
const val MAX_WS_FRAME_BYTES = MessageCodec.MAX_FRAME_CHARS * 3 + 1024

internal fun draft() = Draft_6455(emptyList(), listOf<org.java_websocket.protocols.IProtocol>(org.java_websocket.protocols.Protocol("")), MAX_WS_FRAME_BYTES)

/** A [Connection] backed by one Java-WebSocket socket. */
class WsConnection(private val socket: WebSocket, override val description: String) : AbstractConnection() {
    override fun send(text: String) {
        if (isClosed || !socket.isOpen) return
        try {
            socket.send(text)
        } catch (e: Exception) {
            // WebsocketNotConnectedException etc.: the close callback follows.
        }
    }

    override fun close(reason: String) {
        socket.close(1000, reason.take(120))
        markClosed(reason)
    }

    internal fun received(text: String) = deliver(text)
    internal fun markClosed(reason: String) = deliverClosed(reason)
}

/**
 * The host's direct listener: a WebSocket server over TLS with an ephemeral certificate.
 * Plain `ws://` is intentionally not supported.
 */
class DirectHostTransport(
    private val bindAddress: String,
    private val port: Int,
    private val identity: TlsIdentity,
    private val advertisedHosts: List<String>? = null,
) : HostTransport {
    private var server: Server? = null
    @Volatile var boundPort: Int = port
        private set

    private inner class Server(address: InetSocketAddress, val onConnection: (Connection) -> Unit) :
        WebSocketServer(address, listOf(draft())) {
        val started = CountDownLatch(1)
        @Volatile var startError: Exception? = null

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val c = WsConnection(conn, "direct:${conn.remoteSocketAddress}")
            conn.setAttachment(c)
            onConnection(c)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            conn.getAttachment<WsConnection>()?.markClosed(reason?.ifEmpty { null } ?: "closed ($code)")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            conn.getAttachment<WsConnection>()?.received(message)
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            conn.close(1003, "binary frames are not supported")
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

    override val endpoints: List<String>
        get() = (advertisedHosts ?: localAddresses(bindAddress)).map { "wss://${formatHost(it)}:$boundPort" }

    override fun start(onConnection: (Connection) -> Unit) {
        val s = Server(InetSocketAddress(bindAddress, port), onConnection)
        s.setWebSocketFactory(DefaultSSLWebSocketServerFactory(identity.serverContext()))
        s.isReuseAddr = true
        s.connectionLostTimeout = 30
        s.start()
        if (!s.started.await(10, TimeUnit.SECONDS)) throw IOException("Server did not start")
        s.startError?.let { throw IOException("Cannot listen on $bindAddress:$port: ${it.message}", it) }
        boundPort = s.port
        server = s
    }

    override fun close() {
        server?.stop(1000)
        server = null
    }

    companion object {
        fun localAddresses(bindAddress: String): List<String> {
            if (bindAddress != "0.0.0.0" && bindAddress != "::") return listOf(bindAddress)
            val result = NetworkHints.lanAddresses()
            return result.ifEmpty { listOf("127.0.0.1") }
        }

        private fun formatHost(h: String) = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
    }
}

/** Opens a WSS connection to [uri]; blocks until the TLS + WebSocket handshakes complete. */
class WebSocketConnector(
    private val uri: URI,
    private val trust: TrustMode,
    private val connectTimeoutMillis: Int = 8_000,
) : Connector {
    init {
        require(uri.scheme == "wss") { "Only wss:// is supported (got ${uri.scheme})" }
    }

    override fun connect(): Connection {
        val opened = CountDownLatch(1)
        var failure: String? = null
        lateinit var connection: WsConnection
        val client = object : WebSocketClient(uri, draft(), emptyMap(), connectTimeoutMillis) {
            override fun onSetSSLParameters(sslParameters: SSLParameters) {
                sslParameters.protocols = TLS_PROTOCOLS
                // Pinned certificates are verified by fingerprint, not by host name.
                if (trust.verifiesHostname()) sslParameters.endpointIdentificationAlgorithm = "HTTPS"
            }
            override fun onOpen(handshakedata: ServerHandshake) { opened.countDown() }
            override fun onMessage(message: String) { connection.received(message) }
            override fun onMessage(bytes: ByteBuffer) { close(1003, "binary frames are not supported") }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (opened.count > 0) {
                    failure = failure ?: (reason?.ifEmpty { null } ?: "connection closed ($code)")
                    opened.countDown()
                }
                connection.markClosed(reason?.ifEmpty { null } ?: "closed ($code)")
            }
            override fun onError(ex: Exception) {
                failure = describe(ex)
            }
        }
        connection = WsConnection(client, uri.toString())
        client.setSocketFactory(trust.clientContext().socketFactory)
        client.connectionLostTimeout = 30
        val ok = try {
            client.connectBlocking(connectTimeoutMillis.toLong() + 2_000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ok || !client.isOpen) {
            client.close()
            throw IOException("Cannot connect to $uri: ${failure ?: "timed out"}")
        }
        return connection
    }

    companion object {
        fun describe(ex: Throwable): String {
            var t: Throwable? = ex
            while (t != null) {
                if (t is java.security.cert.CertificateException) return "TLS certificate rejected: ${t.message}"
                if (t is javax.net.ssl.SSLHandshakeException && t.cause == null) return "TLS handshake failed: ${t.message}"
                t = t.cause
            }
            return ex.message ?: ex.javaClass.simpleName
        }
    }
}

/** Tries several direct endpoints in order (e.g. every LAN address of the host). */
class FirstWorkingConnector(private val connectors: List<Connector>) : Connector {
    override fun connect(): Connection {
        val errors = mutableListOf<String>()
        for (c in connectors) {
            try {
                return c.connect()
            } catch (e: IOException) {
                errors += e.message ?: "failed"
            }
        }
        throw IOException(errors.joinToString("; ").ifEmpty { "no endpoints" })
    }
}
