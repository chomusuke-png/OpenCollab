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
import dev.opencollab.core.files.ProjectDirMacro
import dev.opencollab.core.protocol.ChatEntry
import dev.opencollab.core.protocol.ConsoleKind
import dev.opencollab.core.protocol.ConsoleStream
import dev.opencollab.core.protocol.Features
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.RunTarget
import dev.opencollab.core.session.RunRefusal
import dev.opencollab.core.session.RunRequestInfo
import java.util.concurrent.ConcurrentHashMap
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.ParticipantInfo
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.protocol.Permission
import dev.opencollab.core.session.JoinRequestInfo
import dev.opencollab.plugin.ui.permissionName
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
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
            /** A new invitation (its own token, with the expiry and use limit of the settings). */
            private val makeInvite: (Role) -> String,
            val endpoints: List<String>,
            val fingerprint: String,
            val consoles: HostConsoleSharing,
            /** The debugger, breakpoints, VCS status and code style shown to guests; null when not shared. */
            val ide: HostIdeSharing?,
        ) : Mode
        {
            /** Each read creates a new invitation; invitations can be revoked all at once. */
            val editorInvite: String get() = makeInvite(Role.EDITOR)
            val viewerInvite: String get() = makeInvite(Role.VIEWER)
        }
        class Joined(val client: CollabClient, val bridge: IdeBridge, val project: String) : Mode
    }

    fun interface Listener {
        fun changed()
    }

    private val log = logger<CollabSessionService>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val logLines = CopyOnWriteArrayList<String>()
    /** A console of the host as a guest sees it: output kept in order, with this guest's paths. */
    class SharedConsole(val id: String, val title: String, val requestedBy: String?, val kind: ConsoleKind = ConsoleKind.RUN) {
        /** Guarded by itself. */
        val parts = ArrayList<Pair<ConsoleStream, String>>()
        internal var chars = 0
        @Volatile var finished = false
            internal set
        @Volatile var exitCode: Int? = null
            internal set
    }

    /** Console events for the UI, delivered on the EDT in order. */
    interface ConsoleListener {
        fun started(console: SharedConsole) {}
        fun output(console: SharedConsole, stream: ConsoleStream, text: String) {}
        /** Test events of a test run (IntelliJ test service messages). */
        fun tests(console: SharedConsole, text: String) {}
        fun ended(console: SharedConsole) {}
        fun removed(console: SharedConsole) {}
    }

    private val consoles = LinkedHashMap<String, SharedConsole>() // guarded by itself
    private val consoleListeners = CopyOnWriteArrayList<ConsoleListener>()
    @Volatile private var projectDir: ProjectDirMacro? = null
    private var guestRuns: GuestRunMirror? = null
    /** Guest: the host's debugger, breakpoints, VCS status and code style. */
    @Volatile var hostIde: GuestIdeMirror? = null
        private set
    /** What the host lets guests ask to run (empty while hosting or idle). */
    @Volatile var runTargets: List<RunTarget> = emptyList()
        private set
    /** Our open run requests: request id → target name. */
    private val runRequests = ConcurrentHashMap<String, String>()

    fun consoles(): List<SharedConsole> = synchronized(consoles) { consoles.values.toList() }

    fun addConsoleListener(listener: ConsoleListener, parent: Disposable) {
        consoleListeners += listener
        Disposer.register(parent) { consoleListeners -= listener }
    }

    private fun onConsoleEdt(block: (ConsoleListener) -> Unit) =
        ApplicationManager.getApplication().invokeLater({ consoleListeners.forEach(block) }, ModalityState.any())

    private fun clearConsoles() {
        val old = synchronized(consoles) { consoles.values.toList().also { consoles.clear() } }
        old.forEach { c -> onConsoleEdt { it.removed(c) } }
        runTargets = emptyList()
        runRequests.clear()
    }

    /** Guest: asks the host to run [target]; the host's user decides. False if it could not be sent. */
    fun requestRun(target: RunTarget): Boolean {
        val joined = mode as? Mode.Joined ?: return false
        val id = joined.client.requestRun(target.id) ?: return false
        runRequests[id] = target.name
        appendLog(message("console.request.sent", target.name))
        return true
    }

    /** Guest: whether the host accepts command requests from us (an editor, host supports it). */
    val canRequestCommands: Boolean
        get() = (mode as? Mode.Joined)?.client?.let { Features.COMMANDS in it.sharedFeatures && it.role.canEdit && it.may(Permission.COMMANDS) } ?: false

    /** Guest: may ask the host to run its configurations or build. */
    val canRequestRuns: Boolean
        get() = (mode as? Mode.Joined)?.client?.let { it.role.canEdit && it.may(Permission.RUN) } ?: false

    /** Guest: may write in the chat. */
    val canChat: Boolean
        get() = (mode as? Mode.Joined)?.client?.may(Permission.CHAT) ?: true

    /** Guest: asks the host to run [command] in a shell in its project folder; the host's user decides. */
    fun requestCommand(command: String): Boolean {
        val joined = mode as? Mode.Joined ?: return false
        val id = joined.client.requestCommand(command) ?: return false
        runRequests[id] = command.trim()
        appendLog(message("console.request.sent", command.trim()))
        return true
    }

    /** Guest: asks the host to add or remove a line breakpoint. */
    fun requestBreakpoint(path: String, line: Int, add: Boolean): Boolean =
        (mode as? Mode.Joined)?.client?.requestBreakpoint(path, line, add) ?: false

    /** Lets helpers refresh the panel and write to the activity log. */
    internal fun changed() = fire()
    internal fun logLine(line: String) = appendLog(line)

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
    /** Participant this IDE is following, if any. */
    @Volatile var following: String? = null
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

    /** Who is typing a chat message: participant id → when that expires unless refreshed. */
    private val typing = ConcurrentHashMap<String, Long>()

    /** Names of the other participants typing right now, in list order. */
    fun typingNames(): List<String> {
        val now = System.currentTimeMillis()
        return participants.filter { p -> (typing[p.id] ?: 0) > now && p.id != ownParticipantId() }.map { it.displayName }
    }

    /** Tells the others whether we are typing in the chat (the chat panel refreshes it while typing). */
    fun setChatTyping(isTyping: Boolean) {
        when (val m = mode) {
            is Mode.Hosting -> m.bridge.client.setChatTyping(isTyping)
            is Mode.Joined -> m.client.setChatTyping(isTyping)
            else -> Unit
        }
    }

    /** Sends a chat message to the session; false if it could not be sent (not connected, empty, too long). */
    fun sendChat(text: String): Boolean = when (val m = mode) {
        is Mode.Hosting -> m.bridge.client.sendChat(text)
        is Mode.Joined -> m.client.sendChat(text)
        else -> false
    }

    private fun fire() = ApplicationManager.getApplication().invokeLater({ listeners.forEach { it.changed() } }, ModalityState.any())

    private fun appendLog(line: String) {
        val stamped = "${java.time.LocalTime.now().withNano(0)}  $line"
        logLines += stamped
        while (logLines.size > 300) logLines.removeAt(0)
        log.info("OpenCollab: $line")
        fire()
    }

    /**
     * Says something on the OpenCollab tool window (the Notifications list is kept for people asking
     * to join or to run something). It can be seen on a stream or shared screen: addresses are
     * hidden unless shown in the settings.
     */
    private fun notify(message: String, type: NotificationType = NotificationType.INFORMATION, title: String = "") {
        val shown = OpenCollabSettings.getInstance().shown(message)
        val body = (if (title.isEmpty()) "" else "<b>$title</b><br>") + shown.replace("\n", "<br>")
        val kind = when (type) {
            NotificationType.ERROR -> com.intellij.openapi.ui.MessageType.ERROR
            NotificationType.WARNING -> com.intellij.openapi.ui.MessageType.WARNING
            else -> com.intellij.openapi.ui.MessageType.INFO
        }
        dev.opencollab.plugin.ui.PanelNotice.show(project, body, kind)
    }

    private fun projectRoot(): Path {
        val base = project.basePath ?: project.guessProjectDir()?.path ?: throw IOException(message("error.no.basedir"))
        return Path.of(base)
    }

    /** Reasons the host sends in English, in the user's language when we know them. */
    private fun hostReason(detail: String): String = when (detail) {
        "Host ended the session" -> message("reason.host.ended")
        "Removed by host" -> message("reason.removed")
        "The host did not let you join" -> message("reason.not.let.in")
        "The host did not answer your request to join" -> message("reason.no.answer")
        else -> detail.removePrefix("Host refused the connection: ").let { if (it != detail) hostReason(it) else detail }
    }

    private fun bridgeListener() = object : BridgeListener {
        override fun onStateChanged(state: ClientState, detail: String) {
            val reasonText = hostReason(detail)
            if (mode is Mode.Joined) {
                status = when (state) {
                    ClientState.CONNECTED -> message("status.connected", (mode as Mode.Joined).project)
                    ClientState.DISCONNECTED -> message("status.disconnected", reasonText)
                    ClientState.SYNCING -> message("status.syncing")
                    ClientState.RECONNECTING -> message("status.reconnecting", reasonText)
                    ClientState.CONNECTING -> message("status.connecting", (mode as Mode.Joined).project)
                }
                if (state == ClientState.CONNECTED) joinedOnce = true
                if (state == ClientState.DISCONNECTED) {
                    appendLog(message("status.disconnected", reasonText))
                    val hint = if (!joinedOnce) connectionHint(reasonText) else null
                    hint?.let(::appendLog)
                    notify(listOfNotNull(message("notify.disconnected", reasonText), hint).joinToString("\n\n"), NotificationType.WARNING)
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
        override fun onChatTyping(participantId: String, isTyping: Boolean) {
            if (isTyping) typing[participantId] = System.currentTimeMillis() + TYPING_EXPIRES_MS else typing.remove(participantId)
            fire()
        }
        override fun onChat(entry: ChatEntry) {
            if (mode == Mode.Idle) return
            typing.remove(entry.participantId)
            chatEntries += entry
            while (chatEntries.size > MAX_CHAT_KEPT) chatEntries.removeAt(0)
            if (!chatOnScreen && entry.participantId != ownParticipantId()) unreadChat++
            fire()
        }
        override fun onConsoleStarted(started: Message.ConsoleStarted) {
            if (mode !is Mode.Joined) return
            val console = SharedConsole(started.consoleId, started.title, started.requestedBy, started.kind)
            val dropped = synchronized(consoles) {
                consoles[console.id] = console
                generateSequence { if (consoles.size > MAX_CONSOLES) consoles.remove(consoles.keys.first()) else null }.toList()
            }
            dropped.forEach { c -> onConsoleEdt { it.removed(c) } }
            onConsoleEdt { it.started(console) }
        }
        override fun onConsoleOutput(output: Message.ConsoleOutput) {
            if (mode !is Mode.Joined) return
            val console = synchronized(consoles) { consoles[output.consoleId] } ?: return
            val text = projectDir?.reveal(output.text) ?: output.text
            synchronized(console.parts) {
                if (console.chars > MAX_CONSOLE_CHARS) return
                console.parts += output.stream to text
                console.chars += text.length
            }
            onConsoleEdt { it.output(console, output.stream, text) }
        }
        override fun onConsoleEnded(ended: Message.ConsoleEnded) {
            val console = synchronized(consoles) { consoles[ended.consoleId] } ?: return
            console.exitCode = ended.exitCode
            console.finished = true
            onConsoleEdt { it.ended(console) }
        }
        override fun onHostIdeState(message: Message) {
            if (mode !is Mode.Joined) return
            if (message is Message.ConsoleTests) {
                val console = synchronized(consoles) { consoles[message.consoleId] } ?: return
                val text = projectDir?.reveal(message.text) ?: message.text
                onConsoleEdt { it.tests(console, text) }
            } else {
                hostIde?.onState(message)
            }
        }
        override fun onRunTargets(targets: List<RunTarget>) {
            if (mode !is Mode.Joined) return
            runTargets = targets
            fire()
        }
        override fun onRunResponse(response: Message.RunResponse) {
            val name = runRequests.remove(response.requestId) ?: return
            // Notification content is HTML: a command may contain <, > or &.
            val shown = StringUtil.escapeXmlEntities(name)
            if (response.accepted) {
                appendLog(message("console.request.accepted", name))
                ApplicationManager.getApplication().invokeLater({
                    notify(message("console.request.accepted", shown), title = message("console.accepted.title"))
                }, ModalityState.any())
            } else {
                val why = message("console.refused.${response.reason ?: RunRefusal.REFUSED}")
                appendLog(message("console.request.refused", name, why))
                ApplicationManager.getApplication().invokeLater({
                    notify(message("console.request.refused", shown, why), NotificationType.WARNING, message("console.refused.title"))
                }, ModalityState.any())
            }
        }
        override fun onFollowingChanged(participantId: String?) {
            following = participantId
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
        override fun onJoinPending(timeoutMillis: Long) {
            status = message("status.waiting.approval")
            appendLog(message("log.waiting.approval", timeoutMillis / 1000))
            fire()
        }
        override fun onPermissionsChanged(permissions: Set<String>) {
            val names = Permission.ALL.filter { it in permissions }.joinToString { permissionName(it) }
            appendLog(message("log.permissions.now", names.ifEmpty { message("permission.none") }))
            fire()
        }
        override fun onLog(message: String) = appendLog(message)
    }

    // ---- Hosting ------------------------------------------------------------------------

    /** Starts hosting. Must be called on the EDT; network setup runs in the background. */
    fun startHosting() {
        if (mode != Mode.Idle) return
        mode = Mode.Starting
        clearConsoles()
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
        val features = buildSet {
            if (s.shareConsoles) add(Features.TESTS)
            if (s.shareIdeState) addAll(listOf(Features.DEBUG, Features.VCS, Features.CODE_STYLE))
            if (s.allowCommandRequests) add(Features.COMMANDS)
            add(Features.JOIN_APPROVAL)
            add(Features.PERMISSIONS)
        }
        val inviteTtl = s.inviteExpiryMinutes.coerceAtLeast(0).toLong() * 60_000
        val config = HostConfig(projectName = project.name, features = features, approveJoins = s.approveJoins, inviteTtlMillis = inviteTtl)
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
        var consoleSharing: HostConsoleSharing? = null
        var ideSharing: HostIdeSharing? = null
        val session = HostSession(config, files, documentSource, sched, object : HostSessionListener {
            override fun onParticipantsChanged(participants: List<ParticipantInfo>) {
                this@CollabSessionService.participants = participants
                fire()
            }
            override fun onLog(message: String) = appendLog(message)
            override fun onRunRequest(request: RunRequestInfo) {
                consoleSharing?.onRunRequest(request)
            }
            override fun onBreakpointRequest(participantId: String, path: String, line: Int, add: Boolean) {
                val who = participants.firstOrNull { it.id == participantId }?.displayName ?: "?"
                ideSharing?.onBreakpointRequest(who, path, line, add)
            }
            override fun onJoinRequest(request: JoinRequestInfo) {
                joinRequests += request
                fire()
                askToLetIn(request)
            }
            override fun onJoinRequestClosed(requestId: String) {
                joinRequests.removeIf { it.requestId == requestId }
                fire()
                joinPrompts.remove(requestId)?.let { ApplicationManager.getApplication().invokeLater({ it.expire() }, ModalityState.any()) }
            }
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
            makeInvite = { role -> invite(role, session.createInvite(role, inviteTtl, maxUses = if (s.singleUseInvites) 1 else 0)) },
            endpoints = endpoints + listOfNotNull(room?.let { "relay $relayUrl (room ${it.take(6)}…)" }),
            fingerprint = identity.fingerprint,
            consoles = HostConsoleSharing(project, root, session, s.shareConsoles) { appendLog(it) }.also { consoleSharing = it },
            ide = if (s.shareIdeState) HostIdeSharing(project, root, rules, session) { appendLog(it) }.also { ideSharing = it } else null,
        )
        ApplicationManager.getApplication().invokeAndWait({
            bridge.install()
            hosting.consoles.install()
            hosting.ide?.install()
        }, ModalityState.any())
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
            clientId = OpenCollabSettings.getInstance().clientId(),
        )
        bridge.client = client
        bridge.install()
        clearConsoles()
        projectDir = ProjectDirMacro(root)
        // The host's runs and builds show in this IDE's Run and Build tool windows.
        guestRuns = GuestRunMirror(project, this, root).also { addConsoleListener(it, it) }
        // The host's debugger position, breakpoints, branch and changes, and code style.
        hostIde = GuestIdeMirror(project, root, this).also { it.install() }
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

    /** Host: replaces what a guest may do ([Permission]). */
    fun setPermissions(participantId: String, permissions: Set<String>) {
        (mode as? Mode.Hosting)?.session?.setPermissions(participantId, permissions)
    }

    /** Host: every invitation handed out so far stops working; people in the session stay. */
    fun revokeInvites() {
        val hosting = mode as? Mode.Hosting ?: return
        hosting.session.revokeInvites()
        appendLog(message("log.invites.revoked"))
    }

    /** Host: people waiting to be let in, oldest first (also listed in the tool window, in case a notification is missed). */
    val joinRequests = CopyOnWriteArrayList<JoinRequestInfo>()

    /** Host: lets someone in or refuses them (from the notification or the tool window). */
    fun answerJoin(requestId: String, accepted: Boolean) {
        val hosting = mode as? Mode.Hosting ?: return
        joinPrompts.remove(requestId)?.expire()
        hosting.session.answerJoin(requestId, accepted)
    }

    /** Open "let in?" prompts by join request id. */
    private val joinPrompts = ConcurrentHashMap<String, Notification>()

    /** "tls(direct:/192.168.1.12:49442)" → "192.168.1.12"; through the relay the address is the relay's, so say that. */
    internal fun shownAddress(description: String): String {
        val inner = description.removePrefix("tls(").removeSuffix(")")
        if (inner.startsWith("relay")) return message("join.request.via.relay")
        val address = inner.substringAfterLast('/')
        return when {
            address.startsWith("[") -> address.substringBefore("]") + "]" // IPv6 with port
            address.count { it == ':' } == 1 -> address.substringBefore(':')
            else -> address
        }
    }

    /** Session thread: someone with a valid invitation waits to be let in. */
    private fun askToLetIn(request: JoinRequestInfo) {
        ApplicationManager.getApplication().invokeLater({
            val hosting = mode as? Mode.Hosting ?: return@invokeLater
            val text = message("join.request", StringUtil.escapeXmlEntities(request.displayName), roleName(request.role), StringUtil.escapeXmlEntities(OpenCollabSettings.getInstance().shown(shownAddress(request.address))))
            val notification = NotificationGroupManager.getInstance().getNotificationGroup("OpenCollab")
                .createNotification(message("join.request.title"), text, NotificationType.WARNING)
            notification.addAction(NotificationAction.createSimpleExpiring(message("join.request.accept")) { answerJoin(request.requestId, true) })
            notification.addAction(NotificationAction.createSimpleExpiring(message("join.request.reject")) { answerJoin(request.requestId, false) })
            joinPrompts[request.requestId] = notification
            notification.notify(project)
        }, ModalityState.any())
    }

    fun kick(participantId: String) {
        (mode as? Mode.Hosting)?.session?.kick(participantId)
    }

    /** How the session is doing, for status colors: null when not in a session. */
    enum class Health { OK, BUSY, DOWN }

    fun health(): Health? = when (val m = mode) {
        Mode.Idle -> null
        Mode.Starting -> Health.BUSY
        is Mode.Hosting -> Health.OK
        is Mode.Joined -> when (m.client.state) {
            ClientState.CONNECTED -> Health.OK
            ClientState.DISCONNECTED -> Health.DOWN
            else -> Health.BUSY
        }
    }

    /** "01:23:45" since the host started the session, or null when not in one. */
    fun sessionClock(): String? {
        val started = sessionStartedAt ?: return null
        val seconds = ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    private fun bridge(): IdeBridge? = when (val m = mode) {
        is Mode.Hosting -> m.bridge
        is Mode.Joined -> m.bridge
        else -> null
    }

    /** Starts following; false if they have no shared file open yet (following still starts). EDT. */
    fun follow(participantId: String): Boolean = bridge()?.startFollowing(participantId) ?: false

    fun stopFollowing() {
        bridge()?.stopFollowing()
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
                Disposer.dispose(m.consoles)
                m.ide?.let(Disposer::dispose)
                m.bridge.client.close()
                // Guests are told the session ended before the transports go down, or they would
                // only see a dropped connection and keep reconnecting. Off the EDT: the session
                // thread may be waiting for the EDT, and closing waits for it a little.
                ApplicationManager.getApplication().executeOnPooledThread {
                    m.session.close()
                    m.transports.forEach { runCatching { it.close() } }
                }
                Disposer.dispose(m.bridge)
                appendLog(message("log.session.ended"))
            }
            is Mode.Joined -> {
                guestRuns?.let(Disposer::dispose)
                guestRuns = null
                hostIde?.let(Disposer::dispose)
                hostIde = null
                m.client.close()
                Disposer.dispose(m.bridge)
                appendLog(message("log.left"))
            }
            else -> Unit
        }
        scheduler?.close()
        scheduler = null
        participants = emptyList()
        joinRequests.clear()
        myRole = null
        sessionStartedAt = null
        latencies = emptyMap()
        following = null
        typing.clear()
        runTargets = emptyList()
        runRequests.clear()
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
        /** "Is typing" disappears this long after the last refresh (senders refresh every 2 s). */
        private const val TYPING_EXPIRES_MS = 5_000L
        /** Consoles a guest keeps, and characters of each (beyond that, output is not stored). */
        private const val MAX_CONSOLES = 5
        private const val MAX_CONSOLE_CHARS = 2_000_000

        fun getInstance(project: Project): CollabSessionService = project.getService(CollabSessionService::class.java)
    }
}
