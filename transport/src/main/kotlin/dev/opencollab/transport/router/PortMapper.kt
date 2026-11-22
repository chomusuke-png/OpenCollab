package dev.opencollab.transport.router

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress

/** The router protocols OpenCollab can ask for a port mapping. */
enum class RouterProtocol(val label: String) {
    UPNP("UPnP"),
    NAT_PMP("NAT-PMP"),
}

/** A TCP port the router forwards to this computer, as the router confirmed it. */
data class PortMapping(
    val protocol: RouterProtocol,
    /** This computer's address on the router's network (the mapping's target). */
    val internalAddress: InetAddress,
    val internalPort: Int,
    val externalPort: Int,
    /** Seconds until the router drops the mapping; 0 means it never expires. */
    val lifetimeSeconds: Int,
)

/**
 * One protocol talking to one router. All calls block for a few seconds at most, and every
 * answer is checked before it is trusted: a router (or a device pretending to be one) never
 * decides anything but whether a mapping exists and on which external port.
 */
interface PortMapper {
    val protocol: RouterProtocol

    /** This computer's address on the router's network, which the mappings point to. */
    val localAddress: InetAddress

    /** The router's own internet address. It is not public behind carrier-grade NAT or a second router. */
    @Throws(IOException::class)
    fun externalAddress(): InetAddress

    /**
     * Forwards TCP [externalPort] to [internalPort] on [localAddress] for [lifetimeSeconds].
     * Throws [PortMappingException] with [PortMappingException.Reason.PORT_TAKEN] when the
     * external port is used by another mapping. NAT-PMP routers may pick another external port.
     */
    @Throws(IOException::class)
    fun map(internalPort: Int, externalPort: Int, lifetimeSeconds: Int): PortMapping

    /** Removes [mapping], but only while it still points to this computer. */
    @Throws(IOException::class)
    fun unmap(mapping: PortMapping)
}

class PortMappingException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** No router answered on any supported protocol. */
        NO_ROUTER,
        /** The router said no (disabled, not allowed, out of resources...). */
        REFUSED,
        /** The external port is used by another mapping. */
        PORT_TAKEN,
        /** The router answered something that makes no sense. */
        INVALID_RESPONSE,
        /** The router's internet address is not public: forwarding cannot reach this computer. */
        NOT_PUBLIC,
        /** Hosting stopped while the mapping was being made. */
        CANCELLED,
    }
}

internal object NetAddresses {
    /** A device on this computer's own network: where a router can be. */
    fun isLocalNetwork(address: InetAddress): Boolean =
        address is Inet4Address && (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)

    /**
     * An IPv4 address reachable from the whole internet: not private, carrier-grade NAT
     * (100.64.0.0/10), loopback, link-local, multicast, reserved or documentation space.
     */
    fun isPublicIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address.map { it.toInt() and 0xff }
        return when {
            b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 -> false
            b[0] == 100 && b[1] in 64..127 -> false
            b[0] == 169 && b[1] == 254 -> false
            b[0] == 172 && b[1] in 16..31 -> false
            b[0] == 192 && b[1] == 168 -> false
            b[0] == 192 && b[1] == 0 && (b[2] == 0 || b[2] == 2) -> false
            b[0] == 198 && (b[1] == 18 || b[1] == 19) -> false
            b[0] == 198 && b[1] == 51 && b[2] == 100 -> false
            b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
            else -> true
        }
    }

    /** Parses a dotted IPv4 literal without any name lookup; null when it is not one. */
    fun parseIpv4(text: String): Inet4Address? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    fun validPort(port: Int) = port in 1..65535
}
