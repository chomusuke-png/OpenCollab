package dev.opencollab.core.session

import dev.opencollab.core.files.FileAccessDenied
import dev.opencollab.core.files.Hashes
import dev.opencollab.core.files.ProjectFiles
import dev.opencollab.core.files.SafePath
import dev.opencollab.core.files.toConfig
import dev.opencollab.core.ot.ServerDocument
import dev.opencollab.core.presence.RateLimiter
import dev.opencollab.core.protocol.ChatEntry
import dev.opencollab.core.protocol.ConsoleKind
import dev.opencollab.core.protocol.ConsoleStream
import dev.opencollab.core.protocol.Features
import dev.opencollab.core.protocol.RunTarget
import dev.opencollab.core.protocol.TextRange
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

    /**
     * An editor asks to run [RunRequestInfo.target] or [RunRequestInfo.command] on this machine
     * (session thread). Answer with [HostSession.answerRun]; nothing runs without that.
     */
    fun onRunRequest(request: RunRequestInfo) {}

    /** An editor adds or removes a line breakpoint in a shared file (session thread). */
    fun onBreakpointRequest(participantId: String, path: String, line: Int, add: Boolean) {}
}

/** Either a run [target] or a shell [command] (exactly one is set). */
data class RunRequestInfo(
    val requestId: String,
    val participantId: String,
    val displayName: String,
    val target: RunTarget?,
    val command: String? = null,
) {
    /** What to show the host's user. */
    val label: String get() = target?.name ?: command.orEmpty()
}

/** Reason codes in [Message.RunResponse]; guests show them in their own language. */
object RunRefusal {
    const val REFUSED = "refused"
    const val VIEWER = "viewer"
    const val UNKNOWN_TARGET = "unknown-target"
    const val TOO_MANY = "too-many"
    const val NO_ANSWER = "no-answer"
    const val HOST = "host"
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
    /** Presence relayed per participant per second; beyond it only the latest position is kept and relayed shortly after. */
    val presencePerSecond: Int = 120,
    val messagesPerSecond: Int = 2_000,
    /** How often the host pings participants and broadcasts their round-trip times; 0 disables. */
    val pingIntervalMillis: Long = 2_000,
    val chatPerSecond: Int = 5,
    /** Recent chat messages kept for participants who join or reconnect later. */
    val chatHistorySize: Int = 200,
    /** Run requests per participant per second (bursts beyond are refused). */
    val runRequestsPerSecond: Int = 1,
    /** A run request the host has not answered by then is refused. */
    val runRequestTimeoutMillis: Long = 120_000,
    /** Most recent consoles, and characters of each, replayed to participants who join later. */
    val consoleReplayCount: Int = 5,
    val consoleReplayChars: Int = 256 * 1024,
    /** File requests streamed to one participant at the same time; later ones wait in a queue. */
    val maxTransfersPerParticipant: Int = 4,
    /** File requests of one participant waiting for a transfer slot; beyond that they fail. */
    val maxQueuedTransfersPerParticipant: Int = 256,
    /** Optional [Features] this host offers (announced in Welcome, used only with guests announcing them too). */
    val features: Set<String> = Features.ALL.toSet(),
)

