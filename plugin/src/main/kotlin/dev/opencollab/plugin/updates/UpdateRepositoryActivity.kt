package dev.opencollab.plugin.updates

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.UpdateSettings

/**
 * Adds OpenCollab's release feed to the IDE's custom plugin repositories, so new versions show up
 * as ordinary plugin updates. Done once per IDE: if the user removes the repository, it stays removed.
 */
class UpdateRepositoryActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val properties = PropertiesComponent.getInstance()
        synchronized(UpdateRepositoryActivity::class.java) {
            if (properties.getBoolean(REGISTERED_KEY)) return
            val hosts = UpdateSettings.getInstance().storedPluginHosts
            if (URL !in hosts) hosts.add(URL)
            properties.setValue(REGISTERED_KEY, true)
        }
    }

    companion object {
        // The updatePlugins.xml attached to the newest GitHub release (see publishRelease in
        // plugin/build.gradle.kts).
        const val URL = "https://github.com/chomusuke-png/OpenCollab/releases/latest/download/updatePlugins.xml"
        private const val REGISTERED_KEY = "dev.opencollab.updateRepositoryRegistered"
    }
}
