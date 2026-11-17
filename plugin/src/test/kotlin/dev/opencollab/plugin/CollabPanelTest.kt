package dev.opencollab.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBSplitter
import dev.opencollab.plugin.ui.CollabPanel
import javax.swing.SwingUtilities

/** The tool window panel switches between the welcome screen and the session view. */
class CollabPanelTest : BasePlatformTestCase() {
    fun testChatAndActivityTabsSurviveLeavingAndJoiningAgain() {
        val panel = CollabPanel(project, testRootDisposable)
        // join, leave (welcome screen), join again
        for (inSession in listOf(true, false, true)) {
            panel.showView(inSession)
            assertTrue(
                "chat/activity tabs must be in the panel (inSession=$inSession)",
                SwingUtilities.isDescendingFrom(panel.bottomTabs, panel),
            )
            if (inSession) assertTrue("tabs belong under the participants in a session", panel.bottomTabs.parent is JBSplitter)
        }
    }
}
