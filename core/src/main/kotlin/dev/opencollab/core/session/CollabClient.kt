package dev.opencollab.core.session

import dev.opencollab.core.files.MirrorWorkspace
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.files.toRules
import dev.opencollab.core.ot.ClientDocument
import dev.opencollab.core.ot.TextOperation
import dev.opencollab.core.presence.PresenceThrottler
import dev.opencollab.core.protocol.ChatEntry
import dev.opencollab.core.protocol.RunTarget
import dev.opencollab.core.protocol.FileEntry
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.protocol.PROTOCOL_VERSION
import dev.opencollab.core.protocol.ParticipantInfo
import dev.opencollab.core.protocol.ProtocolException
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.protocol.TextRange
import dev.opencollab.core.transport.Connection
import dev.opencollab.core.transport.ConnectionListener
import dev.opencollab.core.transport.Connector
import dev.opencollab.core.util.Scheduler
import dev.opencollab.core.util.SerialExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

enum class ClientState { CONNECTING, SYNCING, CONNECTED, RECONNECTING, DISCONNECTED }

/**
 * Callbacks from [CollabClient] into the IDE. Document callbacks run on the client's
 * document executor (the EDT in the plugin); file callbacks run on the client's I/O thread.
 */
interface ClientHandler {
    fun onStateChanged(state: ClientState, detail: String) {}
    fun onWelcome(welcome: Message.Welcome) {}
    fun onParticipantsChanged(participants: List<ParticipantInfo>) {}
    fun onRoleChanged(role: Role) {}

    /** Apply [op] to the local editor document for [path] now (document executor). */
    fun applyRemoteEdit(path: String, op: TextOperation) {}
    fun onDocumentUnavailable(path: String, reason: String) {}
    fun onDocumentSaved(path: String) {}

    fun onPresence(presence: Message.PresenceBroadcast) {}
    fun onParticipantLeft(participantId: String) {}

    /** Each participant's round-trip time to the host in ms, refreshed every few seconds (I/O thread). */
    fun onLatencies(millis: Map<String, Int>) {}

    /** Another participant started or stopped typing a chat message (I/O thread). */
    fun onChatTyping(participantId: String, typing: Boolean) {}

    /** A new chat message, including our own once the host relays it (I/O thread). */
    fun onChat(entry: ChatEntry) {}

    /** Recent chat after (re)joining; replaces what was shown before (I/O thread). */
    fun onChatHistory(entries: List<ChatEntry>) {}

    /** The host's shared consoles and run targets, and answers to [CollabClient.requestRun] (I/O thread). */
    fun onConsoleStarted(started: Message.ConsoleStarted) {}
    fun onConsoleOutput(output: Message.ConsoleOutput) {}
    fun onConsoleEnded(ended: Message.ConsoleEnded) {}
    fun onRunTargets(targets: List<RunTarget>) {}
    fun onRunResponse(response: Message.RunResponse) {}

    /** Mirror files changed on disk (I/O thread). [initial] is true for the join snapshot. */
    fun onFilesChanged(paths: Collection<String>, initial: Boolean) {}
    fun onFileRequestFailed(mutation: FileMutation, error: String) {}

    fun onLog(message: String) {}
}

data class ReconnectPolicy(
    val initialDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 30_000,
    val maxAttempts: Int = 60,
)

/**
 * Participant side of a session (used by guests, and by the host's own IDE bridge through
 * an in-memory connection). Owns the per-document OT clients, the guest file mirror, the
 * presence throttle, and reconnect/recovery.
 */
