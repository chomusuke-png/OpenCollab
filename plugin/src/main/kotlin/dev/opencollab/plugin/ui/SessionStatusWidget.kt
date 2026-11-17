package dev.opencollab.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.session.CollabSessionService
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

class SessionStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = SessionStatusWidget.ID
    override fun getDisplayName() = message("widget.name")
    override fun createWidget(project: Project): StatusBarWidget = SessionStatusWidget(project)
}

/**
 * "● OpenCollab · 3 people · 00:42:10 · 2 unread" in the status bar while in a session (green
 * connected, amber connecting, red disconnected); hidden otherwise. Clicking opens the tool window.
 */
class SessionStatusWidget(private val project: Project) : CustomStatusBarWidget {
    private val service = CollabSessionService.getInstance(project)
    private val label = JBLabel().apply {
        border = JBUI.Borders.empty(0, 6)
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val clock = Timer(1000) { update() }

    override fun ID() = ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        service.addListener({ SwingUtilities.invokeLater(::update) }, this)
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
            }
        })
        clock.start()
        update()
    }

    private fun update() {
        val health = service.health()
        label.isVisible = health != null
        if (health == null) return
        label.icon = CollabUi.Dot(CollabUi.healthColor(health), JBUI.scale(8))
        label.text = listOfNotNull(
            "OpenCollab",
            message("widget.participants", service.participants.size).takeIf { service.participants.isNotEmpty() },
            service.sessionClock(),
            service.unreadChat.takeIf { it > 0 }?.let { message("widget.unread", it) },
        ).joinToString(" · ")
        label.toolTipText = service.status
    }

    override fun dispose() {
        clock.stop()
    }

    companion object {
        const val ID = "OpenCollab.SessionStatus"
        const val TOOL_WINDOW_ID = "OpenCollab"
    }
}
