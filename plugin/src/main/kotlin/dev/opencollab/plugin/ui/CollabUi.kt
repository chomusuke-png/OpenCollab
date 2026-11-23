package dev.opencollab.plugin.ui

import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import dev.opencollab.plugin.session.CollabSessionService.Health
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JComponent

/** Small shared pieces of the OpenCollab UI. */
object CollabUi {
    private val green = JBColor(Color(0x1A7F37), Color(0x57AB5A))
    private val amber = JBColor(Color(0xBF8700), Color(0xC69026))
    private val red = JBColor(Color(0xCF222E), Color(0xE5534B))
    private val faint = JBColor(Color(0xC8CCD1), Color(0x5A5D63))

    fun healthColor(health: Health): Color = when (health) {
        Health.OK -> green
        Health.BUSY -> amber
        Health.DOWN -> red
    }

    /** Pings at or above this show one red bar. */
    const val SLOW_PING_MS = 250
    private const val GOOD_PING_MS = 100

    /** Short-lived balloon under [anchor]; [text] is plain text. */
    fun toast(anchor: JComponent, text: String, type: MessageType = MessageType.INFO, fadeoutMillis: Long = 2_500) {
        if (!anchor.isShowing) return
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(StringUtil.escapeXmlEntities(text).replace("\n", "<br>"), type, null)
            .setFadeoutTime(fadeoutMillis)
            .createBalloon()
            .show(RelativePoint.getSouthOf(anchor), Balloon.Position.below)
    }

    /** A filled circle, for participant colors and the session status. */
    class Dot(private val color: Color, private val size: Int = JBUI.scale(10)) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x + 1, y + 1, size, size)
            g2.dispose()
        }
        override fun getIconWidth() = size + 2
        override fun getIconHeight() = size + 2
    }

    /** Three signal bars for a round-trip time: 3 green (fast), 2 amber, 1 red (slow). */
    class SignalIcon(ms: Int) : Icon {
        private val filled = when {
            ms < GOOD_PING_MS -> 3
            ms < SLOW_PING_MS -> 2
            else -> 1
        }
        private val color = when (filled) {
            3 -> green
            2 -> amber
            else -> red
        }
        private val bar = JBUI.scale(3)
        private val gap = JBUI.scale(1)
        private val height = JBUI.scale(12)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            for (i in 0 until 3) {
                val h = height * (i + 2) / 4
                g.color = if (i < filled) color else faint
                g.fillRect(x + i * (bar + gap), y + height - h, bar, h)
            }
        }
        override fun getIconWidth() = 3 * bar + 2 * gap
        override fun getIconHeight() = height
    }
}
