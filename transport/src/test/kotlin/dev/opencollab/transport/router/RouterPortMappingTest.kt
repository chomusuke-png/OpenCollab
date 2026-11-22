package dev.opencollab.transport.router

import dev.opencollab.transport.router.PortMappingException.Reason
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The mapping lifecycle against simulated routers: no network, no real router. */
class RouterPortMappingTest {
    private val local: InetAddress = InetAddress.getByName("192.168.1.20")

    /** A router that keeps its mappings in memory and can be told to misbehave. */
    private inner class FakeRouter(
        override val protocol: RouterProtocol = RouterProtocol.UPNP,
        var external: String = "81.2.69.160",
        val taken: Set<Int> = emptySet(),
        var refuse: Boolean = false,
        var lifetime: Int? = null,
        var answerInternalPort: Int? = null,
    ) : PortMapper {
        override val localAddress = local
        val mappings = LinkedHashMap<Int, Int>() // external -> internal
        val mapCalls = CopyOnWriteArrayList<Int>()
        var unmapCalls = 0

        override fun externalAddress(): InetAddress = InetAddress.getByName(external)

        override fun map(internalPort: Int, externalPort: Int, lifetimeSeconds: Int): PortMapping {
            mapCalls += externalPort
            if (refuse) throw PortMappingException(Reason.REFUSED, "UPnP is turned off")
            if (externalPort in taken) throw PortMappingException(Reason.PORT_TAKEN, "taken")
            mappings[externalPort] = internalPort
            return PortMapping(protocol, local, answerInternalPort ?: internalPort, externalPort, lifetime ?: lifetimeSeconds)
        }

        override fun unmap(mapping: PortMapping) {
            unmapCalls++
            mappings.remove(mapping.externalPort)
        }
    }

    private val statuses = CopyOnWriteArrayList<RouterPortMapping.Status>()

    private fun mapping(vararg routers: PortMapper, external: Int = 0, assigned: () -> Boolean = { true }) =
        RouterPortMapping(47800, external, onStatus = { statuses += it }, retryMillis = 60_000, discover = { routers.toList() }, stillAssigned = { assigned() })

    @Test
    fun `maps the listen port, reports it, and removes it when hosting ends`() {
        val router = FakeRouter()
        val m = mapping(router)
        val result = m.start()
        assertEquals(47800, result.mapping.externalPort)
        assertEquals("81.2.69.160", result.externalAddress.hostAddress)
        assertEquals(mapOf(47800 to 47800), router.mappings)
        assertTrue(statuses.first() is RouterPortMapping.Status.Searching)
        assertTrue(statuses.any { it == RouterPortMapping.Status.Found(RouterProtocol.UPNP) })
        assertTrue(statuses.last() is RouterPortMapping.Status.Mapped)

        m.close()
        m.close() // twice is harmless
        assertTrue(router.mappings.isEmpty())
        assertEquals(1, router.unmapCalls)
        assertEquals(RouterPortMapping.Status.Removed, statuses.last())
        assertNull(m.current)
    }

    @Test
    fun `a taken external port is replaced by another one, unless the user chose it`() {
        val router = FakeRouter(taken = setOf(47800))
        val result = mapping(router).start()
        assertNotEquals(47800, result.mapping.externalPort)
        assertEquals(47800, result.mapping.internalPort)
        assertTrue(result.mapping.externalPort in 49152..65535)

        val strict = FakeRouter(taken = setOf(50000))
        val e = assertThrows<PortMappingException> { mapping(strict, external = 50000).start() }
        assertEquals(Reason.PORT_TAKEN, e.reason)
        assertEquals(listOf(50000), strict.mapCalls)
        assertTrue(strict.mappings.isEmpty())
    }

    @Test
    fun `no router, a refusing router and carrier-grade NAT fail without touching anything`() {
        assertEquals(Reason.NO_ROUTER, assertThrows<PortMappingException> { mapping().start() }.reason)
        assertTrue(statuses.last() is RouterPortMapping.Status.Failed)

        val refusing = FakeRouter(refuse = true)
        assertEquals(Reason.REFUSED, assertThrows<PortMappingException> { mapping(refusing).start() }.reason)

        for (address in listOf("100.64.3.4", "10.0.0.2", "192.168.0.1", "172.20.0.1")) {
            val behindCgnat = FakeRouter(external = address)
            val e = assertThrows<PortMappingException> { mapping(behindCgnat).start() }
            assertEquals(Reason.NOT_PUBLIC, e.reason, address)
            assertTrue(behindCgnat.mapCalls.isEmpty(), "nothing is mapped when forwarding cannot help ($address)")
        }
    }

