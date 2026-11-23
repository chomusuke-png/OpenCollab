package dev.opencollab.plugin.updates

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.ui.PanelNotice

/** The user guide (docs/USER_GUIDE.md), and the welcome shown once, right after OpenCollab is installed. */
object UserGuide {
    const val URL = "https://github.com/chomusuke-png/OpenCollab/blob/main/docs/USER_GUIDE.md"

    fun open() = BrowserUtil.browse(URL)

    /**
     * How to start, in short, with the guide and the tool window a click away. A notification
     * rather than a balloon on the tool window: the tool window may still be hidden, and the
     * notification stays in the Notifications list until it is dismissed.
     */
    fun welcome(project: Project) {
        NotificationGroupManager.getInstance().getNotificationGroup("OpenCollab")
            .createNotification(message("welcome.notice.title"), message("welcome.notice.content"), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimple(message("welcome.notice.guide")) { open() })
            .addAction(NotificationAction.createSimple(message("welcome.notice.open")) {
                ToolWindowManager.getInstance(project).getToolWindow(PanelNotice.TOOL_WINDOW)?.activate(null)
            })
            .notify(project)
    }
}
