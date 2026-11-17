package dev.opencollab.transport

import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Support for hosts behind a router with a manually forwarded TCP port: the invitation then
 * also carries the router's public address and external port. The TLS certificate is pinned
 * by fingerprint, so a public endpoint is exactly as safe as a LAN one.
 */
object PortForwarding {
    const val DEFAULT_PUBLIC_IP_SERVICE = "https://api.ipify.org"

    private val hostName = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

    /** Builds the public `wss://` endpoint; [externalPort] 0 means "same as the listen port". */
    fun endpoint(publicHost: String, externalPort: Int, listenPort: Int): String {
        val host = normalizeHost(publicHost)
        val port = if (externalPort == 0) listenPort else externalPort
        require(port in 1..65535) { "Invalid external port $port" }
        val formatted = if (host.contains(':')) "[$host]" else host
        return URI("wss://$formatted:$port").toString()
    }

    /** Validates a public host name or IP literal typed by the user. */
    fun normalizeHost(raw: String): String {
        val host = raw.trim().removePrefix("[").removeSuffix("]")
        require(host.isNotEmpty()) { "The public address is empty" }
        require(!host.contains("://") && !host.contains('/')) { "Enter only a host name or IP address, without wss:// or a path" }
        val isIpv6 = host.contains(':')
        require(isIpv6 || hostName.matches(host)) { "Invalid host name or IP address: $host" }
        if (isIpv6) require(host.all { it.isLetterOrDigit() || it == ':' || it == '.' }) { "Invalid IPv6 address: $host" }
        return host
    }

    /**
     * Asks a "what is my IP" HTTPS service (plain-text response) for this machine's public
     * address. Blocking; call it off the UI thread. The service learns the caller's IP address,
     * which is unavoidable for this purpose; it is only contacted when the user asks for it.
     */
    fun detectPublicIp(serviceUrl: String = DEFAULT_PUBLIC_IP_SERVICE, timeout: Duration = Duration.ofSeconds(8)): String {
        val uri = URI(serviceUrl.trim())
        require(uri.scheme == "https") { "The public IP service must use https://" }
        val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
        val request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "text/plain").GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted")
        }
        if (response.statusCode() != 200) throw IOException("Public IP service answered HTTP ${response.statusCode()}")
        val text = response.body().trim()
        return parseIpLiteral(text) ?: throw IOException("Public IP service returned an unexpected answer")
    }

    /** Accepts only an IPv4/IPv6 literal (never resolves names). */
    internal fun parseIpLiteral(text: String): String? {
        if (text.length > 45 || text.isEmpty()) return null
        if (NetworkHints.parseIpv4(text) != null) return text
        if (text.contains(':') && text.all { it.isLetterOrDigit() || it == ':' || it == '.' } && text.any { it == ':' }) {
            return try { InetAddress.getByName(text).hostAddress.takeIf { text.count { c -> c == ':' } >= 2 } } catch (e: Exception) { null }
        }
        return null
    }
}