    @Test
    fun `the next protocol is tried when the first router refuses`() {
        val upnp = FakeRouter(refuse = true)
        val natPmp = FakeRouter(protocol = RouterProtocol.NAT_PMP)
        val result = mapping(upnp, natPmp).start()
        assertEquals(RouterProtocol.NAT_PMP, result.mapping.protocol)
        assertTrue(upnp.mappings.isEmpty())
        assertEquals(1, natPmp.mappings.size)
    }

    @Test
    fun `a router confirming a different mapping is not believed`() {
        val liar = FakeRouter(answerInternalPort = 22)
        assertEquals(Reason.INVALID_RESPONSE, assertThrows<PortMappingException> { mapping(liar).start() }.reason)
    }

    @Test
    fun `renewal asks again for the same port and reports failures`() {
        val router = FakeRouter()
        val m = mapping(router)
        m.start()
        m.renew()
        assertEquals(listOf(47800, 47800), router.mapCalls)
        assertTrue(statuses.last() is RouterPortMapping.Status.Mapped)

        router.refuse = true
        m.renew()
        assertTrue(statuses.last() is RouterPortMapping.Status.RenewalFailed)
        assertEquals(47800, m.current?.mapping?.externalPort, "the mapping is kept and retried")

        m.close()
        val calls = router.mapCalls.size
        m.renew()
        assertEquals(calls, router.mapCalls.size, "nothing is renewed after hosting ended")
    }

    @Test
    fun `a new network address maps the port again`() {
        val router = FakeRouter()
        var assigned = true
        val m = mapping(router, assigned = { assigned })
        m.start()
        assigned = false
        m.renew()
        assertEquals(1, router.unmapCalls)
        assertEquals(mapOf(47800 to 47800), router.mappings)
        m.close()
    }

    @Test
    fun `stopping while the router is searched leaves no mapping`() {
        val searching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val router = FakeRouter()
        val m = RouterPortMapping(47800, discover = {
            searching.countDown()
            release.await(5, TimeUnit.SECONDS)
            listOf(router)
        })
        var failure: Throwable? = null
        val thread = Thread { failure = runCatching { m.start() }.exceptionOrNull() }.apply { start() }
        assertTrue(searching.await(5, TimeUnit.SECONDS))
        m.close()
        release.countDown()
        thread.join(5_000)
        assertEquals(Reason.CANCELLED, (failure as PortMappingException).reason)
        assertTrue(router.mappings.isEmpty())
    }

    @Test
    fun `a router that only answers with errors is reported, not trusted`() {
        val broken = object : PortMapper {
            override val protocol = RouterProtocol.UPNP
            override val localAddress = local
            override fun externalAddress(): InetAddress = throw IOException("connection reset")
            override fun map(internalPort: Int, externalPort: Int, lifetimeSeconds: Int) = error("not reached")
            override fun unmap(mapping: PortMapping) = error("not reached")
        }
        assertEquals(Reason.NO_ROUTER, assertThrows<PortMappingException> { mapping(broken).start() }.reason)
    }

    @Test
    fun `public and private addresses are told apart`() {
        for (public in listOf("81.2.69.160", "8.8.8.8", "1.1.1.1")) assertTrue(NetAddresses.isPublicIpv4(InetAddress.getByName(public)), public)
        for (private in listOf("10.1.2.3", "172.16.0.1", "192.168.1.1", "100.100.0.1", "127.0.0.1", "169.254.1.1", "0.0.0.0", "224.0.0.1", "255.255.255.255")) {
            assertTrue(!NetAddresses.isPublicIpv4(InetAddress.getByName(private)), private)
        }
        assertNull(NetAddresses.parseIpv4("router.local"))
        assertNull(NetAddresses.parseIpv4("1.2.3.256"))
        assertEquals("192.168.0.1", NatPmpGateway.firstHost(InetAddress.getByName("192.168.0.57"), 24).hostAddress)
        assertEquals("10.0.0.1", NatPmpGateway.firstHost(InetAddress.getByName("10.8.3.4"), 8).hostAddress)
    }
}
