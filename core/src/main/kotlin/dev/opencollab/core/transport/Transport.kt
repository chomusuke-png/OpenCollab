package dev.opencollab.core.transport

import java.io.IOException

/**
 * A bidirectional, ordered, reliable message channel to one peer. Implementations:
 * in-memory (tests and the host's own IDE bridge), TLS WebSocket (direct), and relayed
 * WebSocket. Session logic depends only on this interface so transports are replaceable
 * (for example by a future peer-to-peer data channel).
 */
interface Connection {
    val description: String

    /** Thread-safe and non-blocking. Messages are delivered in order. */
    fun send(text: String)

    fun close(reason: String)

    /** Messages received before a listener is set are buffered and delivered on set. */
    fun setListener(listener: ConnectionListener)
}

interface ConnectionListener {
    fun onMessage(text: String)
    fun onClosed(reason: String)
}

/** Accepts incoming participant connections for a hosted session. */
interface HostTransport : AutoCloseable {
    /** Human-readable endpoint information, e.g. "wss://192.168.1.4:47800". */
    val endpoints: List<String>

    @Throws(IOException::class)
    fun start(onConnection: (Connection) -> Unit)
}

/** Opens a connection to the host. Blocking: call it off the UI thread. */
fun interface Connector {
    @Throws(IOException::class)
    fun connect(): Connection
}

/** Handles listener buffering and once-only close notification. */
abstract class AbstractConnection : Connection {
    private val lock = Any()
    private var listener: ConnectionListener? = null
    private val early = ArrayList<String>()
    private var closedReason: String? = null

    @Volatile
    var isClosed = false
        private set

    override fun setListener(listener: ConnectionListener) {
        val buffered: List<String>
        val closed: String?
        synchronized(lock) {
            this.listener = listener
            buffered = early.toList()
            early.clear()
            closed = closedReason
        }
        buffered.forEach(listener::onMessage)
        closed?.let(listener::onClosed)
    }

    protected fun deliver(text: String) {
        val l = synchronized(lock) {
            if (isClosed) return
            val current = listener
            if (current == null) {
                early += text
                return
            }
            current
        }
        l.onMessage(text)
    }

    protected fun deliverClosed(reason: String) {
        val l = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            closedReason = reason
            listener
        }
        l?.onClosed(reason)
    }
}
