package dev.opencollab.transport.router

import com.sun.net.httpserver.HttpServer
import dev.opencollab.transport.router.PortMappingException.Reason
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** UPnP against a simulated router: an HTTP server on the loopback address. */
class UpnpTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
    private val base get() = "http://${loopback.hostAddress}:${server.address.port}"

    /** The router's mappings: external port -> (client, internal port). */
    private val mappings = LinkedHashMap<Int, Pair<String, Int>>()
    private val actions = CopyOnWriteArrayList<String>()
    @Volatile private var description = description("/ctl")
    @Volatile private var onlyPermanent = false
    @Volatile private var listsMappings = true

    private fun description(control: String, type: String = "urn:schemas-upnp-org:service:WANIPConnection:1") = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceList><device><deviceList><device>
          <serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType><controlURL>/common</controlURL></service>
            <service><serviceType>$type</serviceType><controlURL>$control</controlURL></service>
          </serviceList>
        </device></deviceList></device></deviceList></device></root>
    """.trimIndent().trim()

    init {
        server.createContext("/desc.xml") { exchange ->
            val bytes = description.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/ctl") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            val action = exchange.requestHeaders.getFirst("SOAPAction").substringAfter('#').removeSuffix("\"")
            actions += action
            fun arg(name: String) = Regex("<$name>(.*?)</$name>").find(body)?.groupValues?.get(1) ?: ""
            val (status, answer) = when (action) {
                "GetExternalIPAddress" -> 200 to "<NewExternalIPAddress>81.2.69.160</NewExternalIPAddress>"
                "GetSpecificPortMappingEntry" -> {
                    val entry = mappings[arg("NewExternalPort").toInt()]
                    when {
                        !listsMappings -> 500 to error(401, "Invalid Action")
                        entry == null -> 500 to error(714, "NoSuchEntryInArray")
                        else -> 200 to "<NewInternalPort>${entry.second}</NewInternalPort><NewInternalClient>${entry.first}</NewInternalClient><NewEnabled>1</NewEnabled>"
                    }
                }
                "AddPortMapping" -> when {
                    onlyPermanent && arg("NewLeaseDuration") != "0" -> 500 to error(725, "OnlyPermanentLeasesSupported")
                    else -> {
                        mappings[arg("NewExternalPort").toInt()] = arg("NewInternalClient") to arg("NewInternalPort").toInt()
                        200 to ""
                    }
                }
                "DeletePortMapping" -> {
                    mappings.remove(arg("NewExternalPort").toInt())
                    200 to ""
                }
                else -> 500 to error(401, "Invalid Action")
            }
            val xml = if (status == 200) {
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                    "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">$answer</u:${action}Response></s:Body></s:Envelope>"
            } else {
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>$answer</detail></s:Fault></s:Body></s:Envelope>"
            }
            val bytes = xml.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun error(code: Int, text: String) =
        "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode><errorDescription>$text</errorDescription></UPnPError>"

    @AfterEach
    fun stop() = server.stop(0)

    private fun gateway(): UpnpGateway = UpnpDiscovery.gatewaysAt(URI("$base/desc.xml")).single()

    @Test
    fun `finds the WAN service, reads the address and maps the port for this computer`() {
        val router = gateway()
        assertEquals("81.2.69.160", router.externalAddress().hostAddress)
        val mapping = router.map(47800, 47800, 3600)
        assertEquals(47800, mapping.externalPort)
        assertEquals(3600, mapping.lifetimeSeconds)
        assertEquals(loopback.hostAddress to 47800, mappings[47800])

        router.unmap(mapping)
        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `a port forwarded to another device is neither taken over nor removed`() {
        mappings[47800] = "192.168.1.99" to 22
        val router = gateway()
        assertEquals(Reason.PORT_TAKEN, assertThrows<PortMappingException> { router.map(47800, 47800, 3600) }.reason)
        assertEquals("192.168.1.99" to 22, mappings[47800])
        assertTrue("AddPortMapping" !in actions)

        router.unmap(PortMapping(RouterProtocol.UPNP, loopback, 47800, 47800, 3600))
        assertEquals("192.168.1.99" to 22, mappings[47800], "never removes a mapping that is not ours")
    }

    @Test
    fun `an old mapping of ours is reused instead of duplicated`() {
        mappings[47800] = loopback.hostAddress to 47800
        gateway().map(47800, 47800, 3600)
        assertEquals(1, mappings.size)
    }

    @Test
    fun `routers that only keep permanent mappings get one, and routers that cannot list mappings still work`() {
        onlyPermanent = true
        val mapping = gateway().map(47800, 47800, 3600)
        assertEquals(0, mapping.lifetimeSeconds)

        listsMappings = false
        val router = gateway()
        router.unmap(mapping)
        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `descriptions with a DTD, foreign control URLs or no WAN service are refused`() {
        description = "<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root>&x;</root>"
        assertEquals(Reason.INVALID_RESPONSE, assertThrows<PortMappingException> { UpnpDiscovery.gatewaysAt(URI("$base/desc.xml")) }.reason)

        description = description("http://198.51.100.7/ctl")
        assertTrue(UpnpDiscovery.gatewaysAt(URI("$base/desc.xml")).isEmpty(), "a control URL on another host is ignored")

        description = description("/ctl", type = "urn:schemas-upnp-org:service:Layer3Forwarding:1")
        assertTrue(UpnpDiscovery.gatewaysAt(URI("$base/desc.xml")).isEmpty())
    }

    @Test
    fun `search answers are only followed to the device that sent them`() {
        val headers = UpnpDiscovery.parseSsdpAnswer("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nLocation: http://192.168.1.1:5000/rootDesc.xml\r\nST: upnp:rootdevice\r\n\r\n")
        assertEquals("http://192.168.1.1:5000/rootDesc.xml", headers?.get("location"))
        assertNull(UpnpDiscovery.parseSsdpAnswer("NOTIFY * HTTP/1.1\r\n\r\n"))

        val router = InetAddress.getByName("192.168.1.1")
        assertNotNull(UpnpDiscovery.checkLocation("http://192.168.1.1:5000/rootDesc.xml", router))
        for (bad in listOf(
            "http://192.168.1.2:5000/rootDesc.xml", // another device
            "https://192.168.1.1/rootDesc.xml",
            "http://router.local/rootDesc.xml", // no name lookups
            "http://user:pw@192.168.1.1/rootDesc.xml",
            "file:///etc/passwd",
        )) {
            assertNull(UpnpDiscovery.checkLocation(bad, router), bad)
        }
        assertNull(UpnpDiscovery.checkLocation("http://81.2.69.160/desc.xml", InetAddress.getByName("81.2.69.160")), "never a device on the internet")
    }
}
