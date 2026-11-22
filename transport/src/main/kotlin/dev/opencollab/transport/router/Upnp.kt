package dev.opencollab.transport.router

import dev.opencollab.transport.router.PortMappingException.Reason
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * UPnP Internet Gateway Device: the router is found with SSDP (a UDP multicast on the local
 * network), describes itself in XML over HTTP, and takes SOAP requests for port mappings.
 *
 * Nothing here trusts the network blindly: the description must come from the device that
 * answered, from a local-network address, without redirects, proxies or DTDs, and small.
 */
object UpnpDiscovery {
    private const val SSDP_PORT = 1900
    private val SSDP_GROUP: InetAddress = InetAddress.getByName("239.255.255.250")
    private const val MAX_DESCRIPTION_BYTES = 256 * 1024

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1",
    )

    /** The WAN services that take port mappings, the most capable first. */
    internal val WAN_SERVICES = listOf(
        "urn:schemas-upnp-org:service:WANIPConnection:2",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1",
    )

    internal val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        // The router is on this network: never send these requests through the IDE's proxy.
        .proxy(HttpClient.Builder.NO_PROXY)
        .build()

    /** A device that answered the search: where its description is, and who sent the answer. */
    internal data class Answer(val location: URI, val sender: InetAddress)

    /**
     * Searches every private IPv4 network of this computer for [timeoutMillis] and returns the
     * routers that offer a WAN service, plus the addresses of all devices that answered (where
     * a NAT-PMP router may be).
     */
    fun discover(timeoutMillis: Int = 2_500): Pair<List<UpnpGateway>, List<InetAddress>> {
        val answers = search(timeoutMillis)
        val gateways = answers.distinctBy { it.location }.flatMap { answer ->
            try {
                gatewaysAt(answer.location)
            } catch (e: IOException) {
                emptyList()
            }
        }
        return gateways to answers.map { it.sender }.distinct()
    }

    private fun search(timeoutMillis: Int): List<Answer> {
        val sockets = localAddresses().mapNotNull { runCatching { DatagramSocket(InetSocketAddress(it, 0)) }.getOrNull() }
            .ifEmpty { listOfNotNull(runCatching { DatagramSocket() }.getOrNull()) }
        val answers = mutableListOf<Answer>()
        try {
            for (socket in sockets) {
                for (target in SEARCH_TARGETS) {
                    val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    runCatching { socket.send(DatagramPacket(message, message.size, SSDP_GROUP, SSDP_PORT)) }
                }
            }
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            val buffer = ByteArray(2048)
            while (System.nanoTime() < deadline && answers.size < 64) {
                for (socket in sockets) {
                    socket.soTimeout = 50
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: IOException) {
                        continue
                    }
                    val headers = parseSsdpAnswer(String(buffer, 0, packet.length, Charsets.US_ASCII)) ?: continue
                    val location = checkLocation(headers["location"] ?: continue, packet.address) ?: continue
                    answers += Answer(location, packet.address)
                }
            }
        } finally {
            sockets.forEach { it.close() }
        }
        return answers
    }

    /** This computer's addresses on private IPv4 networks (one search per network). */
    private fun localAddresses(): List<InetAddress> = try {
        NetworkInterface.networkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
    } catch (e: IOException) {
        emptyList()
    }

    /** The headers of an SSDP search answer (lower-case names), or null when it is not one. */
    internal fun parseSsdpAnswer(text: String): Map<String, String>? {
        val lines = text.split("\r\n", "\n")
        if (!lines.first().trim().startsWith("HTTP/1.1 200", ignoreCase = true)) return null
        return lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
    }

    /**
     * The description URL of an answer, accepted only as plain http to an IPv4 literal equal to
     * the answer's sender on the local network, so a device can only point at itself and never
     * at another computer or a service on the internet.
     */
    internal fun checkLocation(location: String, sender: InetAddress): URI? {
        val uri = runCatching { URI(location.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "http" || uri.userInfo != null) return null
        val host = NetAddresses.parseIpv4(uri.host ?: return null) ?: return null
        if (host != sender || !NetAddresses.isLocalNetwork(sender)) return null
        if (uri.port != -1 && !NetAddresses.validPort(uri.port)) return null
        return uri
    }

    /** Reads the device description at [location] and returns its WAN services, best first. */
    internal fun gatewaysAt(location: URI): List<UpnpGateway> {
        val xml = get(location)
        val document = parseXml(xml)
        val base = document.getElementsByTagNameNS("*", "URLBase").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val baseUri = base?.let { runCatching { URI(it) }.getOrNull() }?.takeIf { sameHost(it, location) } ?: location
        val services = document.getElementsByTagNameNS("*", "service")
        val found = (0 until services.length).mapNotNull { i ->
            val service = services.item(i) as Element
            val type = child(service, "serviceType") ?: return@mapNotNull null
            if (type !in WAN_SERVICES) return@mapNotNull null
            val control = child(service, "controlURL") ?: return@mapNotNull null
            val controlUri = runCatching { baseUri.resolve(control) }.getOrNull()?.takeIf { sameHost(it, location) } ?: return@mapNotNull null
            type to controlUri
        }
        val local = localAddressTowards(InetAddress.getByName(location.host))
        return found.sortedBy { WAN_SERVICES.indexOf(it.first) }.map { (type, control) -> UpnpGateway(control, type, local) }
    }

    private fun sameHost(uri: URI, location: URI) =
        uri.scheme?.lowercase() == "http" && uri.host == location.host && uri.userInfo == null && (uri.port == -1 || NetAddresses.validPort(uri.port))

    private fun child(element: Element, name: String): String? {
        val nodes = element.getElementsByTagNameNS("*", name)
        return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun localAddressTowards(router: InetAddress): InetAddress = DatagramSocket().use { socket ->
        socket.connect(InetSocketAddress(router, SSDP_PORT))
        socket.localAddress
    }

    private fun get(uri: URI): ByteArray {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build()
        val response = send(request)
        if (response.first != 200) throw PortMappingException(Reason.INVALID_RESPONSE, "The router answered HTTP ${response.first} for its description")
        return response.second
    }

    /** Sends [request] and returns the status and at most [MAX_DESCRIPTION_BYTES] of the body. */
    internal fun send(request: HttpRequest): Pair<Int, ByteArray> {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PortMappingException(Reason.CANCELLED, "Interrupted")
        }
        val body = response.body().use { input ->
            val bytes = input.readNBytes(MAX_DESCRIPTION_BYTES + 1)
            if (bytes.size > MAX_DESCRIPTION_BYTES) throw PortMappingException(Reason.INVALID_RESPONSE, "The router sent too much data")
            bytes
        }
        return response.statusCode() to body
    }

    /** XML without DTDs, external entities or XInclude: a hostile device cannot read files or loop. */
    internal fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        // The JDK's own parser, whatever other parser the IDE puts on the class path.
        val factory = DocumentBuilderFactory.newDefaultInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        return try {
            factory.newDocumentBuilder().apply { setErrorHandler(Quiet) }.parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw PortMappingException(Reason.INVALID_RESPONSE, "The router sent invalid XML", e)
        }
    }
}

