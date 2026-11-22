package dev.opencollab.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.wm.ToolWindowBalloonShowOptions
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener

/**
 * A short message shown on the OpenCollab tool window. Unlike a notification, it does not stay in
 * the IDE's Notifications list: that list is kept for people asking to join or to run something
 * on the host, so they are not buried under everything else.
 */
object PanelNotice {
    const val TOOL_WINDOW = "OpenCollab"

    /** [html] is HTML; [onLink] gets the href of a clicked link. Any thread. */
    fun show(project: Project, html: String, type: MessageType = MessageType.INFO, onLink: ((String) -> Unit)? = null) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val manager = ToolWindowManager.getInstance(project)
            if (manager.getToolWindow(TOOL_WINDOW) == null) return@invokeLater
            val listener = onLink?.let { act ->
                HyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) act(e.description) }
            }
            manager.notifyByBalloon(ToolWindowBalloonShowOptions(toolWindowId = TOOL_WINDOW, type = type, htmlBody = html, listener = listener))
        }, ModalityState.nonModal())
    }
}
