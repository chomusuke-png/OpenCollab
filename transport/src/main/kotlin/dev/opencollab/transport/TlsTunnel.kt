package dev.opencollab.transport

import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.transport.AbstractConnection
import dev.opencollab.core.transport.Connection
import dev.opencollab.core.transport.ConnectionListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * Runs a standard TLS session (JSSE [SSLEngine], TLS 1.3/1.2) on top of another
 * [Connection]. Used through the relay so the relay only forwards TLS records: guests pin
 * the host certificate exactly as with a direct connection, and the relay operator can
 * neither read nor modify session traffic.
 *
 * Application messages are framed as a 4-byte big-endian length followed by UTF-8 bytes;
 * TLS records travel as base64 text frames over the inner connection.
 */
class TlsTunnel private constructor(
    private val inner: Connection,
    private val engine: SSLEngine,
    override val description: String,
) : AbstractConnection() {
    private val lock = Any()
    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize)
    private val appOut = ArrayDeque<ByteBuffer>()
    private val frameBuffer = ByteArrayOutputStream()
    private val handshake = CountDownLatch(1)
    private var handshakeComplete = false
    @Volatile private var failure: String? = null
    private var shuttingDown = false

    private fun start() {
        inner.setListener(object : ConnectionListener {
            override fun onMessage(text: String) = onInnerMessage(text)
            override fun onClosed(reason: String) {
                failure = failure ?: reason
                handshake.countDown()
                deliverClosed(failure ?: reason)
            }
        })
        synchronized(lock) {
            try {
                engine.beginHandshake()
                pump()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun awaitHandshake(timeoutMillis: Long) {
        if (!handshake.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            inner.close("TLS handshake timeout")
            throw IOException("TLS handshake with the host timed out")
        }
        if (!handshakeComplete) throw IOException(failure ?: "TLS handshake failed")
    }

    private fun onInnerMessage(text: String) {
        val bytes = try { Base64.getDecoder().decode(text) } catch (e: IllegalArgumentException) {
            fail(IOException("Malformed tunnel record")); return
        }
        synchronized(lock) {
            if (netIn.remaining() < bytes.size) {
                val bigger = ByteBuffer.allocate(netIn.position() + bytes.size + engine.session.packetBufferSize)
                netIn.flip()
                bigger.put(netIn)
                netIn = bigger
            }
            netIn.put(bytes)
            try {
                pump()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    override fun send(text: String) {
        if (isClosed) return
        val payload = text.toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(4 + payload.size)
        frame.putInt(payload.size).put(payload).flip()
        synchronized(lock) {
            appOut.addLast(frame)
            try {
                pump()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    override fun close(reason: String) {
        synchronized(lock) {
            if (!shuttingDown) {
                shuttingDown = true
                try {
                    engine.closeOutbound()
                    pump()
                } catch (_: Exception) {
                }
            }
        }
        inner.close(reason)
        deliverClosed(reason)
    }

    /** Drives the engine until no further progress is possible. Caller holds [lock]. */
    private fun pump() {
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) (engine.delegatedTask ?: break).run()
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val r = wrap(EMPTY)
                    if (r.status == SSLEngineResult.Status.CLOSED) return
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP, SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                    if (!unwrap()) return
                }
                else -> {
                    if (!handshakeComplete && !engine.isOutboundDone) {
                        handshakeComplete = true
                        handshake.countDown()
                    }
                    var progressed = false
                    while (appOut.isNotEmpty() && !engine.isOutboundDone) {
                        val head = appOut.first()
                        wrap(head)
                        if (!head.hasRemaining()) appOut.removeFirst()
                        progressed = true
                    }
                    if (netIn.position() > 0 && unwrap()) progressed = true
                    if (!progressed || engine.isInboundDone) return
                }
            }
        }
    }

    private fun wrap(src: ByteBuffer): SSLEngineResult {
        var out = ByteBuffer.allocate(engine.session.packetBufferSize)
        while (true) {
            val result = engine.wrap(src, out)
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                out = ByteBuffer.allocate(out.capacity() * 2)
                continue
            }
            out.flip()
            if (out.hasRemaining()) {
                val bytes = ByteArray(out.remaining())
                out.get(bytes)
                inner.send(Base64.getEncoder().encodeToString(bytes))
            }
            return result
        }
    }

    /** Returns true if the engine made progress. */
    private fun unwrap(): Boolean {
        netIn.flip()
        val result = try {
            engine.unwrap(netIn, appIn)
        } finally {
            netIn.compact()
        }
        when (result.status) {
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> return false
            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                val bigger = ByteBuffer.allocate(appIn.capacity() * 2)
                appIn.flip()
                bigger.put(appIn)
                appIn = bigger
                return true
            }
            SSLEngineResult.Status.CLOSED -> {
                consumeApplicationData()
                if (!shuttingDown) {
                    shuttingDown = true
                    engine.closeOutbound()
                }
                deliverClosed("peer closed the TLS session")
                return result.bytesConsumed() > 0
            }
            SSLEngineResult.Status.OK -> {
                consumeApplicationData()
                return result.bytesConsumed() > 0 || result.bytesProduced() > 0
            }
        }
    }

    private fun consumeApplicationData() {
        if (appIn.position() == 0) return
        appIn.flip()
        frameBuffer.write(appIn.array(), appIn.arrayOffset() + appIn.position(), appIn.remaining())
        appIn.clear()
        val data = frameBuffer.toByteArray()
        var offset = 0
        while (data.size - offset >= 4) {
            val len = ByteBuffer.wrap(data, offset, 4).int
            if (len < 0 || len > MessageCodec.MAX_FRAME_CHARS * 3) {
                throw SSLException("Tunnel frame too large")
            }
            if (data.size - offset - 4 < len) break
            val text = String(data, offset + 4, len, Charsets.UTF_8)
            offset += 4 + len
            deliver(text)
        }
        frameBuffer.reset()
        if (offset < data.size) frameBuffer.write(data, offset, data.size - offset)
    }

    private fun fail(e: Throwable) {
        failure = failure ?: WebSocketConnector.describe(e)
        handshake.countDown()
        inner.close("TLS error")
        deliverClosed(failure!!)
    }

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        /** Client side: authenticates the host by [trust] (normally the pinned host fingerprint). */
        fun client(inner: Connection, trust: TrustMode, timeoutMillis: Long = 15_000): Connection {
            val engine = trust.clientContext().createSSLEngine()
            engine.useClientMode = true
            engine.enabledProtocols = TLS_PROTOCOLS
            val tunnel = TlsTunnel(inner, engine, "tls(${inner.description})")
            tunnel.start()
            tunnel.awaitHandshake(timeoutMillis)
            return tunnel
        }

        /** Server side (host): presents [identity]; messages flow once the handshake completes. */
        fun server(inner: Connection, identity: TlsIdentity): Connection {
            val engine = identity.serverContext().createSSLEngine()
            engine.useClientMode = false
            engine.enabledProtocols = TLS_PROTOCOLS
            val tunnel = TlsTunnel(inner, engine, "tls(${inner.description})")
            tunnel.start()
            return tunnel
        }
    }
}
