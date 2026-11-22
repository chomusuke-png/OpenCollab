package dev.opencollab.plugin.ui

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import dev.opencollab.core.files.MirrorWorkspace
import dev.opencollab.core.protocol.Role
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.session.PendingJoins
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/** Localized role name ("editor" / "editor", "viewer" / "lector", …). */
fun roleName(role: Role): String = message("role.${role.name}")

/** Localized name of a [dev.opencollab.core.protocol.Permission]. */
fun permissionName(permission: String): String = message("permission.$permission")

class CollabToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CollabPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Paste an invitation and choose where the shared project goes: this project's folder (if it
 * is empty or an earlier mirror) or a new folder that is opened in a new window and joined.
 */
class JoinDialog(private val project: Project) : DialogWrapper(project) {
    private val inviteArea = JBTextArea().apply { lineWrap = true; wrapStyleWord = false }
    private val currentRoot: Path? = project.basePath?.let { Path.of(it) }
    private val currentUsable = currentRoot != null && MirrorWorkspace(currentRoot).canAdopt()
    private val useCurrent = JBRadioButton(
        if (currentUsable) message("join.target.current", currentRoot.toString()) else message("join.target.current.unavailable"),
    )
    private val useNew = JBRadioButton(message("join.target.new"))
    private val folderField = TextFieldWithBrowseButton()

    init {
        title = message("join.title")
        setOKButtonText(message("join.button"))
        useCurrent.isEnabled = currentUsable
        (if (currentUsable) useCurrent else useNew).isSelected = true
        folderField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(message("join.target.chooser.title")),
        )
        val sync = { folderField.isEnabled = useNew.isSelected }
        useCurrent.addActionListener { sync() }
        useNew.addActionListener { sync() }
        sync()
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(message("join.prompt")) }
        row { scrollCell(inviteArea).rows(6).align(AlignX.FILL) }
        row { label(message("join.target.label")) }
        // The DSL owns the radio grouping: a cell() radio button outside buttonsGroup throws.
        buttonsGroup {
            row { cell(useCurrent) }
            row { cell(useNew) }
        }
        row { cell(folderField).align(AlignX.FILL).comment(message("join.target.new.comment")) }
        row { comment(message("join.comment")) }
    }

    override fun getPreferredFocusedComponent(): JComponent = inviteArea

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        val service = CollabSessionService.getInstance(project)
        service.checkInvite(inviteArea.text)?.let { return com.intellij.openapi.ui.ValidationInfo(it, inviteArea) }
        if (useCurrent.isSelected) return null
        if (folderField.text.isBlank()) {
            // Suggest ~/OpenCollab/<project name from the invitation>.
            val name = runCatching { dev.opencollab.transport.Invite.decode(inviteArea.text).project }.getOrDefault("project")
            folderField.text = PendingJoins.suggestFolder(name).toString()
        }
        val folder = runCatching { Path.of(folderField.text.trim()) }.getOrNull()
        if (folder == null || !folder.isAbsolute) return com.intellij.openapi.ui.ValidationInfo(message("join.target.invalid"), folderField)
        if (Files.exists(folder) && (!Files.isDirectory(folder) || !MirrorWorkspace(folder).canAdopt())) {
            return com.intellij.openapi.ui.ValidationInfo(message("join.target.not.empty", folder.toString()), folderField)
        }
        return null
    }

    override fun doOKAction() {
        try {
            val invite = inviteArea.text
            if (useCurrent.isSelected) {
                CollabSessionService.getInstance(project).join(invite)
            } else {
                val folder = Path.of(folderField.text.trim()).toAbsolutePath().normalize()
                Files.createDirectories(folder)
                PendingJoins.put(folder, invite)
                super.doOKAction()
                ProjectUtil.openOrImport(folder, OpenProjectTask { forceOpenInNewFrame = true })
                return
            }
            super.doOKAction()
        } catch (e: Exception) {
            setErrorText(e.message)
        }
    }
}
