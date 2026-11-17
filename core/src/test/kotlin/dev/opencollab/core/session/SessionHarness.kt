package dev.opencollab.core.session

import dev.opencollab.core.files.MirrorWorkspace
import dev.opencollab.core.files.ProjectFiles
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.ot.TextOperation
import dev.opencollab.core.protocol.ChatEntry
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.transport.Connector
import dev.opencollab.core.transport.InMemoryTransport
import dev.opencollab.core.util.SerialExecutor
import dev.opencollab.core.util.SystemScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** A fake IDE: one "EDT" thread holding editor document texts. */
class FakeIde(name: String) : ClientHandler {
    val edt = SerialExecutor("edt-$name")
    val texts = HashMap<String, String>() // edt only
    val states = CopyOnWriteArrayList<ClientState>()
    val stateDetails = CopyOnWriteArrayList<String>()
    val log = CopyOnWriteArrayList<String>()
    val presence = ConcurrentHashMap<String, Message.PresenceBroadcast>()
    val unavailable = CopyOnWriteArrayList<String>()
    val failedFileRequests = CopyOnWriteArrayList<Pair<FileMutation, String>>()
    val filesChanged = CopyOnWriteArrayList<String>()
    @Volatile var latencies: Map<String, Int> = emptyMap()
    @Volatile var welcome: Message.Welcome? = null
    lateinit var client: CollabClient

    override fun onWelcome(welcome: Message.Welcome) { this.welcome = welcome }
    override fun onLatencies(millis: Map<String, Int>) { latencies = millis }
    val chat = CopyOnWriteArrayList<ChatEntry>()
    override fun onChat(entry: ChatEntry) { chat += entry }
    override fun onChatHistory(entries: List<ChatEntry>) { chat.clear(); chat.addAll(entries) }

    override fun onStateChanged(state: ClientState, detail: String) { states += state; stateDetails += detail }
    override fun applyRemoteEdit(path: String, op: TextOperation) { texts[path] = op.apply(texts.getValue(path)) }
    override fun onDocumentUnavailable(path: String, reason: String) { unavailable += path }
    override fun onPresence(presence: Message.PresenceBroadcast) { this.presence[presence.participantId] = presence }
    override fun onParticipantLeft(participantId: String) { presence.remove(participantId) }
    override fun onFileRequestFailed(mutation: FileMutation, error: String) { failedFileRequests += mutation to error }
    override fun onFilesChanged(paths: Collection<String>, initial: Boolean) { filesChanged += paths }
    override fun onLog(message: String) { log += message }

    fun <T> onEdt(block: () -> T): T {
        val f = CompletableFuture<T>()
        edt.execute { f.complete(block()) }
        return f.get(10, TimeUnit.SECONDS)
    }

    /** Opens [path] in the "editor" with [diskText] as its current content. */
    fun open(path: String, diskText: String) = onEdt {
        texts[path] = diskText
        client.openDocument(path, diskText)
    }

    /** Types: replaces [deleteLength] chars at [offset] by [insert]. */
    fun type(path: String, offset: Int, insert: String, deleteLength: Int = 0) = onEdt {
        val text = texts.getValue(path)
        val op = TextOperation.replace(text.length, offset.coerceIn(0, text.length), deleteLength.coerceAtMost(text.length - offset.coerceIn(0, text.length)), insert)
        texts[path] = op.apply(text)
        client.localEdit(path, op)
    }

    fun text(path: String): String? = onEdt { texts[path] }
}

class Harness(val hostRoot: Path, rules: SharingRules = SharingRules(), config: HostConfig = HostConfig("demo")) : AutoCloseable {
    val scheduler = SystemScheduler()
    val server = InMemoryTransport.Server()
    val hostIde = FakeIde("host")
    val files = ProjectFiles(hostRoot, rules)
    val links = CopyOnWriteArrayList<InMemoryTransport.MemoryConnection>()
    private val clients = CopyOnWriteArrayList<CollabClient>()

    /** Host documents: unsaved editor text wins over disk; pre-registered with the host bridge. */
    val session: HostSession = HostSession(
        config,
        files,
        DocumentSource { path ->
            val f = CompletableFuture<String?>()
            hostIde.edt.execute {
                val text = hostIde.texts[path] ?: files.read(path)?.toString(Charsets.UTF_8)
                if (text != null) {
                    hostIde.texts[path] = text
                    hostIde.client.preRegisterDocument(path, text)
                }
                f.complete(text)
            }
            f.get(10, TimeUnit.SECONDS)
        },
        scheduler,
    )

    init {
        server.start { session.accept(it) }
        hostIde.client = CollabClient(server.connector("host-bridge"), "Host", session.config.hostToken, hostIde, hostIde.edt, scheduler, mirror = null)
        clients += hostIde.client
        hostIde.client.start()
        awaitState(hostIde, ClientState.CONNECTED)
    }

    fun guest(
        name: String,
        mirrorRoot: Path,
        token: String = session.config.editorToken,
        connector: Connector? = null,
        presenceIntervalMillis: Long = 20,
    ): FakeIde {
        val ide = FakeIde(name)
        val conn = connector ?: server.connector(name) { links += it }
        ide.client = CollabClient(
            conn, name, token, ide, ide.edt, scheduler, MirrorWorkspace(mirrorRoot),
            ReconnectPolicy(initialDelayMillis = 100, maxDelayMillis = 400, maxAttempts = 20),
            presenceIntervalMillis = presenceIntervalMillis,
        )
        clients += ide.client
        ide.client.start()
        return ide
    }

    override fun close() {
        clients.forEach { it.close() }
        session.close()
        scheduler.close()
    }

    companion object {
        fun awaitState(ide: FakeIde, state: ClientState, timeoutMs: Long = 10_000) =
            eventually(timeoutMs) { ide.client.state == state }

        fun eventually(timeoutMs: Long = 10_000, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(10)
            }
            throw AssertionError("Condition not met within ${timeoutMs}ms")
        }

        fun write(root: Path, rel: String, content: String) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, content)
        }
    }
}
