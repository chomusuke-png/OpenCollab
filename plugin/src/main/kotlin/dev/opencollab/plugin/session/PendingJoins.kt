package dev.opencollab.plugin.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.opencollab.core.files.MirrorWorkspace
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Joins that must happen in a project window that is still opening (joining into a new folder).
 * Entries expire after five minutes and are consumed once.
 */
object PendingJoins {
    private class Entry(val invite: String, val expiresAt: Long)
    private val pending = ConcurrentHashMap<String, Entry>()

    private fun key(path: Path) = path.toAbsolutePath().normalize().toString().lowercase()

    fun put(folder: Path, invite: String) {
        pending[key(folder)] = Entry(invite, System.currentTimeMillis() + 5 * 60_000)
    }

    fun take(folder: Path): String? {
        val entry = pending.remove(key(folder)) ?: return null
        return entry.invite.takeIf { entry.expiresAt > System.currentTimeMillis() }
    }

    /**
     * A free folder for a new mirror: `<home>/OpenCollab/<project>` (or `-2`, `-3`, … if that one
     * holds unrelated files). Characters that are invalid in folder names are replaced.
     */
    fun suggestFolder(projectName: String, base: Path = Path.of(System.getProperty("user.home"), "OpenCollab")): Path {
        val safe = projectName.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim().trimEnd('.', ' ').ifEmpty { "project" }.take(80)
        var candidate = base.resolve(safe)
        var n = 2
        while (Files.exists(candidate) && !MirrorWorkspace(candidate).canAdopt()) {
            candidate = base.resolve("$safe-${n++}")
        }
        return candidate
    }
}

/** Completes a pending join once the new project window has opened. */
class PendingJoinActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val base = project.basePath ?: return
        val invite = PendingJoins.take(Path.of(base)) ?: return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            try {
                CollabSessionService.getInstance(project).join(invite)
            } catch (e: Exception) {
                logger<PendingJoinActivity>().warn("OpenCollab: could not join after opening $base", e)
            }
        }, ModalityState.nonModal())
    }
}
