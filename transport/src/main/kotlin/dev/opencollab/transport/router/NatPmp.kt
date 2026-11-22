package dev.opencollab.transport.router

import dev.opencollab.transport.router.PortMappingException.Reason
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * NAT-PMP (RFC 6886): small UDP messages to the router on port 5351. The router maps the
 * port for the address the request came from, so a guest or another device can never point
 * a mapping at someone else. Answers are only accepted from [gateway].
 */
class NatPmpGateway(
    private val gateway: InetAddress,
    private val port: Int = PORT,
    /** Waits before each retry; RFC 6886 starts at 250 ms and doubles. */
    private val retryDelaysMillis: List<Int> = listOf(250, 500, 1000, 2000),
) : PortMapper {
    override val protocol = RouterProtocol.NAT_PMP

    override val localAddress: InetAddress by lazy {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(gateway, port))
            socket.localAddress
        }
    }

    override fun externalAddress(): InetAddress {
        val answer = request(byteArrayOf(VERSION, OP_EXTERNAL_ADDRESS), OP_EXTERNAL_ADDRESS, ANSWER_EXTERNAL_ADDRESS_SIZE)
        return InetAddress.getByAddress(answer.copyOfRange(8, 12))
    }

    override fun map(internalPort: Int, externalPort: Int, lifetimeSeconds: Int): PortMapping {
        require(NetAddresses.validPort(internalPort) && NetAddresses.validPort(externalPort) && lifetimeSeconds > 0)
        val answer = ByteBuffer.wrap(request(mapRequest(internalPort, externalPort, lifetimeSeconds), OP_MAP_TCP, ANSWER_MAP_SIZE))
        val answeredInternal = answer.getShort(8).toInt() and 0xffff
        val mappedExternal = answer.getShort(10).toInt() and 0xffff
        val lifetime = answer.getInt(12)
        if (answeredInternal != internalPort) throw PortMappingException(Reason.INVALID_RESPONSE, "The router answered for port $answeredInternal instead of $internalPort")
        if (!NetAddresses.validPort(mappedExternal)) throw PortMappingException(Reason.INVALID_RESPONSE, "The router returned an invalid external port $mappedExternal")
        if (lifetime <= 0) throw PortMappingException(Reason.INVALID_RESPONSE, "The router returned an invalid lifetime $lifetime")
        return PortMapping(protocol, localAddress, internalPort, mappedExternal, lifetime)
    }

    override fun unmap(mapping: PortMapping) {
        // Lifetime 0 deletes; the router only deletes mappings of the requesting address.
        request(mapRequest(mapping.internalPort, 0, 0), OP_MAP_TCP, ANSWER_MAP_SIZE)
    }

    private fun mapRequest(internalPort: Int, externalPort: Int, lifetimeSeconds: Int): ByteArray =
        ByteBuffer.allocate(12).put(VERSION).put(OP_MAP_TCP).putShort(0)
            .putShort(internalPort.toShort()).putShort(externalPort.toShort()).putInt(lifetimeSeconds).array()

    /** Sends [message] with retries and returns the checked answer of at least [size] bytes. */
    private fun request(message: ByteArray, op: Byte, size: Int): ByteArray {
        DatagramSocket().use { socket ->
            // A connected socket drops datagrams from anyone but the router.
            socket.connect(InetSocketAddress(gateway, port))
            val buffer = ByteArray(64)
            for (delay in retryDelaysMillis) {
                socket.send(DatagramPacket(message, message.size))
                socket.soTimeout = delay
                val deadline = System.nanoTime() + delay * 1_000_000L
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val answer = buffer.copyOf(packet.length)
                    if (check(answer, op, size)) return answer
                    val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                    if (left <= 0) break
                    socket.soTimeout = left
                }
            }
        }
        throw PortMappingException(Reason.NO_ROUTER, "No NAT-PMP answer from the router")
    }

    /** True for a well-formed successful answer to [op]; throws for a router error; false to ignore. */
    private fun check(answer: ByteArray, op: Byte, size: Int): Boolean {
        if (answer.size < 4 || answer[0] != VERSION || answer[1] != (op + 128).toByte()) return false
        val result = ByteBuffer.wrap(answer).getShort(2).toInt() and 0xffff
        when (result) {
            0 -> Unit
            1 -> throw PortMappingException(Reason.NO_ROUTER, "The router does not support NAT-PMP")
            2 -> throw PortMappingException(Reason.REFUSED, "The router does not allow port mappings (NAT-PMP is turned off)")
            3 -> throw PortMappingException(Reason.REFUSED, "The router is not connected to the internet")
            4 -> throw PortMappingException(Reason.REFUSED, "The router has no room for another port mapping")
            5 -> throw PortMappingException(Reason.REFUSED, "The router does not support TCP port mappings")
            else -> throw PortMappingException(Reason.REFUSED, "The router refused the request (NAT-PMP result $result)")
        }
        if (answer.size < size) throw PortMappingException(Reason.INVALID_RESPONSE, "The router sent a truncated NAT-PMP answer")
        return true
    }

    companion object {
        const val PORT = 5351
        private const val VERSION: Byte = 0
        private const val OP_EXTERNAL_ADDRESS: Byte = 0
        private const val OP_MAP_TCP: Byte = 2
        private const val ANSWER_EXTERNAL_ADDRESS_SIZE = 12
        private const val ANSWER_MAP_SIZE = 16

        /** Where the router probably is when UPnP found none: the first address of each private network of this computer. */
        fun likelyGateways(): List<InetAddress> = try {
            java.net.NetworkInterface.networkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is java.net.Inet4Address && it.address.isSiteLocalAddress && it.networkPrefixLength in 8..30 }
                .map { firstHost(it.address, it.networkPrefixLength.toInt()) }
                .distinct()
        } catch (e: IOException) {
            emptyList()
        }

        internal fun firstHost(address: InetAddress, prefix: Int): InetAddress {
            val value = ByteBuffer.wrap(address.address).int
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt((value and mask) + 1).array())
        }
    }
}
