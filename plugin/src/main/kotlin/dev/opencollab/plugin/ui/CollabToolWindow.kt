package dev.opencollab.plugin.ui

import com.intellij.icons.AllIcons
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
    private val logArea = JBTextArea().apply { isEditable = false; lineWrap = true }

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
            secondComponent = titled(message("panel.activity.title"), JBScrollPane(logArea))
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

    private fun titled(title: String, c: JComponent) = JPanel(BorderLayout()).apply {
        add(JBLabel(title).apply { border = JBUI.Borders.empty(4, 0) }, BorderLayout.NORTH)
        add(c, BorderLayout.CENTER)
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

    private class ColorIcon(private val color: java.awt.Color) : Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
            g.color = color
            g.fillOval(x + 1, y + 1, 10, 10)
        }
        override fun getIconWidth() = 12
        override fun getIconHeight() = 12
    }
}

/** Paste an invitation and join into the current (empty) project folder. */
class JoinDialog(private val project: Project) : DialogWrapper(project) {
    private val inviteArea = JBTextArea().apply { lineWrap = true; wrapStyleWord = false }

    init {
        title = message("join.title")
        setOKButtonText(message("join.button"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(message("join.prompt")) }
        row { scrollCell(inviteArea).rows(6).align(AlignX.FILL) }
        row { comment(message("join.comment")) }
    }

    override fun getPreferredFocusedComponent(): JComponent = inviteArea

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        val error = CollabSessionService.getInstance(project).checkCanJoin(inviteArea.text) ?: return null
        return com.intellij.openapi.ui.ValidationInfo(error, inviteArea)
    }

    override fun doOKAction() {
        try {
            CollabSessionService.getInstance(project).join(inviteArea.text)
            super.doOKAction()
        } catch (e: Exception) {
            setErrorText(e.message)
        }
    }
}
