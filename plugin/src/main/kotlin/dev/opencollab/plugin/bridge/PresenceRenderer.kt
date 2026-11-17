package dev.opencollab.plugin.bridge

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import dev.opencollab.core.protocol.Message
import java.awt.Color
import java.awt.Graphics

/** Draws remote participants' carets (with a name tag) and selections. EDT only. */
class PresenceRenderer(private val project: Project) {
    private val highlighters = HashMap<String, MutableList<RangeHighlighter>>()
    private val presence = HashMap<String, Message.PresenceBroadcast>()

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
    }

    fun presenceOf(participantId: String): Message.PresenceBroadcast? = presence[participantId]

    private fun clear(participantId: String) {
        highlighters.remove(participantId)?.forEach { if (it.isValid) it.dispose() }
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
        caretHighlighter.customRenderer = CaretRenderer(color, name)
        list += caretHighlighter
    }

    private class CaretRenderer(private val color: Color, private val name: String) : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            if (!highlighter.isValid) return
            val point = editor.offsetToXY(highlighter.startOffset)
            val lineHeight = editor.lineHeight
            g.color = color
            g.fillRect(point.x, point.y, 2, lineHeight)
            val font = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(editor.colorsScheme.editorFontSize2D * 0.75f)
            g.font = font
            val metrics = g.fontMetrics
            val width = metrics.stringWidth(name) + 6
            val height = metrics.height
            val top = (point.y - height).coerceAtLeast(0)
            g.fillRect(point.x, top, width, height)
            g.color = JBColor.WHITE
            g.drawString(name, point.x + 3, top + metrics.ascent)
        }
    }

    companion object {
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
