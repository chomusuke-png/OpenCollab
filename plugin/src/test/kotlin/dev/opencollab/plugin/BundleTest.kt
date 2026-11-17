package dev.opencollab.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.Role
import java.io.File
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

/** Keeps the English and Spanish bundles complete and consistent. */
class BundleTest : BasePlatformTestCase() {
    private fun load(name: String): Properties = Properties().apply {
        val loader = OpenCollabBundle::class.java.classLoader
        (loader.getResourceAsStream("messages/$name.properties") ?: File("src/main/resources/messages/$name.properties").inputStream())
            .use { load(it) }
    }

    private val english by lazy { load("OpenCollabBundle") }
    private val spanish by lazy { load("OpenCollabBundle_es") }

    fun testSpanishHasExactlyTheEnglishKeys() {
        assertEquals("missing in Spanish", emptySet<String>(), english.stringPropertyNames() - spanish.stringPropertyNames())
        assertEquals("unknown in Spanish", emptySet<String>(), spanish.stringPropertyNames() - english.stringPropertyNames())
    }

    fun testPlaceholdersMatchAndPatternsAreValid() {
        val placeholder = Regex("""\{(\d+)}""")
        for (key in english.stringPropertyNames()) {
            val en = english.getProperty(key)
            val es = spanish.getProperty(key) ?: continue
            assertEquals("placeholders differ for $key", placeholder.findAll(en).map { it.value }.toSet(), placeholder.findAll(es).map { it.value }.toSet())
            MessageFormat(en, Locale.ENGLISH)
            MessageFormat(es, Locale.forLanguageTag("es"))
            // The IDE applies MessageFormat only when a message has parameters: without {n} a
            // doubled apostrophe would be shown literally; with {n} a single one would vanish.
            for ((lang, text) in listOf("en" to en, "es" to es)) {
                val hasParams = placeholder.containsMatchIn(text)
                if (hasParams) {
                    assertFalse("single apostrophe in $lang $key", Regex("(?<!')'(?!')").containsMatchIn(text))
                } else {
                    assertFalse("doubled apostrophe in $lang $key", text.contains("''"))
                }
            }
        }
    }

    fun testEveryKeyUsedInCodeExists() {
        val sources = File("src/main").walkTopDown().filter { it.extension == "kt" || it.extension == "xml" }
        val keyUse = Regex("""message\("([a-zA-Z0-9_.]+)"""")
        val xmlKey = Regex("""key="([a-zA-Z0-9_.]+)"""")
        val used = sources.flatMap { f ->
            val text = f.readText()
            (keyUse.findAll(text) + xmlKey.findAll(text)).map { it.groupValues[1] }
        }.toMutableSet()
        used += Role.entries.map { "role.${it.name}" }
        used += FileOpKind.entries.map { "file.op.${it.name}" }
        used += listOf("StartHosting", "Join", "CopyInvite", "Leave").flatMap { listOf("action.OpenCollab.$it.text", "action.OpenCollab.$it.description") }
        used += "group.OpenCollab.Menu.text"
        val missing = used.filterNot { english.containsKey(it) }
        assertTrue("keys used in code but missing in the bundle: $missing", missing.isEmpty())
        assertTrue(used.size > 50)
    }

    fun testSpanishLocaleResolvesSpanishTexts() {
        val es = java.util.ResourceBundle.getBundle(BUNDLE, Locale.forLanguageTag("es-CL"), javaClass.classLoader)
        assertEquals("Salir", es.getString("panel.leave"))
        assertEquals("anfitrión", es.getString("role.HOST"))
        assertEquals("¿Terminar la sesión para todos los participantes?", es.getString("panel.end.confirm"))
        assertEquals("Conectado a demo", MessageFormat(es.getString("status.connected")).format(arrayOf("demo")))
    }

    fun testIdeResolvesActionAndBundleTexts() {
        val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("OpenCollab.StartHosting")
        assertNotNull(action)
        assertFalse(action.templatePresentation.text.isNullOrBlank())
        assertEquals(english.getProperty("action.OpenCollab.StartHosting.text"), action.templatePresentation.text)
        assertEquals(english.getProperty("panel.leave"), OpenCollabBundle.message("panel.leave"))
    }
}
