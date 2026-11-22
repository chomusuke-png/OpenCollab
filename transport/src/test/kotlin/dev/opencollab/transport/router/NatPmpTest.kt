package dev.opencollab.transport.router

import dev.opencollab.transport.router.PortMappingException.Reason
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** NAT-PMP against a simulated router on the loopback address. */
class NatPmpTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val server = DatagramSocket(InetSocketAddress(loopback, 0))
    private val requests = CopyOnWriteArrayList<ByteArray>()

    /** What the router answers to each request; null = stay silent. */
    @Volatile private var answer: (ByteBuffer) -> List<ByteArray>? = { request -> listOf(goodAnswer(request)) }

    private val thread = Thread {
        val buffer = ByteArray(64)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                server.receive(packet)
            } catch (e: SocketException) {
                return@Thread
            }
            val request = buffer.copyOf(packet.length)
            requests += request
            answer(ByteBuffer.wrap(request))?.forEach { server.send(DatagramPacket(it, it.size, packet.socketAddress)) }
        }
    }.apply { isDaemon = true; start() }

    @AfterEach
    fun stop() {
        server.close()
        thread.join(2_000)
    }

    private fun gateway() = NatPmpGateway(loopback, server.localPort, retryDelaysMillis = listOf(100, 200))

    private fun goodAnswer(request: ByteBuffer, result: Int = 0, external: Int? = null): ByteArray = when (request.get(1).toInt()) {
        0 -> ByteBuffer.allocate(12).put(0).put(128.toByte()).putShort(result.toShort()).putInt(1).put(byteArrayOf(81, 2, 69, 160.toByte())).array()
        else -> {
            val internal = request.getShort(4)
            val suggested = request.getShort(6).toInt() and 0xffff
            val lifetime = request.getInt(8)
            ByteBuffer.allocate(16).put(0).put(130.toByte()).putShort(result.toShort()).putInt(1)
                .putShort(internal).putShort((external ?: suggested).toShort()).putInt(lifetime).array()
        }
    }

    @Test
    fun `reads the public address and maps a port`() {
        val router = gateway()
        assertEquals("81.2.69.160", router.externalAddress().hostAddress)
        val mapping = router.map(47800, 47800, 3600)
        assertEquals(47800, mapping.externalPort)
        assertEquals(3600, mapping.lifetimeSeconds)
        assertEquals(RouterProtocol.NAT_PMP, mapping.protocol)
        val request = ByteBuffer.wrap(requests.last())
        assertEquals(2, request.get(1).toInt(), "a TCP mapping")
        assertEquals(47800, request.getShort(4).toInt() and 0xffff)
    }

    @Test
    fun `the router may choose another external port`() {
        answer = { request -> listOf(goodAnswer(request, external = 51234)) }
        val mapping = gateway().map(47800, 47800, 3600)
        assertEquals(51234, mapping.externalPort)
        assertEquals(47800, mapping.internalPort)
    }

    @Test
    fun `removing a mapping asks for lifetime 0`() {
        val router = gateway()
        router.unmap(router.map(47800, 47800, 3600))
        val request = ByteBuffer.wrap(requests.last())
        assertEquals(0, request.getInt(8))
        assertEquals(0, request.getShort(6).toInt())
    }

    @Test
    fun `refusals, silence and garbage are reported`() {
        answer = { request -> listOf(goodAnswer(request, result = 2)) }
        assertEquals(Reason.REFUSED, assertThrows<PortMappingException> { gateway().map(47800, 47800, 3600) }.reason)

        answer = { null }
        assertEquals(Reason.NO_ROUTER, assertThrows<PortMappingException> { gateway().externalAddress() }.reason)

        // Junk, a wrong opcode and a short packet are ignored; the router never answers properly.
        answer = { listOf(byteArrayOf(1, 2, 3), byteArrayOf(0, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), byteArrayOf(0)) }
        assertEquals(Reason.NO_ROUTER, assertThrows<PortMappingException> { gateway().map(47800, 47800, 3600) }.reason)

        // A successful answer that is cut short is not believed.
        answer = { request -> listOf(goodAnswer(request).copyOf(10)) }
        assertEquals(Reason.INVALID_RESPONSE, assertThrows<PortMappingException> { gateway().map(47800, 47800, 3600) }.reason)
    }

    @Test
    fun `answers for another port or with an invalid port are rejected`() {
        answer = { request -> listOf(goodAnswer(request, external = 0)) }
        assertEquals(Reason.INVALID_RESPONSE, assertThrows<PortMappingException> { gateway().map(47800, 47800, 3600) }.reason)

        answer = { request ->
            val a = goodAnswer(request)
            if (a.size == 16) ByteBuffer.wrap(a).putShort(8, 22)
            listOf(a)
        }
        assertEquals(Reason.INVALID_RESPONSE, assertThrows<PortMappingException> { gateway().map(47800, 47800, 3600) }.reason)
        assertTrue(requests.isNotEmpty())
    }
}
