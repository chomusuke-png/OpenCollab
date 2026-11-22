package dev.opencollab.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.opencollab.core.protocol.ParticipantInfo
import dev.opencollab.core.protocol.Permission
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.protocol.RunTargetKind
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.bridge.PresenceRenderer
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.session.CollabSessionService.Mode
import dev.opencollab.plugin.settings.OpenCollabConfigurable
import dev.opencollab.plugin.settings.OpenCollabSettings
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import com.intellij.util.ui.WrapLayout
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The OpenCollab tool window. Outside a session it is a welcome screen with the two ways to
 * start; in a session it shows the status, invitations, participants, chat and activity.
 */
class CollabPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val service = CollabSessionService.getInstance(project)

    // ---- Shared bottom tabs (chat of the current or last session, activity log) --------
    // Wrapped by default, so nothing hides past the edge of a narrow tool window; the choice is
    // remembered per IDE.
    private val logArea = JBTextArea().apply {
        isEditable = false
        lineWrap = PropertiesComponent.getInstance().getBoolean(WRAP_KEY, true)
        wrapStyleWord = true
    }
    internal val bottomTabs = JBTabbedPane() // internal: checked by CollabPanelTest
    private val chatPanel = ChatPanel(service)
    private val hostPanel = HostIdePanel(service)

    // ---- Welcome (no session) ----------------------------------------------------------
    private val lastStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val welcome = panel {
        row { label(message("welcome.title")).applyToComponent { font = JBFont.h3().asBold() } }
        row { text(message("welcome.subtitle")) }
        row {
            button(message("welcome.host")) { service.startHosting() }
                .align(AlignX.FILL)
                .applyToComponent { icon = AllIcons.Actions.Execute }
        }.topGap(com.intellij.ui.dsl.builder.TopGap.SMALL)
        row { comment(message("welcome.host.description")) }
        row {
            button(message("welcome.join")) { JoinDialog(project).show() }
                .align(AlignX.FILL)
                .applyToComponent { icon = AllIcons.Actions.Download }
        }
        row { comment(message("welcome.join.description")) }
        row { link(message("welcome.settings")) { openSettings() } }
        row { cell(lastStatus) }
    }.apply { border = JBUI.Borders.empty(4, 2, 8, 2) }
    private val idleView = JPanel(BorderLayout())

    // ---- Session header -----------------------------------------------------------------
    private val status = JBLabel()
    private val sessionTime = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val inviteButton = JButton(message("panel.invite"), AllIcons.General.ArrowDown).apply {
        horizontalTextPosition = SwingConstants.LEADING
    }
    private val leaveButton = JButton(message("panel.leave"), AllIcons.Actions.Suspend)
    /** Guests: ask the host to run one of its run configurations or build (output shows in Run / Build). */
    private val runOnHostButton = JButton(message("console.run.on.host"), AllIcons.General.ArrowDown).apply {
        horizontalTextPosition = SwingConstants.LEADING
    }
    private val settingsButton = JButton(AllIcons.General.Settings).apply { toolTipText = message("panel.settings.tooltip") }
    private val endpoints = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    /** Host: what the router says about automatic port forwarding. */
    private val routerStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    // ---- Host: people waiting to be let in (also offered as notifications, which can be missed)
    private val requestsTitle = JBLabel().apply { font = JBUI.Fonts.label().asBold(); border = JBUI.Borders.empty(4, 0, 2, 0) }
    private val requestRows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val requestsPanel = JPanel(BorderLayout()).apply {
        add(requestsTitle, BorderLayout.NORTH)
        add(requestRows, BorderLayout.CENTER)
        border = JBUI.Borders.emptyBottom(6)
        isVisible = false
    }
    private var shownRequests: List<String> = emptyList()
    private val detailsLink = ActionLink(message("panel.connection.details")) { toggleDetails() }

    // ---- Participants -------------------------------------------------------------------
    private val model = DefaultListModel<ParticipantInfo>()
    private val list = JBList(model)
    private var hovered = -1
    private val followLabel = JBLabel(AllIcons.Actions.Show)
    private val followBar = JPanel(BorderLayout(6, 0)).apply {
        border = JBUI.Borders.empty(2, 0, 4, 0)
        add(followLabel, BorderLayout.CENTER)
        add(ActionLink(message("panel.stop.following")) { service.stopFollowing() }, BorderLayout.EAST)
    }
    private val sessionView = JPanel(BorderLayout())
    private val splitter = JBSplitter(true, 0.5f)
    private var shownInSession: Boolean? = null

    init {
        border = JBUI.Borders.empty(6)

        val activity = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(activityToolbar(), BorderLayout.EAST) }, BorderLayout.NORTH)
            add(JBScrollPane(logArea), BorderLayout.CENTER)
        }
        bottomTabs.addTab(message("panel.chat.title"), chatPanel)
        bottomTabs.addTab(message("panel.activity.title"), activity)
        bottomTabs.addTab(message("panel.host.title"), hostPanel)
        bottomTabs.addChangeListener { chatPanel.selected = bottomTabs.selectedComponent === chatPanel }

        idleView.add(welcome, BorderLayout.NORTH)

        // Wraps onto a second line instead of cutting buttons off in a narrow tool window.
        val buttons = JPanel(WrapLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(inviteButton); add(runOnHostButton); add(leaveButton); add(settingsButton)
        }
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(row(status, fill = true)); add(row(sessionTime, fill = true)); add(row(routerStatus, fill = true)); add(row(buttons, fill = true)); add(row(detailsLink)); add(row(endpoints, fill = true))
            add(row(requestsPanel, fill = true))
        }
        list.cellRenderer = ParticipantRenderer()
        list.emptyText.text = message("panel.no.participants")
        installListMouse()
        val participants = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                add(JBLabel(message("panel.participants.title")).apply { border = JBUI.Borders.empty(4, 0) }, BorderLayout.NORTH)
                add(followBar, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        splitter.firstComponent = participants
        sessionView.add(header, BorderLayout.NORTH)
        sessionView.add(splitter, BorderLayout.CENTER)

        inviteButton.addActionListener { showInviteMenu() }
        runOnHostButton.addActionListener { showRunTargets() }
        leaveButton.addActionListener {
            val hosting = service.mode is Mode.Hosting
            val ok = !hosting || Messages.showYesNoDialog(project, message("panel.end.confirm"), "OpenCollab", null) == Messages.YES
            if (ok) service.leave()
        }
        settingsButton.addActionListener { openSettings() }

        service.addListener({ SwingUtilities.invokeLater(::refresh) }, parent)
        val clock = Timer(1000) { updateSessionTime() }.apply { start() }
        Disposer.register(parent) { clock.stop() }
        refresh()
    }

    /** One header line; [fill] lets [c] take the width (labels then end in "…" instead of overflowing). */
    private fun row(c: JComponent, fill: Boolean = false) = JPanel(BorderLayout()).apply {
        add(c, if (fill) BorderLayout.CENTER else BorderLayout.WEST)
        border = JBUI.Borders.emptyBottom(4)
        alignmentX = LEFT_ALIGNMENT
    }

    private fun openSettings() = ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenCollabConfigurable::class.java)

    // ---- Layout per mode ----------------------------------------------------------------

    /** Welcome screen outside a session, session view inside one; the chat/activity tabs move between them. */
    internal fun showView(inSession: Boolean) {
        if (shownInSession == inSession) return
        shownInSession = inSession
        removeAll()
        // The tabs are one component moving between views. Swing reparents it silently, but the
        // splitter still remembers it and ignores setting the same component again, so after
        // leave + rejoin the bottom half stayed empty: clear the splitter's reference first.
        splitter.secondComponent = null
        if (inSession) {
            splitter.secondComponent = bottomTabs
            add(sessionView, BorderLayout.CENTER)
        } else {
            idleView.add(bottomTabs, BorderLayout.CENTER)
            add(idleView, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    /** "You are editor · 00:01:20": the role only once in the session (not while waiting to be let in). */
    private fun updateSessionTime() {
        val clock = service.sessionClock()
        sessionTime.isVisible = clock != null
        if (clock == null) return
        val role = service.myRole
        sessionTime.text = if (role != null) message("panel.role.and.time", roleName(role), clock) else message("panel.session.time", clock)
    }

    // ---- Invitations and connection details ---------------------------------------------

    private fun showRunTargets() {
        val targets = if (service.canRequestRuns) service.runTargets else emptyList()
        if (targets.isEmpty() && !service.canRequestCommands) return
        val menu = JPopupMenu()
        for (target in targets) {
            val icon = if (target.kind == RunTargetKind.BUILD) AllIcons.Actions.Compile else AllIcons.Actions.Execute
            menu.add(JMenuItem(target.name, icon).apply {
                addActionListener { if (service.requestRun(target)) CollabUi.toast(runOnHostButton, message("console.request.sent", target.name)) }
            })
        }
        if (service.canRequestCommands) {
            if (targets.isNotEmpty()) menu.addSeparator()
            menu.add(JMenuItem(message("command.menu"), AllIcons.Debugger.Console).apply {
                addActionListener { askForCommand() }
            })
        }
        menu.show(runOnHostButton, 0, runOnHostButton.height)
    }

    /** A shell command for the host's project folder; the host sees it and approves or refuses it. */
    private fun askForCommand() {
        val command = Messages.showInputDialog(project, message("command.dialog.message"), message("command.dialog.title"), null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (service.requestCommand(command)) CollabUi.toast(runOnHostButton, message("console.request.sent", command))
    }

    private fun showInviteMenu() {
        val hosting = service.mode as? Mode.Hosting ?: return
        val menu = JPopupMenu()
        menu.add(JMenuItem(message("panel.invite.editor"), AllIcons.Actions.Edit).apply {
            addActionListener { copyInvite(hosting.editorInvite, message("panel.invite.copied.editor")) }
        })
        menu.add(JMenuItem(message("panel.invite.viewer"), AllIcons.Actions.Show).apply {
            addActionListener { copyInvite(hosting.viewerInvite, message("panel.invite.copied.viewer")) }
        })
        menu.addSeparator()
        menu.add(JMenuItem(message("panel.invite.revoke"), AllIcons.Actions.Cancel).apply {
            addActionListener {
                service.revokeInvites()
                CollabUi.toast(inviteButton, message("panel.invite.revoked"))
            }
        })
        menu.show(inviteButton, 0, inviteButton.height)
    }

    /** Copies an invitation and confirms with a short balloon, leaving the status line alone. */
    private fun copyInvite(invite: String, confirmation: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(invite))
        CollabUi.toast(inviteButton, confirmation)
    }

    private fun detailsExpanded() = PropertiesComponent.getInstance().getBoolean(DETAILS_KEY, false)

    private fun toggleDetails() {
        PropertiesComponent.getInstance().setValue(DETAILS_KEY, !detailsExpanded(), false)
        refresh()
    }

    // ---- Participants: hover actions and following -------------------------------------

    private fun own(p: ParticipantInfo) = p.id == service.ownParticipantId()

    /** Only the host has more actions, and only on guests. */
    private fun hasMoreActions(p: ParticipantInfo) = service.mode is Mode.Hosting && p.role != Role.HOST

    /** The host's actions on a guest, in groups (role, permissions, removal); null marks a separator. */
    private fun moreActions(p: ParticipantInfo): List<JMenuItem?> {
        if (!hasMoreActions(p)) return emptyList()
        val items = mutableListOf<JMenuItem?>()
        if (p.role != Role.EDITOR) items += JMenuItem(message("panel.make.editor")).apply { addActionListener { service.setRole(p.id, Role.EDITOR) } }
        if (p.role != Role.VIEWER) items += JMenuItem(message("panel.make.viewer")).apply { addActionListener { service.setRole(p.id, Role.VIEWER) } }
        items += null
        // What this guest may do besides editing; viewers can at most chat.
        val current = p.effectivePermissions
        val offered = if (p.role.canEdit) Permission.ALL else listOf(Permission.CHAT)
        for (permission in offered) {
            items += JCheckBoxMenuItem(message("panel.permission", permissionName(permission)), permission in current).apply {
                addActionListener { service.setPermissions(p.id, if (isSelected) current + permission else current - permission) }
            }
        }
        items += null
        items += JMenuItem(message("panel.remove"), AllIcons.Actions.Exit).apply { addActionListener { service.kick(p.id) } }
        return items
    }

    private fun toggleFollow(p: ParticipantInfo) {
        if (own(p)) return
        if (service.following == p.id) {
            service.stopFollowing()
            return
        }
        if (!service.follow(p.id)) CollabUi.toast(list, message("panel.no.open.file", p.displayName))
    }

    private enum class Hit { FOLLOW, MORE, ROW }

    /** Which part of row [index] the point is over: the hover icons sit at the right edge. */
    private fun hitAt(index: Int, x: Int): Hit {
        val bounds = list.getCellBounds(index, index) ?: return Hit.ROW
        val gap = JBUI.scale(ICON_GAP)
        val moreStart = bounds.x + bounds.width - gap - AllIcons.Actions.More.iconWidth
        val followStart = moreStart - gap - AllIcons.Actions.Show.iconWidth
        return when {
            x >= moreStart -> Hit.MORE
            x >= followStart -> Hit.FOLLOW
            else -> Hit.ROW
        }
    }

    private fun installListMouse() {
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = list.locationToIndex(e.point).takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(e.point) == true } ?: -1
                if (index != hovered) {
                    hovered = index
                    list.repaint()
                }
                val actionable = index >= 0 && hitAt(index, e.x) != Hit.ROW && !own(model[index])
                list.cursor = Cursor.getPredefinedCursor(if (actionable) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = -1
                list.cursor = Cursor.getDefaultCursor()
                list.repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val index = list.locationToIndex(e.point).takeIf { it >= 0 } ?: return
                val p = model[index]
                when {
                    hitAt(index, e.x) == Hit.FOLLOW && e.clickCount == 1 -> toggleFollow(p)
                    hitAt(index, e.x) == Hit.MORE && e.clickCount == 1 -> showMore(p, e)
                    e.clickCount == 2 -> toggleFollow(p)
                }
            }

            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
        }
        list.addMouseListener(mouse)
        list.addMouseMotionListener(mouse)
    }

    private fun showMore(p: ParticipantInfo, e: MouseEvent) {
        val items = moreActions(p)
        if (items.isEmpty()) return
        JPopupMenu().apply { addAll(items) }.show(list, e.x, e.y)
    }

    /** Right-click menu: follow plus the host's actions. */
    private fun popup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = list.locationToIndex(e.point)
        if (index < 0) return
        list.selectedIndex = index
        val p = model[index]
        val menu = JPopupMenu()
        if (!own(p)) {
            val following = service.following == p.id
            menu.add(JMenuItem(message(if (following) "panel.stop.following" else "panel.follow")).apply { addActionListener { toggleFollow(p) } })
        }
        val more = moreActions(p)
        if (menu.componentCount > 0 && more.isNotEmpty()) menu.addSeparator()
        menu.addAll(more)
        if (menu.componentCount > 0) menu.show(list, e.x, e.y)
    }

    private fun JPopupMenu.addAll(items: List<JMenuItem?>) = items.forEach { if (it == null) addSeparator() else add(it) }

    // ---- Refresh ------------------------------------------------------------------------

    private fun refresh() {
        val mode = service.mode
        val inSession = mode != Mode.Idle
        showView(inSession)
        if (!inSession) {
            // e.g. "Disconnected: Host ended the session" after an automatic leave.
            val idle = message("status.idle")
            lastStatus.text = service.status.takeIf { it != idle }?.let(::shown) ?: ""
            lastStatus.isVisible = lastStatus.text.isNotEmpty()
        }
        // Before any session the welcome screen stands alone; afterwards the last chat and activity stay readable.
        bottomTabs.isVisible = inSession || service.log().isNotEmpty() || service.chat().isNotEmpty()

        status.text = shown(service.status)
        status.toolTipText = status.text
        status.icon = service.health()?.let { CollabUi.Dot(CollabUi.healthColor(it)) }
        updateSessionTime()

        val hosting = mode as? Mode.Hosting
        inviteButton.isVisible = hosting != null
        val guestEditor = mode is Mode.Joined && service.myRole?.canEdit == true
        runOnHostButton.isVisible = mode is Mode.Joined
        runOnHostButton.isEnabled = guestEditor && ((service.canRequestRuns && service.runTargets.isNotEmpty()) || service.canRequestCommands)
        hostPanel.refresh()
        runOnHostButton.toolTipText = if (mode is Mode.Joined && !guestEditor) message("console.viewer") else null
        leaveButton.isEnabled = mode is Mode.Hosting || mode is Mode.Joined
        leaveButton.text = message(if (hosting != null) "panel.end.session" else "panel.leave")
        detailsLink.isVisible = hosting != null
        detailsLink.text = message("panel.connection.details") + if (detailsExpanded()) " ▾" else " ▸"
        endpoints.isVisible = hosting != null && detailsExpanded()
        endpoints.text = hosting?.let { shown(message("panel.endpoints", it.endpoints.joinToString(", "), it.fingerprint.take(29))) } ?: ""
        val router = service.routerStatus.takeIf { inSession && mode !is Mode.Joined }
        routerStatus.isVisible = router != null
        routerStatus.text = router?.let { "<html>${StringUtil.escapeXmlEntities(shown(it))}</html>" } ?: ""
        routerStatus.icon = if (router != null && service.routerProblem) AllIcons.General.Warning else null
        refreshJoinRequests(hosting != null)

        val selectedId = list.selectedValue?.id
        model.clear()
        service.participants.forEach(model::addElement)
        selectedId?.let { id -> (0 until model.size()).firstOrNull { model[it].id == id }?.let(list::setSelectedIndex) }
        val followed = service.following?.let { id -> service.participants.firstOrNull { it.id == id } }
        followBar.isVisible = followed != null
        followLabel.text = followed?.let { message("panel.following", it.displayName) } ?: ""

        val unread = service.unreadChat
        bottomTabs.setTitleAt(0, if (unread > 0) message("panel.chat.unread", unread) else message("panel.chat.title"))
        chatPanel.refresh(inSession = mode is Mode.Hosting || mode is Mode.Joined)
        // Ping updates refresh the panel every 2 s; leave the log (and where it is scrolled) alone unless it changed.
        val log = shown(service.log().joinToString("\n"))
        if (logArea.text != log) {
            logArea.text = log
            logArea.caretPosition = logArea.document.length
        }
    }

    /** Addresses partly hidden unless the user chose to see them (Settings | Tools | OpenCollab). */
    private fun shown(text: String) = OpenCollabSettings.getInstance().shown(text)

    /** One row per person waiting: name, role and (hidden) address, with Let in and Reject. */
    private fun refreshJoinRequests(hosting: Boolean) {
        val requests = if (hosting) service.joinRequests.toList() else emptyList()
        requestsPanel.isVisible = requests.isNotEmpty()
        val ids = requests.map { it.requestId }
        if (ids == shownRequests) return
        shownRequests = ids
        requestsTitle.text = message("panel.join.requests", requests.size)
        requestRows.removeAll()
        for (request in requests) {
            val who = JBLabel(request.displayName, AllIcons.General.User, SwingConstants.LEADING).apply {
                toolTipText = message("join.request.tooltip", roleName(request.role), shown(service.shownAddress(request.address)))
            }
            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(JButton(message("join.request.accept")).apply { addActionListener { service.answerJoin(request.requestId, true) } })
                add(JButton(message("join.request.reject")).apply { addActionListener { service.answerJoin(request.requestId, false) } })
            }
            requestRows.add(JPanel(BorderLayout()).apply {
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.empty(2, 0)
                add(who, BorderLayout.CENTER)
                add(JBLabel(roleName(request.role)).apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyLeft(6) }, BorderLayout.SOUTH)
                add(actions, BorderLayout.EAST)
            })
        }
        requestRows.revalidate()
        requestRows.repaint()
    }

    /** Word-wrap toggle and Clear for the activity log, as in the IDE's own consoles. */
    private fun activityToolbar(): JComponent {
        val wrap = object : DumbAwareToggleAction(message("panel.activity.wrap"), null, AllIcons.Actions.ToggleSoftWrap) {
            override fun isSelected(e: AnActionEvent) = logArea.lineWrap
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                logArea.lineWrap = state
                PropertiesComponent.getInstance().setValue(WRAP_KEY, state, true)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val clear = object : DumbAwareAction(message("panel.activity.clear"), null, AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) = service.clearLog()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = logArea.document.length > 0 }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val diagnostics = object : DumbAwareAction(message("action.OpenCollab.CopyDiagnostics.text"), message("action.OpenCollab.CopyDiagnostics.description"), AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) = dev.opencollab.plugin.session.Diagnostics.copy(project)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("OpenCollab.Activity", DefaultActionGroup(wrap, clear, diagnostics), true)
        toolbar.targetComponent = logArea
        return toolbar.component.apply { isOpaque = false; border = JBUI.Borders.empty() }
    }

    /**
     * Name, "(you)", role; on the right the ping as signal bars and, on hover (or while
     * following), the follow and more icons. Names are plain text.
     */
    private inner class ParticipantRenderer : ListCellRenderer<ParticipantInfo> {
        private val text = SimpleColoredComponent().apply { isOpaque = false }
        private val signal = JBLabel()
        private val follow = JBLabel()
        private val more = JBLabel()
        private val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(ICON_GAP), 0)).apply {
            isOpaque = false
            add(signal); add(follow); add(more)
        }
        private val row = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 4, 2, 0)
            add(text, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out ParticipantInfo>, value: ParticipantInfo, index: Int, selected: Boolean, hasFocus: Boolean,
        ): JComponent {
            row.background = UIUtil.getListBackground(selected, list.hasFocus())
            text.clear()
            text.icon = CollabUi.Dot(PresenceRenderer.colorFor(value.colorIndex))
            text.append(value.displayName)
            if (own(value)) text.append(message("panel.you"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            text.append("  " + roleName(value.role), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

            // Round-trip time to the host; the host's own row has none.
            val ms = if (value.role == Role.HOST) null else service.latencies[value.id]
            signal.icon = ms?.let { CollabUi.SignalIcon(it) } ?: EmptyIcon.ICON_0
            row.toolTipText = ms?.let { message("panel.ping.tooltip", it) }

            val following = service.following == value.id
            val showActions = !own(value) && (index == hovered || following)
            follow.icon = if (showActions) AllIcons.Actions.Show else EmptyIcon.create(AllIcons.Actions.Show)
            follow.toolTipText = message(if (following) "panel.stop.following" else "panel.follow")
            more.icon = if (showActions && index == hovered && hasMoreActions(value)) AllIcons.Actions.More else EmptyIcon.create(AllIcons.Actions.More)
            return row
        }
    }

    private companion object {
        const val WRAP_KEY = "dev.opencollab.activity.wrap"
        const val DETAILS_KEY = "dev.opencollab.connection.details"
        const val ICON_GAP = 4
    }
}
