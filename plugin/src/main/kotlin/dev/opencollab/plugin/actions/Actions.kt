package dev.opencollab.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.ui.JoinDialog
import java.awt.datatransfer.StringSelection

private fun AnActionEvent.session() = project?.let(CollabSessionService::getInstance)

abstract class CollabAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class StartHostingAction : CollabAction() {
    override fun actionPerformed(e: AnActionEvent) { e.session()?.startHosting() }
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.session()?.mode == CollabSessionService.Mode.Idle }
}

class JoinSessionAction : CollabAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { JoinDialog(it).show() } }
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.session()?.mode == CollabSessionService.Mode.Idle }
}

class LeaveSessionAction : CollabAction() {
    override fun actionPerformed(e: AnActionEvent) { e.session()?.leave() }
    override fun update(e: AnActionEvent) {
        val mode = e.session()?.mode
        e.presentation.isEnabled = mode is CollabSessionService.Mode.Hosting || mode is CollabSessionService.Mode.Joined
    }
}

class CopyInviteAction : CollabAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val hosting = e.session()?.mode as? CollabSessionService.Mode.Hosting ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(hosting.editorInvite))
    }
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.session()?.mode is CollabSessionService.Mode.Hosting }
}

class WhatsNewAction : CollabAction() {
    override fun actionPerformed(e: AnActionEvent) { com.intellij.ide.BrowserUtil.browse(dev.opencollab.plugin.updates.WhatsNew.CHANGELOG_URL) }
}

class UserGuideAction : CollabAction() {
    override fun actionPerformed(e: AnActionEvent) { dev.opencollab.plugin.updates.UserGuide.open() }
}

/** Copies a report for a bug report: versions, connection kind and the recent log, with addresses and names hidden. */
class CopyDiagnosticsAction : CollabAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let(dev.opencollab.plugin.session.Diagnostics::copy) }
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
}
