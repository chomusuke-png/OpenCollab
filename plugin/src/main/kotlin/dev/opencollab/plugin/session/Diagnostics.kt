package dev.opencollab.plugin.session

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import dev.opencollab.core.protocol.PROTOCOL_VERSION
import dev.opencollab.core.util.AddressMask
import dev.opencollab.plugin.settings.OpenCollabSettings
import java.util.Locale

/**
 * A report to paste into a bug report: versions, the kind of session and connection, and the
 * recent activity log. Meant to be posted publicly, so it never contains the relay secret or
 * invitations, and [sanitize] hides addresses (whatever the on-screen setting), the user's
 * home folder, the project path and the names of the people in the session.
 */
object Diagnostics {
    const val LOG_LINES = 150

    fun report(project: Project): String {
        val service = CollabSessionService.getInstance(project)
        val s = OpenCollabSettings.getInstance().state
        val app = ApplicationInfo.getInstance()
        val plugin = pluginVersion()
        val mode = when (val m = service.mode) {
            is CollabSessionService.Mode.Hosting -> "hosting (${m.transports.size} transport(s), console sharing ${onOff(s.shareConsoles)}, IDE state ${onOff(m.ide != null)})"
            is CollabSessionService.Mode.Joined -> "joined as ${service.myRole?.name?.lowercase() ?: "?"}"
            CollabSessionService.Mode.Starting -> "starting"
            CollabSessionService.Mode.Idle -> "not in a session"
        }
        val participants = service.participants
        val roles = participants.groupingBy { it.role.name.lowercase() }.eachCount().entries.joinToString { "${it.value} ${it.key}" }
        val text = buildString {
            appendLine("OpenCollab diagnostics")
            appendLine("Plugin: $plugin (protocol $PROTOCOL_VERSION)")
            appendLine("IDE: ${app.fullApplicationName} (${app.build.asString()})")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("Java: ${System.getProperty("java.runtime.version") ?: System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            appendLine("Language: ${Locale.getDefault().toLanguageTag()}")
            appendLine()
            appendLine("Session: $mode")
            appendLine("Status: ${service.status}")
            if (participants.isNotEmpty()) appendLine("Participants: ${participants.size} ($roles)")
            service.latencies.values.takeIf { it.isNotEmpty() }?.let { appendLine("Round trips (ms): ${it.sorted().joinToString()}") }
            appendLine()
            appendLine("Settings:")
            appendLine("  Direct: ${onOff(s.enableDirect)}, listen ${s.bindAddress.ifBlank { "-" }}:${s.port}, advertised ${s.advertisedHosts.ifBlank { "-" }}")
            appendLine("  Port forwarding: ${onOff(s.portForwarding)}, public ${s.publicAddress.ifBlank { "-" }}, external port ${s.externalPort}")
            appendLine("  Relay: ${onOff(s.enableRelay)}, ${s.relayUrl.ifBlank { "-" }}, fingerprint ${if (s.relayFingerprint.isBlank()) "not set" else "set"}")
            appendLine("  Join approval ${onOff(s.approveJoins)}, invitations expire after ${s.inviteExpiryMinutes} min, single use ${onOff(s.singleUseInvites)}")
            appendLine("  Share consoles ${onOff(s.shareConsoles)}, IDE state ${onOff(s.shareIdeState)}, command requests ${onOff(s.allowCommandRequests)}, max file ${s.maxFileSizeMb} MB")
            appendLine("  Build files read-only ${onOff(s.protectBuildFiles)}, hide passwords and tokens ${onOff(s.hideSecrets)}, debugger values ${onOff(s.shareDebugValues)}")
            appendLine()
            val log = service.log().takeLast(LOG_LINES)
            appendLine("Activity (last ${log.size} lines):")
            log.forEach { appendLine("  $it") }
        }
        val names = (participants.map { it.displayName } + s.displayName).distinct()
        return sanitize(text, names, System.getProperty("user.home"), project.basePath)
    }

    /**
     * Hides addresses, [home] (as `~`), [projectPath] (as `<project>`) and each of [names]
     * (as `<person N>`, the longest first so one name inside another is not left half-hidden).
     */
    fun sanitize(text: String, names: List<String>, home: String?, projectPath: String?): String {
        var out = text
        for (path in listOfNotNull(projectPath).filter { it.length > 3 }) {
            out = replacePath(out, path, "<project>")
        }
        home?.takeIf { it.length > 3 }?.let { out = replacePath(out, it, "~") }
        names.map { it.trim() }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }.forEachIndexed { i, name ->
            out = out.replace(Regex("""(?<![\p{L}\p{N}_])${Regex.escape(name)}(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE), "<person ${i + 1}>")
        }
        return AddressMask.mask(out)
    }

    /** Both separators: IDE paths use '/', Windows messages '\'. */
    private fun replacePath(text: String, path: String, with: String): String {
        val forward = path.replace('\\', '/')
        val back = path.replace('/', '\\')
        return text.replace(forward, with, ignoreCase = true).replace(back, with, ignoreCase = true)
    }

    /** Written into the plugin's resources by the build. */
    fun pluginVersion(): String = runCatching {
        Diagnostics::class.java.getResourceAsStream("/opencollab-build.properties")!!.use { stream ->
            java.util.Properties().apply { load(stream) }.getProperty("version")
        }
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("$") } ?: "?"

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    /** Copies the report and says what to check before posting it. */
    fun copy(project: Project) {
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(report(project)))
        dev.opencollab.plugin.ui.PanelNotice.show(project, dev.opencollab.plugin.OpenCollabBundle.message("diagnostics.copied"))
    }
}
