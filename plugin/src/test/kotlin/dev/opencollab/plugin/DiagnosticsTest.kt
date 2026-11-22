package dev.opencollab.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.opencollab.plugin.session.Diagnostics

/** The diagnostics report is meant to be posted publicly: nothing personal may be left in it. */
class DiagnosticsTest : BasePlatformTestCase() {
    fun testAddressesPathsAndNamesAreHidden() {
        val text = """
            10:00:01  Session started: 192.168.1.54:47800, wss://myhome.duckdns.org:8443
            10:00:05  Ana Maria joined as editor from 203.0.113.9
            10:00:06  Ana joined as viewer
            10:00:07  Opened C:\Users\zumito\Projects\Secret\src\Main.kt and C:/Users/zumito/.gradle/x
            10:00:08  Banana is not a name here
        """.trimIndent()
        val out = Diagnostics.sanitize(text, listOf("Ana", "Ana Maria"), home = "C:\\Users\\zumito", projectPath = "C:/Users/zumito/Projects/Secret")
        assertFalse(out, out.contains("192.168.1.54"))
        assertFalse(out, out.contains("203.0.113.9"))
        assertFalse(out, out.contains("myhome"))
        assertFalse(out, out.contains("zumito"))
        assertFalse(out, out.contains("Secret"))
        assertFalse(out, out.contains("Ana "))
        assertTrue(out, out.contains("<person 1> joined as editor"))
        assertTrue(out, out.contains("<person 2> joined as viewer"))
        assertTrue(out, out.contains("<project>\\src\\Main.kt"))
        assertTrue(out, out.contains("~/.gradle/x"))
        assertTrue(out, out.contains("Banana")) // only whole words are names
        assertTrue(out, out.contains(".54:•••"))
        assertFalse(out, out.contains("47800"))
        assertFalse(out, out.contains("8443"))
    }

    fun testTheReportHasVersionsAndNoSecrets() {
        val report = Diagnostics.report(project)
        assertTrue(report, report.startsWith("OpenCollab diagnostics"))
        assertTrue(report, report.contains("Plugin: ${Diagnostics.pluginVersion()} "))
        assertTrue(Diagnostics.pluginVersion(), Diagnostics.pluginVersion().first().isDigit())
        assertTrue(report, report.contains("Session: not in a session"))
        assertFalse(report, report.contains("secret", ignoreCase = true))
    }
}
