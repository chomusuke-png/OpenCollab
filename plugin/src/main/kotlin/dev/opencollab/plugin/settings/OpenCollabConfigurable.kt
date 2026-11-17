package dev.opencollab.plugin.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.selected
import dev.opencollab.core.files.SharingRules
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.transport.PortForwarding

/** Settings | Tools | OpenCollab. */
class OpenCollabConfigurable : BoundConfigurable(message("configurable.name")) {
    private val settings = OpenCollabSettings.getInstance()
    private val secretField = JBPasswordField()

    /** Fills [field] with this machine's public IP, queried off the UI thread. */
    private fun detectPublicIp(field: JBTextField, service: String) {
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
                row(message("settings.default.role")) {
                    comboBox(
                        listOf("EDITOR", "VIEWER"),
                        com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { value: String? ->
                            value?.let { dev.opencollab.plugin.ui.roleName(dev.opencollab.core.protocol.Role.valueOf(it)) } ?: ""
                        },
                    ).bindItem(s::defaultInviteRole.toNullableProperty())
                }
                row(message("settings.presence.interval")) {
                    spinner(0..1000, 5).bindIntValue(s::presenceIntervalMs)
                        .comment(message("settings.presence.interval.comment"))
                }
                row {
                    checkBox(message("settings.share.consoles")).bindSelected(s::shareConsoles)
                        .comment(message("settings.share.consoles.comment"))
                }
                row {
                    checkBox(message("settings.share.ide")).bindSelected(s::shareIdeState)
                        .comment(message("settings.share.ide.comment"))
                }
                row {
                    checkBox(message("settings.allow.commands")).bindSelected(s::allowCommandRequests)
                        .comment(message("settings.allow.commands.comment"))
                }
            }

            group(message("settings.group.sharing")) {
                row(message("settings.max.size")) {
                    intTextField(1..SharingRules.MAX_ALLOWED_FILE_MB).bindIntText(s::maxFileSizeMb)
                        .comment(message("settings.max.size.comment"))
                }
                collapsibleGroup(message("settings.rules.advanced")) {
                    row {
                        textArea().bindText(s::extraExcludes).rows(3).align(AlignX.FILL)
                            .label(message("settings.excludes"), LabelPosition.TOP)
                            .comment(message("settings.excludes.comment", SharingRules.DEFAULT_EXCLUDES.take(12).joinToString(", ")))
                    }
                    row {
                        textArea().bindText(s::includes).rows(2).align(AlignX.FILL)
                            .label(message("settings.includes"), LabelPosition.TOP)
                            .comment(message("settings.includes.comment"))
                    }
                    row {
                        textArea().bindText(s::extraGuestReadOnly).rows(2).align(AlignX.FILL)
                            .label(message("settings.readonly"), LabelPosition.TOP)
                            .comment(message("settings.readonly.comment"))
                    }
                }
            }

            group(message("settings.group.connection")) {
                lateinit var directEnabled: Cell<JBCheckBox>
                collapsibleGroup(message("settings.group.direct")) {
                    row { directEnabled = checkBox(message("settings.direct.enable")).bindSelected(s::enableDirect) }
                    indent {
                        row(message("settings.listen.address")) {
                            textField().bindText(s::bindAddress).comment(message("settings.listen.address.comment"))
                        }.enabledIf(directEnabled.selected)
                        row(message("settings.port")) {
                            intTextField(0..65535).bindIntText(s::port).comment(message("settings.port.comment"))
                        }.enabledIf(directEnabled.selected)
                        row(message("settings.advertised")) {
                            textField().bindText(s::advertisedHosts).columns(COLUMNS_LARGE).comment(message("settings.advertised.comment"))
                        }.enabledIf(directEnabled.selected)
                    }
                }.apply { expanded = s.enableDirect && (s.bindAddress != "0.0.0.0" || s.advertisedHosts.isNotBlank()) }

                collapsibleGroup(message("settings.group.forwarding")) {
                    lateinit var forwardingEnabled: Cell<JBCheckBox>
                    lateinit var publicField: Cell<JBTextField>
                    row {
                        forwardingEnabled = checkBox(message("settings.forwarding.enable")).bindSelected(s::portForwarding)
                            .comment(message("settings.forwarding.enable.comment"))
                    }
                    indent {
                        row(message("settings.forwarding.public")) {
                            publicField = textField().bindText(s::publicAddress).columns(COLUMNS_LARGE)
                                .comment(message("settings.forwarding.public.comment"))
                            button(message("settings.forwarding.detect")) { detectPublicIp(publicField.component, s.publicIpService) }
                        }.enabledIf(forwardingEnabled.selected)
                        row(message("settings.forwarding.port")) {
                            intTextField(0..65535).bindIntText(s::externalPort).comment(message("settings.forwarding.port.comment"))
                        }.enabledIf(forwardingEnabled.selected)
                        row(message("settings.forwarding.service")) {
                            textField().bindText(s::publicIpService).columns(COLUMNS_LARGE).comment(message("settings.forwarding.service.comment"))
                        }.enabledIf(forwardingEnabled.selected)
                    }
                }.apply { expanded = s.portForwarding }

                collapsibleGroup(message("settings.group.relay")) {
                    lateinit var relayEnabled: Cell<JBCheckBox>
                    row { relayEnabled = checkBox(message("settings.relay.enable")).bindSelected(s::enableRelay) }
                    indent {
                        row(message("settings.relay.url")) {
                            textField().bindText(s::relayUrl).columns(COLUMNS_LARGE).comment("wss://relay.example.com:8443")
                                .validationOnApply {
                                    if (relayEnabled.component.isSelected && !it.text.trim().startsWith("wss://")) error(message("settings.relay.url.invalid")) else null
                                }
                        }.enabledIf(relayEnabled.selected)
                        row(message("settings.relay.fingerprint")) {
                            textField().bindText(s::relayFingerprint).columns(COLUMNS_LARGE).comment(message("settings.relay.fingerprint.comment"))
                        }.enabledIf(relayEnabled.selected)
                        row(message("settings.relay.secret")) {
                            cell(secretField).columns(COLUMNS_LARGE).comment(message("settings.relay.secret.comment"))
                        }.enabledIf(relayEnabled.selected)
                    }
                }.apply { expanded = s.enableRelay }
            }
        }
    }

    override fun reset() {
        super.reset()
        secretField.text = settings.relaySecret
    }

    override fun isModified(): Boolean = super.isModified() || String(secretField.password) != settings.relaySecret

    override fun apply() {
        super.apply()
        settings.relaySecret = String(secretField.password)
    }
}
