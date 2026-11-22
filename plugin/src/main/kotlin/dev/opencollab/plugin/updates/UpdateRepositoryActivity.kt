package dev.opencollab.plugin.updates

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import dev.opencollab.plugin.OpenCollabBundle
import dev.opencollab.plugin.session.Diagnostics
import dev.opencollab.plugin.ui.PanelNotice

/**
 * Adds OpenCollab's release feed to the IDE's custom plugin repositories, so new versions show up
 * as ordinary plugin updates. Done once per IDE: if the user removes the repository, it stays removed.
 * After an update, says which version is now installed, with a link to what is new in it.
 */
class UpdateRepositoryActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val properties = PropertiesComponent.getInstance()
        val announce = synchronized(UpdateRepositoryActivity::class.java) {
            // Set by every version since the first release, so it also tells an update apart
            // from a first installation for those that did not record their version yet.
            val installedBefore = properties.getBoolean(REGISTERED_KEY)
            if (!installedBefore) {
                val hosts = UpdateSettings.getInstance().storedPluginHosts
                if (URL !in hosts) hosts.add(URL)
                properties.setValue(REGISTERED_KEY, true)
            }
            WhatsNew.recordVersion(properties, Diagnostics.pluginVersion(), installedBefore)
        }
        announce?.let { WhatsNew.notifyUpdated(project, it) }
    }

    companion object {
        // The updatePlugins.xml attached to the newest GitHub release (see publishRelease in
        // plugin/build.gradle.kts).
        const val URL = "https://github.com/chomusuke-png/OpenCollab/releases/latest/download/updatePlugins.xml"
        private const val REGISTERED_KEY = "dev.opencollab.updateRepositoryRegistered"
    }
}

/** The changes of each version: CHANGELOG.md, also published as each GitHub release's notes. */
object WhatsNew {
    const val CHANGELOG_URL = "https://github.com/chomusuke-png/OpenCollab/blob/main/CHANGELOG.md"
    private const val VERSION_KEY = "dev.opencollab.lastVersion"
    private val RELEASE = Regex("""(\d+)\.(\d+)\.(\d+)""")

    fun releaseUrl(version: String) = "https://github.com/chomusuke-png/OpenCollab/releases/tag/v$version"

    /**
     * Remembers [version] as the one in use and returns it when it should be announced: a released
     * version newer than the last one this IDE ran. Never on the first installation (unless
     * [installedBefore]) nor for development builds, which have no release page.
     */
    fun recordVersion(properties: PropertiesComponent, version: String, installedBefore: Boolean): String? {
        val current = parse(version) ?: return null
        val last = properties.getValue(VERSION_KEY)
        if (last == version) return null
        properties.setValue(VERSION_KEY, version)
        val previous = last?.let(::parse) ?: return if (last == null && installedBefore) version else null
        return version.takeIf { compare(current, previous) > 0 }
    }

    fun notifyUpdated(project: Project, version: String) {
        val html = "<b>" + OpenCollabBundle.message("whats.new.title", version) + "</b><br>" + OpenCollabBundle.message("whats.new.content") +
            "<br><a href=\"" + releaseUrl(version) + "\">" + OpenCollabBundle.message("whats.new.open") + "</a>"
        PanelNotice.show(project, html) { BrowserUtil.browse(it) }
    }

    private fun parse(version: String) = RELEASE.matchEntire(version)?.groupValues?.drop(1)?.map(String::toInt)

    private fun compare(a: List<Int>, b: List<Int>) = a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
}
