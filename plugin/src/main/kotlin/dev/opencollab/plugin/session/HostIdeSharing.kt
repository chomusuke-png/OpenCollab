package dev.opencollab.plugin.session

import com.intellij.application.options.CodeStyle
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Computable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.codeStyle.CodeStyleSettingsListener
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.opencollab.core.files.SafePath
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.protocol.BreakpointLine
import dev.opencollab.core.protocol.DebugFrame
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.protocol.VcsChange
import dev.opencollab.core.session.HostSession
import dev.opencollab.plugin.OpenCollabBundle.message
import org.jdom.Element
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host side of the IDE state guests see: where this IDE's debugger stopped (frames and
 * variables), its line breakpoints (guest editors may add and remove them), the version control
 * branch and changed files, and the code style, so guests format the same way. Guests never
 * get control of the debugger, version control or settings: they only see them.
 */
class HostIdeSharing(
    private val project: Project,
    private val root: Path,
    private val rules: SharingRules,
    private val session: HostSession,
    private val log: (String) -> Unit,
) : Disposable {
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val vcsPoll = scheduler.scheduleWithFixedDelay({ runCatching { publishVcs() } }, 0, VCS_POLL_SECONDS, TimeUnit.SECONDS)
    /** Each pause gets a generation; snapshots of an older one are dropped. */
    private val debugGeneration = AtomicInteger()

    /** EDT. */
    fun install() {
        val bus = project.messageBus.connect(this)
        bus.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                watch(debugProcess.session)
                publishDebug()
            }
            override fun processStopped(debugProcess: XDebugProcess) = publishDebug()
            override fun currentSessionChanged(previousSession: XDebugSession?, currentSession: XDebugSession?) = publishDebug()
        })
        XDebuggerManager.getInstance(project).debugSessions.forEach(::watch)
        publishDebug()

        bus.subscribe(XBreakpointListener.TOPIC, object : XBreakpointListener<XBreakpoint<*>> {
            override fun breakpointAdded(breakpoint: XBreakpoint<*>) = publishBreakpoints()
            override fun breakpointRemoved(breakpoint: XBreakpoint<*>) = publishBreakpoints()
            override fun breakpointChanged(breakpoint: XBreakpoint<*>) = publishBreakpoints()
        })
        publishBreakpoints()

        bus.subscribe(CodeStyleSettingsListener.TOPIC, CodeStyleSettingsListener { publishCodeStyle() })
        publishCodeStyle()
    }

    override fun dispose() {
        vcsPoll.cancel(false)
    }

    private fun relative(file: VirtualFile?): String? {
        if (file == null || !file.isInLocalFileSystem) return null
        val path = try { SafePath.relativize(root, file.toNioPath()) } catch (e: Exception) { null } ?: return null
        return path.takeIf { rules.isShared(it) }
    }

    // ---- Debugger -------------------------------------------------------------------------

    private fun watch(debugSession: XDebugSession) {
        debugSession.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = publishDebug()
            override fun sessionResumed() = publishDebug()
            override fun sessionStopped() = publishDebug()
            override fun stackFrameChanged() = publishDebug()
        }, this)
    }

    /**
     * Publishes the current debugger state. While paused, frames and variables are computed
     * asynchronously by the debugger; snapshots go out as they fill in (identical ones are not
     * sent again).
     */
    private fun publishDebug() {
        val generation = debugGeneration.incrementAndGet()
        val manager = XDebuggerManager.getInstance(project)
        val current = manager.currentSession ?: manager.debugSessions.firstOrNull()
        if (current == null || current.isStopped) {
            session.publishIdeState(Message.DebugState(active = false))
            return
        }
        val name = current.sessionName.take(MessageCodec.MAX_TITLE).ifBlank { "?" }
        if (!current.isSuspended) {
            session.publishIdeState(Message.DebugState(active = true, paused = false, sessionName = name))
            return
        }
        val position = current.currentPosition
        val snapshot = DebugSnapshot(
            name = name,
            path = relative(position?.file),
            line = position?.line ?: -1,
            describe = ::frameOf,
        )
        snapshot.collect(current)
        // The debugger fills frames and values in over a short while: send what is known by then.
        for (delay in SNAPSHOT_DELAYS_MS) {
            scheduler.schedule({ if (debugGeneration.get() == generation) session.publishIdeState(snapshot.message()) }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun frameOf(name: String, position: XSourcePosition?): DebugFrame =
        DebugFrame(name.take(MessageCodec.MAX_TITLE).ifBlank { "?" }, relative(position?.file), position?.line ?: -1)

    // ---- Breakpoints --------------------------------------------------------------------------

    private fun publishBreakpoints() {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val lines = ApplicationManager.getApplication().runReadAction(Computable {
                XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
                    .filterIsInstance<XLineBreakpoint<*>>()
                    .mapNotNull { bp ->
                        val file = VirtualFileManager.getInstance().findFileByUrl(bp.fileUrl)
                        relative(file)?.let { BreakpointLine(it, bp.line) }
                    }
                    .distinct().sortedWith(compareBy({ it.path }, { it.line }))
                    .take(MessageCodec.MAX_BREAKPOINTS)
            })
            session.publishIdeState(Message.Breakpoints(lines))
        }, ModalityState.nonModal())
    }

    /** Session thread: a guest editor adds or removes a line breakpoint in a shared file. */
    fun onBreakpointRequest(displayName: String, path: String, line: Int, add: Boolean) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val target = try { SafePath.resolveInside(root, path) } catch (e: SecurityException) { return@invokeLater }
            val file = LocalFileSystem.getInstance().findFileByNioFile(target) ?: return@invokeLater
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val util = XDebuggerUtil.getInstance()
            val types = util.lineBreakpointTypes.filter { it.canPutAt(file, line, project) }
            val existing = types.flatMap { manager.findBreakpointsAtLine(it, file, line) }
            if (add && existing.isEmpty() && types.isNotEmpty()) {
                util.toggleLineBreakpoint(project, file, line, false)
                log(message("debug.breakpoint.added", displayName, "$path:${line + 1}"))
            } else if (!add && existing.isNotEmpty()) {
                WriteAction.run<RuntimeException> { existing.forEach(manager::removeBreakpoint) }
                log(message("debug.breakpoint.removed", displayName, "$path:${line + 1}"))
            }
        }, ModalityState.nonModal())
    }

    // ---- Version control ----------------------------------------------------------------------

    /** Background: the branch and changed files, polled (cheap: both are cached by the IDE). */
    private fun publishVcs() {
        if (project.isDisposed) return
        val repositories = VcsRepositoryManager.getInstance(project).getRepositories()
        val rootFile = LocalFileSystem.getInstance().findFileByNioFile(root)
        val repository = repositories.firstOrNull { it.root == rootFile }
            ?: repositories.firstOrNull { rootFile != null && com.intellij.openapi.vfs.VfsUtilCore.isAncestor(it.root, rootFile, false) }
            ?: repositories.firstOrNull()
        val branch = repository?.currentBranchName?.take(MessageCodec.MAX_TITLE)?.takeIf { it.isNotBlank() }
        val changes = ArrayList<VcsChange>()
        val manager = ChangeListManager.getInstance(project)
        for (change in manager.allChanges) {
            val status = when (change.type) {
                Change.Type.NEW -> VcsChange.ADDED
                Change.Type.DELETED -> VcsChange.DELETED
                Change.Type.MOVED -> VcsChange.MOVED
                else -> VcsChange.MODIFIED
            }
            val filePath = (change.afterRevision ?: change.beforeRevision)?.file ?: continue
            relativeOf(filePath)?.let { changes += VcsChange(it, status) }
        }
        manager.unversionedFilesPaths.forEach { fp -> relativeOf(fp)?.let { changes += VcsChange(it, VcsChange.UNVERSIONED) } }
        if (branch == null && changes.isEmpty() && repositories.isEmpty()) return // not under version control
        val sorted = changes.distinctBy { it.path }.sortedBy { it.path }.take(MessageCodec.MAX_VCS_CHANGES)
        session.publishIdeState(Message.VcsStatus(branch, sorted))
    }

    private fun relativeOf(filePath: FilePath): String? {
        val path = try { SafePath.relativize(root, Path.of(filePath.path)) } catch (e: Exception) { null } ?: return null
        return path.takeIf { rules.isShared(it) }
    }

    // ---- Code style -------------------------------------------------------------------------

    /** The effective code style (project or IDE scheme), so guests format exactly like the host. */
    private fun publishCodeStyle() {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val xml = try {
                val element = Element("code_scheme")
                CodeStyle.getSettings(project).writeExternal(element)
                JDOMUtil.write(element)
            } catch (e: Exception) {
                return@invokeLater
            }
            if (xml.length <= MessageCodec.MAX_CODE_STYLE_CHARS) session.publishIdeState(Message.CodeStyle(xml))
        }, ModalityState.nonModal())
    }

    private companion object {
        const val VCS_POLL_SECONDS = 2L
        val SNAPSHOT_DELAYS_MS = listOf(150L, 600L, 1_500L, 4_000L)
    }
}
