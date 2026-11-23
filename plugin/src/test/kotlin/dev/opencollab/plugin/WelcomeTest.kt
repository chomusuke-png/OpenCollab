package dev.opencollab.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.opencollab.plugin.updates.UpdateRepositoryActivity
import kotlinx.coroutines.runBlocking

/** The welcome with the user guide: once, after the first installation; never after an update. */
class WelcomeTest : BasePlatformTestCase() {
    private val properties get() = PropertiesComponent.getInstance()
    private val shown = mutableListOf<Notification>()
    private var registered = false
    private var lastVersion: String? = null

    override fun setUp() {
        super.setUp()
        registered = properties.getBoolean(REGISTERED_KEY)
        lastVersion = properties.getValue(VERSION_KEY)
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { shown += notification }
        })
    }

    override fun tearDown() {
        try {
            properties.setValue(REGISTERED_KEY, registered)
            properties.setValue(VERSION_KEY, lastVersion)
            UpdateSettings.getInstance().storedPluginHosts.remove(UpdateRepositoryActivity.URL)
        } finally {
            super.tearDown()
        }
    }

    private fun startIde() = runBlocking { UpdateRepositoryActivity().execute(project) }

    private fun welcomes() = shown.filter { it.title == OpenCollabBundle.message("welcome.notice.title") }

    fun testFirstInstallationWelcomesWithTheGuideOnce() {
        properties.unsetValue(REGISTERED_KEY)
        startIde()
        val welcome = welcomes().single()
        assertEquals(
            listOf(OpenCollabBundle.message("welcome.notice.guide"), OpenCollabBundle.message("welcome.notice.open")),
            welcome.actions.map { it.templateText },
        )
        startIde()
        assertEquals("only once", 1, welcomes().size)
    }

    fun testAnUpdateDoesNotWelcomeAgain() {
        properties.setValue(REGISTERED_KEY, true)
        startIde()
        assertEmpty(welcomes())
    }

    private companion object {
        const val REGISTERED_KEY = "dev.opencollab.updateRepositoryRegistered"
        const val VERSION_KEY = "dev.opencollab.lastVersion"
    }
}
