package dev.opencollab.plugin

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.opencollab.core.files.MirrorWorkspace
import dev.opencollab.core.files.ProjectFiles
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.session.ClientState
import dev.opencollab.core.session.CollabClient
import dev.opencollab.core.session.DocumentSource
import dev.opencollab.core.session.HostConfig
import dev.opencollab.core.session.HostSession
import dev.opencollab.core.session.ReconnectPolicy
import dev.opencollab.core.util.SystemScheduler
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.session.PendingJoinActivity
import dev.opencollab.plugin.session.PendingJoins
import dev.opencollab.plugin.settings.OpenCollabSettings
import dev.opencollab.transport.DirectHostTransport
import dev.opencollab.transport.Invite
import dev.opencollab.transport.TlsIdentity
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class PortForwardingAndJoinTest : HeavyPlatformTestCase() {
    private val scheduler = SystemScheduler()
    private val cleanups = mutableListOf<() -> Unit>()

    override fun tearDown() {
        try {
            cleanups.reversed().forEach { runCatching(it) }
            CollabSessionService.getInstance(project).leave()
            OpenCollabSettings.getInstance().loadState(OpenCollabSettings.State())
            scheduler.close()
        } finally {
            super.tearDown()
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out: $what")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    fun testHostAdvertisesForwardedPublicEndpointAndGuestJoinsThroughIt() {
        val root = Files.createDirectories(Path.of(project.basePath!!))
        Files.writeString(root.resolve("Shared.txt"), "hello\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
        val port = freePort()
        OpenCollabSettings.getInstance().state.apply {
            enableDirect = true; enableRelay = false; bindAddress = "0.0.0.0"; this.port = port; advertisedHosts = ""; approveJoins = false
            portForwarding = true; publicAddress = "localhost"; externalPort = 0 // "router" forwards the same port
        }
        val service = CollabSessionService.getInstance(project)
        service.startHosting()
        waitFor("hosting") { service.mode is CollabSessionService.Mode.Hosting }
        val hosting = service.mode as CollabSessionService.Mode.Hosting
        val invite = Invite.decode(hosting.editorInvite)
        assertTrue(invite.direct.toString(), "wss://localhost:$port" in invite.direct)
        assertTrue(service.log().any { it.contains("wss://localhost:$port") })

        // A guest that can ONLY use the public (forwarded) endpoint.
        val onlyPublic = invite.copy(direct = listOf("wss://localhost:$port"))
        val dir = tempDir("oc-pf-guest")
        val guest = RemoteIde("pf")
        guest.client = CollabClient(onlyPublic.connector(), "Remote", onlyPublic.token, guest, guest.edt, scheduler,
            MirrorWorkspace(dir), ReconnectPolicy(maxAttempts = 2))
        cleanups += { guest.client.close() }
        guest.client.start()
        waitFor("guest connected via public endpoint") { guest.client.state == ClientState.CONNECTED }
        assertEquals("hello\n", Files.readString(dir.resolve("Shared.txt")))
    }

    fun testPortForwardingRequiresFixedPort() {
        OpenCollabSettings.getInstance().state.apply {
            enableDirect = true; enableRelay = false; bindAddress = "0.0.0.0"; port = 0
            portForwarding = true; publicAddress = "203.0.113.7"
        }
        val service = CollabSessionService.getInstance(project)
        service.startHosting()
        waitFor("start failure") { service.mode == CollabSessionService.Mode.Idle && service.log().isNotEmpty() }
        assertTrue(service.log().last(), service.log().last().contains(OpenCollabBundle.message("error.forwarding.port")))
    }

    fun testPendingJoinCompletesWhenTheNewProjectOpens() {
        val hostDir = tempDir("oc-pj-host")
        Files.writeString(hostDir.resolve("A.kt"), "class A\n")
        val files = ProjectFiles(hostDir, SharingRules())
        val session = HostSession(HostConfig("pj"), files, DocumentSource { files.read(it)?.toString(Charsets.UTF_8) }, scheduler)
        val identity = TlsIdentity.ephemeral()
        val transport = DirectHostTransport("127.0.0.1", 0, identity)
        transport.start(session::accept)
        cleanups += { session.close(); transport.close() }
        val invite = Invite(project = "pj", role = Role.EDITOR, token = session.config.editorToken,
            fingerprint = identity.fingerprint, direct = transport.endpoints).encode()

        PendingJoins.put(Path.of(project.basePath!!), invite)
        runBlocking { PendingJoinActivity().execute(project) }
        val service = CollabSessionService.getInstance(project)
        waitFor("joined") { (service.mode as? CollabSessionService.Mode.Joined)?.client?.state == ClientState.CONNECTED }
        assertNull("consumed once", PendingJoins.take(Path.of(project.basePath!!)))
        waitFor("file mirrored") { Files.exists(Path.of(project.basePath!!, "A.kt")) }
    }

    fun testSuggestedFolderAvoidsNonEmptyFolders() {
        val base = tempDir("oc-suggest")
        assertEquals(base.resolve("My_Proj"), PendingJoins.suggestFolder("My:Proj", base))
        Files.createDirectories(base.resolve("My_Proj"))
        Files.writeString(base.resolve("My_Proj/own.txt"), "x")
        assertEquals(base.resolve("My_Proj-2"), PendingJoins.suggestFolder("My:Proj", base))
    }
}
