package dev.opencollab.plugin.bridge

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.CustomHighlighterOrder
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import dev.opencollab.core.protocol.Message
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.Timer

/**
 * Draws remote participants' carets and selections. The name tag above a caret shows for a few
 * seconds after each move and then fades out, so it does not keep covering code; the caret bar
 * stays. EDT only.
 */
class PresenceRenderer(private val project: Project) {
    private val highlighters = HashMap<String, MutableList<RangeHighlighter>>()
    private val presence = HashMap<String, Message.PresenceBroadcast>()
    /** Name tags still visible or fading, per (editor, participant): when the latest one appeared. */
    private val liveTags = HashMap<Pair<Editor, String>, Long>()
    private val fadeTimer = Timer(FRAME_MS) { repaintFadingTags() }
    /** Each participant's caret renderers by editor, to repaint where their tag was. */
    private val tags = HashMap<String, MutableList<Pair<Editor, CaretRenderer>>>()

    fun update(p: Message.PresenceBroadcast, name: String, colorIndex: Int, documentFor: (String) -> Document?) {
        presence[p.participantId] = p
        clear(p.participantId)
        val path = p.path ?: return
        val document = documentFor(path) ?: return
        for (editor in EditorFactory.getInstance().getEditors(document, project)) draw(editor, p, name, colorIndex)
    }

    /** Re-draws everybody's presence in a newly opened editor. */
    fun editorOpened(editor: Editor, path: String, names: (String) -> Pair<String, Int>) {
        for (p in presence.values) {
            if (p.path != path) continue
            val (name, color) = names(p.participantId)
            draw(editor, p, name, color)
        }
    }

    fun remove(participantId: String) {
        presence.remove(participantId)
        clear(participantId)
    }

    fun clearAll() {
        highlighters.keys.toList().forEach(::clear)
        presence.clear()
        tags.clear()
        liveTags.clear()
        fadeTimer.stop()
    }

    /** Repaints editors whose tags are fading (and once more after they vanish); stops when none are left. */
    private fun repaintFadingTags() {
        val now = System.currentTimeMillis()
        val iterator = liveTags.entries.iterator()
        while (iterator.hasNext()) {
            val (key, shownAt) = iterator.next()
            val editor = key.first
            val age = now - shownAt
            if (editor.isDisposed) { iterator.remove(); continue }
            if (age >= TAG_VISIBLE_MS) editor.contentComponent.repaint()
            if (age > TAG_VISIBLE_MS + TAG_FADE_MS) iterator.remove()
        }
        if (liveTags.isEmpty()) fadeTimer.stop()
    }

    fun presenceOf(participantId: String): Message.PresenceBroadcast? = presence[participantId]

    private fun clear(participantId: String) {
        highlighters.remove(participantId)?.forEach { if (it.isValid) it.dispose() }
        // The tag sits above the caret's line, outside what disposing the highlighter repaints.
        tags.remove(participantId)?.forEach { (editor, renderer) ->
            renderer.lastTag?.let { if (!editor.isDisposed) editor.contentComponent.repaint(it) }
        }
    }

    private fun draw(editor: Editor, p: Message.PresenceBroadcast, name: String, colorIndex: Int) {
        if (editor.isDisposed) return
        val length = editor.document.textLength
        val color = colorFor(colorIndex)
        val list = highlighters.getOrPut(p.participantId) { mutableListOf() }
        val markup = editor.markupModel
        val selectionAttributes = TextAttributes().apply { backgroundColor = translucent(color) }
        for (range in p.selections) {
            val start = range.start.coerceIn(0, length)
            val end = range.end.coerceIn(start, length)
            if (start == end) continue
            list += markup.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, selectionAttributes, HighlighterTargetArea.EXACT_RANGE)
        }
        val caret = p.caret.coerceIn(0, length)
        val caretHighlighter = markup.addRangeHighlighter(caret, caret, HighlighterLayer.LAST, null, HighlighterTargetArea.EXACT_RANGE)
        // Text typed at the caret extends the range and the caret is drawn at its end, so it moves
        // with what that person types right away instead of waiting for their next caret update.
        caretHighlighter.isGreedyToRight = true
        val shownAt = System.currentTimeMillis()
        val renderer = CaretRenderer(color, name, shownAt)
        caretHighlighter.customRenderer = renderer
        list += caretHighlighter
        tags.getOrPut(p.participantId) { mutableListOf() } += editor to renderer
        liveTags[editor to p.participantId] = shownAt
        fadeTimer.start()
    }

    private class CaretRenderer(private val color: Color, private val name: String, private val shownAt: Long) : CustomHighlighterRenderer {
        /** Where the name tag was last painted, to repaint that area once the caret moves on. */
        var lastTag: Rectangle? = null

        /** Painted after the text, so the text of the line above does not cover the name tag. */
        override fun getOrder(): CustomHighlighterOrder = CustomHighlighterOrder.AFTER_TEXT

        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            if (!highlighter.isValid) return
            val point = editor.offsetToXY(highlighter.endOffset)
            val lineHeight = editor.lineHeight
            g.color = color
            g.fillRect(point.x, point.y, 2, lineHeight)

            val age = System.currentTimeMillis() - shownAt
            val opacity = when {
                age <= TAG_VISIBLE_MS -> 1f
                age >= TAG_VISIBLE_MS + TAG_FADE_MS -> 0f
                else -> 1f - (age - TAG_VISIBLE_MS).toFloat() / TAG_FADE_MS
            }
            if (opacity <= 0f) return
            val g2 = g.create() as Graphics2D
            try {
                g2.composite = AlphaComposite.SrcOver.derive(opacity)
                g2.font = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(editor.colorsScheme.editorFontSize2D * 0.75f)
                val metrics = g2.fontMetrics
                val width = metrics.stringWidth(name) + 6
                val height = metrics.height
                val top = (point.y - height).coerceAtLeast(0)
                lastTag = Rectangle(point.x, top, width + 1, height + 1)
                g2.color = color
                g2.fillRect(point.x, top, width, height)
                g2.color = JBColor.WHITE
                g2.drawString(name, point.x + 3, top + metrics.ascent)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        /** How long a name tag stays fully visible after its participant last moved or typed. */
        private const val TAG_VISIBLE_MS = 3_000L
        private const val TAG_FADE_MS = 500L
        private const val FRAME_MS = 40

        private val palette = listOf(
            JBColor(Color(0xE5534B), Color(0xF47067)), JBColor(Color(0x2F81F7), Color(0x539BF5)),
            JBColor(Color(0x1A7F37), Color(0x57AB5A)), JBColor(Color(0xBF8700), Color(0xC69026)),
            JBColor(Color(0x8250DF), Color(0x986EE2)), JBColor(Color(0xCF222E), Color(0xE0823D)),
            JBColor(Color(0x0E8A8A), Color(0x39C5CF)), JBColor(Color(0xBF3989), Color(0xE275AD)),
        )

        fun colorFor(index: Int): Color = palette[Math.floorMod(index, palette.size)]

        private fun translucent(c: Color) = Color(c.red, c.green, c.blue, 70)
    }
}
