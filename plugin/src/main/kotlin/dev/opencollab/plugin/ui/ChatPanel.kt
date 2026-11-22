package dev.opencollab.plugin.ui

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.opencollab.core.protocol.ChatEntry
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.bridge.PresenceRenderer
import dev.opencollab.plugin.session.CollabSessionService
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import javax.swing.Timer
import javax.swing.event.DocumentEvent
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
    private val typingLabel = JBLabel(" ").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBFont.small().asItalic()
        border = JBUI.Borders.empty(2, 4, 0, 0)
    }
    // Our own typing state, refreshed while we type and cleared when idle or sent.
    private var typingSentAt = 0L
    private var lastKeyAt = 0L
    private val typingTimer = Timer(500) { tickTyping() }
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
        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = typed()
        })
        typingTimer.start()
        val inputRow = JPanel(BorderLayout(4, 0)).apply {
            add(input, BorderLayout.CENTER)
            add(send, BorderLayout.EAST)
            add(hint, BorderLayout.SOUTH)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(typingLabel, BorderLayout.NORTH)
            add(inputRow, BorderLayout.CENTER)
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
            stopTyping()
            showHint(null)
        } else {
            showHint(message("chat.not.sent"))
        }
    }

    /** Tells the others we are typing: at once, then every 2 s while we keep typing. */
    private fun typed() {
        val now = System.currentTimeMillis()
        lastKeyAt = now
        if (input.text.isBlank()) return stopTyping()
        if (now - typingSentAt >= TYPING_REFRESH_MS) {
            typingSentAt = now
            service.setChatTyping(true)
        }
    }

    private fun stopTyping() {
        if (typingSentAt == 0L) return
        typingSentAt = 0L
        service.setChatTyping(false)
    }

    /** Clears our typing state after a pause, and updates who else is typing. */
    private fun tickTyping() {
        if (typingSentAt != 0L && System.currentTimeMillis() - lastKeyAt > TYPING_IDLE_MS) stopTyping()
        updateTypingLabel()
    }

    private fun updateTypingLabel() {
        val names = service.typingNames()
        typingLabel.text = when (names.size) {
            0 -> " " // keeps the line's height so the input does not jump
            1 -> message("chat.typing.one", names[0])
            2 -> message("chat.typing.two", names[0], names[1])
            else -> message("chat.typing.many")
        }
    }

    private fun showHint(text: String?) {
        hint.text = text ?: ""
        hint.isVisible = text != null
    }

    /** Brings the panel up to date with the session's chat. EDT. */
    fun refresh(inSession: Boolean) {
        // The host may mute a participant in the chat: they still read it.
        input.isEnabled = inSession && service.canChat
        send.isEnabled = inSession && service.canChat
        updateTypingLabel()
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

    private companion object {
        const val TYPING_REFRESH_MS = 2_000L
        const val TYPING_IDLE_MS = 4_000L
    }
}
