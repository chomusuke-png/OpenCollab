package dev.opencollab.plugin.ui

import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.opencollab.core.protocol.ConsoleStream
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.protocol.RunTargetKind
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.session.CollabSessionService.Mode
import dev.opencollab.plugin.session.CollabSessionService.SharedConsole
import dev.opencollab.plugin.settings.OpenCollabSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants

/**
 * The Console tab. Guests see the host's runs and builds live, one IntelliJ console per run
 * (paths like `src/Main.kt:12` open the file in the mirror), and editors can ask the host to run
 * one of its run configurations or build the project. On the host it explains that its own runs
 * are shared.
 */
class SharedConsolePanel(private val project: Project, private val service: CollabSessionService, parent: Disposable) :
    JPanel(BorderLayout()), Disposable {
    private val runButton = JButton(message("console.run.on.host"), AllIcons.Actions.Execute).apply {
        horizontalTextPosition = SwingConstants.LEADING
        addActionListener { showTargets() }
    }
    private val hint = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val tabs = JBTabbedPane()
    private val views = LinkedHashMap<String, ConsoleView>()
    private val cards = CardLayout()
    private val body = JPanel(cards)

    init {
        Disposer.register(parent, this)
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { add(runButton); add(hint) }
        val empty = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JBLabel(message("console.empty")).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.NORTH)
        }
        body.add(empty, EMPTY)
        body.add(tabs, CONSOLES)
        add(toolbar, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)

        // Snapshot and subscribe in the same EDT task: events arrive later, in order, on the EDT.
        service.consoles().forEach { console ->
            open(console)
            synchronized(console.parts) { console.parts.toList() }.forEach { (stream, text) -> print(console, stream, text) }
            if (console.finished) finish(console)
        }
        service.addConsoleListener(object : CollabSessionService.ConsoleListener {
            override fun started(console: SharedConsole) = open(console)
            override fun output(console: SharedConsole, stream: ConsoleStream, text: String) = print(console, stream, text)
            override fun ended(console: SharedConsole) = finish(console)
            override fun removed(console: SharedConsole) = close(console)
        }, this)
        refresh()
    }

    /** Toolbar state for the current mode and role. EDT. */
    fun refresh() {
        val mode = service.mode
        val guestEditor = mode is Mode.Joined && service.myRole?.canEdit == true
        runButton.isVisible = mode is Mode.Joined
        runButton.isEnabled = guestEditor && service.runTargets.isNotEmpty()
        hint.text = when {
            mode is Mode.Hosting -> message(if (OpenCollabSettings.getInstance().state.shareConsoles) "console.host.sharing" else "console.host.not.sharing")
            mode is Mode.Joined && service.myRole == Role.VIEWER -> message("console.viewer")
            else -> ""
        }
        cards.show(body, if (views.isEmpty()) EMPTY else CONSOLES)
    }

    private fun showTargets() {
        val targets = service.runTargets
        if (targets.isEmpty()) return
        val menu = JPopupMenu()
        for (target in targets) {
            val icon = if (target.kind == RunTargetKind.BUILD) AllIcons.Actions.Compile else AllIcons.Actions.Execute
            menu.add(JMenuItem(target.name, icon).apply {
                addActionListener {
                    if (service.requestRun(target)) CollabUi.toast(runButton, message("console.request.sent", target.name))
                }
            })
        }
        menu.show(runButton, 0, runButton.height)
    }

    private fun open(console: SharedConsole) {
        if (console.id in views) return
        val view = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply {
            // Shared output names mirror paths ("…/src/Main.kt:12"): make them clickable.
            addFilter(RegexpFilter(project, "${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}"))
        }.console
        Disposer.register(this, view)
        views[console.id] = view
        val who = console.requestedBy?.let { id -> service.participants.firstOrNull { it.id == id }?.displayName }
        val title = if (who != null) message("console.title.requested", console.title, who) else console.title
        tabs.addTab(title, AllIcons.Actions.Execute, view.component)
        tabs.selectedIndex = tabs.tabCount - 1
        refresh()
    }

    private fun print(console: SharedConsole, stream: ConsoleStream, text: String) {
        views[console.id]?.print(text, when (stream) {
            ConsoleStream.STDOUT -> ConsoleViewContentType.NORMAL_OUTPUT
            ConsoleStream.STDERR -> ConsoleViewContentType.ERROR_OUTPUT
            ConsoleStream.SYSTEM -> ConsoleViewContentType.SYSTEM_OUTPUT
        })
    }

    private fun finish(console: SharedConsole) {
        val view = views[console.id] ?: return
        val code = console.exitCode
        view.print("\n" + (code?.let { message("console.finished.code", it) } ?: message("console.finished")) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val index = tabs.indexOfComponent(view.component)
        if (index >= 0) tabs.setIconAt(index, if (code == null || code == 0) AllIcons.RunConfigurations.TestPassed else AllIcons.RunConfigurations.TestError)
    }

    private fun close(console: SharedConsole) {
        val view = views.remove(console.id) ?: return
        val index = tabs.indexOfComponent(view.component)
        if (index >= 0) tabs.removeTabAt(index)
        Disposer.dispose(view)
        refresh()
    }

    override fun dispose() {}

    private companion object {
        const val EMPTY = "empty"
        const val CONSOLES = "consoles"
    }
}
