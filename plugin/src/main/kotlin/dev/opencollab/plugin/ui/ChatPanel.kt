package dev.opencollab.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.opencollab.core.protocol.ChatEntry
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.bridge.PresenceRenderer
import dev.opencollab.plugin.session.CollabSessionService
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Session chat: the messages relayed by the host (names in each participant's color) and an
 * input that sends on Enter. Text is inserted as plain document text, never as HTML.
 */
class ChatPanel(private val service: CollabSessionService) : JPanel(BorderLayout()) {
    private val pane = JTextPane().apply { isEditable = false; border = JBUI.Borders.empty(4) }
    private val input = JBTextField()
    private val send = JButton(message("chat.send"))
    private val hint = JBLabel().apply { foreground = JBColor.RED; isVisible = false }
    private val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private var shown: List<Long> = emptyList()

    /** True while the chat tab is the selected one. */
    var selected = true
        set(value) {
            field = value
            updateOnScreen()
        }

    /** Messages count as read only while the chat is really visible: tab selected and tool window shown. */
    private fun updateOnScreen() {
        service.chatOnScreen = selected && isShowing
    }

    init {
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) updateOnScreen()
        }
        input.addActionListener { submit() }
        send.addActionListener { submit() }
        val bottom = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.emptyTop(4)
            add(input, BorderLayout.CENTER)
            add(send, BorderLayout.EAST)
            add(hint, BorderLayout.SOUTH)
        }
        add(JBScrollPane(pane), BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
    }

    private fun submit() {
        val text = input.text
        if (text.isBlank()) return
        if (text.trim().length > MessageCodec.MAX_CHAT_CHARS) {
            showHint(message("chat.too.long", MessageCodec.MAX_CHAT_CHARS))
            return
        }
        if (service.sendChat(text)) {
            input.text = ""
            showHint(null)
        } else {
            showHint(message("chat.not.sent"))
        }
    }

    private fun showHint(text: String?) {
        hint.text = text ?: ""
        hint.isVisible = text != null
    }

    /** Brings the panel up to date with the session's chat. EDT. */
    fun refresh(inSession: Boolean) {
        input.isEnabled = inSession
        send.isEnabled = inSession
        input.emptyText.text = message(if (inSession) "chat.placeholder" else "chat.not.in.session")
        if (!inSession) showHint(null)

        val entries = service.chat()
        val ids = entries.map { it.id }
        if (ids == shown) return
        // Usually new messages were appended; anything else (history after joining, a new
        // session, old messages trimmed) is redrawn from scratch.
        val appended = shown.isNotEmpty() && ids.size > shown.size && ids.subList(0, shown.size) == shown
        val doc = pane.styledDocument
        val added = if (appended) entries.drop(shown.size) else entries.also { doc.remove(0, doc.length) }
        added.forEach(::append)
        shown = ids
        pane.caretPosition = doc.length
    }

    private fun append(e: ChatEntry) {
        val doc = pane.styledDocument
        val muted = SimpleAttributeSet().apply { StyleConstants.setForeground(this, UIUtil.getContextHelpForeground()) }
        val name = SimpleAttributeSet().apply {
            StyleConstants.setBold(this, true)
            StyleConstants.setForeground(this, PresenceRenderer.colorFor(e.colorIndex))
        }
        if (doc.length > 0) doc.insertString(doc.length, "\n", null)
        doc.insertString(doc.length, time.format(Instant.ofEpochMilli(e.sentAtMillis)) + "  ", muted)
        doc.insertString(doc.length, e.displayName, name)
        doc.insertString(doc.length, ": " + e.text, null)
    }
}