/** Turns XML errors into exceptions instead of printing them. */
private object Quiet : org.xml.sax.ErrorHandler {
    override fun warning(e: org.xml.sax.SAXParseException) = Unit
    override fun error(e: org.xml.sax.SAXParseException) = throw e
    override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
}

/** One WAN service of a UPnP router, reached at [control]. */
class UpnpGateway internal constructor(
    private val control: URI,
    private val serviceType: String,
    override val localAddress: InetAddress,
) : PortMapper {
    override val protocol = RouterProtocol.UPNP

    /** A UPnP error answer: [code] says why (718 conflict, 725 permanent leases only...). */
    internal class UpnpError(val code: Int, description: String) : IOException("UPnP error $code: $description")

    override fun externalAddress(): InetAddress {
        val text = call("GetExternalIPAddress")["NewExternalIPAddress"]
            ?: throw PortMappingException(Reason.INVALID_RESPONSE, "The router did not say its internet address")
        return NetAddresses.parseIpv4(text) ?: throw PortMappingException(Reason.INVALID_RESPONSE, "The router reported an invalid internet address")
    }

    override fun map(internalPort: Int, externalPort: Int, lifetimeSeconds: Int): PortMapping {
        require(NetAddresses.validPort(internalPort) && NetAddresses.validPort(externalPort) && lifetimeSeconds >= 0)
        val existing = lookup(externalPort)
        if (existing is Lookup.Found && !existing.entry.isOurs(internalPort)) {
            throw PortMappingException(Reason.PORT_TAKEN, "External port $externalPort is already forwarded to another device or port")
        }
        var lease = lifetimeSeconds
        try {
            add(internalPort, externalPort, lease)
        } catch (e: UpnpError) {
            when (e.code) {
                // Some routers only keep permanent mappings; they are removed when hosting ends.
                725 -> { lease = 0; addOrFail(internalPort, externalPort, lease) }
                else -> throw mappingError(e, externalPort)
            }
        }
        // Believe the mapping only when the router lists it for this computer.
        val confirmed = lookup(externalPort)
        if (confirmed is Lookup.Found && !confirmed.entry.isOurs(internalPort)) {
            throw PortMappingException(Reason.INVALID_RESPONSE, "The router lists external port $externalPort for another device after mapping it")
        }
        return PortMapping(protocol, localAddress, internalPort, externalPort, lease)
    }

    override fun unmap(mapping: PortMapping) {
        // Never remove a mapping that is no longer ours (it expired and someone else took the port).
        when (val existing = lookup(mapping.externalPort)) {
            Lookup.None -> return
            is Lookup.Found -> if (!existing.entry.isOurs(mapping.internalPort)) return
            Lookup.Unknown -> Unit // the router cannot list mappings: delete the one we created
        }
        try {
            call("DeletePortMapping", "NewRemoteHost" to "", "NewExternalPort" to mapping.externalPort.toString(), "NewProtocol" to "TCP")
        } catch (e: UpnpError) {
            if (e.code != 714) throw mappingError(e, mapping.externalPort)
        }
    }

    private fun addOrFail(internalPort: Int, externalPort: Int, lease: Int) = try {
        add(internalPort, externalPort, lease)
    } catch (e: UpnpError) {
        throw mappingError(e, externalPort)
    }

    private fun add(internalPort: Int, externalPort: Int, lease: Int) {
        call(
            "AddPortMapping",
            "NewRemoteHost" to "",
            "NewExternalPort" to externalPort.toString(),
            "NewProtocol" to "TCP",
            "NewInternalPort" to internalPort.toString(),
            "NewInternalClient" to localAddress.hostAddress,
            "NewEnabled" to "1",
            "NewPortMappingDescription" to DESCRIPTION,
            "NewLeaseDuration" to lease.toString(),
        )
    }

    private fun mappingError(e: UpnpError, externalPort: Int) = when (e.code) {
        718, 724 -> PortMappingException(Reason.PORT_TAKEN, "External port $externalPort cannot be used (${e.message})", e)
        else -> PortMappingException(Reason.REFUSED, "The router refused the port mapping (${e.message})", e)
    }

    private data class Entry(val client: String, val internalPort: Int)

    private fun Entry.isOurs(port: Int) = client == localAddress.hostAddress && internalPort == port

    private sealed interface Lookup {
        data object None : Lookup
        /** The router cannot list mappings (the action is optional for some routers). */
        data object Unknown : Lookup
        data class Found(val entry: Entry) : Lookup
    }

    /** What the router forwards TCP [externalPort] to. */
    private fun lookup(externalPort: Int): Lookup {
        val values = try {
            call("GetSpecificPortMappingEntry", "NewRemoteHost" to "", "NewExternalPort" to externalPort.toString(), "NewProtocol" to "TCP")
        } catch (e: UpnpError) {
            return if (e.code == 714) Lookup.None else Lookup.Unknown // 714 NoSuchEntryInArray
        }
        val client = values["NewInternalClient"]?.let { NetAddresses.parseIpv4(it)?.hostAddress }
            ?: throw PortMappingException(Reason.INVALID_RESPONSE, "The router listed a port mapping without a valid target")
        val port = values["NewInternalPort"]?.toIntOrNull()?.takeIf(NetAddresses::validPort)
            ?: throw PortMappingException(Reason.INVALID_RESPONSE, "The router listed a port mapping with an invalid port")
        return Lookup.Found(Entry(client, port))
    }

    /** Calls a SOAP [action] and returns the answer's values; throws [UpnpError] for a UPnP error. */
    private fun call(action: String, vararg args: Pair<String, String>): Map<String, String> {
        val body = buildString {
            append("<?xml version=\"1.0\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><u:$action xmlns:u=\"$serviceType\">")
            for ((name, value) in args) append("<$name>").append(escape(value)).append("</$name>")
            append("</u:$action></s:Body></s:Envelope>")
        }
        val request = HttpRequest.newBuilder(control)
            .timeout(Duration.ofSeconds(4))
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPAction", "\"$serviceType#$action\"")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val (status, bytes) = UpnpDiscovery.send(request)
        val document = UpnpDiscovery.parseXml(bytes)
        if (status != 200) {
            val code = document.getElementsByTagNameNS("*", "errorCode").item(0)?.textContent?.trim()?.toIntOrNull()
                ?: throw PortMappingException(Reason.INVALID_RESPONSE, "The router answered HTTP $status to $action")
            val description = document.getElementsByTagNameNS("*", "errorDescription").item(0)?.textContent?.trim().orEmpty()
            // Shown to the user: only plain characters, never markup from the network.
            throw UpnpError(code, description.filter { it.isLetterOrDigit() || it in " .,:;-_()'/" }.take(100))
        }
        val answer = document.getElementsByTagNameNS("*", "${action}Response").item(0) as? Element
            ?: throw PortMappingException(Reason.INVALID_RESPONSE, "The router sent no answer to $action")
        val values = LinkedHashMap<String, String>()
        var node = answer.firstChild
        while (node != null) {
            if (node is Element) values[node.localName ?: node.nodeName] = node.textContent.trim()
            node = node.nextSibling
        }
        return values
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun toString() = "UPnP $serviceType"

    companion object {
        const val DESCRIPTION = "OpenCollab"
    }
}
