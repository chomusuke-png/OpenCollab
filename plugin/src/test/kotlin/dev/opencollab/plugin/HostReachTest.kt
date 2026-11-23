package dev.opencollab.plugin

import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.settings.OpenCollabSettings
import dev.opencollab.transport.NetworkHints
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/** The host learns who can use its invitations before sending them, not from a failing guest. */
class HostReachTest : HeavyPlatformTestCase() {
    override fun setUp() {
        super.setUp()
        Files.createDirectories(Path.of(project.basePath!!))
    }

    override fun tearDown() {
        try {
            CollabSessionService.getInstance(project).leave()
            OpenCollabSettings.getInstance().loadState(OpenCollabSettings.State())
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

    private fun host(configure: OpenCollabSettings.State.() -> Unit): CollabSessionService {
        OpenCollabSettings.getInstance().state.apply {
            enableDirect = true; enableRelay = false; approveJoins = false; port = freePort()
            configure()
        }
        return CollabSessionService.getInstance(project).also { it.startHosting() }
    }

    private fun hosting(service: CollabSessionService): CollabSessionService.Mode.Hosting {
        waitFor("hosting") { service.mode is CollabSessionService.Mode.Hosting }
        return service.mode as CollabSessionService.Mode.Hosting
    }

    fun testOnlyThisComputerIsSaidWhenListeningOnLoopback() {
        val service = host { bindAddress = "127.0.0.1" }
        assertEquals(NetworkHints.Reach.THIS_COMPUTER, hosting(service).reach)
        assertEquals(OpenCollabBundle.message("hint.reach.self"), service.reachWarning())
        assertTrue("said when hosting starts", service.log().any { it.contains(OpenCollabBundle.message("hint.reach.self")) })
    }

    fun testInternetReachNeedsNoWarning() {
        val service = host { bindAddress = "0.0.0.0"; portForwarding = true; publicAddress = "collab.example.org"; port = freePort() }
        assertEquals(NetworkHints.Reach.INTERNET, hosting(service).reach)
        assertNull(service.reachWarning())
        assertTrue(service.log().none { it.contains(OpenCollabBundle.message("hint.reach.local")) || it.contains(OpenCollabBundle.message("hint.reach.self")) })
    }

    fun testARelayListenAddressIsRefusedBeforeConnecting() {
        val service = host { enableDirect = false; enableRelay = true; relayUrl = "wss://0.0.0.0:25566" }
        waitFor("start failure") { service.mode == CollabSessionService.Mode.Idle && service.log().isNotEmpty() }
        assertTrue(service.log().last(), service.log().last().contains(OpenCollabBundle.message("error.relay.unspecified", "wss://0.0.0.0:25566")))
    }

    fun testNoWarningOutsideHosting() {
        assertNull(CollabSessionService.getInstance(project).reachWarning())
    }
}
