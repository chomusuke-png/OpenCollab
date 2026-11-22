package dev.opencollab.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.opencollab.core.session.GuestChange
import dev.opencollab.plugin.session.HostConsoleSharing

/** A run request tells the host what guests changed, build files first and in bold. */
class RunRequestChangesTest : BasePlatformTestCase() {
    fun testNothingChangedAddsNothing() {
        assertEquals("", HostConsoleSharing.guestChangesHtml(emptyList()))
    }

    fun testBuildFilesComeFirstAndNamesAreEscaped() {
        val html = HostConsoleSharing.guestChangesHtml(listOf(
            GuestChange("src/Main.kt", listOf("Ana")),
            GuestChange("build.gradle.kts", listOf("<Bob>")),
        ))
        assertTrue(html, html.contains("build files"))
        assertTrue(html, html.indexOf("build.gradle.kts") < html.indexOf("src/Main.kt"))
        assertTrue(html, html.contains("<b>build.gradle.kts (&lt;Bob&gt;)</b>"))
        assertTrue(html, html.contains("2 files"))
    }

    fun testLongListsAreCut() {
        val html = HostConsoleSharing.guestChangesHtml((1..12).map { GuestChange("src/F$it.kt", listOf("Ana")) })
        assertTrue(html, html.contains("src/F12.kt")) // the most recent first
        assertFalse(html, html.contains("src/F1.kt"))
        assertTrue(html, html.contains("and 4 more"))
    }
}
