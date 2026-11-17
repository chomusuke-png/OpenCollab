package dev.opencollab.core.session

import dev.opencollab.core.files.FileAccessDenied
import dev.opencollab.core.files.Hashes
import dev.opencollab.core.files.ProjectFiles
import dev.opencollab.core.files.SafePath
import dev.opencollab.core.files.toConfig
import dev.opencollab.core.ot.ServerDocument
import dev.opencollab.core.presence.RateLimiter
import dev.opencollab.core.protocol.DocHistoryEntry
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.protocol.PROTOCOL_VERSION
import dev.opencollab.core.protocol.ParticipantInfo
import dev.opencollab.core.protocol.ProtocolException
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.transport.Connection
import dev.opencollab.core.transport.ConnectionListener
import dev.opencollab.core.util.Cancellable
import dev.opencollab.core.util.Scheduler
import dev.opencollab.core.util.SerialExecutor
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Supplies the host's current text of a shared document (unsaved editor content wins over disk). */
fun interface DocumentSource {
    /** Returns null if the path cannot be shared as a text document. Called on the session thread. */
    fun loadForSharing(path: String): String?
}

interface HostSessionListener {
    fun onParticipantsChanged(participants: List<ParticipantInfo>) {}
    fun onLog(message: String) {}
}

data class HostConfig(
    val projectName: String,
    val editorToken: String = randomToken(),
    val viewerToken: String = randomToken(),
    /** Secret used only by the host's own in-process IDE bridge; never leaves the process. */
    val hostToken: String = randomToken(),
    val sessionId: String = randomToken(12),
    val maxParticipants: Int = 16,
    val resumeWindowMillis: Long = 30 * 60 * 1000L,
    val handshakeTimeoutMillis: Long = 15_000,
    val maxHistoryPerDocument: Int = 2_000,
    val fileEventLogSize: Int = 5_000,
    val presencePerSecond: Int = 40,
    val messagesPerSecond: Int = 2_000,
)

