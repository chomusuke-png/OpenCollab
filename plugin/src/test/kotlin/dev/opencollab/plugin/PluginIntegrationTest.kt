package dev.opencollab.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.opencollab.core.files.MirrorWorkspace
import dev.opencollab.core.files.ProjectFiles
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.ot.TextOperation
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.protocol.TextRange
import dev.opencollab.core.session.ClientHandler
import dev.opencollab.core.session.ClientState
import dev.opencollab.core.session.CollabClient
import dev.opencollab.core.session.DocumentSource
import dev.opencollab.core.session.HostConfig
import dev.opencollab.core.session.HostSession
import dev.opencollab.core.session.ReconnectPolicy
import dev.opencollab.core.util.SerialExecutor
import dev.opencollab.core.util.SystemScheduler
import dev.opencollab.plugin.bridge.IdeBridge
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.settings.OpenCollabSettings
import dev.opencollab.transport.DirectHostTransport
import dev.opencollab.transport.Invite
import dev.opencollab.transport.TlsIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A headless "remote IDE" built from the core client, used as the other side of the plugin. */
class RemoteIde(name: String) : ClientHandler {
    val edt = SerialExecutor("remote-$name")
    val texts = ConcurrentHashMap<String, String>()
    val presence = ConcurrentHashMap<String, Message.PresenceBroadcast>()
    lateinit var client: CollabClient
    override fun applyRemoteEdit(path: String, op: TextOperation) { texts[path] = op.apply(texts.getValue(path)) }
    override fun onPresence(presence: Message.PresenceBroadcast) { this.presence[presence.participantId] = presence }
    fun <T> onEdt(block: () -> T): T {
        val f = CompletableFuture<T>(); edt.execute { f.complete(block()) }; return f.get(10, TimeUnit.SECONDS)
    }
    fun open(path: String, text: String) = onEdt { texts[path] = text; client.openDocument(path, text) }
    fun type(path: String, offset: Int, s: String) = onEdt {
        val t = texts.getValue(path)
        val op = TextOperation.replace(t.length, offset, 0, s)
        texts[path] = op.apply(t)
        client.localEdit(path, op)
    }
}

class PluginIntegrationTest : HeavyPlatformTestCase() {
    private val scheduler = SystemScheduler()
    private val cleanups = mutableListOf<() -> Unit>()

    override fun tearDown() {
        try {
            cleanups.reversed().forEach { runCatching(it) }
            CollabSessionService.getInstance(project).leave()
            pumpUntil(2_000) { false }
            scheduler.close()
        } finally {
            super.tearDown()
        }
    }

    private fun pumpUntil(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun waitFor(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        assertTrue("Timed out: $message", pumpUntil(timeoutMs, condition))
    }

    private fun projectRoot(): Path = Path.of(project.basePath!!)

    private fun vf(path: String): VirtualFile? =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectRoot().resolve(path))

    private fun hostDocumentText(path: String): String? = vf(path)?.let { FileDocumentManager.getInstance().getDocument(it)?.text }

    private fun configureDirectOnLoopback() {
        OpenCollabSettings.getInstance().state.apply {
            enableDirect = true
            enableRelay = false
            bindAddress = "127.0.0.1"
            port = 0
            advertisedHosts = ""
            displayName = "PluginHost"
        }
    }

    private fun connectGuest(name: String, invite: String, dir: Path): RemoteIde {
        val ide = RemoteIde(name)
        val decoded = Invite.decode(invite)
        ide.client = CollabClient(decoded.connector(), name, decoded.token, ide, ide.edt, scheduler, MirrorWorkspace(dir), ReconnectPolicy(maxAttempts = 2))
        cleanups += { ide.client.close() }
        ide.client.start()
        return ide
    }

