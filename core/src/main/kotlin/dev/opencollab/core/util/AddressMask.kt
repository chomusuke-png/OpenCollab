package dev.opencollab.core.util

/**
 * Hides network addresses in text shown on screen, like a card number that shows only its last
 * digits: `192.168.1.54:47800` → `•••.•••.•••.54:•••`, `wss://myhome.duckdns.org:47800` →
 * `wss://my•••.duckdns.org:•••`. The port after an address is hidden too. Meant for anything a
 * host might show while streaming; logs written to disk keep the full addresses. Loopback
 * addresses are left as they are.
 */
object AddressMask {
    private const val DOTS = "•••"

    private val ipv4 = Regex("""(?<![\w.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:\d{1,5})?(?![\w.])""")
    /** Bracketed (as in URLs) or containing "::" — so certificate fingerprints (AB:CD:…) are not taken for IPv6. */
    private val ipv6 = Regex(
        """\[[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*](?::\d{1,5}(?!\d))?|(?<![\w:])(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4}){0,6})?(?![\w:])""",
    )
    /** A port named in a sentence: "TCP 47800", "port 47800", "puerto 47800". */
    private val namedPort = Regex("""(?i)\b(tcp|udp|port|puerto)(\s+)(\d{1,5})\b""")
    /** The host part of a URL that is a name, not an address. */
    private val urlHost = Regex("""((?:wss?|https?)://)([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)(:\d{1,5}(?!\d))?""")

    fun mask(text: String): String {
        var out = ipv6.replace(text) { maskIpv6(it.value) }
        out = ipv4.replace(out) { m ->
            if (m.groupValues[1] == "127") m.value else "$DOTS.$DOTS.$DOTS.${m.groupValues[4]}" + port(m.groupValues[5])
        }
        out = urlHost.replace(out) { m ->
            val host = m.groupValues[2]
            if (isLocal(host)) m.value else m.groupValues[1] + maskHostName(host) + port(m.groupValues[3])
        }
        return namedPort.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + DOTS }
    }

    private fun maskIpv6(value: String): String {
        val bracketed = value.startsWith("[")
        val address = if (bracketed) value.substringBefore(']') + "]" else value
        val bare = address.removePrefix("[").removeSuffix("]")
        if (bare == "::1") return value
        val last = bare.substringAfterLast(':')
        val masked = "$DOTS:$DOTS:$last"
        return if (bracketed) "[$masked]" + port(value.substringAfter(']')) else masked
    }

    /** ":47800" → ":•••"; nothing stays nothing. */
    private fun port(value: String) = if (value.isEmpty()) "" else ":$DOTS"

    private fun isLocal(host: String) = host.equals("localhost", ignoreCase = true) || host.startsWith("127.")

    private fun maskHostName(host: String): String {
        if (host.equals("localhost", ignoreCase = true) || host.contains(DOTS) || host.all { it.isDigit() || it == '.' }) return host
        val labels = host.split('.')
        val first = labels.first()
        val shown = first.take(2) + DOTS
        // Keep the domain a name belongs to ("duckdns.org") so the host still recognises it.
        return if (labels.size >= 3) (listOf(shown) + labels.takeLast(2)).joinToString(".") else (listOf(shown) + labels.drop(1)).joinToString(".")
    }
}