fun randomToken(bytes: Int = 24): String {
    val b = ByteArray(bytes)
    SecureRandom().nextBytes(b)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

/**
 * The authoritative session running in the host's IDE. All state lives on one serial
 * thread; transports post inbound messages to it. Every message from a participant is
 * authenticated, validated, and checked against that participant's role HERE, regardless
 * of what the participant's UI allows.
 */
class HostSession(
    val config: HostConfig,
    val files: ProjectFiles,
    private val documentSource: DocumentSource,
    private val scheduler: Scheduler,
    private val listener: HostSessionListener = object : HostSessionListener {},
) : AutoCloseable {
    private val exec = SerialExecutor("opencollab-host")
    private val io = SerialExecutor("opencollab-host-io")

    private inner class Peer(val connection: Connection) {
        var info: ParticipantInfo? = null
        var resumeKey: String = ""
        val subscriptions = HashSet<String>()
        var presence: Message.PresenceBroadcast? = null
        val presenceLimiter = RateLimiter(config.presencePerSecond, scheduler)
        val messageLimiter = RateLimiter(config.messagesPerSecond, scheduler)
        var handshakeTimer: Cancellable? = null
        var open = true
        val role: Role get() = info?.role ?: Role.VIEWER
        val id: String get() = info?.id ?: "?"

        fun send(m: Message) {
            if (open) connection.send(MessageCodec.encode(m))
        }
    }

    private data class Detached(val info: ParticipantInfo, val resumeKey: String, val subscriptions: Set<String>, val expiry: Cancellable)

    private val peers = LinkedHashMap<Connection, Peer>()
    private val detached = HashMap<String, Detached>() // by resumeKey
    private val docs = HashMap<String, ServerDocument>()
    private val fileLog = ArrayDeque<Message.FileEvent>()
    private var fileSeq = 0L
    private var nextColor = 0
    private var closed = false

    val endpointsInfo = mutableListOf<String>()

    // ---- Public API (thread-safe; work is posted to the session thread) --------------

    fun accept(connection: Connection) = exec.execute {
        if (closed) { connection.close("session closed"); return@execute }
        val peer = Peer(connection)
        peers[connection] = peer
        peer.handshakeTimer = scheduler.schedule(config.handshakeTimeoutMillis) {
            exec.execute { if (peer.info == null && peer.open) drop(peer, "handshake timeout") }
        }
        connection.setListener(object : ConnectionListener {
            override fun onMessage(text: String) = exec.execute { onMessage(peer, text) }
            override fun onClosed(reason: String) = exec.execute { onDisconnected(peer, reason) }
        })
    }

    fun setRole(participantId: String, role: Role) = exec.execute { applyRole(participantId, role) }

    fun kick(participantId: String) = exec.execute {
        peers.values.firstOrNull { it.info?.id == participantId && it.role != Role.HOST }?.let {
            it.send(Message.SessionEnded("Removed by host"))
            drop(it, "kicked", allowResume = false)
        }
    }

    fun participants(): List<ParticipantInfo> = peers.values.mapNotNull { it.info }

    /** Runs [block] on the session thread (tests, diagnostics). */
    fun <T> query(block: HostSession.() -> T): T {
        val future = java.util.concurrent.CompletableFuture<T>()
        exec.execute { future.complete(block()) }
        return future.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun documentText(path: String): String? = docs[path]?.text
    fun documentRevision(path: String): Int? = docs[path]?.revision

    override fun close() {
        exec.execute {
            if (closed) return@execute
            closed = true
            for (peer in peers.values.toList()) {
                peer.send(Message.SessionEnded("Host ended the session"))
                peer.open = false
                peer.connection.close("session ended")
            }
            peers.clear()
            detached.values.forEach { it.expiry.cancel() }
            detached.clear()
            listener.onParticipantsChanged(emptyList())
        }
        exec.close()
        io.close()
    }

    // ---- Message handling (session thread) -----------------------------------------

    private fun onMessage(peer: Peer, text: String) {
        if (!peer.open || closed) return
        if (!peer.messageLimiter.tryAcquire()) {
            drop(peer, "message rate limit exceeded")
            return
        }
        val message = try {
            MessageCodec.decode(text)
        } catch (e: ProtocolException) {
            listener.onLog("Protocol error from ${peer.connection.description}: ${e.message}")
            if (peer.info == null) {
                drop(peer, "protocol error")
            } else {
                peer.send(Message.Error("protocol", e.message ?: "invalid message"))
            }
            return
        }
        if (peer.info == null) {
            if (message is Message.Hello) handshake(peer, message) else drop(peer, "expected hello")
            return
        }
        try {
            handle(peer, message)
        } catch (e: SecurityException) {
            peer.send(Message.Error("forbidden", e.message ?: "forbidden"))
        } catch (e: Exception) {
            listener.onLog("Error handling ${message::class.simpleName} from ${peer.id}: ${e.message}")
            peer.send(Message.Error("internal", "Host failed to process request"))
        }
    }

    private fun handshake(peer: Peer, hello: Message.Hello) {
        peer.handshakeTimer?.cancel()
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            reject(peer, "Unsupported protocol version ${hello.protocolVersion} (host speaks $PROTOCOL_VERSION)")
            return
        }
        val tokenRole = when {
            constantTimeEquals(hello.token, config.hostToken) -> Role.HOST
            constantTimeEquals(hello.token, config.editorToken) -> Role.EDITOR
            constantTimeEquals(hello.token, config.viewerToken) -> Role.VIEWER
            else -> null
        }
        if (tokenRole == null) {
            listener.onLog("Rejected connection from ${peer.connection.description}: invalid invite token")
            reject(peer, "Invalid or expired invitation")
            return
        }
        if (tokenRole == Role.HOST && peers.values.any { it.info?.role == Role.HOST }) {
            reject(peer, "Host is already connected")
            return
        }
        val active = peers.values.count { it.info != null }
        if (active >= config.maxParticipants) {
            reject(peer, "Session is full")
            return
        }

        val resumed = hello.resumeKey?.let { key -> detached.remove(key)?.also { it.expiry.cancel() } }
        val info: ParticipantInfo
        if (resumed != null && resumed.info.role != Role.HOST) {
            // Keep the role the host assigned; a token can never escalate a resumed participant.
            info = resumed.info.copy(displayName = hello.displayName)
            peer.resumeKey = resumed.resumeKey
            peer.subscriptions.addAll(resumed.subscriptions)
        } else {
            info = ParticipantInfo(randomToken(9), hello.displayName, tokenRole, nextColor++ % 8)
            peer.resumeKey = randomToken(24)
        }
        peer.info = info
        peer.send(
            Message.Welcome(
                sessionId = config.sessionId,
                participantId = info.id,
                role = info.role,
                resumeKey = peer.resumeKey,
                resumed = resumed != null,
                projectName = config.projectName,
                participants = participants(),
                fileEventSeq = fileSeq,
                sharing = files.rules.toConfig(),
            ),
        )
        broadcast(Message.ParticipantJoined(info), except = peer)
        peers.values.forEach { other -> if (other !== peer) other.presence?.let(peer::send) }
        listener.onLog("${info.displayName} joined as ${info.role}${if (resumed != null) " (resumed)" else ""}")
        listener.onParticipantsChanged(participants())
    }

    private fun reject(peer: Peer, reason: String) {
        peer.send(Message.HandshakeRejected(reason))
        drop(peer, reason, allowResume = false)
    }

    private fun handle(peer: Peer, m: Message) {
        when (m) {
            is Message.Hello -> peer.send(Message.Error("protocol", "Already authenticated"))
            is Message.Ping -> peer.send(Message.Pong(m.nonce))
            is Message.SetRole -> {
                requireRole(peer, Role.HOST)
                applyRole(m.participantId, m.role)
            }
            is Message.RequestManifest -> {
                val seq = fileSeq
                io.execute {
                    val manifest = try { files.listShared() } catch (e: Exception) { emptyList() }
                    exec.execute { peer.send(Message.Manifest(m.requestId, manifest, seq)) }
                }
            }
            is Message.RequestFiles -> sendFiles(peer, m)
            is Message.ResumeFiles -> resumeFiles(peer, m)
            is Message.FileRequest -> fileRequest(peer, m)
            is Message.OpenDocument -> openDocument(peer, m.path)
            is Message.CloseDocument -> peer.subscriptions.remove(m.path)
            is Message.ResumeDocument -> resumeDocument(peer, m)
            is Message.DocOp -> docOp(peer, m)
            is Message.DocumentSaved -> {
                requireRole(peer, Role.HOST)
                val doc = docs[m.path] ?: return
                broadcastToSubscribers(m.path, Message.DocumentSaved(m.path, doc.revision), except = peer)
            }
            is Message.Presence -> presence(peer, m)
            else -> peer.send(Message.Error("protocol", "Unexpected message ${m::class.simpleName}"))
        }
    }

    // ---- Roles -------------------------------------------------------------------------

    private fun requireRole(peer: Peer, role: Role) {
        if (peer.role != role) throw SecurityException("Only the $role may do this")
    }

    private fun requireEditor(peer: Peer) {
        if (!peer.role.canEdit) throw SecurityException("Viewers cannot modify the project")
    }

    private fun applyRole(participantId: String, role: Role) {
        if (role == Role.HOST) return
        val peer = peers.values.firstOrNull { it.info?.id == participantId } ?: return
        val info = peer.info ?: return
        if (info.role == Role.HOST || info.role == role) return
        peer.info = info.copy(role = role)
        broadcast(Message.RoleChanged(participantId, role))
        listener.onLog("${info.displayName} is now $role")
        listener.onParticipantsChanged(participants())
    }

    // ---- Files -------------------------------------------------------------------------

    private fun sendFiles(peer: Peer, m: Message.RequestFiles) {
        val paths = m.paths.distinct()
        io.execute {
            val missing = ArrayList<String>()
            for (path in paths) {
                val bytes = try { files.read(path) } catch (e: Exception) { null }
                if (bytes == null) { missing += path; continue }
                val sha = Hashes.sha256(bytes)
                var offset = 0
                do {
                    val end = minOf(bytes.size, offset + MessageCodec.MAX_CHUNK_BYTES / 2)
                    val chunk = MessageCodec.encodeBase64(bytes.copyOfRange(offset, end))
                    val msg = Message.FileChunk(m.requestId, path, offset.toLong(), chunk, end == bytes.size, sha)
                    exec.execute { peer.send(msg) }
                    offset = end
                } while (offset < bytes.size)
            }
            exec.execute { peer.send(Message.FilesDone(m.requestId, missing)) }
        }
    }

    private fun resumeFiles(peer: Peer, m: Message.ResumeFiles) {
        val oldest = fileLog.firstOrNull()?.seq ?: (fileSeq + 1)
        if (m.lastSeq > fileSeq || (m.lastSeq + 1 < oldest && m.lastSeq < fileSeq)) {
            peer.send(Message.FilesResyncRequired(m.requestId))
            return
        }
        fileLog.filter { it.seq > m.lastSeq && it.origin != peer.id }.forEach(peer::send)
        peer.send(Message.RequestResult(m.requestId, true))
    }

    private fun fileRequest(peer: Peer, m: Message.FileRequest) {
        val mutation = m.mutation
        try {
            if (peer.role == Role.HOST) {
                // The host reports a change that already happened in its own project.
                if (!files.rules.isShared(mutation.path) && mutation.newPath?.let(files.rules::isShared) != true) {
                    peer.send(Message.RequestResult(m.requestId, true))
                    return
                }
                SafePath.resolveInside(files.root, mutation.path)
                mutation.newPath?.let { SafePath.resolveInside(files.root, it) }
                val effective = hostVisibleMutation(mutation) ?: run {
                    peer.send(Message.RequestResult(m.requestId, true)); return
                }
                invalidateDocuments(effective)
                publishFileEvent(peer, effective)
                peer.send(Message.RequestResult(m.requestId, true))
                return
            }
            requireEditor(peer)
            files.validateGuestMutation(mutation)
            if (mutation.kind == FileOpKind.WRITE && docs.containsKey(mutation.path)) {
                throw FileAccessDenied("${mutation.path} is open as a shared document; edit it in the editor")
            }
            files.perform(mutation)
            invalidateDocuments(mutation)
            publishFileEvent(peer, mutation)
            peer.send(Message.RequestResult(m.requestId, true))
        } catch (e: SecurityException) {
            peer.send(Message.RequestResult(m.requestId, false, e.message))
        } catch (e: java.io.IOException) {
            peer.send(Message.RequestResult(m.requestId, false, "I/O error: ${e.message}"))
        }
    }

    /**
     * Translates a host change into what guests may see. A rename that moves a file out of
     * (or into) the shared set becomes a delete (or a create with content).
     */
    private fun hostVisibleMutation(m: FileMutation): FileMutation? {
        if (m.kind != FileOpKind.RENAME) return if (files.rules.isShared(m.path)) m else null
        val fromShared = files.rules.isShared(m.path)
        val toShared = files.rules.isShared(m.newPath!!)
        return when {
            fromShared && toShared -> m
            fromShared -> FileMutation(FileOpKind.DELETE, m.path)
            toShared -> files.read(m.newPath)?.let {
                FileMutation(FileOpKind.CREATE_FILE, m.newPath, contentBase64 = MessageCodec.encodeBase64(it))
            }
            else -> null
        }
    }

    private fun publishFileEvent(origin: Peer, mutation: FileMutation) {
        val event = Message.FileEvent(++fileSeq, origin.id, mutation)
        fileLog.addLast(event)
        while (fileLog.size > config.fileEventLogSize) fileLog.removeFirst()
        for (peer in peers.values) {
            if (peer === origin || peer.info == null || peer.role == Role.HOST) continue
            peer.send(event)
        }
    }

    /** Deleted or renamed files can no longer be edited under their old path. */
    private fun invalidateDocuments(m: FileMutation) {
        if (m.kind != FileOpKind.DELETE && m.kind != FileOpKind.RENAME) return
        val affected = docs.keys.filter { it == m.path || it.startsWith(m.path + "/") }
        for (path in affected) {
            docs.remove(path)
            broadcastToSubscribers(path, Message.DocumentUnavailable(path, if (m.kind == FileOpKind.DELETE) "deleted" else "renamed"))
            peers.values.forEach { it.subscriptions.remove(path) }
        }
    }

    // ---- Documents ---------------------------------------------------------------------

    private fun loadDocument(path: String): ServerDocument? {
        docs[path]?.let { return it }
        if (!files.rules.isShared(path)) return null
        val text = documentSource.loadForSharing(path) ?: return null
        if (text.length > files.rules.maxFileBytes) return null
        val doc = ServerDocument(path, text, maxHistory = config.maxHistoryPerDocument)
        docs[path] = doc
        // The host's own editor bridge always follows shared documents.
        peers.values.filter { it.role == Role.HOST }.forEach { it.subscriptions += path }
        return doc
    }

    private fun openDocument(peer: Peer, path: String) {
        val doc = loadDocument(path)
        if (doc == null) {
            peer.send(Message.DocumentUnavailable(path, "not shared or not a text file"))
            return
        }
        peer.subscriptions += path
        // The host bridge pre-registered its copy when the document was loaded.
        if (peer.role == Role.HOST && doc.revision == 0) return
        peer.send(Message.DocumentState(path, doc.revision, doc.text))
    }

    private fun resumeDocument(peer: Peer, m: Message.ResumeDocument) {
        val doc = docs[m.path] ?: loadDocument(m.path)
        if (doc == null) {
            peer.send(Message.DocumentUnavailable(m.path, "not shared or not a text file"))
            return
        }
        peer.subscriptions += m.path
        val history = if (m.forceSnapshot) null else doc.historySince(m.revision)
        if (history != null) {
            peer.send(
                Message.DocumentCatchUp(
                    m.path, m.revision,
                    history.map { DocHistoryEntry(it.opId, it.author, it.revision, MessageCodec.opToJson(it.op)) },
                ),
            )
        } else {
            val applied = m.outstandingOpId?.let { doc.appliedRevisionOf(peer.id, it) != null } ?: false
            peer.send(Message.DocumentState(m.path, doc.revision, doc.text, outstandingApplied = applied))
        }
    }

    private fun docOp(peer: Peer, m: Message.DocOp) {
        val doc = docs[m.path]
        if (doc == null || m.path !in peer.subscriptions) {
            peer.send(Message.OpRejected(m.path, m.opId, "not-open"))
            return
        }
        if (!peer.role.canEdit || (peer.role != Role.HOST && files.rules.isGuestReadOnly(m.path))) {
            peer.send(Message.OpRejected(m.path, m.opId, "forbidden"))
            return
        }
        val op = MessageCodec.opFromJson(m.op)
        when (val result = doc.receive(peer.id, m.opId, m.baseRevision, op)) {
            is ServerDocument.Result.Applied -> {
                if (doc.text.length > files.rules.maxFileBytes) {
                    listener.onLog("Document ${m.path} exceeds the size limit")
                }
                peer.send(Message.OpAck(m.path, m.opId, result.revision))
                val remote = Message.RemoteOp(m.path, m.opId, peer.id, result.revision, MessageCodec.opToJson(result.op))
                broadcastToSubscribers(m.path, remote, except = peer)
            }
            is ServerDocument.Result.Duplicate -> peer.send(Message.OpAck(m.path, m.opId, result.revision))
            is ServerDocument.Result.Rejected -> peer.send(
                Message.OpRejected(m.path, m.opId, if (result.reason == ServerDocument.RejectReason.STALE) "stale" else "invalid"),
            )
        }
    }

    // ---- Presence ----------------------------------------------------------------------

    private fun presence(peer: Peer, m: Message.Presence) {
        if (!peer.presenceLimiter.tryAcquire()) return
        if (m.path != null && !files.rules.isShared(m.path)) return
        val broadcast = Message.PresenceBroadcast(peer.id, m.path, m.caret, m.selections)
        peer.presence = broadcast
        broadcast(broadcast, except = peer)
    }

    // ---- Connection lifecycle ----------------------------------------------------------

    private fun onDisconnected(peer: Peer, reason: String) {
        if (peers.containsKey(peer.connection)) drop(peer, reason)
    }

    private fun drop(peer: Peer, reason: String, allowResume: Boolean = true) {
        if (!peer.open) return
        peer.open = false
        peer.handshakeTimer?.cancel()
        peers.remove(peer.connection)
        peer.connection.close(reason)
        val info = peer.info ?: return
        if (allowResume && !closed && info.role != Role.HOST) {
            val key = peer.resumeKey
            val expiry = scheduler.schedule(config.resumeWindowMillis) { exec.execute { detached.remove(key) } }
            detached[key] = Detached(info, key, peer.subscriptions.toSet(), expiry)
        }
        broadcast(Message.ParticipantLeft(info.id))
        listener.onLog("${info.displayName} left ($reason)")
        listener.onParticipantsChanged(participants())
    }

    private fun broadcast(m: Message, except: Peer? = null) {
        val text = MessageCodec.encode(m)
        for (peer in peers.values) {
            if (peer === except || peer.info == null || !peer.open) continue
            peer.connection.send(text)
        }
    }

    private fun broadcastToSubscribers(path: String, m: Message, except: Peer? = null) {
        val text = MessageCodec.encode(m)
        for (peer in peers.values) {
            if (peer === except || peer.info == null || !peer.open || path !in peer.subscriptions) continue
            peer.connection.send(text)
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
