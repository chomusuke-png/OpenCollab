package dev.opencollab.plugin.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.selected
import dev.opencollab.core.files.SharingRules
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.transport.PortForwarding

/** Settings | Tools | OpenCollab. */
class OpenCollabConfigurable : BoundConfigurable(message("configurable.name")) {
    private val settings = OpenCollabSettings.getInstance()
    private val secretField = JBPasswordField()

    /** Address fields, shown as dots unless addresses are shown (streaming-safe) or being edited. */
    private val addressFields = mutableListOf<AddressField>()
    private fun addressField() = AddressField(settings.state.showAddresses).also { addressFields += it }

    /** A port, hidden like the addresses (an address is often recognised by its port). */
    private fun Row.portField(property: kotlin.reflect.KMutableProperty0<Int>): Cell<AddressField> =
        cell(addressField()).columns(COLUMNS_SHORT)
            .bindText({ property.get().toString() }, { text -> text.trim().toIntOrNull()?.let(property::set) })
            .validationOnInput { field ->
                if (String(field.password).trim().toIntOrNull()?.takeIf { it in 0..65535 } == null) error(message("settings.port.invalid")) else null
            }

    /** Fills [field] with this machine's public IP, queried off the UI thread. */
    private fun detectPublicIp(field: AddressField, service: String) {
        val modality = ModalityState.stateForComponent(field)
        field.emptyText.text = message("settings.forwarding.detecting")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { PortForwarding.detectPublicIp(service) }
            ApplicationManager.getApplication().invokeLater({
                result.onSuccess { field.text = it }
                    .onFailure { field.emptyText.text = message("settings.forwarding.detect.failed", it.message ?: "") }
            }, modality)
        }
    }

    /**
     * Everyday options first (who you are, how invitations and the session behave), then what is
     * shared, then the connection options most people never touch, folded unless in use.
     */
    override fun createPanel(): DialogPanel {
        val s = settings.state
        return panel {
            group(message("settings.group.session")) {
                row(message("settings.display.name")) {
                    textField().bindText(s::displayName).columns(COLUMNS_LARGE)
                        .validationOnApply { if (it.text.isBlank() || it.text.trim().length > 64) error(message("settings.display.name.invalid")) else null }
                }
                row(message("settings.presence.interval")) {
                    spinner(0..1000, 5).bindIntValue(s::presenceIntervalMs)
                        .comment(message("settings.presence.interval.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
                row {
                    checkBox(message("settings.share.consoles")).bindSelected(s::shareConsoles)
                        .comment(message("settings.share.consoles.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
                row {
                    checkBox(message("settings.share.ide")).bindSelected(s::shareIdeState)
                        .comment(message("settings.share.ide.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
                row {
                    checkBox(message("settings.allow.commands")).bindSelected(s::allowCommandRequests)
                        .comment(message("settings.allow.commands.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
            }

            group(message("settings.group.invitations")) {
                row {
                    checkBox(message("settings.approve.joins")).bindSelected(s::approveJoins)
                        .comment(message("settings.approve.joins.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
                row(message("settings.invite.expiry")) {
                    intTextField(0..MAX_INVITE_MINUTES).bindIntText(s::inviteExpiryMinutes)
                        .comment(message("settings.invite.expiry.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
                row {
                    checkBox(message("settings.show.addresses")).bindSelected(s::showAddresses)
                        .comment(message("settings.show.addresses.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                        .onChanged { box -> addressFields.forEach { it.shown = box.isSelected } }
                }
                row {
                    checkBox(message("settings.invite.single")).bindSelected(s::singleUseInvites)
                        .comment(message("settings.invite.single.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
            }

            group(message("settings.group.sharing")) {
                row(message("settings.max.size")) {
                    intTextField(1..SharingRules.MAX_ALLOWED_FILE_MB).bindIntText(s::maxFileSizeMb)
                        .comment(message("settings.max.size.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                }
                collapsibleGroup(message("settings.rules.advanced")) {
                    row {
                        textArea().bindText(s::extraExcludes).rows(3).align(AlignX.FILL)
                            .label(message("settings.excludes"), LabelPosition.TOP)
                            .comment(message("settings.excludes.comment", SharingRules.DEFAULT_EXCLUDES.take(12).joinToString(", ")), MAX_LINE_LENGTH_WORD_WRAP)
                    }
                    row {
                        textArea().bindText(s::includes).rows(2).align(AlignX.FILL)
                            .label(message("settings.includes"), LabelPosition.TOP)
                            .comment(message("settings.includes.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                    }
                    row {
                        textArea().bindText(s::extraGuestReadOnly).rows(2).align(AlignX.FILL)
                            .label(message("settings.readonly"), LabelPosition.TOP)
                            .comment(message("settings.readonly.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                    }
                }
            }

            group(message("settings.group.connection")) {
                lateinit var directEnabled: Cell<JBCheckBox>
                collapsibleGroup(message("settings.group.direct")) {
                    row { directEnabled = checkBox(message("settings.direct.enable")).bindSelected(s::enableDirect) }
                    indent {
                        row(message("settings.listen.address")) {
                            cell(addressField()).bindText(s::bindAddress).comment(message("settings.listen.address.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                        }.enabledIf(directEnabled.selected)
                        row(message("settings.port")) {
                            portField(s::port).comment(message("settings.port.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                        }.enabledIf(directEnabled.selected)
                        row(message("settings.advertised")) {
                            cell(addressField()).bindText(s::advertisedHosts).columns(COLUMNS_LARGE).comment(message("settings.advertised.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                        }.enabledIf(directEnabled.selected)
                    }
                }.apply { expanded = s.enableDirect && (s.bindAddress != "0.0.0.0" || s.advertisedHosts.isNotBlank()) }

                collapsibleGroup(message("settings.group.forwarding")) {
                    lateinit var forwardingEnabled: Cell<JBCheckBox>
                    lateinit var publicField: Cell<AddressField>
                    row {
                        forwardingEnabled = checkBox(message("settings.forwarding.enable")).bindSelected(s::portForwarding)
                            .comment(message("settings.forwarding.enable.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                    }
                    indent {
                        row(message("settings.forwarding.public")) {
                            publicField = cell(addressField()).bindText(s::publicAddress).columns(COLUMNS_LARGE)
                                .comment(message("settings.forwarding.public.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                            button(message("settings.forwarding.detect")) { detectPublicIp(publicField.component, s.publicIpService) }
                        }.enabledIf(forwardingEnabled.selected)
                        row(message("settings.forwarding.port")) {
                            portField(s::externalPort).comment(message("settings.forwarding.port.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                        }.enabledIf(forwardingEnabled.selected)
                        row(message("settings.forwarding.service")) {
                            textField().bindText(s::publicIpService).columns(COLUMNS_LARGE).comment(message("settings.forwarding.service.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                        }.enabledIf(forwardingEnabled.selected)
                    }
                }.apply { expanded = s.portForwarding }

                collapsibleGroup(message("settings.group.relay")) {
                    lateinit var relayEnabled: Cell<JBCheckBox>
                    row { relayEnabled = checkBox(message("settings.relay.enable")).bindSelected(s::enableRelay) }
                    indent {
                        row(message("settings.relay.url")) {
                            cell(addressField()).bindText(s::relayUrl).columns(COLUMNS_LARGE).comment("wss://relay.example.com:8443", MAX_LINE_LENGTH_WORD_WRAP)
                                .validationOnApply {
                                    if (relayEnabled.component.isSelected && !String(it.password).trim().startsWith("wss://")) error(message("settings.relay.url.invalid")) else null
                                }
                        }.enabledIf(relayEnabled.selected)
                        row(message("settings.relay.fingerprint")) {
                            textField().bindText(s::relayFingerprint).columns(COLUMNS_LARGE).comment(message("settings.relay.fingerprint.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                        }.enabledIf(relayEnabled.selected)
                        row(message("settings.relay.secret")) {
                            cell(secretField).columns(COLUMNS_LARGE).comment(message("settings.relay.secret.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                        }.enabledIf(relayEnabled.selected)
                    }
                }.apply { expanded = s.enableRelay }
            }
        }
    }

    /**
     * The relay secret as stored in the IDE password safe; null until loaded. The password safe can
     * be slow (it may ask the operating system's keychain), so it is never read or written on the
     * UI thread: it is loaded once in the background and the field stays disabled until then.
     */
    @Volatile private var storedSecret: String? = null

    override fun reset() {
        super.reset()
        val known = storedSecret
        if (known != null) {
            secretField.text = known
            return
        }
        secretField.isEnabled = false
        val modality = ModalityState.stateForComponent(secretField)
        ApplicationManager.getApplication().executeOnPooledThread {
            val secret = settings.relaySecret
            ApplicationManager.getApplication().invokeLater({
                storedSecret = secret
                secretField.text = secret
                secretField.isEnabled = true
            }, modality)
        }
    }

    override fun isModified(): Boolean {
        val known = storedSecret ?: return super.isModified() // not loaded yet: the field is untouched
        return super.isModified() || String(secretField.password) != known
    }

    override fun apply() {
        super.apply()
        val known = storedSecret ?: return
        val secret = String(secretField.password)
        if (secret == known) return
        storedSecret = secret
        ApplicationManager.getApplication().executeOnPooledThread { settings.relaySecret = secret }
    }
}

/**
 * A text field for an IP address, host name or URL. While addresses are hidden it shows dots, like
 * a password, except while it has the focus, so the address can still be typed and checked.
 */
internal class AddressField(shown: Boolean) : JBPasswordField() {
    private val dot = echoChar

    var shown: Boolean = shown
        set(value) { field = value; update() }

    init {
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = update()
            override fun focusLost(e: java.awt.event.FocusEvent) = update()
        })
        update()
    }

    val revealed: Boolean get() = shown || isFocusOwner

    private fun update() {
        echoChar = if (revealed) 0.toChar() else dot
        // A password field refuses copy and cut; an address shown on screen can be copied.
        putClientProperty("JPasswordField.cutCopyAllowed", revealed)
        repaint()
    }
}

/** Longest invitation lifetime offered in the settings: one week. */
private const val MAX_INVITE_MINUTES = 7 * 24 * 60
