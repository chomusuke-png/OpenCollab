package dev.opencollab.transport

import dev.opencollab.core.protocol.Role
import dev.opencollab.core.transport.Connector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Base64

/**
 * Everything a guest needs to join: where to connect, which certificate to expect, and the
 * session secret. Treat an invitation like a password and share it over a private channel.
 */
@Serializable
data class Invite(
    val v: Int = 1,
    val project: String,
    val role: Role,
    val token: String,
    /** SHA-256 fingerprint of the host's session certificate. */
    val fingerprint: String,
    /** Direct WSS endpoints, tried in order. */
    val direct: List<String> = emptyList(),
    val relay: String? = null,
    val room: String? = null,
    /** Optional pinned fingerprint of a self-signed relay certificate. */
    val relayFingerprint: String? = null,
) {
    fun encode(): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8))

    /** Direct endpoints first (same network, then public/forwarded, then other networks), then the relay. */
    fun connector(directTimeoutMillis: Int = 3_000): Connector {
        val connectors = mutableListOf<Connector>()
        NetworkHints.orderEndpoints(direct).forEach {
            connectors += WebSocketConnector(URI(it), TrustMode.Pinned(fingerprint), directTimeoutMillis)
        }
        if (relay != null && room != null) {
            val trust = relayFingerprint?.let { TrustMode.PinnedRelay(it) } ?: TrustMode.SystemCertificateAuthorities
            connectors += RelayConnector(URI(relay), room, fingerprint, trust)
        }
        return FirstWorkingConnector(connectors)
    }

    companion object {
        const val PREFIX = "oc1:"
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): Invite {
            val trimmed = text.trim().filterNot { it.isWhitespace() }
            require(trimmed.startsWith(PREFIX)) { "Not an OpenCollab invitation" }
            require(trimmed.length < 8_192) { "Invitation too long" }
            val raw = try {
                String(Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX)), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Malformed invitation")
            }
            val invite = try { json.decodeFromString(serializer(), raw) } catch (e: Exception) {
                throw IllegalArgumentException("Malformed invitation")
            }
            require(invite.v == 1) { "Unsupported invitation version ${invite.v}" }
            require(invite.role != Role.HOST) { "Invalid invitation role" }
            require(TlsIdentity.normalizeFingerprint(invite.fingerprint).length == 64) { "Invalid certificate fingerprint" }
            invite.direct.forEach { require(URI(it).scheme == "wss") { "Only wss:// endpoints are allowed" } }
            invite.relay?.let { require(URI(it).scheme == "wss") { "The relay must use wss://" } }
            require(invite.direct.isNotEmpty() || (invite.relay != null && invite.room != null)) { "Invitation has no endpoint" }
            return invite
        }
    }
}
