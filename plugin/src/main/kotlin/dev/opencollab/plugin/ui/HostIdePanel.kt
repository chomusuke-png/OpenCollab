package dev.opencollab.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.opencollab.core.protocol.DebugFrame
import dev.opencollab.core.protocol.DebugVariable
import dev.opencollab.core.protocol.VcsChange
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.session.CollabSessionService.Mode
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Guests' view of the host IDE: the branch and changed files of the host's working copy, and
 * where the host's debugger is paused (frames and variables). Double-click opens the file in the
 * mirror. Read-only: the host keeps control of its debugger and version control. EDT only.
 */
internal class HostIdePanel(private val service: CollabSessionService) : JPanel(CardLayout()) {
    private val branch = JBLabel()
    private val changes = DefaultListModel<VcsChange>()
    private val changeList = JBList(changes)
    private val debugStatus = JBLabel()
    private val frames = DefaultListModel<DebugFrame>()
    private val frameList = JBList(frames)
    private val variables = DefaultListModel<DebugVariable>()
    private val variableList = JBList(variables)

    init {
        add(JBLabel(message("host.only.guests")).apply {
            foreground = UIUtil.getContextHelpForeground()
            verticalAlignment = JBLabel.TOP
            border = JBUI.Borders.empty(6)
        }, IDLE)

        changeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        changeList.cellRenderer = object : ColoredListCellRenderer<VcsChange>() {
            override fun customizeCellRenderer(list: JList<out VcsChange>, value: VcsChange, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = FileTypeManager.getInstance().getFileTypeByFileName(value.path.substringAfterLast('/')).icon
                append(value.path.substringAfterLast('/'), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, colorOf(value.status)))
                append("  " + value.path.substringBeforeLast('/', ""), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        onDoubleClick(changeList) { service.hostIde?.navigate(it.path, 0) }
        val git = section(message("host.git.title"), branch, JBScrollPane(changeList))

        frameList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        frameList.cellRenderer = object : ColoredListCellRenderer<DebugFrame>() {
            override fun customizeCellRenderer(list: JList<out DebugFrame>, value: DebugFrame, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = if (index == 0) AllIcons.Debugger.Frame else null
                append(value.name, if (value.path != null) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        onDoubleClick(frameList) { frame -> frame.path?.let { service.hostIde?.navigate(it, frame.line) } }
        variableList.cellRenderer = object : ColoredListCellRenderer<DebugVariable>() {
            override fun customizeCellRenderer(list: JList<out DebugVariable>, value: DebugVariable, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = AllIcons.Debugger.Value
                append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                value.type?.let { append(" ($it)", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                append(" = " + value.value)
            }
        }
        val stack = JBSplitter(false, 0.4f).apply {
            firstComponent = titled(message("host.debug.frames"), JBScrollPane(frameList))
            secondComponent = titled(message("host.debug.variables"), JBScrollPane(variableList))
        }
        val debug = section(message("host.debug.title"), debugStatus, stack)

        add(JBSplitter(true, 0.4f).apply {
            firstComponent = git
            secondComponent = debug
        }, GUEST)
    }

    fun refresh() {
        val ide = service.hostIde
        (layout as CardLayout).show(this, if (service.mode is Mode.Joined && ide != null) GUEST else IDLE)
        ide ?: return

        val vcs = ide.vcs
        branch.text = when {
            vcs == null -> message("host.git.none")
            else -> (vcs.branch?.let { message("host.git.branch", it) } ?: message("host.git.detached")) + " · " +
                message("host.git.changes", vcs.changes.size)
        }
        replace(changes, vcs?.changes.orEmpty())

        val debug = ide.debug
        debugStatus.text = when {
            debug == null -> message("host.debug.idle")
            !debug.paused -> message("host.debug.running", debug.sessionName)
            else -> message("host.debug.paused", debug.sessionName, debug.path?.let { "$it:${debug.line + 1}" } ?: "?")
        }
        replace(frames, debug?.frames.orEmpty())
        replace(variables, debug?.variables.orEmpty())
    }

    private fun <T> replace(model: DefaultListModel<T>, items: List<T>) {
        if ((0 until model.size()).map(model::get) == items) return
        model.clear()
        model.addAll(items)
    }

    private fun section(title: String, status: JBLabel, content: java.awt.Component) = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            add(JBLabel(title).apply { font = JBUI.Fonts.label().asBold() }, BorderLayout.NORTH)
            add(status.apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(4, 0)
        }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    private fun titled(title: String, content: java.awt.Component) = JPanel(BorderLayout()).apply {
        add(JBLabel(title).apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(2, 0) }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    private fun <T> onDoubleClick(list: JBList<T>, action: (T) -> Unit) {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) list.selectedValue?.let(action)
            }
        })
    }

    private fun colorOf(status: String): JBColor = when (status) {
        VcsChange.ADDED -> JBColor(0x3C7A3C, 0x62B543)
        VcsChange.DELETED -> JBColor(0x6C6C6C, 0x8C8C8C)
        VcsChange.UNVERSIONED -> JBColor(0x993300, 0xD1675A)
        VcsChange.MOVED -> JBColor(0x0A5FA8, 0x6897BB)
        else -> JBColor(0x0032A0, 0x6897BB)
    }

    private companion object {
        const val IDLE = "idle"
        const val GUEST = "guest"
    }
}
