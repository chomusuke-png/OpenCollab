package dev.opencollab.plugin.session

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import dev.opencollab.core.files.FileAccessDenied
import dev.opencollab.core.files.MirrorWorkspace
import dev.opencollab.core.files.ProjectFiles
import dev.opencollab.core.files.SafePath
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import dev.opencollab.core.protocol.ChatEntry
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.ParticipantInfo
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.session.ClientState
import dev.opencollab.core.session.CollabClient
import dev.opencollab.core.session.DocumentSource
import dev.opencollab.core.session.HostConfig
import dev.opencollab.core.session.HostSession
import dev.opencollab.core.session.HostSessionListener
import dev.opencollab.core.session.ReconnectPolicy
import dev.opencollab.core.transport.Connector
import dev.opencollab.core.transport.HostTransport
import dev.opencollab.core.transport.InMemoryTransport
import dev.opencollab.core.util.SystemScheduler
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.bridge.BridgeListener
import dev.opencollab.plugin.ui.roleName
import dev.opencollab.plugin.bridge.IdeBridge
import dev.opencollab.plugin.settings.OpenCollabSettings
import dev.opencollab.transport.DirectHostTransport
import dev.opencollab.transport.Invite
import dev.opencollab.transport.NetworkHints
import dev.opencollab.transport.PortForwarding
import dev.opencollab.transport.RelayHostTransport
import dev.opencollab.transport.TlsIdentity
import dev.opencollab.transport.TrustMode
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-scoped session lifecycle: at most one session (hosting or joined) per project.
 * Disposed with the project, which ends the session.
 */
@Service(Service.Level.PROJECT)
class CollabSessionService(private val project: Project) : Disposable {
    sealed interface Mode {
        data object Idle : Mode
        data object Starting : Mode
        class Hosting(
            val session: HostSession,
            val transports: List<HostTransport>,
            val bridge: IdeBridge,
            val editorInvite: String,
            val viewerInvite: String,
            val endpoints: List<String>,
            val fingerprint: String,
        ) : Mode
        class Joined(val client: CollabClient, val bridge: IdeBridge, val project: String) : Mode
    }

    fun interface Listener {
        fun changed()
    }

    private val log = logger<CollabSessionService>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val logLines = CopyOnWriteArrayList<String>()
    /** Chat of the current (or last) session, oldest first; kept after leaving so it can still be read. */
    private val chatEntries = CopyOnWriteArrayList<ChatEntry>()
    private var scheduler: SystemScheduler? = null

    @Volatile var mode: Mode = Mode.Idle
        private set
    @Volatile var status: String = message("status.idle")
        private set
    @Volatile var participants: List<ParticipantInfo> = emptyList()
        private set
    @Volatile var myRole: Role? = null
        private set
    /** Wall-clock time the session started (from the host's session age), or null when not in one. */
    @Volatile var sessionStartedAt: Long? = null
        private set
    /** Round-trip time to the host per participant id, in ms. */
    @Volatile var latencies: Map<String, Int> = emptyMap()
        private set
    /** Messages from others that arrived while the chat was not on screen. */
    @Volatile var unreadChat: Int = 0
        private set
    /** Set by the chat panel: true while the chat is actually visible; reading it clears the unread count. */
    @Volatile var chatOnScreen: Boolean = false
        set(value) {
            field = value
            if (value && unreadChat != 0) {
                unreadChat = 0
                fire()
            }
        }


    fun addListener(l: Listener, parent: Disposable) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    fun log(): List<String> = logLines.toList()

    fun clearLog() {
        logLines.clear()
        fire()
    }

    fun chat(): List<ChatEntry> = chatEntries.toList()

    /** Sends a chat message to the session; false if it could not be sent (not connected, empty, too long). */
    fun sendChat(text: String): Boolean = when (val m = mode) {
        is Mode.Hosting -> m.bridge.client.sendChat(text)
        is Mode.Joined -> m.client.sendChat(text)
        else -> false
    }

