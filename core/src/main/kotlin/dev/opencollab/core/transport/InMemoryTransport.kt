package dev.opencollab.core.transport

import dev.opencollab.core.util.SerialExecutor
import java.io.IOException

/**
 * In-process connection pair. Each side delivers asynchronously on its own thread,
 * preserving order. Used in tests and for the host's own IDE bridge, which keeps the
 * host's editor decoupled from the session thread exactly like a remote participant.
 */
object InMemoryTransport {
    fun pair(nameA: String = "a", nameB: String = "b"): Pair<MemoryConnection, MemoryConnection> {
        val a = MemoryConnection(nameA)
        val b = MemoryConnection(nameB)
        a.peer = b
        b.peer = a
        return a to b
    }

    class MemoryConnection(override val description: String) : AbstractConnection() {
        lateinit var peer: MemoryConnection
        private val inbound = SerialExecutor("mem-$description")

        override fun send(text: String) {
            if (isClosed) return
            val target = peer
            target.inbound.execute { target.deliver(text) }
        }

        override fun close(reason: String) {
            if (isClosed) return
            val target = peer
            inbound.execute { deliverClosed(reason); inbound.close() }
            target.inbound.execute { target.deliverClosed(reason); target.inbound.close() }
        }
    }

    /** A host transport whose clients connect in-process. */
    class Server : HostTransport {
        @Volatile private var acceptor: ((Connection) -> Unit)? = null
        override val endpoints: List<String> = listOf("memory://host")

        override fun start(onConnection: (Connection) -> Unit) {
            acceptor = onConnection
        }

        /** Each call opens a new connection; [onOpen] exposes the client end (to break it in tests). */
        fun connector(name: String, onOpen: (MemoryConnection) -> Unit = {}) = Connector {
            val (client, server) = pair(name, "host<-$name")
            val accept = acceptor ?: throw IOException("server not started")
            accept(server)
            onOpen(client)
            client
        }

        override fun close() {
            acceptor = null
        }
    }
}
