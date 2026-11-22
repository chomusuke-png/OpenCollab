package dev.opencollab.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.opencollab.plugin.updates.WhatsNew

/** The "updated to" notification: once per newer release, never on a first installation. */
class WhatsNewTest : BasePlatformTestCase() {
    private val properties get() = PropertiesComponent.getInstance()

    override fun tearDown() {
        try { properties.unsetValue("dev.opencollab.lastVersion") } finally { super.tearDown() }
    }

    fun testFirstInstallationIsNotAnnouncedButLaterUpdatesAre() {
        assertNull(WhatsNew.recordVersion(properties, "3.6.0", installedBefore = false))
        assertNull(WhatsNew.recordVersion(properties, "3.6.0", installedBefore = true))
        assertEquals("3.10.0", WhatsNew.recordVersion(properties, "3.10.0", installedBefore = true))
        assertNull(WhatsNew.recordVersion(properties, "3.10.0", installedBefore = true))
        assertNull("not a downgrade", WhatsNew.recordVersion(properties, "3.9.1", installedBefore = true))
    }

    fun testUpdatesFromVersionsThatRecordedNothingAreAnnounced() {
        assertEquals("3.7.0", WhatsNew.recordVersion(properties, "3.7.0", installedBefore = true))
    }

    fun testDevelopmentBuildsAreNeverAnnounced() {
        assertNull(WhatsNew.recordVersion(properties, "3.7.0-dev.2", installedBefore = true))
        assertNull(WhatsNew.recordVersion(properties, "?", installedBefore = true))
    }
}