    fun testApplyOperationEditsIntelliJDocument() {
        val file = createChildData(getOrCreateProjectBaseDir(), "a.txt")
        setFileText(file, "hello world")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            IdeBridge.applyOperation(document, TextOperation.Builder().retain(6).insert("big ").delete(5).insert("cat").build())
        }
        assertEquals("hello big cat", document.text)
    }

    fun testPluginHostSharesProjectWithRemoteGuestOverTls() {
        val root = projectRoot()
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Main.kt"), "fun main() {\n}\n")
        Files.writeString(root.resolve(".env"), "SECRET=1\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
        configureDirectOnLoopback()

        val service = CollabSessionService.getInstance(project)
        service.startHosting()
        waitFor("hosting started") { service.mode is CollabSessionService.Mode.Hosting }
        val hosting = service.mode as CollabSessionService.Mode.Hosting
        assertTrue(hosting.endpoints.first().startsWith("wss://127.0.0.1:"))

        // Guest connects over real TLS with certificate pinning and receives the snapshot.
        val guestDir = Files.createTempDirectory("oc-guest")
        val guest = connectGuest("RemoteGuest", hosting.editorInvite, guestDir)
        waitFor("guest connected") { guest.client.state == ClientState.CONNECTED }
        assertEquals("fun main() {\n}\n", Files.readString(guestDir.resolve("src/Main.kt")))
        assertFalse("secrets are not shared", Files.exists(guestDir.resolve(".env")))
        waitFor("host sees the guest") { service.participants.size == 2 }

        // Guest edits -> applied to the host's real IntelliJ Document through write actions.
        guest.open("src/Main.kt", "fun main() {\n}\n")
        waitFor("document synced") { guest.onEdt { guest.client.isDocumentSynced("src/Main.kt") } }
        guest.type("src/Main.kt", 13, "    println(\"from guest\")\n")
        waitFor("host document updated") { hostDocumentText("src/Main.kt") == "fun main() {\n    println(\"from guest\")\n}\n" }

        // Host types in its editor document -> guest receives it.
        val document = FileDocumentManager.getInstance().getDocument(vf("src/Main.kt")!!)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// host\n") }
        waitFor("guest received host edit") { guest.texts["src/Main.kt"] == "// host\nfun main() {\n    println(\"from guest\")\n}\n" }

        // Concurrent typing from both sides converges.
        repeat(20) { i ->
            guest.type("src/Main.kt", 0, "g")
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "h$i") }
        }
        waitFor("concurrent edits converged") {
            val hostText = hostDocumentText("src/Main.kt")
            hostText != null && hostText == guest.texts["src/Main.kt"] && hostText == hosting.session.documentText("src/Main.kt")
        }
        val converged = hostDocumentText("src/Main.kt")!!
        assertTrue(converged, converged.startsWith("g".repeat(20) + "// host\n"))
        assertTrue(converged, converged.endsWith((0 until 20).joinToString("") { "h$it" }))

        // Presence reaches the host bridge.
        guest.client.updatePresence("src/Main.kt", 3, listOf(TextRange(0, 3)))
        waitFor("presence received") { hosting.bridge.presenceOf(guest.client.participantId)?.caret == 3 }

        // Guest file creation is validated and applied on the host through the VFS.
        guest.client.requestFileChange(FileMutation(FileOpKind.CREATE_FILE, "src/Util.kt", contentBase64 = MessageCodec.encodeBase64("object Util\n".toByteArray())))
        waitFor("file created on host") { vf("src/Util.kt")?.let { VfsUtil.loadText(it) } == "object Util\n" }

        // Host creates a file through the VFS -> mirrored to the guest.
        WriteCommandAction.runWriteCommandAction(project) {
            val src = vf("src")!!
            VfsUtil.saveText(src.createChildData(this, "HostNew.kt"), "class HostNew\n")
        }
        waitFor("guest received host file") { Files.exists(guestDir.resolve("src/HostNew.kt")) && Files.readString(guestDir.resolve("src/HostNew.kt")) == "class HostNew\n" }

        // Viewers are refused by the host even if they bypass their UI.
        val viewerDir = Files.createTempDirectory("oc-viewer")
        val viewer = connectGuest("Viewer", hosting.viewerInvite, viewerDir)
        waitFor("viewer connected") { viewer.client.state == ClientState.CONNECTED }
        assertEquals(Role.VIEWER, viewer.client.role)
        viewer.open("src/Main.kt", Files.readString(viewerDir.resolve("src/Main.kt")))
        waitFor("viewer synced") { viewer.onEdt { viewer.client.isDocumentSynced("src/Main.kt") } }
        val before = hosting.session.documentText("src/Main.kt")
        viewer.type("src/Main.kt", 0, "VIEWER WAS HERE")
        pumpUntil(1_000) { false }
        assertEquals(before, hosting.session.documentText("src/Main.kt"))
        assertEquals(before, hostDocumentText("src/Main.kt"))

        // Ending the session disconnects guests.
        service.leave()
        waitFor("guest disconnected") { guest.client.state == ClientState.DISCONNECTED }
    }

    fun testPluginGuestJoinsRemoteHostAndCoEdits() {
        // A headless host (core HostSession + TLS) and a second headless guest.
        val hostDir = Files.createTempDirectory("oc-host")
        Files.createDirectories(hostDir.resolve("src"))
        Files.writeString(hostDir.resolve("src/App.kt"), "class App\n")
        Files.writeString(hostDir.resolve("README.md"), "# Hello\n")
        val files = ProjectFiles(hostDir, SharingRules())
        val session = HostSession(HostConfig("remote-project"), files, DocumentSource { files.read(it)?.toString(Charsets.UTF_8) }, scheduler)
        val identity = TlsIdentity.ephemeral()
        val transport = DirectHostTransport("127.0.0.1", 0, identity)
        transport.start(session::accept)
        cleanups += { session.close(); transport.close() }
        val invite = Invite(project = "remote-project", role = Role.EDITOR, token = session.config.editorToken,
            fingerprint = identity.fingerprint, direct = transport.endpoints).encode()

        val service = CollabSessionService.getInstance(project)
        val canJoin = service.checkCanJoin(invite)
        if (canJoin != null) {
            // The heavy test project folder may contain IDE files; use a clean sub-check instead.
            assertTrue(canJoin, canJoin.contains("not empty"))
            val stray = Files.list(projectRoot()).use { s -> s.map { it.fileName.toString() }.toList() }
            fail("Test project folder unexpectedly non-empty: $stray")
        }
        service.join(invite)
        waitFor("plugin guest connected", 20_000) {
            (service.mode as? CollabSessionService.Mode.Joined)?.client?.state == ClientState.CONNECTED
        }
        waitFor("snapshot visible in VFS") { vf("src/App.kt")?.let { VfsUtil.loadText(it) } == "class App\n" }
        assertTrue(Files.exists(projectRoot().resolve(".opencollab/mirror.json")))

        // The plugin guest edits a document (no editor needed: refactoring-style edit).
        val document = FileDocumentManager.getInstance().getDocument(vf("src/App.kt")!!)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "// plugin guest\n") }
        waitFor("host received plugin edit") { session.documentText("src/App.kt") == "class App\n// plugin guest\n" }

        // A second guest types; the plugin guest's IntelliJ document follows.
        val otherDir = Files.createTempDirectory("oc-other")
        val other = connectGuest("Other", invite, otherDir)
        waitFor("other connected") { other.client.state == ClientState.CONNECTED }
        other.open("src/App.kt", Files.readString(otherDir.resolve("src/App.kt")))
        waitFor("other synced") { other.texts["src/App.kt"] == "class App\n// plugin guest\n" }
        other.type("src/App.kt", 0, "// other\n")
        waitFor("plugin document updated") { document.text == "// other\nclass App\n// plugin guest\n" }

        // Host-side file creation arrives in the plugin guest's VFS.
        val created = FileMutation(FileOpKind.CREATE_FILE, "src/Late.kt", contentBase64 = MessageCodec.encodeBase64("object Late\n".toByteArray()))
        Files.writeString(hostDir.resolve("src/Late.kt"), "object Late\n")
        other.client.requestFileChange(created.copy(path = "src/Late2.kt"))
        waitFor("file from other guest appears in plugin guest VFS") { vf("src/Late2.kt")?.let { VfsUtil.loadText(it) } == "object Late\n" }

        service.leave()
    }
}
