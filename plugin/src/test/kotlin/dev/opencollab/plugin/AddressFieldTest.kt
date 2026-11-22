package dev.opencollab.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.opencollab.plugin.settings.AddressField

/** Address fields in the settings: dots while addresses are hidden, plain text when shown. */
class AddressFieldTest : BasePlatformTestCase() {
    fun testHiddenAddressesShowDotsAndCannotBeCopied() {
        val field = AddressField(shown = false)
        field.text = "203.0.113.54"
        assertFalse(field.revealed)
        assertTrue(field.echoChar != 0.toChar())
        assertEquals(false, field.getClientProperty("JPasswordField.cutCopyAllowed"))
        assertEquals("203.0.113.54", String(field.password)) // the value itself is kept
    }

    fun testShowingAddressesRevealsTheTextAtOnce() {
        val field = AddressField(shown = false)
        field.shown = true
        assertTrue(field.revealed)
        assertEquals(0.toChar(), field.echoChar)
        assertEquals(true, field.getClientProperty("JPasswordField.cutCopyAllowed"))
        field.shown = false
        assertTrue(field.echoChar != 0.toChar())
    }
}