    /** Pops up someone else's message when the OpenCollab tool window is hidden. EDT. */
    private fun notifyChat(entry: ChatEntry) {
        if (project.isDisposed || entry.participantId == ownParticipantId()) return
        if (ToolWindowManager.getInstance(project).getToolWindow("OpenCollab")?.isVisible == true) return
        // Notification content is HTML: escape what other participants typed.
        val text = StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(entry.text, 200, 0))
        NotificationGroupManager.getInstance().getNotificationGroup("OpenCollab")
            .createNotification(StringUtil.escapeXmlEntities(entry.displayName), text, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun fire() = ApplicationManager.getApplication().invokeLater({ listeners.forEach { it.changed() } }, ModalityState.any())

    private fun appendLog(line: String) {
        val stamped = "${java.time.LocalTime.now().withNano(0)}  $line"
        logLines += stamped
        while (logLines.size > 300) logLines.removeAt(0)
        log.info("OpenCollab: $line")
        fire()
    }

    private fun notify(message: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup("OpenCollab").createNotification(message, type).notify(project)
    }

    private fun projectRoot(): Path {
        val base = project.basePath ?: project.guessProjectDir()?.path ?: throw IOException(message("error.no.basedir"))
        return Path.of(base)
    }

    private fun bridgeListener() = object : BridgeListener {
        override fun onStateChanged(state: ClientState, detail: String) {
            if (mode is Mode.Joined) {
                status = when (state) {
                    ClientState.CONNECTED -> message("status.connected", (mode as Mode.Joined).project)
                    ClientState.DISCONNECTED -> message("status.disconnected", detail)
                    ClientState.SYNCING -> message("status.syncing")
                    ClientState.RECONNECTING -> message("status.reconnecting", detail)
                    ClientState.CONNECTING -> message("status.connecting", (mode as Mode.Joined).project)
                }
                if (state == ClientState.CONNECTED) joinedOnce = true
                if (state == ClientState.DISCONNECTED) {
                    appendLog(message("status.disconnected", detail))
                    val hint = if (!joinedOnce) connectionHint(detail) else null
                    hint?.let(::appendLog)
                    notify(listOfNotNull(message("notify.disconnected", detail), hint).joinToString("\n\n"), NotificationType.WARNING)
                    // The host ended the session, removed us, or stayed unreachable: leave on our own
                    // so the panel is ready to host or join again. The status keeps the reason.
                    val ended = mode
                    val reason = status
                    ApplicationManager.getApplication().invokeLater({ if (mode === ended) leave(finalStatus = reason) }, ModalityState.any())
                }
                fire()
            }
        }
        // Ignored once Idle: a message still in flight after leaving must not bring them back.
        override fun onSessionAge(sessionAgeMillis: Long) {
            if (mode == Mode.Idle) return
            sessionStartedAt = System.currentTimeMillis() - sessionAgeMillis
            fire()
        }
        override fun onLatencies(millis: Map<String, Int>) {
            if (mode == Mode.Idle) return
            latencies = millis
            fire()
        }
        override fun onChat(entry: ChatEntry) {
            if (mode == Mode.Idle) return
            chatEntries += entry
            while (chatEntries.size > MAX_CHAT_KEPT) chatEntries.removeAt(0)
            if (!chatOnScreen && entry.participantId != ownParticipantId()) unreadChat++
            ApplicationManager.getApplication().invokeLater({ notifyChat(entry) }, ModalityState.any())
            fire()
        }
        override fun onChatHistory(entries: List<ChatEntry>) {
            if (mode == Mode.Idle) return
            chatEntries.clear()
            chatEntries.addAll(entries)
            fire()
        }
        override fun onParticipantsChanged(participants: List<ParticipantInfo>) {
            if (mode is Mode.Joined) { this@CollabSessionService.participants = participants; fire() }
        }
        override fun onRoleChanged(role: Role) {
            myRole = role
            appendLog(message("log.role.now", roleName(role)))
        }
        override fun onLog(message: String) = appendLog(message)
    }

    // ---- Hosting ------------------------------------------------------------------------

    /** Starts hosting. Must be called on the EDT; network setup runs in the background. */
    fun startHosting() {
        if (mode != Mode.Idle) return
        mode = Mode.Starting
        chatEntries.clear()
        unreadChat = 0
        status = message("status.starting")
        fire()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                doStartHosting()
            } catch (e: Exception) {
                log.warn("OpenCollab: could not start hosting", e)
                cleanupAfterFailure()
                status = message("status.could.not.start", e.message ?: "")
                appendLog(status)
                notify(message("notify.could.not.start", e.message ?: ""), NotificationType.ERROR)
            }
        }
    }

    private var startingTransports = mutableListOf<HostTransport>()

    private fun doStartHosting() {
        val settings = OpenCollabSettings.getInstance()
        val s = settings.state
        if (!s.enableDirect && !s.enableRelay) throw IOException(message("error.enable.transport"))
        val root = projectRoot()
        val rules = settings.sharingRules()
        val sched = SystemScheduler("opencollab-host-timer").also { scheduler = it }
        val identity = TlsIdentity.ephemeral()
        val config = HostConfig(projectName = project.name)
        val bridge = IdeBridge(project, root, isHost = true, listener = bridgeListener())

        val files = object : ProjectFiles(root, rules, bridge.writer) {
            override fun perform(m: FileMutation) {
                // Never overwrite a file the host is editing with unsaved changes.
                if (m.kind == dev.opencollab.core.protocol.FileOpKind.WRITE || m.kind == dev.opencollab.core.protocol.FileOpKind.DELETE) {
                    val vf = LocalFileSystem.getInstance().findFileByNioFile(SafePath.resolveInside(root, m.path))
                    if (vf != null && !vf.isDirectory && FileDocumentManager.getInstance().isFileModified(vf)) {
                        throw FileAccessDenied("The host has unsaved changes in ${m.path}")
                    }
                }
                super.perform(m)
            }
        }
        val documentSource = DocumentSource { path -> loadHostDocument(bridge, root, path) }
        val session = HostSession(config, files, documentSource, sched, object : HostSessionListener {
            override fun onParticipantsChanged(participants: List<ParticipantInfo>) {
                this@CollabSessionService.participants = participants
                fire()
            }
            override fun onLog(message: String) = appendLog(message)
        })

        val transports = mutableListOf<HostTransport>()
        startingTransports = transports
        val endpoints = mutableListOf<String>()
        var relayUrl: String? = null
        var room: String? = null
        if (s.enableDirect) {
            val bind = s.bindAddress.ifBlank { "0.0.0.0" }
            if (s.portForwarding) {
                if (s.port == 0) throw IOException(message("error.forwarding.port"))
                if (bind == "127.0.0.1" || bind == "::1" || bind == "localhost") throw IOException(message("error.forwarding.bind"))
            }
            val advertised = OpenCollabSettings.lines(s.advertisedHosts).ifEmpty { null }
            val direct = DirectHostTransport(bind, s.port, identity, advertised)
            direct.start(session::accept)
            transports += direct
            endpoints += direct.endpoints
            if (s.portForwarding) {
                val publicHost = s.publicAddress.trim().ifEmpty {
                    try {
                        PortForwarding.detectPublicIp(s.publicIpService)
                    } catch (e: Exception) {
                        throw IOException(message("error.forwarding.detect", e.message ?: ""), e)
                    }
                }
                val publicEndpoint = PortForwarding.endpoint(publicHost, s.externalPort, direct.boundPort)
                if (publicEndpoint !in endpoints) endpoints += publicEndpoint
                val external = if (s.externalPort == 0) direct.boundPort else s.externalPort
                appendLog(message("log.forwarding.endpoint", publicEndpoint, external, direct.boundPort))
            }
        }
        if (s.enableRelay) {
            if (s.relayUrl.isBlank()) throw IOException(message("error.relay.url"))
            val trust = s.relayFingerprint.takeIf { it.isNotBlank() }?.let { TrustMode.PinnedRelay(it) } ?: TrustMode.SystemCertificateAuthorities
            val relay = RelayHostTransport(URI(s.relayUrl.trim()), settings.relaySecret, trust, identity) { appendLog(it) }
            try {
                relay.start(session::accept)
                transports += relay
                relayUrl = s.relayUrl.trim()
                room = relay.room
            } catch (e: IOException) {
                relay.close()
                if (transports.isEmpty()) throw e
                appendLog(message("log.relay.unavailable", e.message ?: ""))
            }
        }

        // The host's own IDE joins its session through an in-process connection.
        val (bridgeEnd, sessionEnd) = InMemoryTransport.pair("host-ide", "host<-ide")
        session.accept(sessionEnd)
        val client = CollabClient(
            Connector { bridgeEnd }, s.displayName.ifBlank { "Host" }, config.hostToken, bridge, bridge.edtExecutor, sched,
            mirror = null, reconnectPolicy = null, presenceIntervalMillis = s.presenceIntervalMs.toLong(),
        )
        bridge.client = client

        fun invite(role: Role, token: String) = Invite(
            project = project.name, role = role, token = token, fingerprint = identity.fingerprint,
            direct = endpoints.toList(), relay = relayUrl, room = room,
            relayFingerprint = s.relayFingerprint.takeIf { it.isNotBlank() && relayUrl != null },
        ).encode()

        val hosting = Mode.Hosting(
            session, transports, bridge,
            editorInvite = invite(Role.EDITOR, config.editorToken),
            viewerInvite = invite(Role.VIEWER, config.viewerToken),
            endpoints = endpoints + listOfNotNull(room?.let { "relay $relayUrl (room ${it.take(6)}…)" }),
            fingerprint = identity.fingerprint,
        )
        ApplicationManager.getApplication().invokeAndWait({ bridge.install() }, ModalityState.any())
        client.start()
        mode = hosting
        myRole = Role.HOST
        status = message("status.hosting", project.name)
        appendLog(message("log.session.started", hosting.endpoints.joinToString()))
        appendLog(message("log.fingerprint", identity.fingerprint))
        fire()
    }

    /**
     * Host: returns the current text of [path] for sharing and registers it with the host's
     * editor bridge at revision 0, atomically on the EDT so no local keystroke is lost.
     */
    private fun loadHostDocument(bridge: IdeBridge, root: Path, path: String): String? {
        var text: String? = null
        ApplicationManager.getApplication().invokeAndWait({
            val target = try { SafePath.resolveInside(root, path) } catch (e: SecurityException) { return@invokeAndWait }
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target) ?: return@invokeAndWait
            if (file.isDirectory || file.fileType.isBinary) return@invokeAndWait
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@invokeAndWait
            val current = document.text
            if (!bridge.client.isDocumentOpen(path)) bridge.client.preRegisterDocument(path, current)
            text = current
        }, ModalityState.any())
        return text
    }

    // ---- Joining ------------------------------------------------------------------------

    /** Validates an invitation and the target folder; returns an error message or null. */
    /** Validates an invitation only; returns an error message or null. */
    fun checkInvite(inviteText: String): String? = try {
        Invite.decode(inviteText)
        null
    } catch (e: IllegalArgumentException) {
        e.message
    }

    fun checkCanJoin(inviteText: String): String? {
        try {
            Invite.decode(inviteText)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        val root = try { projectRoot() } catch (e: IOException) { return e.message }
        if (!MirrorWorkspace(root).canAdopt()) {
            return message("error.folder.not.empty")
        }
        return null
    }

    fun join(inviteText: String) {
        if (mode != Mode.Idle) return
        val invite = Invite.decode(inviteText)
        val root = projectRoot()
        val settings = OpenCollabSettings.getInstance().state
        val bridge = IdeBridge(project, root, isHost = false, listener = bridgeListener())
        val mirror = MirrorWorkspace(root, bridge.writer)
        if (!mirror.canAdopt()) throw IOException(message("error.folder.not.empty.short"))
        val sched = SystemScheduler("opencollab-guest-timer").also { scheduler = it }
        val client = CollabClient(
            invite.connector(), settings.displayName.ifBlank { "Guest" }, invite.token, bridge, bridge.edtExecutor, sched,
            // About two minutes of retries (1+2+4+8+16+30+30+30 s): enough for a network blip; after
            // that the host is considered gone and the guest leaves automatically.
            mirror, ReconnectPolicy(maxAttempts = 8), presenceIntervalMillis = settings.presenceIntervalMs.toLong(),
        )
        bridge.client = client
        bridge.install()
        chatEntries.clear()
        unreadChat = 0
        mode = Mode.Joined(client, bridge, invite.project)
        myRole = invite.role
        status = message("status.connecting", invite.project)
        appendLog(message("log.joining", invite.project, roleName(invite.role), invite.fingerprint.take(23)))
        joinedOnce = false
        joiningInvite = invite
        // Warn before the timeouts when the host advertised addresses of another private network.
        val foreign = NetworkHints.unreachableDirectEndpoints(invite)
        if (foreign.isNotEmpty() && invite.relay == null && foreign.size == invite.direct.size) {
            appendLog(message("hint.other.network", foreign.joinToString()))
        }
        client.start()
        fire()
    }

    @Volatile private var joinedOnce = false
    @Volatile private var joiningInvite: Invite? = null

    /** Explains a failed first connection in actionable terms (localized). */
    private fun connectionHint(detail: String): String? {
        val invite = joiningInvite ?: return null
        if (!detail.contains("timed out", ignoreCase = true) && !detail.contains("refused", ignoreCase = true) &&
            !detail.contains("unreachable", ignoreCase = true)
        ) return null
        val foreign = NetworkHints.unreachableDirectEndpoints(invite)
        // Endpoints that are neither on our subnet nor another private network: public / forwarded.
        val public = NetworkHints.publicEndpoints(invite)
        return when {
            public.isNotEmpty() && invite.relay == null -> {
                val first = java.net.URI(public.first())
                message("hint.forwarding.failed", first.host, first.port)
            }
            foreign.isNotEmpty() && invite.relay == null -> message("hint.other.network", foreign.joinToString())
            else -> message("hint.timeout")
        }
    }

    // ---- Participants -------------------------------------------------------------------

    fun setRole(participantId: String, role: Role) {
        (mode as? Mode.Hosting)?.session?.setRole(participantId, role)
    }

    fun kick(participantId: String) {
        (mode as? Mode.Hosting)?.session?.kick(participantId)
    }

    fun follow(participantId: String): Boolean = when (val m = mode) {
        is Mode.Hosting -> m.bridge.follow(participantId)
        is Mode.Joined -> m.bridge.follow(participantId)
        else -> false
    }

    fun ownParticipantId(): String? = when (val m = mode) {
        is Mode.Hosting -> m.bridge.client.participantId
        is Mode.Joined -> m.client.participantId
        else -> null
    }

    // ---- Leaving ------------------------------------------------------------------------

    /** Ends hosting or leaves the joined session. [finalStatus] replaces "Not in a session" (e.g. why we left). */
    fun leave(finalStatus: String? = null) {
        val m = mode
        // Idle before closing the client, so its final DISCONNECTED event is not reported as a
        // connection loss (and does not trigger another automatic leave).
        mode = Mode.Idle
        when (m) {
            is Mode.Hosting -> {
                m.bridge.client.close()
                m.session.close()
                m.transports.forEach { runCatching { it.close() } }
                Disposer.dispose(m.bridge)
                appendLog(message("log.session.ended"))
            }
            is Mode.Joined -> {
                m.client.close()
                Disposer.dispose(m.bridge)
                appendLog(message("log.left"))
            }
            else -> Unit
        }
        scheduler?.close()
        scheduler = null
        participants = emptyList()
        myRole = null
        sessionStartedAt = null
        latencies = emptyMap()
        status = finalStatus ?: message("status.idle")
        fire()
    }

    private fun cleanupAfterFailure() {
        startingTransports.forEach { runCatching { it.close() } }
        startingTransports = mutableListOf()
        scheduler?.close()
        scheduler = null
        mode = Mode.Idle
        fire()
    }

    override fun dispose() {
        if (mode != Mode.Idle) leave()
    }

    companion object {
        private const val MAX_CHAT_KEPT = 500

        fun getInstance(project: Project): CollabSessionService = project.getService(CollabSessionService::class.java)
    }
}
