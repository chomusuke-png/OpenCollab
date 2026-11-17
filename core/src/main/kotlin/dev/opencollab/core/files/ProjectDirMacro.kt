package dev.opencollab.core.files

import dev.opencollab.core.protocol.PROJECT_DIR_MACRO
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Shared console text names project files relative to [PROJECT_DIR_MACRO]: the host replaces its
 * project folder with the macro (so its folder layout is not exposed), and each guest puts its
 * own mirror folder back so the paths open there.
 */
class ProjectDirMacro(root: Path) {
    private val folder = root.toAbsolutePath().normalize()

    /** This folder with either separator, case-insensitive, then the rest of the path (group 1). */
    private val pattern: Pattern = run {
        val parts = folder.toString().split('\\', '/').filter { it.isNotEmpty() }
        val prefix = (if (folder.toString().startsWith("/")) "[\\\\/]" else "") + parts.joinToString("[\\\\/]") { Pattern.quote(it) }
        // The lookahead keeps /dev/tests from matching inside /dev/tests2.
        Pattern.compile(prefix + "(?![^\\\\/\\s:\"'()<>|*?])((?:[\\\\/][^\\s:\"'()<>|*?]*)?)", Pattern.CASE_INSENSITIVE)
    }

    /** Host: this folder becomes the macro; the rest of each such path gets forward slashes. */
    fun hide(text: String): String {
        val matcher = pattern.matcher(text)
        if (!matcher.find()) return text
        val out = StringBuilder()
        var last = 0
        do {
            out.append(text, last, matcher.start())
            out.append(PROJECT_DIR_MACRO).append(matcher.group(1).replace('\\', '/'))
            last = matcher.end()
        } while (matcher.find())
        return out.append(text, last, text.length).toString()
    }

    /** Guest: the macro becomes this folder (with forward slashes, which every IDE platform accepts). */
    fun reveal(text: String): String =
        if (PROJECT_DIR_MACRO in text) text.replace(PROJECT_DIR_MACRO, folder.toString().replace('\\', '/')) else text
}