/** "Is typing" updates relayed per participant per second (clients send far fewer). */
private const val TYPING_PER_SECOND = 5

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
    /** Wall clock for chat timestamps ([Scheduler.nowMillis] is monotonic, not a date). */
    private val wallClock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val exec = SerialExecutor("opencollab-host")
    private val io = SerialExecutor("opencollab-host-io")
    private val transferPool = java.util.concurrent.Executors.newCachedThreadPool(dev.opencollab.core.util.daemonThreadFactory("opencollab-transfer"))

    private inner class Peer(val connection: Connection) {
        var info: ParticipantInfo? = null
        var resumeKey: String = ""
        val subscriptions = HashSet<String>()
        val transfers = java.util.concurrent.ConcurrentHashMap<String, Transfer>()
        /** File requests waiting for a free transfer slot (session thread). */
        val queuedTransfers = ArrayDeque<Message.RequestFiles>()
        var presence: Message.PresenceBroadcast? = null
        val presenceLimiter = RateLimiter(config.presencePerSecond, scheduler)
        val messageLimiter = RateLimiter(config.messagesPerSecond, scheduler)
        val chatLimiter = RateLimiter(config.chatPerSecond, scheduler)
        val typingLimiter = RateLimiter(TYPING_PER_SECOND, scheduler)
        val runLimiter = RateLimiter(config.runRequestsPerSecond, scheduler)
        var handshakeTimer: Cancellable? = null
        var open = true
        /** Latest position held back by the presence limit, and the timer that relays it. */
        var pendingPresence: Message.Presence? = null
        var presenceFlush: Cancellable? = null
        /** Nonce of the unanswered ping, if any, and when it was sent. */
        var pingNonce: Long? = null
        var pingSentAt = 0L
        var rttMillis: Int? = null
        /** Features both this participant and the host support. */
        var features: Set<String> = emptySet()
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
    private var nextPingNonce = 1L
    private val chatLog = ArrayDeque<ChatEntry>()
    private var nextChatId = 1L
    private var runTargets: List<RunTarget> = emptyList()
    private inner class PendingRun(val peer: Peer, val expiry: Cancellable)
    /** Unanswered run requests by "participantId/requestId". */
    private val pendingRuns = HashMap<String, PendingRun>()
    private class ConsoleLog(val started: Message.ConsoleStarted) {
        /** [Message.ConsoleOutput] and [Message.ConsoleTests], in order. */
        val chunks = ArrayDeque<Message>()
        var chars = 0
        var truncated = false
        var ended: Message.ConsoleEnded? = null
    }
    /** The latest consoles, oldest first, for participants who join later. */
    private val consoles = LinkedHashMap<String, ConsoleLog>()
    /** Latest host IDE state per message type (debugger, breakpoints, VCS, code style), for newcomers. */
    private val ideState = LinkedHashMap<Class<out Message>, Message>()
    @Volatile private var closed = false
    private val startedAt = scheduler.nowMillis()

    val endpointsInfo = mutableListOf<String>()

    init {
        schedulePingRound()
    }

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
        transferPool.shutdownNow()
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
        peer.features = hello.features.toSet() intersect config.features
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
                sessionAgeMillis = (scheduler.nowMillis() - startedAt).coerceAtLeast(0),
                features = config.features.toList(),
            ),
        )
        if (chatLog.isNotEmpty()) peer.send(Message.ChatHistory(chatLog.toList()))
        if (info.role != Role.HOST) {
            replayConsoles(peer)
            ideState.values.forEach { if (supports(peer, it)) peer.send(it) }
        }
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
            is Message.Pong -> if (m.nonce == peer.pingNonce) {
                peer.pingNonce = null
                peer.rttMillis = (scheduler.nowMillis() - peer.pingSentAt).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            }
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
            is Message.FileChunkAck -> peer.transfers[m.requestId]?.ack(m.receivedBytes)
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
            is Message.ChatSend -> chat(peer, m)
            is Message.ChatTyping -> if (peer.typingLimiter.tryAcquire()) broadcast(Message.ChatTypingBroadcast(peer.id, m.typing), except = peer)
            is Message.RunRequest -> runRequest(peer, m)
            is Message.CommandRequest -> commandRequest(peer, m)
            is Message.BreakpointRequest -> breakpointRequest(peer, m)
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
        if (!role.canEdit) refusePendingRuns(peer, RunRefusal.VIEWER)
        broadcast(Message.RoleChanged(participantId, role))
        listener.onLog("${info.displayName} is now $role")
        listener.onParticipantsChanged(participants())
    }

    // ---- Files -------------------------------------------------------------------------

    /**
     * Streams the requested files from disk in chunks. At most [MessageCodec.TRANSFER_WINDOW_BYTES]
     * may be unacknowledged, so a slow guest never makes the host buffer a whole large file.
     * Each request runs on its own transfer thread; the SHA-256 travels with the last chunk.
     */
    private fun sendFiles(peer: Peer, m: Message.RequestFiles) {
        peer.transfers.remove(m.requestId)?.cancel()
        peer.queuedTransfers.removeIf { it.requestId == m.requestId }
        // Each transfer holds a thread and an open file: a participant gets a few at a time.
        if (peer.transfers.size >= config.maxTransfersPerParticipant) {
            if (peer.queuedTransfers.size >= config.maxQueuedTransfersPerParticipant) {
                peer.send(Message.FilesDone(m.requestId, emptyList(), failed = m.paths.distinct()))
            } else {
                peer.queuedTransfers.addLast(m)
            }
            return
        }
        startTransfer(peer, m)
    }

    private fun startTransfer(peer: Peer, m: Message.RequestFiles) {
        val paths = m.paths.distinct()
        val transfer = Transfer()
        peer.transfers[m.requestId] = transfer
        transferPool.execute {
            val missing = ArrayList<String>()
            val failed = ArrayList<String>()
            var sent = 0L
            try {
                for ((index, path) in paths.withIndex()) {
                    val file = try { files.readableFile(path) } catch (e: Exception) { null }
                    if (file == null) { missing += path; continue }
                    try {
                        sent = streamFile(peer, m.requestId, path, file, transfer, sent)
                    } catch (e: TransferAborted) {
                        failed += paths.subList(index, paths.size)
                        break
                    } catch (e: java.io.IOException) {
                        failed += path // exists but could not be read now: not the same as missing
                    }
                }
            } finally {
                exec.execute {
                    peer.transfers.remove(m.requestId, transfer)
                    peer.send(Message.FilesDone(m.requestId, missing, failed))
                    if (peer.open && !closed) peer.queuedTransfers.removeFirstOrNull()?.let { startTransfer(peer, it) }
                }
            }
        }
    }

    private fun streamFile(peer: Peer, requestId: String, path: String, file: java.nio.file.Path, transfer: Transfer, alreadySent: Long): Long {
        var sent = alreadySent
        val digest = MessageDigest.getInstance("SHA-256")
        java.nio.file.Files.newInputStream(file).use { input ->
            val buffer = ByteArray(MessageCodec.CHUNK_BYTES)
            var offset = 0L
            while (true) {
                val n = input.readNBytes(buffer, 0, buffer.size)
                val last = n < buffer.size
                if (n > 0) digest.update(buffer, 0, n)
                if (offset + n > files.rules.maxFileBytes) throw java.io.IOException("$path grew beyond the size limit")
                transfer.awaitWindow(sent, MessageCodec.TRANSFER_WINDOW_BYTES) { peer.open && !closed }
                val sha = if (last) Hashes.run { digest.digest().toHex() } else ""
                val msg = Message.FileChunk(requestId, path, offset, MessageCodec.encodeBase64(buffer.copyOf(n)), last, sha)
                exec.execute { peer.send(msg) }
                offset += n
                sent += n
                if (last) return sent
            }
        }
    }

    /** Flow-control state of one file request (thread-safe). */
    private class Transfer {
        private val lock = Object()
        private var acked = 0L
        private var cancelled = false

        fun ack(bytes: Long) = synchronized(lock) {
            if (bytes > acked) acked = bytes
            lock.notifyAll()
        }

        fun cancel() = synchronized(lock) {
            cancelled = true
            lock.notifyAll()
        }

        /** Blocks until fewer than [window] sent bytes are unacknowledged. */
        fun awaitWindow(sent: Long, window: Long, alive: () -> Boolean) = synchronized(lock) {
            var idleSince = System.currentTimeMillis()
            var lastAcked = acked
            while (sent - acked >= window) {
                if (cancelled || !alive()) throw TransferAborted()
                if (acked != lastAcked) { lastAcked = acked; idleSince = System.currentTimeMillis() }
                if (System.currentTimeMillis() - idleSince > 60_000) throw TransferAborted()
                lock.wait(500)
            }
        }
    }

    private class TransferAborted : RuntimeException()

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
            toShared -> files.readableFile(m.newPath)?.let {
                // Large files are announced without content; guests fetch them in chunks.
                FileMutation(FileOpKind.CREATE_FILE, m.newPath, contentBase64 = files.readInline(m.newPath)?.let(MessageCodec::encodeBase64))
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
        if (text.length > maxDocumentChars()) return null
        val doc = ServerDocument(path, text, maxHistory = config.maxHistoryPerDocument, maxLength = maxDocumentChars())
        docs[path] = doc
        // The host's own editor bridge always follows shared documents.
        peers.values.filter { it.role == Role.HOST }.forEach { it.subscriptions += path }
        return doc
    }

    private fun maxDocumentChars(): Int = minOf(files.rules.maxFileBytes, MessageCodec.MAX_DOCUMENT_CHARS.toLong()).toInt()

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
            // The host applies an author's operations in order, so the applied ones are a prefix.
            val applied = m.inFlightOpIds.takeWhile { doc.appliedRevisionOf(peer.id, it) != null }.size
            peer.send(Message.DocumentState(m.path, doc.revision, doc.text, appliedCount = applied))
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
                peer.send(Message.OpAck(m.path, m.opId, result.revision))
                val remote = Message.RemoteOp(m.path, m.opId, peer.id, result.revision, MessageCodec.opToJson(result.op))
                broadcastToSubscribers(m.path, remote, except = peer)
            }
            is ServerDocument.Result.Duplicate -> peer.send(Message.OpAck(m.path, m.opId, result.revision))
            is ServerDocument.Result.Rejected -> peer.send(
                Message.OpRejected(m.path, m.opId, when (result.reason) {
                    ServerDocument.RejectReason.STALE -> "stale"
                    ServerDocument.RejectReason.TOO_LARGE -> "too-large"
                    ServerDocument.RejectReason.INVALID -> "invalid"
                }),
            )
        }
    }

    // ---- Presence ----------------------------------------------------------------------

    private fun presence(peer: Peer, m: Message.Presence) {
        if (m.path != null && !files.rules.isShared(m.path)) return
        if (!peer.presenceLimiter.tryAcquire()) {
            // Over the limit: keep only the latest position and relay it as soon as the limit
            // allows, so a burst never leaves a stale caret behind.
            peer.pendingPresence = m
            if (peer.presenceFlush == null) {
                val delay = (1000L / config.presencePerSecond).coerceAtLeast(1)
                peer.presenceFlush = scheduler.schedule(delay) {
                    exec.execute {
                        peer.presenceFlush = null
                        val pending = peer.pendingPresence ?: return@execute
                        peer.pendingPresence = null
                        if (peer.open && !closed) presence(peer, pending)
                    }
                }
            }
            return
        }
        peer.pendingPresence = null // superseded by this newer position
        val broadcast = mappedPresence(peer, m)
        peer.presence = broadcast
        broadcast(broadcast, except = peer)
    }

    /**
     * The sender's caret and selections, mapped from the text it had (revision + its own edits) to
     * the current text, like its edits: concurrent typing by others does not shift them.
     */
    private fun mappedPresence(peer: Peer, m: Message.Presence): Message.PresenceBroadcast {
        val doc = m.path?.let { docs[it] }
        if (doc != null && m.revision >= 0) {
            val offsets = listOf(m.caret) + m.selections.flatMap { listOf(it.start, it.end) }
            val mapped = doc.mapPositions(peer.id, m.revision, offsets)
            if (mapped != null) {
                val selections = m.selections.indices.map { i ->
                    val a = mapped[1 + 2 * i]
                    val b = mapped[2 + 2 * i]
                    TextRange(minOf(a, b), maxOf(a, b))
                }
                return Message.PresenceBroadcast(peer.id, m.path, mapped[0], selections, doc.revision)
            }
        }
        return Message.PresenceBroadcast(peer.id, m.path, m.caret, m.selections)
    }

    // ---- Chat --------------------------------------------------------------------------

    /** Every participant may chat (viewers too). The host, not the sender, sets who and when. */
    private fun chat(peer: Peer, m: Message.ChatSend) {
        val info = peer.info ?: return
        if (!peer.chatLimiter.tryAcquire()) {
            peer.send(Message.Error("chat-rate", "Too many chat messages; slow down"))
            return
        }
        val entry = ChatEntry(nextChatId++, info.id, info.displayName, info.colorIndex, m.text.trimEnd(), wallClock())
        broadcast(Message.ChatTypingBroadcast(info.id, false), except = peer) // the message is out: no longer typing
        chatLog.addLast(entry)
        while (chatLog.size > config.chatHistorySize) chatLog.removeFirst()
        broadcast(Message.Chat(entry))
    }

    // ---- Shared consoles (host IDE → guests) --------------------------------------------

    /** What guests may ask to run; replaces the previous list. Thread-safe. */
    fun setRunTargets(targets: List<RunTarget>) = exec.execute {
        runTargets = targets.take(MessageCodec.MAX_RUN_TARGETS)
        broadcastToGuests(Message.RunTargets(runTargets))
    }

    /** A process or build started in the host IDE. Thread-safe. */
    fun consoleStarted(consoleId: String, title: String, requestedBy: String? = null, kind: ConsoleKind = ConsoleKind.RUN) = exec.execute {
        val started = Message.ConsoleStarted(consoleId, title.take(MessageCodec.MAX_TITLE).ifBlank { "?" }, requestedBy, kind)
        consoles[consoleId] = ConsoleLog(started)
        while (consoles.size > config.consoleReplayCount) consoles.remove(consoles.keys.first())
        broadcastToGuests(started)
    }

    /** Output of a console; long text is split into protocol-sized chunks. Thread-safe. */
    fun consoleOutput(consoleId: String, stream: ConsoleStream, text: String) = exec.execute {
        val log = consoles[consoleId] ?: return@execute
        for (part in text.chunked(MessageCodec.MAX_CONSOLE_CHUNK_CHARS)) record(log, Message.ConsoleOutput(consoleId, stream, part))
    }

    /**
     * Test progress of a console as IntelliJ test service messages (whole lines), for guests that
     * show test trees ([Features.TESTS]). Thread-safe.
     */
    fun consoleTests(consoleId: String, text: String) = exec.execute {
        val log = consoles[consoleId] ?: return@execute
        for (part in chunkLines(text, MessageCodec.MAX_CONSOLE_CHUNK_CHARS)) record(log, Message.ConsoleTests(consoleId, part))
    }

    private fun record(log: ConsoleLog, chunk: Message) {
        log.chunks.addLast(chunk)
        log.chars += chunkChars(chunk)
        while (log.chars > config.consoleReplayChars && log.chunks.size > 1) {
            log.chars -= chunkChars(log.chunks.removeFirst())
            log.truncated = true
        }
        broadcastToGuests(chunk)
    }

    private fun chunkChars(m: Message) = when (m) {
        is Message.ConsoleOutput -> m.text.length
        is Message.ConsoleTests -> m.text.length
        else -> 0
    }

    /** Splits at line breaks where possible, so each chunk holds whole service messages. */
    private fun chunkLines(text: String, max: Int): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                val nl = text.lastIndexOf('\n', end - 1)
                if (nl >= start) end = nl + 1
            }
            parts += text.substring(start, end)
            start = end
        }
        return parts
    }

    /**
     * The host IDE's current debugger state, breakpoints, VCS status or code style: replaces the
     * previous one of the same kind, goes to guests supporting its feature and to later joiners.
     * Thread-safe.
     */
    fun publishIdeState(m: Message) = exec.execute {
        require(featureOf(m) != null) { "Not host IDE state: ${m::class.simpleName}" }
        if (ideState[m::class.java] == m) return@execute
        ideState[m::class.java] = m
        broadcastToGuests(m)
    }

    fun consoleEnded(consoleId: String, exitCode: Int?) = exec.execute {
        val log = consoles[consoleId] ?: return@execute
        val ended = Message.ConsoleEnded(consoleId, exitCode)
        log.ended = ended
        broadcastToGuests(ended)
    }

    private fun replayConsoles(peer: Peer) {
        if (runTargets.isNotEmpty()) peer.send(Message.RunTargets(runTargets))
        for (log in consoles.values) {
            peer.send(log.started)
            if (log.truncated) peer.send(Message.ConsoleOutput(log.started.consoleId, ConsoleStream.SYSTEM, "[…]\n"))
            log.chunks.forEach { if (supports(peer, it)) peer.send(it) }
            log.ended?.let(peer::send)
        }
    }

    /** The host's own IDE shows its consoles natively; only guests get the copies (those supporting its feature). */
    private fun broadcastToGuests(m: Message) {
        val text = MessageCodec.encode(m)
        for (peer in peers.values) {
            if (peer.info == null || !peer.open || peer.role == Role.HOST || !supports(peer, m)) continue
            peer.connection.send(text)
        }
    }

    private fun supports(peer: Peer, m: Message): Boolean = featureOf(m)?.let { it in peer.features } ?: true

    private fun featureOf(m: Message): String? = when (m) {
        is Message.ConsoleTests -> Features.TESTS
        is Message.DebugState, is Message.Breakpoints -> Features.DEBUG
        is Message.VcsStatus -> Features.VCS
        is Message.CodeStyle -> Features.CODE_STYLE
        else -> null
    }

    // ---- Run requests (guest → host) -------------------------------------------------

    private fun runRequest(peer: Peer, m: Message.RunRequest) {
        val refusal = runRefusal(peer)
        if (refusal != null) return peer.send(Message.RunResponse(m.requestId, false, refusal))
        val target = runTargets.firstOrNull { it.id == m.targetId }
            ?: return peer.send(Message.RunResponse(m.requestId, false, RunRefusal.UNKNOWN_TARGET))
        awaitAnswer(peer, m.requestId, target, null)
    }

    /** A shell command; like a run request, it runs only once the host's user allows this one. */
    private fun commandRequest(peer: Peer, m: Message.CommandRequest) {
        val refusal = if (Features.COMMANDS !in peer.features) RunRefusal.UNKNOWN_TARGET else runRefusal(peer)
        if (refusal != null) return peer.send(Message.RunResponse(m.requestId, false, refusal))
        awaitAnswer(peer, m.requestId, null, m.command)
    }

    private fun runRefusal(peer: Peer): String? {
        val info = peer.info ?: return RunRefusal.REFUSED
        return when {
            info.role == Role.HOST -> RunRefusal.HOST
            !info.role.canEdit -> RunRefusal.VIEWER
            !peer.runLimiter.tryAcquire() -> RunRefusal.TOO_MANY
            else -> null
        }
    }

    private fun awaitAnswer(peer: Peer, requestId: String, target: RunTarget?, command: String?) {
        val info = peer.info ?: return
        val key = "${info.id}/$requestId"
        if (key in pendingRuns) return
        val expiry = scheduler.schedule(config.runRequestTimeoutMillis) {
            exec.execute { if (pendingRuns.remove(key) != null) peer.send(Message.RunResponse(requestId, false, RunRefusal.NO_ANSWER)) }
        }
        pendingRuns[key] = PendingRun(peer, expiry)
        listener.onRunRequest(RunRequestInfo(requestId, info.id, info.displayName, target, command))
    }

    private fun breakpointRequest(peer: Peer, m: Message.BreakpointRequest) {
        requireEditor(peer)
        if (Features.DEBUG !in peer.features) return
        if (!files.rules.isShared(m.path)) throw SecurityException("Not a shared file: ${m.path}")
        listener.onBreakpointRequest(peer.id, m.path, m.line, m.add)
    }

    /**
     * The host's answer to a run request. Completes with true if the answer was delivered: for an
     * acceptance, only if the request was still waiting (not expired, requester still connected
     * and still an editor): run the target only then. Never blocks the caller (the IDE calls it on the EDT, which the session thread may be
     * waiting for).
     */
    fun answerRun(participantId: String, requestId: String, accepted: Boolean): java.util.concurrent.CompletableFuture<Boolean> {
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        exec.execute {
            val pending = pendingRuns.remove("$participantId/$requestId")
            if (pending == null) { result.complete(false); return@execute }
            pending.expiry.cancel()
            val peer = pending.peer
            if (!peer.open) { result.complete(false); return@execute }
            // The role may have changed since the request: only a current editor gets it run.
            val granted = accepted && peer.role.canEdit && peer.role != Role.HOST
            val reason = when {
                granted -> null
                accepted -> RunRefusal.VIEWER
                else -> RunRefusal.REFUSED
            }
            peer.send(Message.RunResponse(requestId, granted, reason))
            result.complete(granted || !accepted) // a refusal always goes through
        }
        if (closed) result.complete(false)
        return result
    }

    private fun refusePendingRuns(peer: Peer, reason: String) {
        val keys = pendingRuns.filterValues { it.peer === peer }.keys
        for (key in keys) {
            pendingRuns.remove(key)?.expiry?.cancel()
            peer.send(Message.RunResponse(key.substringAfter('/'), false, reason))
        }
    }

    // ---- Latency -----------------------------------------------------------------------

    private fun schedulePingRound() {
        if (config.pingIntervalMillis <= 0) return
        scheduler.schedule(config.pingIntervalMillis) { exec.execute { pingRound() } }
    }

    /**
     * Pings every participant and broadcasts the round-trip times measured so far. A ping still
     * unanswered from the previous round counts as at least its age, so a stalled peer's
     * latency keeps rising instead of freezing at its last good value.
     */
    private fun pingRound() {
        if (closed) return
        val now = scheduler.nowMillis()
        for (peer in peers.values) {
            if (peer.info == null || !peer.open) continue
            if (peer.pingNonce != null) {
                val waited = (now - peer.pingSentAt).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                peer.rttMillis = maxOf(peer.rttMillis ?: 0, waited)
            }
            val nonce = nextPingNonce++
            peer.pingNonce = nonce
            peer.pingSentAt = now
            peer.send(Message.Ping(nonce))
        }
        val latencies = peers.values.mapNotNull { peer -> peer.info?.let { info -> peer.rttMillis?.let { info.id to it } } }.toMap()
        if (latencies.isNotEmpty()) broadcast(Message.Latencies(latencies))
        schedulePingRound()
    }

    // ---- Connection lifecycle ----------------------------------------------------------

    private fun onDisconnected(peer: Peer, reason: String) {
        if (peers.containsKey(peer.connection)) drop(peer, reason)
    }

    private fun drop(peer: Peer, reason: String, allowResume: Boolean = true) {
        if (!peer.open) return
        peer.open = false
        peer.handshakeTimer?.cancel()
        peer.presenceFlush?.cancel()
        peer.transfers.values.forEach { it.cancel() }
        peer.queuedTransfers.clear()
        // Their unanswered run requests can no longer be granted.
        pendingRuns.values.removeIf { pending -> (pending.peer === peer).also { if (it) pending.expiry.cancel() } }
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
