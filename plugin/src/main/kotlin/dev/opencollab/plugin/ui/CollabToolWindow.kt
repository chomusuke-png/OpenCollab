package dev.opencollab.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.util.ui.JBUI
import dev.opencollab.core.protocol.ParticipantInfo
import dev.opencollab.core.protocol.Role
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.bridge.PresenceRenderer
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.session.PendingJoins
import dev.opencollab.core.files.MirrorWorkspace
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBRadioButton
import java.nio.file.Files
import java.nio.file.Path
import dev.opencollab.plugin.settings.OpenCollabConfigurable
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/** Localized role name ("editor" / "editor", "viewer" / "lector", …). */
fun roleName(role: Role): String = message("role.${role.name}")

class CollabToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CollabPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/** The OpenCollab tool window: session controls, invitations, participants and activity log. */
class CollabPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val service = CollabSessionService.getInstance(project)
    private val status = JBLabel()
    private val hostButton = JButton(message("panel.start.hosting"), AllIcons.Actions.Execute)
    private val joinButton = JButton(message("panel.join"), AllIcons.Actions.Download)
    private val leaveButton = JButton(message("panel.leave"), AllIcons.Actions.Suspend)
    private val settingsButton = JButton(AllIcons.General.Settings)
    private val copyEditor = JButton(message("panel.copy.editor"), AllIcons.Actions.Copy)
    private val copyViewer = JButton(message("panel.copy.viewer"), AllIcons.Actions.Copy)
    private val endpoints = JBLabel()
    private val model = DefaultListModel<ParticipantInfo>()
    private val list = JBList(model)
    // Unwrapped by default (long lines scroll horizontally); the choice is remembered per IDE.
    private val logArea = JBTextArea().apply {
        isEditable = false
        lineWrap = PropertiesComponent.getInstance().getBoolean(WRAP_KEY, false)
    }

    init {
        border = JBUI.Borders.empty(6)
        settingsButton.toolTipText = message("panel.settings.tooltip")
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(hostButton); add(joinButton); add(leaveButton); add(settingsButton)
        }
        val invites = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(copyEditor); add(copyViewer) }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(status)); add(row(buttons)); add(row(invites)); add(row(endpoints))
        }
        add(top, BorderLayout.NORTH)

        list.cellRenderer = ParticipantRenderer()
        list.emptyText.text = message("panel.no.participants")
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selected()?.let { follow(it) }
            }
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
        })
        val splitter = JBSplitter(true, 0.55f).apply {
            firstComponent = titled(message("panel.participants.title"), JBScrollPane(list))
            secondComponent = titled(message("panel.activity.title"), JBScrollPane(logArea), activityToolbar())
        }
        add(splitter, BorderLayout.CENTER)

        hostButton.addActionListener { service.startHosting() }
        joinButton.addActionListener { JoinDialog(project).show() }
        leaveButton.addActionListener {
            val hosting = service.mode is CollabSessionService.Mode.Hosting
            val ok = !hosting || Messages.showYesNoDialog(project, message("panel.end.confirm"), "OpenCollab", null) == Messages.YES
            if (ok) service.leave()
        }
        settingsButton.addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenCollabConfigurable::class.java) }
        copyEditor.addActionListener { copyInvite(editor = true) }
        copyViewer.addActionListener { copyInvite(editor = false) }

        service.addListener({ SwingUtilities.invokeLater(::refresh) }, parent)
        refresh()
    }

    private fun row(c: JComponent) = JPanel(BorderLayout()).apply {
        add(c, BorderLayout.CENTER)
        border = JBUI.Borders.emptyBottom(4)
        alignmentX = LEFT_ALIGNMENT
    }

    private fun titled(title: String, c: JComponent, actions: JComponent? = null) = JPanel(BorderLayout()).apply {
        val header = JPanel(BorderLayout()).apply {
            add(JBLabel(title).apply { border = JBUI.Borders.empty(4, 0) }, BorderLayout.CENTER)
            actions?.let { add(it, BorderLayout.EAST) }
        }
        add(header, BorderLayout.NORTH)
        add(c, BorderLayout.CENTER)
    }

    /** Word-wrap toggle and Clear for the activity log, as in the IDE's own consoles. */
    private fun activityToolbar(): JComponent {
        val wrap = object : DumbAwareToggleAction(message("panel.activity.wrap"), null, AllIcons.Actions.ToggleSoftWrap) {
            override fun isSelected(e: AnActionEvent) = logArea.lineWrap
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                logArea.lineWrap = state
                PropertiesComponent.getInstance().setValue(WRAP_KEY, state, false)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val clear = object : DumbAwareAction(message("panel.activity.clear"), null, AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) = service.clearLog()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = logArea.document.length > 0 }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("OpenCollab.Activity", DefaultActionGroup(wrap, clear), true)
        toolbar.targetComponent = logArea
        return toolbar.component.apply { isOpaque = false; border = JBUI.Borders.empty() }
    }

    private fun selected(): ParticipantInfo? = list.selectedValue

    private fun follow(p: ParticipantInfo) {
        if (!service.follow(p.id)) status.text = message("panel.no.open.file", p.displayName)
    }

    private fun copyInvite(editor: Boolean) {
        val hosting = service.mode as? CollabSessionService.Mode.Hosting ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(if (editor) hosting.editorInvite else hosting.viewerInvite))
        status.text = message(if (editor) "panel.invite.copied.editor" else "panel.invite.copied.viewer")
    }

    private fun popup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = list.locationToIndex(e.point)
        if (index < 0) return
        list.selectedIndex = index
        val p = model[index]
        val menu = JPopupMenu()
        menu.add(JMenuItem(message("panel.follow")).apply { addActionListener { follow(p) } })
        if (service.mode is CollabSessionService.Mode.Hosting && p.role != Role.HOST) {
            if (p.role != Role.EDITOR) menu.add(JMenuItem(message("panel.make.editor")).apply { addActionListener { service.setRole(p.id, Role.EDITOR) } })
            if (p.role != Role.VIEWER) menu.add(JMenuItem(message("panel.make.viewer")).apply { addActionListener { service.setRole(p.id, Role.VIEWER) } })
            menu.add(JMenuItem(message("panel.remove")).apply { addActionListener { service.kick(p.id) } })
        }
        menu.show(list, e.x, e.y)
    }

    private fun refresh() {
        val mode = service.mode
        status.text = service.myRole?.let { message("panel.status.with.role", service.status, roleName(it)) } ?: service.status
        hostButton.isEnabled = mode == CollabSessionService.Mode.Idle
        joinButton.isEnabled = mode == CollabSessionService.Mode.Idle
        leaveButton.isEnabled = mode is CollabSessionService.Mode.Hosting || mode is CollabSessionService.Mode.Joined
        leaveButton.text = message(if (mode is CollabSessionService.Mode.Hosting) "panel.end.session" else "panel.leave")
        val hosting = mode as? CollabSessionService.Mode.Hosting
        copyEditor.isVisible = hosting != null
        copyViewer.isVisible = hosting != null
        endpoints.text = hosting?.let { message("panel.endpoints", it.endpoints.joinToString(", "), it.fingerprint.take(29)) } ?: ""
        val selectedId = list.selectedValue?.id
        model.clear()
        service.participants.forEach(model::addElement)
        selectedId?.let { id -> (0 until model.size()).firstOrNull { model[it].id == id }?.let(list::setSelectedIndex) }
        logArea.text = service.log().joinToString("\n")
        logArea.caretPosition = logArea.document.length
    }

    private inner class ParticipantRenderer : ColoredListCellRenderer<ParticipantInfo>() {
        override fun customizeCellRenderer(list: JList<out ParticipantInfo>, value: ParticipantInfo, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = ColorIcon(PresenceRenderer.colorFor(value.colorIndex))
            append(value.displayName)
            if (value.id == service.ownParticipantId()) append(message("panel.you"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  " + roleName(value.role), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private companion object {
        const val WRAP_KEY = "dev.opencollab.activity.wrap"
    }

    private class ColorIcon(private val color: java.awt.Color) : Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
            g.color = color
            g.fillOval(x + 1, y + 1, 10, 10)
        }
        override fun getIconWidth() = 12
        override fun getIconHeight() = 12
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
