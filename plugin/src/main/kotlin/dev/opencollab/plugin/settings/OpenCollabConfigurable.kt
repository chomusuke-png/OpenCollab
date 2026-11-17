package dev.opencollab.plugin.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.toNullableProperty
import dev.opencollab.core.files.SharingRules
import dev.opencollab.plugin.OpenCollabBundle.message

/** Settings | Tools | OpenCollab. */
class OpenCollabConfigurable : BoundConfigurable(message("configurable.name")) {
    private val settings = OpenCollabSettings.getInstance()
    private val secretField = JBPasswordField()

    override fun createPanel(): DialogPanel {
        val s = settings.state
        return panel {
            group(message("settings.group.identity")) {
                row(message("settings.display.name")) { textField().bindText(s::displayName).columns(COLUMNS_LARGE) }
            }
            group(message("settings.group.direct")) {
                row { checkBox(message("settings.direct.enable")).bindSelected(s::enableDirect) }
                row(message("settings.listen.address")) { textField().bindText(s::bindAddress).comment(message("settings.listen.address.comment")) }
                row(message("settings.port")) { intTextField(0..65535).bindIntText(s::port).comment(message("settings.port.comment")) }
                row(message("settings.advertised")) {
                    textField().bindText(s::advertisedHosts).columns(COLUMNS_LARGE)
                        .comment(message("settings.advertised.comment"))
                }
            }
            group(message("settings.group.relay")) {
                row { checkBox(message("settings.relay.enable")).bindSelected(s::enableRelay) }
                row(message("settings.relay.url")) { textField().bindText(s::relayUrl).columns(COLUMNS_LARGE).comment("wss://relay.example.com:8443") }
                row(message("settings.relay.fingerprint")) {
                    textField().bindText(s::relayFingerprint).columns(COLUMNS_LARGE)
                        .comment(message("settings.relay.fingerprint.comment"))
                }
                row(message("settings.relay.secret")) {
                    cell(secretField).columns(COLUMNS_LARGE)
                        .comment(message("settings.relay.secret.comment"))
                }
            }
            group(message("settings.group.sharing")) {
                row(message("settings.default.role")) {
                    comboBox(
                        listOf("EDITOR", "VIEWER"),
                        com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { value: String? ->
                            value?.let { dev.opencollab.plugin.ui.roleName(dev.opencollab.core.protocol.Role.valueOf(it)) } ?: ""
                        },
                    ).bindItem(s::defaultInviteRole.toNullableProperty())
                }
                row(message("settings.max.size")) { intTextField(1..100).bindIntText(s::maxFileSizeMb) }
                row(message("settings.excludes")) {}
                row {
                    textArea().bindText(s::extraExcludes).rows(3).align(AlignX.FILL)
                        .comment(message("settings.excludes.comment",
                            SharingRules.DEFAULT_EXCLUDES.take(12).joinToString(", ")))
                }
                row(message("settings.includes")) {}
                row {
                    textArea().bindText(s::includes).rows(2).align(AlignX.FILL)
                        .comment(message("settings.includes.comment"))
                }
                row(message("settings.readonly")) {}
                row {
                    textArea().bindText(s::extraGuestReadOnly).rows(2).align(AlignX.FILL)
                        .comment(message("settings.readonly.comment"))
                }
            }
            group(message("settings.group.presence")) {
                row(message("settings.presence.interval")) { intTextField(20..1000).bindIntText(s::presenceIntervalMs) }
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
