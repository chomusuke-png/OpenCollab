package dev.opencollab.plugin

import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.settings.OpenCollabSettings
import dev.opencollab.transport.Invite
import dev.opencollab.transport.router.PortMapper
import dev.opencollab.transport.router.PortMapping
import dev.opencollab.transport.router.PortMappingException
import dev.opencollab.transport.router.RouterProtocol
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Hosting with automatic port forwarding, against a simulated router (never a real one). */
class RouterForwardingTest : HeavyPlatformTestCase() {
    private class FakeRouter(var refuse: Boolean = false) : PortMapper {
        override val protocol = RouterProtocol.UPNP
        override val localAddress: InetAddress = InetAddress.getByName("192.168.1.20")
        val mappings = ConcurrentHashMap<Int, Int>()
        override fun externalAddress(): InetAddress = InetAddress.getByName("81.2.69.160")
        override fun map(internalPort: Int, externalPort: Int, lifetimeSeconds: Int): PortMapping {
            if (refuse) throw PortMappingException(PortMappingException.Reason.REFUSED, "UPnP is turned off")
            mappings[externalPort] = internalPort
            return PortMapping(protocol, localAddress, internalPort, externalPort, lifetimeSeconds)
        }
        override fun unmap(mapping: PortMapping) {
            mappings.remove(mapping.externalPort)
        }
    }

    private val searches = AtomicInteger()

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

    private fun host(port: Int, automatic: Boolean, manual: Boolean = false, routers: List<PortMapper> = emptyList()): CollabSessionService.Mode.Hosting {
        OpenCollabSettings.getInstance().state.apply {
            enableDirect = true; enableRelay = false; bindAddress = "0.0.0.0"; this.port = port; approveJoins = false
            automaticPortForwarding = automatic
            portForwarding = manual; publicAddress = if (manual) "collab.example.org" else ""; externalPort = 0
        }
        val service = CollabSessionService.getInstance(project)
        service.routerDiscovery = { searches.incrementAndGet(); routers }
        service.startHosting()
        waitFor("hosting") { service.mode is CollabSessionService.Mode.Hosting }
        return service.mode as CollabSessionService.Mode.Hosting
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    fun testAutomaticForwardingIsOffByDefaultAndIsSaved() {
        assertFalse(OpenCollabSettings.State().automaticPortForwarding)
        val state = OpenCollabSettings.State().apply { automaticPortForwarding = true }
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), OpenCollabSettings.State::class.java)
        assertTrue(restored.automaticPortForwarding)
    }

    fun testOffMeansTheRouterIsNeverAsked() {
        val hosting = host(freePort(), automatic = false, routers = listOf(FakeRouter()))
        assertEquals(0, searches.get())
        assertNull(CollabSessionService.getInstance(project).routerStatus)
        assertTrue(Invite.decode(hosting.editorInvite).direct.none { it.contains("81.2.69.160") })
    }

    fun testMappedPortIsInvitedAndRemovedWhenHostingEnds() {
        val router = FakeRouter()
        val port = freePort()
        val hosting = host(port, automatic = true, routers = listOf(router))
        val service = CollabSessionService.getInstance(project)
        assertEquals(mapOf(port to port), router.mappings.toMap())
        assertTrue(Invite.decode(hosting.editorInvite).direct.toString(), "wss://81.2.69.160:$port" in Invite.decode(hosting.editorInvite).direct)
        assertFalse(service.routerProblem)

        service.leave()
        waitFor("mapping removed") { router.mappings.isEmpty() }
    }

    fun testTurningItOffWhileHostingRemovesTheMapping() {
        val router = FakeRouter()
        val hosting = host(freePort(), automatic = true, routers = listOf(router))
        CollabSessionService.getInstance(project).stopRouterMapping()
        waitFor("mapping removed") { router.mappings.isEmpty() }
        assertTrue(Invite.decode(hosting.editorInvite).direct.none { it.contains("81.2.69.160") })
    }

    fun testAFailingRouterDoesNotStopHostingAndManualForwardingStillApplies() {
        val port = freePort()
        val hosting = host(port, automatic = true, manual = true, routers = listOf(FakeRouter(refuse = true)))
        val service = CollabSessionService.getInstance(project)
        assertTrue(service.routerProblem)
        val direct = Invite.decode(hosting.editorInvite).direct
        assertTrue(direct.toString(), "wss://collab.example.org:$port" in direct)
        assertTrue(direct.none { it.contains("81.2.69.160") })
    }

    fun testNoRouterStillHostsOnTheLocalNetwork() {
        val hosting = host(freePort(), automatic = true)
        val service = CollabSessionService.getInstance(project)
        assertTrue(service.routerProblem)
        assertTrue(Invite.decode(hosting.editorInvite).direct.isNotEmpty())
        assertTrue(service.log().any { it.contains("no router answered") })
    }
}
