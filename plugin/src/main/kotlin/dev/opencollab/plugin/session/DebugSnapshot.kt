package dev.opencollab.plugin.session

import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.openapi.editor.colors.TextAttributesKey
import dev.opencollab.core.protocol.DebugFrame
import dev.opencollab.core.protocol.DebugVariable
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.MessageCodec
import javax.swing.Icon

/**
 * What the host's debugger shows while paused: the stack frames of the active thread and the
 * variables of the selected frame (names, types and values, one level deep), collected from the
 * debugger's asynchronous callbacks. Thread-safe; [message] is a snapshot of what arrived so far.
 */
internal class DebugSnapshot(
    private val name: String,
    private val path: String?,
    private val line: Int,
    private val describe: (String, XSourcePosition?) -> DebugFrame,
) {
    private val frames = ArrayList<DebugFrame>()
    /** Variable name → (type, value), in the debugger's order. */
    private val variables = LinkedHashMap<String, Pair<String?, String>>()

    fun collect(session: XDebugSession) {
        session.suspendContext?.activeExecutionStack?.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) {
                val described = stackFrames.map { describe(textOf(it), it.sourcePosition) }
                synchronized(this@DebugSnapshot) {
                    val room = MessageCodec.MAX_DEBUG_FRAMES - frames.size
                    if (room > 0) frames += described.take(room)
                }
            }
            override fun errorOccurred(errorMessage: String) = Unit
            override fun isObsolete(): Boolean = synchronized(this@DebugSnapshot) { frames.size >= MessageCodec.MAX_DEBUG_FRAMES }
        })
        session.currentStackFrame?.computeChildren(Children())
    }

    @Synchronized
    fun message(): Message.DebugState = Message.DebugState(
        active = true,
        paused = true,
        sessionName = name,
        path = path,
        line = line,
        frames = frames.toList(),
        variables = variables.entries.map { (n, v) -> DebugVariable(n, v.second, v.first) },
    )

    private fun textOf(frame: XStackFrame): String {
        val text = StringBuilder()
        frame.customizePresentation(object : ColoredTextContainer {
            override fun append(fragment: String, attributes: SimpleTextAttributes) { text.append(fragment) }
            override fun append(fragment: String, attributes: SimpleTextAttributes, tag: Any?) { text.append(fragment) }
            override fun setIcon(icon: Icon?) = Unit
            override fun setToolTipText(text: String?) = Unit
        })
        return clean(text.toString(), MessageCodec.MAX_TITLE)
    }

    /** The frame's top-level variables; each value's text arrives later through its own node. */
    private inner class Children : XCompositeNode {
        override fun addChildren(children: XValueChildrenList, last: Boolean) {
            for (i in 0 until children.size()) {
                val varName = clean(children.getName(i), MessageCodec.MAX_TITLE)
                synchronized(this@DebugSnapshot) {
                    if (variables.size >= MessageCodec.MAX_DEBUG_VARIABLES || varName.isEmpty()) return
                    variables.putIfAbsent(varName, null to "…")
                }
                children.getValue(i).computePresentation(ValueNode(varName), XValuePlace.TREE)
            }
        }
        override fun tooManyChildren(remaining: Int) = Unit
        override fun setAlreadySorted(alreadySorted: Boolean) = Unit
        override fun setErrorMessage(errorMessage: String) = Unit
        override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) = Unit
        override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) = Unit
        override fun isObsolete(): Boolean = false
    }

    private inner class ValueNode(private val varName: String) : XValueNode {
        override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) = store(type, value)

        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
            val text = StringBuilder()
            presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                override fun renderValue(value: String) { text.append(value) }
                override fun renderStringValue(value: String) { text.append('"').append(value).append('"') }
                override fun renderNumericValue(value: String) { text.append(value) }
                override fun renderKeywordValue(value: String) { text.append(value) }
                override fun renderValue(value: String, key: TextAttributesKey) { text.append(value) }
                override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
                    text.append('"').append(value).append('"')
                }
                override fun renderComment(comment: String) { text.append(comment) }
                override fun renderSpecialSymbol(symbol: String) { text.append(symbol) }
                override fun renderError(error: String) { text.append(error) }
            })
            store(presentation.type, text.toString())
        }

        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) = Unit
        override fun isObsolete(): Boolean = false

        private fun store(type: String?, value: String) = synchronized(this@DebugSnapshot) {
            variables[varName] = type?.let { clean(it, MessageCodec.MAX_TITLE) }?.ifEmpty { null } to clean(value, MessageCodec.MAX_DEBUG_VALUE)
        }
    }

    private companion object {
        /** One bounded line without control characters (the protocol refuses them). */
        fun clean(text: String, max: Int): String {
            val oneLine = text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
            return if (oneLine.length <= max) oneLine else oneLine.take(max - 1) + "…"
        }
    }
}
