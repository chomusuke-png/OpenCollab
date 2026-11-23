package dev.opencollab.transport

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI

/**
 * Helps choose which host addresses go into an invitation and explains, before connecting,
 * when a direct endpoint cannot possibly be reached from this machine.
 */
object NetworkHints {
    /** Adapters that exist only inside this machine (VMs, containers, WSL, VPN-less tunnels). */
    private val virtualAdapter = Regex(
        "vethernet|hyper-v|wsl|docker|virtualbox|vmware|vmnet|vboxnet|veth|br-|virbr|default switch|loopback|npcap|bluetooth",
        RegexOption.IGNORE_CASE,
    )

    data class LocalNetwork(val address: InetAddress, val prefixLength: Int)

    /** IPv4 addresses of physical (or real VPN) adapters that other machines can usually reach. */
    fun lanAddresses(): List<String> = localNetworks().map { it.address.hostAddress }

    fun localNetworks(): List<LocalNetwork> {
        val result = mutableListOf<LocalNetwork>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                if (virtualAdapter.containsMatchIn(ni.name) || virtualAdapter.containsMatchIn(ni.displayName ?: "")) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && !addr.isLinkLocalAddress && !addr.isLoopbackAddress) {
                        result += LocalNetwork(addr, ia.networkPrefixLength.toInt())
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /**
     * True if [host] is a private RFC 1918 IPv4 address outside every local subnet. Without a VPN
     * route such an address belongs to a different private network and is unreachable directly
     * (only a hint: VPNs can route other private ranges; Tailscale 100.64/10 is never flagged).
     */
    fun isPrivateAddressOnAnotherNetwork(host: String, networks: List<LocalNetwork> = localNetworks()): Boolean {
        val address = parseIpv4(host) ?: return false
        if (!isPrivate(address)) return false
        return networks.none { sameSubnet(address, it.address.address, it.prefixLength) }
    }

    /**
     * Orders direct endpoints for connection attempts: addresses on one of this machine's
     * subnets first, then public addresses / host names (port forwarding, VPN), and private
     * addresses of other networks last (they only work through a VPN route).
     */
    fun orderEndpoints(endpoints: List<String>, networks: List<LocalNetwork> = localNetworks()): List<String> =
        endpoints.sortedBy { endpoint ->
            val host = runCatching { URI(endpoint).host }.getOrNull()?.trim('[', ']') ?: return@sortedBy 1
            val ip = parseIpv4(host)
            when {
                ip == null || !isPrivate(ip) -> 1
                networks.any { sameSubnet(ip, it.address.address, it.prefixLength) } -> 0
                else -> 2
            }
        }

    /** Direct endpoints that are public addresses or host names (port forwarding, DNS, VPN). */
    fun publicEndpoints(invite: Invite): List<String> = invite.direct.filter { endpoint ->
        val host = runCatching { URI(endpoint).host }.getOrNull()?.trim('[', ']') ?: return@filter false
        val ip = parseIpv4(host)
        ip == null || !isPrivate(ip)
    }

    /** Direct endpoints of an invitation that point to a different private network. */
    fun unreachableDirectEndpoints(invite: Invite, networks: List<LocalNetwork> = localNetworks()): List<String> =
        invite.direct.filter { endpoint ->
            val host = runCatching { URI(endpoint).host }.getOrNull()?.trim('[', ']') ?: return@filter false
            isPrivateAddressOnAnotherNetwork(host, networks)
        }

    /** Who can use an address, as far as the address itself tells (no DNS lookups). */
    enum class Reach { NOBODY, THIS_COMPUTER, LOCAL_NETWORK, INTERNET }

    /**
     * Who can reach [host]: `0.0.0.0` and `::` nobody (a listen address, not a destination),
     * loopback only this computer, private, link-local, carrier-grade NAT / Tailscale (100.64/10),
     * IPv6 unique-local and `.local` names only the local network or a VPN, anything else
     * (public addresses, DNS names) the internet.
     */
    fun reachOf(host: String): Reach {
        val h = host.trim().trim('[', ']').lowercase()
        if (h.isEmpty()) return Reach.NOBODY
        if (h == "localhost" || h.endsWith(".localhost")) return Reach.THIS_COMPUTER
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return Reach.LOCAL_NETWORK
        val ipv4 = parseIpv4(h)
        val address = when {
            ipv4 != null -> InetAddress.getByAddress(ipv4)
            ':' in h -> runCatching { InetAddress.getByName(h) }.getOrNull() ?: return Reach.NOBODY // a literal: no lookup
            else -> return Reach.INTERNET // a DNS name
        }
        val bytes = address.address
        return when {
            address.isAnyLocalAddress -> Reach.NOBODY
            address.isLoopbackAddress -> Reach.THIS_COMPUTER
            address.isSiteLocalAddress || address.isLinkLocalAddress -> Reach.LOCAL_NETWORK
            bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64 -> Reach.LOCAL_NETWORK
            bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc -> Reach.LOCAL_NETWORK
            else -> Reach.INTERNET
        }
    }

    /** Who can reach the host of a `wss://` URL; [Reach.NOBODY] when it is not a URL. */
    fun reachOfUrl(url: String): Reach =
        runCatching { URI(url.trim()).host }.getOrNull()?.let(::reachOf) ?: Reach.NOBODY

    /**
     * Who can use an invitation with these addresses: the widest reach of its direct endpoints
     * and its relay. Seen from the host, before anyone tries.
     */
    fun inviteReach(direct: List<String>, relay: String?): Reach =
        (direct + listOfNotNull(relay)).map(::reachOfUrl).maxOrNull() ?: Reach.NOBODY

    internal fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            bytes[i] = n.toByte()
        }
        return bytes
    }

    internal fun isPrivate(a: ByteArray): Boolean {
        val b0 = a[0].toInt() and 0xff
        val b1 = a[1].toInt() and 0xff
        return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168)
    }

    internal fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        if (b.size != 4) return false
        val bits = prefix.coerceIn(0, 32)
        for (i in 0 until 4) {
            val maskBits = (bits - i * 8).coerceIn(0, 8)
            val mask = if (maskBits == 0) 0 else (0xff shl (8 - maskBits)) and 0xff
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
        }
        return true
    }
}
