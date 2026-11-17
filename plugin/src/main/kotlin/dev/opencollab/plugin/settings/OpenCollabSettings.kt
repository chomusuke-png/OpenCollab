package dev.opencollab.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.protocol.Role

/**
 * Application-level OpenCollab settings (stored in opencollab.xml in the IDE config
 * directory). The relay host secret is kept in the IDE's PasswordSafe, never in this file.
 */
@Service(Service.Level.APP)
@State(name = "OpenCollabSettings", storages = [Storage("opencollab.xml")])
class OpenCollabSettings : PersistentStateComponent<OpenCollabSettings.State> {
    class State {
        var displayName: String = System.getProperty("user.name") ?: "Developer"
        var enableDirect: Boolean = true
        var bindAddress: String = "0.0.0.0"
        var port: Int = 47800
        /** Overrides the advertised direct addresses (comma separated), e.g. a public DNS name. */
        var advertisedHosts: String = ""
        /** The listen port is forwarded on the router: also advertise the public endpoint. */
        var portForwarding: Boolean = false
        /** Public IP or DNS name of the router; empty = detect with [publicIpService] when hosting starts. */
        var publicAddress: String = ""
        /** Port opened on the router; 0 = same as the listen port. */
        var externalPort: Int = 0
        var publicIpService: String = dev.opencollab.transport.PortForwarding.DEFAULT_PUBLIC_IP_SERVICE
        var enableRelay: Boolean = false
        var relayUrl: String = ""
        /** SHA-256 fingerprint for self-signed relays; empty = verify with system CAs. */
        var relayFingerprint: String = ""
        var defaultInviteRole: String = Role.EDITOR.name
        var extraExcludes: String = ""
        var includes: String = ""
        var extraGuestReadOnly: String = ""
        var maxFileSizeMb: Int = 10
        /** Minimum gap between cursor updates during continuous movement; 0 = send every change at once. */
        var presenceIntervalMs: Int = 0
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun sharingRules(): SharingRules = SharingRules(
        excludes = SharingRules.DEFAULT_EXCLUDES + lines(state.extraExcludes),
        includes = lines(state.includes),
        guestReadOnly = SharingRules.DEFAULT_GUEST_READ_ONLY + lines(state.extraGuestReadOnly),
        maxFileBytes = state.maxFileSizeMb.coerceIn(1, SharingRules.MAX_ALLOWED_FILE_MB).toLong() * 1024 * 1024,
    )

    var relaySecret: String
        get() = PasswordSafe.instance.getPassword(secretAttributes()) ?: ""
        set(value) {
            PasswordSafe.instance.set(secretAttributes(), if (value.isEmpty()) null else Credentials("relay-host", value))
        }

    private fun secretAttributes() = CredentialAttributes(generateServiceName("OpenCollab", "relay-host-secret"))

    companion object {
        fun getInstance(): OpenCollabSettings = ApplicationManager.getApplication().getService(OpenCollabSettings::class.java)

        fun lines(text: String): List<String> = text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