class CollabClient(
    private val connector: Connector,
    private val displayName: String,
    private val token: String,
    private val handler: ClientHandler,
    private val docExecutor: Executor,
    private val scheduler: Scheduler,
    /** Guest file mirror; null for the host's own bridge (the host owns the real files). */
    val mirror: MirrorWorkspace?,
    private val reconnectPolicy: ReconnectPolicy? = ReconnectPolicy(),
    presenceIntervalMillis: Long = 0,
) : AutoCloseable {
    private val inbound = SerialExecutor("opencollab-client")
    private val requestCounter = AtomicLong()
    private val pendingFileRequests = ConcurrentHashMap<String, FileMutation>()

    @Volatile private var connection: Connection? = null
    @Volatile var state: ClientState = ClientState.DISCONNECTED
        private set
    @Volatile var participantId: String = ""
        private set
    @Volatile var role: Role = Role.VIEWER
        private set
    @Volatile var sessionId: String? = null
        private set
    @Volatile var sharingRules: SharingRules = SharingRules()
        private set
    @Volatile private var resumeKey: String? = null
    @Volatile private var userClosed = false
    /** Only true between Welcome and connection loss; nothing but Hello may be sent before. */
    @Volatile private var welcomed = false
    private var attempts = 0
    private val participants = LinkedHashMap<String, ParticipantInfo>()

    // Guest snapshot state (inbound thread).
    private var manifestSeq = 0L
    private var initialSync = false
    private val syncedPaths = ArrayList<String>()
    private val receivedPerRequest = HashMap<String, Long>()

    // Document state (document executor only).
    private val docs = HashMap<String, ClientDocument>()
    private val discardOnResync = HashSet<String>()
    private val openPaths = ConcurrentHashMap.newKeySet<String>()

    private val presence = PresenceThrottler<Message.Presence>(presenceIntervalMillis, scheduler) { send(it) }

    /**
     * Carets received since the last document message, newest per participant, drawn in one
     * document-executor task. A burst then costs one redraw per participant instead of one per
     * message, and a caret is never drawn before the document change it was sent after: a
     * document message starts a new batch behind it.
     */
    private class PresenceBatch {
        val latest = LinkedHashMap<String, Message.PresenceBroadcast>()
        var drawn = false
    }
    private var presenceBatch: PresenceBatch? = null // inbound thread

    private fun queuePresence(m: Message.PresenceBroadcast) {
        presenceBatch?.let { batch ->
            synchronized(batch) {
                if (!batch.drawn) { batch.latest[m.participantId] = m; return }
            }
        }
        val batch = PresenceBatch().also { it.latest[m.participantId] = m }
        presenceBatch = batch
        docExecutor.execute {
            val carets = synchronized(batch) { batch.drawn = true; batch.latest.values.toList() }
            carets.forEach(handler::onPresence)
        }
    }

    fun participants(): List<ParticipantInfo> = synchronized(participants) { participants.values.toList() }

    /** Starts connecting in the background. */
    fun start() {
        inbound.execute { connectOnce(initial = true) }
    }

    // ---- API for the IDE ---------------------------------------------------------------

    /** Starts sharing a document; [localText] is the editor's current content (document executor). */
    fun openDocument(path: String, localText: String) {
        if (docs.containsKey(path)) return
        docs[path] = ClientDocument.unsynced(path, newOpPrefix(), localText)
        openPaths += path
        send(Message.OpenDocument(path))
    }

    /** Host bridge only: registers the host's document at revision 0 (document executor). */
    fun preRegisterDocument(path: String, text: String) {
        docs[path] = ClientDocument.synced(path, newOpPrefix(), text, 0)
        openPaths += path
    }

    fun closeDocument(path: String) {
        if (docs.remove(path) != null) {
            openPaths -= path
            send(Message.CloseDocument(path))
        }
    }

    fun isDocumentOpen(path: String): Boolean = path in openPaths

    fun isDocumentSynced(path: String): Boolean = docs[path]?.isSynced == true

    /** Records a local edit made in the editor (document executor). */
    fun localEdit(path: String, op: TextOperation) {
        val doc = docs[path] ?: return
        val out = doc.applyLocal(op) ?: return
        // While catching up after a reconnect, the edit stays in flight and is sent right after.
        if (!needsResume && path !in catchingUp) sendDocOp(path, out)
    }

    fun documentSaved(path: String) {
        val doc = docs[path] ?: return
        send(Message.DocumentSaved(path, doc.revision))
    }

    /** Caret held back until the edits it refers to have been sent (document executor). */
    private var heldPresence: Message.Presence? = null

    /**
     * Shares our caret (document executor). Edits are sent as they happen, but not before the
     * first sync or while catching up after a reconnect: then the caret waits for them, since
     * sent earlier it would point past text the others do not have yet (a line down at the end
     * of a line).
     */
    fun updatePresence(path: String?, caret: Int, selections: List<TextRange>) {
        val m = Message.Presence(path, caret, selections.take(MessageCodec.MAX_SELECTIONS))
        if (path != null && hasUnsentEdits(path)) {
            heldPresence = m
            return
        }
        heldPresence = null
        presence.submit(m)
    }

    private fun hasUnsentEdits(path: String) =
        docs[path]?.hasUnsentChanges == true || ((needsResume || path in catchingUp) && docs[path]?.hasPendingChanges == true)

    /** Sends an operation, then a caret that was waiting for it (document executor). */
    private fun sendDocOp(path: String, out: ClientDocument.Outgoing) {
        send(Message.DocOp(path, out.opId, out.baseRevision, MessageCodec.opToJson(out.op)))
        releaseHeldPresence()
    }

    private fun releaseHeldPresence() {
        val held = heldPresence ?: return
        if (held.path != null && hasUnsentEdits(held.path)) return
        heldPresence = null
        presence.submit(held)
    }

    /** Asks the host to apply a local filesystem change (guests) or reports one (host bridge). */
    fun requestFileChange(mutation: FileMutation) {
        val id = "f${requestCounter.incrementAndGet()}"
        pendingFileRequests[id] = mutation
        send(Message.FileRequest(id, mutation))
    }

    fun setRole(participantId: String, role: Role) = send(Message.SetRole(participantId, role))

    /** Tells the others whether we are typing a chat message; call again every few seconds while typing. */
    fun setChatTyping(typing: Boolean) {
        send(Message.ChatTyping(typing))
    }

    /**
     * Asks the host to run [targetId] (from [ClientHandler.onRunTargets]); the answer arrives
     * through [ClientHandler.onRunResponse] with the returned request id. Null if not connected.
     */
    fun requestRun(targetId: String): String? {
        if (!welcomed) return null
        val id = "run-${requestCounter.incrementAndGet()}"
        send(Message.RunRequest(id, targetId))
        return id
    }

    /**
     * Sends a chat message; it shows up through [ClientHandler.onChat] once the host relays it.
     * Returns false (nothing sent) for blank or over-long text or while not connected.
     */
    fun sendChat(text: String): Boolean {
        // Control characters other than line breaks and tabs would get the message refused.
        val clean = text.filter { !it.isISOControl() || it == '\n' || it == '\t' }.trim()
        if (clean.isEmpty() || clean.length > MessageCodec.MAX_CHAT_CHARS || !welcomed) return false
        send(Message.ChatSend(clean))
        return true
    }

    /** Re-downloads host versions of [paths] (e.g. to undo a refused local change). */
    fun restoreFromHost(paths: List<String>) {
        if (mirror == null || paths.isEmpty()) return
        send(Message.RequestFiles("restore-${requestCounter.incrementAndGet()}", paths))
    }

    override fun close() {
        userClosed = true
        presence.close()
        connection?.close("participant left")
        inbound.execute {
            mirror?.let { runCatching { it.saveState() } }
            setState(ClientState.DISCONNECTED, "Left the session")
        }
        inbound.close()
    }

    // ---- Connection management (inbound thread) ----------------------------------------

    private fun connectOnce(initial: Boolean) {
        if (userClosed) return
        setState(if (initial) ClientState.CONNECTING else ClientState.RECONNECTING, "Connecting…")
        val conn = try {
            connector.connect()
        } catch (e: Exception) {
            handler.onLog("Connection failed: ${e.message}")
            scheduleReconnect("Connection failed: ${e.message}")
            return
        }
        connection = conn
        conn.setListener(object : ConnectionListener {
            override fun onMessage(text: String) = inbound.execute { if (connection === conn) receive(text) }
            override fun onClosed(reason: String) = inbound.execute { if (connection === conn) onConnectionLost(reason) }
        })
        conn.send(MessageCodec.encode(Message.Hello(PROTOCOL_VERSION, displayName, token, resumeKey)))
    }

    private fun onConnectionLost(reason: String) {
        connection = null
        needsResume = true // before welcomed: no edit may slip out between reconnecting and catching up
        welcomed = false
        receivedPerRequest.clear()
        mirror?.discardPartials()
        if (userClosed || state == ClientState.DISCONNECTED) return
        handler.onLog("Connection lost: $reason")
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        val policy = reconnectPolicy
        val neverConnected = sessionId == null
        if (userClosed || policy == null || attempts >= policy.maxAttempts || (neverConnected && attempts >= 2)) {
            setState(ClientState.DISCONNECTED, reason)
            return
        }
        val delay = minOf(policy.maxDelayMillis, policy.initialDelayMillis shl minOf(attempts, 5))
        attempts++
        setState(ClientState.RECONNECTING, "Reconnecting in ${delay / 1000}s ($reason)")
        scheduler.schedule(delay) { inbound.execute { if (connection == null) connectOnce(initial = false) } }
    }

    private fun setState(newState: ClientState, detail: String) {
        state = newState
        handler.onStateChanged(newState, detail)
    }

    private fun send(m: Message) {
        if (!welcomed) return // recovered after (re)connecting: documents resync, file requests are re-sent
        connection?.send(MessageCodec.encode(m))
    }

    // ---- Inbound dispatch (inbound thread) ---------------------------------------------

    private fun receive(text: String) {
        val m = try {
            MessageCodec.decode(text)
        } catch (e: ProtocolException) {
            handler.onLog("Ignoring invalid message from host: ${e.message}")
            return
        }
        when (m) {
            is Message.Welcome -> onWelcome(m)
            is Message.HandshakeRejected -> {
                userClosed = true
                connection?.close("rejected")
                setState(ClientState.DISCONNECTED, "Host refused the connection: ${m.reason}")
            }
            is Message.SessionEnded -> {
                userClosed = true
                connection?.close("ended")
                mirror?.let { runCatching { it.saveState() } }
                setState(ClientState.DISCONNECTED, m.reason)
            }
            is Message.ParticipantJoined -> {
                synchronized(participants) { participants[m.participant.id] = m.participant }
                handler.onParticipantsChanged(participants())
            }
            is Message.ParticipantLeft -> {
                synchronized(participants) { participants.remove(m.participantId) }
                handler.onParticipantsChanged(participants())
                presenceBatch = null
                docExecutor.execute { handler.onParticipantLeft(m.participantId) }
            }
            is Message.RoleChanged -> {
                synchronized(participants) { participants[m.participantId]?.let { participants[m.participantId] = it.copy(role = m.role) } }
                if (m.participantId == participantId) {
                    role = m.role
                    handler.onRoleChanged(m.role)
                }
                handler.onParticipantsChanged(participants())
            }
            is Message.Error -> handler.onLog("Host: ${m.message}")
            is Message.Pong -> Unit
            is Message.Ping -> send(Message.Pong(m.nonce))
            is Message.Latencies -> handler.onLatencies(m.millis)
            is Message.Chat -> handler.onChat(m.entry)
            is Message.ChatTypingBroadcast -> handler.onChatTyping(m.participantId, m.typing)
            is Message.ChatHistory -> handler.onChatHistory(m.entries)
            is Message.ConsoleStarted -> handler.onConsoleStarted(m)
            is Message.ConsoleOutput -> handler.onConsoleOutput(m)
            is Message.ConsoleEnded -> handler.onConsoleEnded(m)
            is Message.RunTargets -> handler.onRunTargets(m.targets)
            is Message.RunResponse -> handler.onRunResponse(m)

            is Message.Manifest -> onManifest(m)
            is Message.FileChunk -> onFileChunk(m)
            is Message.FilesDone -> onFilesDone(m)
            is Message.FileEvent -> onFileEvent(m)
            is Message.FilesResyncRequired -> send(Message.RequestManifest("manifest-${requestCounter.incrementAndGet()}"))
            is Message.RequestResult -> onRequestResult(m)

            is Message.DocumentState, is Message.DocumentCatchUp, is Message.RemoteOp, is Message.OpAck,
            is Message.OpRejected, is Message.DocumentUnavailable, is Message.DocumentSaved,
            -> {
                presenceBatch = null // later carets refer to the text after this change
                docExecutor.execute { onDocumentMessage(m) }
            }

            is Message.PresenceBroadcast -> queuePresence(m)
            else -> handler.onLog("Unexpected message ${m::class.simpleName}")
        }
    }

    private fun onWelcome(w: Message.Welcome) {
        val previousSession = sessionId
        if (previousSession != null && previousSession != w.sessionId) {
            userClosed = true
            connection?.close("different session")
            setState(ClientState.DISCONNECTED, "The host started a new session; join again with a new invitation")
            return
        }
        attempts = 0
        welcomed = true
        sessionId = w.sessionId
        participantId = w.participantId
        role = w.role
        resumeKey = w.resumeKey
        sharingRules = w.sharing.toRules()
        synchronized(participants) {
            participants.clear()
            w.participants.forEach { participants[it.id] = it }
        }
        handler.onWelcome(w)
        handler.onParticipantsChanged(participants())

        val ws = mirror
        if (ws != null) {
            setState(ClientState.SYNCING, "Synchronizing project files…")
            val sameMirror = ws.sessionId == w.sessionId
            ws.bind(w.sessionId, w.projectName)
            initialSync = true
            syncedPaths.clear()
            if (w.resumed && sameMirror) {
                send(Message.ResumeFiles("resume-${requestCounter.incrementAndGet()}", ws.fileEventSeq))
            } else {
                send(Message.RequestManifest("manifest-${requestCounter.incrementAndGet()}"))
            }
        } else {
            setState(ClientState.CONNECTED, "Connected")
        }
        val resumed = w.resumed
        docExecutor.execute { resumeDocuments(resumed) }
        pendingFileRequests.forEach { (id, mutation) -> send(Message.FileRequest(id, mutation)) }
    }

    // ---- Files (inbound thread) --------------------------------------------------------

    private fun onManifest(m: Message.Manifest) {
        val ws = mirror ?: return
        manifestSeq = m.fileEventSeq
        val sharedManifest: List<FileEntry> = m.files.filter { it.size <= sharingRules.maxFileBytes }
        for (stale in ws.stalePaths(sharedManifest)) {
            if (openPaths.contains(stale)) continue
            runCatching { ws.deleteStale(stale) }.onSuccess { syncedPaths += stale }
        }
        val toFetch = ws.pathsToFetch(sharedManifest).filterNot { openPaths.contains(it) }
        handler.onLog("Project manifest: ${m.files.size} files, ${toFetch.size} to download")
        send(Message.RequestFiles(m.requestId, toFetch))
    }

    private fun onFileChunk(m: Message.FileChunk) {
        val ws = mirror ?: return
        val data = try { MessageCodec.decodeBase64(m.dataBase64) } catch (e: ProtocolException) { ByteArray(0) }
        try {
            val complete = ws.acceptChunk(m.path, m.offset, data, m.last, m.sha256, sharingRules.maxFileBytes) ?: return
            if (openPaths.contains(m.path)) {
                java.nio.file.Files.deleteIfExists(complete.file) // the open document is synchronised via OT instead
                return
            }
            ws.writeReceived(complete)
            syncedPaths += m.path
        } catch (e: Exception) {
            handler.onLog("Failed to write ${m.path}: ${e.message}")
        } finally {
            // Flow control: tell the host how much of this request has been processed (written to disk).
            val received = (receivedPerRequest[m.requestId] ?: 0L) + data.size
            receivedPerRequest[m.requestId] = received
            send(Message.FileChunkAck(m.requestId, received))
        }
    }

    private fun onFilesDone(m: Message.FilesDone) {
        val ws = mirror ?: return
        receivedPerRequest.remove(m.requestId)
        if (m.requestId.startsWith("restore-")) {
            for (missing in m.missing) {
                if (ws.isTracked(missing)) runCatching { ws.deleteStale(missing) }
            }
            runCatching { ws.saveState() }
            handler.onFilesChanged(syncedPaths.toList() + m.missing, initial = false)
            syncedPaths.clear()
            return
        }
        if (manifestSeq > ws.fileEventSeq) ws.setFileEventSeq(manifestSeq)
        runCatching { ws.saveState() }
        val changed = syncedPaths.toList()
        syncedPaths.clear()
        val initial = initialSync
        initialSync = false
        handler.onFilesChanged(changed, initial)
        setState(ClientState.CONNECTED, "Connected")
    }

    private fun onFileEvent(e: Message.FileEvent) {
        val ws = mirror ?: return
        if (e.seq <= ws.fileEventSeq && !initialSync) return // already applied (replay overlap)
        val mu = e.mutation
        if (mu.kind == FileOpKind.WRITE && openPaths.contains(mu.path)) {
            ws.setFileEventSeq(maxOf(ws.fileEventSeq, e.seq)) // content is synchronised via OT
            return
        }
        if ((mu.kind == FileOpKind.CREATE_FILE || mu.kind == FileOpKind.WRITE) && mu.contentBase64 == null) {
            // Too large to travel inline: download it in acknowledged chunks.
            ws.setFileEventSeq(maxOf(ws.fileEventSeq, e.seq))
            send(Message.RequestFiles("restore-${requestCounter.incrementAndGet()}", listOf(mu.path)))
            return
        }
        try {
            ws.apply(mu, e.seq)
            handler.onFilesChanged(listOfNotNull(mu.path, mu.newPath), initial = false)
        } catch (ex: Exception) {
            handler.onLog("Could not apply ${mu.kind} ${mu.path}: ${ex.message}")
            if (e.seq > ws.fileEventSeq) ws.setFileEventSeq(e.seq)
        }
    }

    private fun onRequestResult(r: Message.RequestResult) {
        if (r.requestId.startsWith("resume-")) {
            mirror?.let { runCatching { it.saveState() } }
            initialSync = false
            handler.onFilesChanged(emptyList(), initial = false)
            setState(ClientState.CONNECTED, "Connected")
            return
        }
        val mutation = pendingFileRequests.remove(r.requestId) ?: return
        if (r.ok) {
            mirror?.recordLocal(mutation)
        } else {
            handler.onFileRequestFailed(mutation, r.error ?: "refused")
        }
    }

    // ---- Documents (document executor) -------------------------------------------------

    /**
     * Documents waiting for a catch-up or snapshot (document executor). Their new edits are not
     * sent meanwhile: an edit sent before the reply would be counted as not applied and resent.
     */
    private val catchingUp = HashSet<String>()
    /** Set when the connection drops, until [resumeDocuments] has asked to catch up. */
    @Volatile private var needsResume = false

    private fun resumeDocuments(resumed: Boolean) {
        for ((path, doc) in docs) {
            if (!doc.isSynced) {
                send(Message.OpenDocument(path))
            } else {
                catchingUp += path
                send(Message.ResumeDocument(path, doc.revision, doc.inFlightOpIds, forceSnapshot = !resumed))
            }
        }
        needsResume = false
    }

    private fun forceResync(path: String, doc: ClientDocument, discard: Boolean) {
        if (discard) discardOnResync += path
        // One recovery at a time: a second reply would count the first one's resent edits as new.
        if (!catchingUp.add(path)) return
        send(Message.ResumeDocument(path, doc.revision, doc.inFlightOpIds, forceSnapshot = true))
    }

    private fun onDocumentMessage(m: Message) {
        when (m) {
            is Message.DocumentState -> {
                val doc = docs[m.path] ?: return
                val discard = discardOnResync.remove(m.path)
                catchingUp -= m.path
                val r = doc.resync(m.text, m.revision, m.appliedCount, discardLocal = discard)
                if (!r.local.isNoop || r.local.baseLength != r.local.targetLength) handler.applyRemoteEdit(m.path, r.local)
                r.outgoing?.let { sendDocOp(m.path, it) }
                releaseHeldPresence()
            }
            is Message.DocumentCatchUp -> {
                val doc = docs[m.path] ?: return
                if (m.fromRevision != doc.revision) { forceResync(m.path, doc, false); return }
                try {
                    for (entry in m.entries) {
                        if (entry.author == participantId && entry.opId == doc.firstInFlightOpId) {
                            doc.acknowledge(entry.opId, entry.revision)
                        } else {
                            val local = doc.applyServer(MessageCodec.opFromJson(entry.op), entry.revision)
                            handler.applyRemoteEdit(m.path, local)
                        }
                    }
                } catch (e: RuntimeException) {
                    forceResync(m.path, doc, false)
                    return
                }
                // Caught up: what is still in flight never reached the host. Send it again, in order.
                catchingUp -= m.path
                doc.inFlightForResend().forEach { sendDocOp(m.path, it) }
                releaseHeldPresence()
            }
            is Message.RemoteOp -> {
                val doc = docs[m.path] ?: return
                // While recovering, the awaited snapshot or catch-up already contains this.
                if (!doc.isSynced || m.path in catchingUp) return
                try {
                    val local = doc.applyServer(MessageCodec.opFromJson(m.op), m.revision)
                    handler.applyRemoteEdit(m.path, local)
                } catch (e: RuntimeException) {
                    handler.onLog("Resynchronising ${m.path}: ${e.message}")
                    forceResync(m.path, doc, false)
                }
            }
            is Message.OpAck -> {
                val doc = docs[m.path] ?: return
                if (doc.firstInFlightOpId != m.opId) return // duplicate acknowledgement
                if (m.path in catchingUp) return // covered by the awaited snapshot or catch-up
                try {
                    doc.acknowledge(m.opId, m.revision)
                } catch (e: RuntimeException) {
                    forceResync(m.path, doc, false)
                }
            }
            is Message.OpRejected -> {
                val doc = docs[m.path] ?: return
                if (m.path in catchingUp) { // already recovering; a refusal still drops our edits
                    if (m.reason == "forbidden") discardOnResync += m.path
                    return
                }
                handler.onLog("Edit to ${m.path} was refused (${m.reason}); resynchronising")
                forceResync(m.path, doc, discard = m.reason == "forbidden")
            }
            is Message.DocumentUnavailable -> {
                docs.remove(m.path)
                openPaths -= m.path
                handler.onDocumentUnavailable(m.path, m.reason)
            }
            is Message.DocumentSaved -> if (docs.containsKey(m.path)) handler.onDocumentSaved(m.path)
            else -> Unit
        }
    }

    private fun newOpPrefix(): String = "${participantId.ifEmpty { "p" }}.${randomToken(6)}"
}
