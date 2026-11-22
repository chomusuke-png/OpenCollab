package dev.opencollab.plugin.session

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.ui.DebuggerColors
import dev.opencollab.core.files.SafePath
import dev.opencollab.core.protocol.BreakpointLine
import dev.opencollab.core.protocol.Message
import dev.opencollab.plugin.OpenCollabBundle.message
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Guest side of the host's IDE state: highlights the line where the host's debugger stopped,
 * keeps this IDE's line breakpoints in the shared files equal to the host's (adding or removing
 * one here asks the host to do the same), keeps the host's branch and changed files for the
 * panel, and formats with the host's code style. EDT only, except [onState].
 */
class GuestIdeMirror(
    private val project: Project,
    private val root: Path,
    private val service: CollabSessionService,
) : Disposable {
    @Volatile var debug: Message.DebugState? = null
        private set
    @Volatile var vcs: Message.VcsStatus? = null
        private set
    /** The host's breakpoints; local breakpoints at these lines are the host's, not ours to report. */
    private var hostBreakpoints: Set<BreakpointLine> = emptySet()
    private var retries = 0
    private var executionPoint: RangeHighlighter? = null

    fun install() {
        project.messageBus.connect(this).subscribe(XBreakpointListener.TOPIC, object : XBreakpointListener<XBreakpoint<*>> {
            override fun breakpointAdded(breakpoint: XBreakpoint<*>) = localChange(breakpoint, add = true)
            override fun breakpointRemoved(breakpoint: XBreakpoint<*>) = localChange(breakpoint, add = false)
        })
    }

    /** Any thread. */
    fun onState(state: Message) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            when (state) {
                is Message.DebugState -> showDebug(state)
                is Message.Breakpoints -> mirrorBreakpoints(state.breakpoints)
                is Message.VcsStatus -> vcs = state
                is Message.CodeStyle -> applyCodeStyle(state.xml)
                else -> return@invokeLater
            }
            service.changed()
        }, ModalityState.nonModal())
    }

    override fun dispose() {
        clearExecutionPoint()
    }

    fun fileOf(path: String): VirtualFile? {
        val target = try { SafePath.resolveInside(root, path) } catch (e: SecurityException) { return null }
        return LocalFileSystem.getInstance().findFileByNioFile(target)
    }

    fun navigate(path: String, line: Int) {
        val file = fileOf(path) ?: return
        OpenFileDescriptor(project, file, maxOf(line, 0), 0).navigate(true)
    }

    // ---- Debugger -------------------------------------------------------------------------

    private fun showDebug(state: Message.DebugState) {
        val wasPaused = debug?.paused == true
        debug = state.takeIf { it.active }
        clearExecutionPoint()
        val path = state.path
        if (!state.paused || path == null || state.line < 0) {
            return
        }
        val document = fileOf(path)?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (document != null && state.line < document.lineCount) {
            val markup = DocumentMarkupModel.forDocument(document, project, true)
            executionPoint = markup.addLineHighlighter(DebuggerColors.EXECUTIONPOINT_ATTRIBUTES, state.line, DebuggerColors.EXECUTION_LINE_HIGHLIGHTERLAYER).also {
                it.errorStripeTooltip = message("debug.paused.here")
            }
        }
        if (!wasPaused) {
            val where = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities("$path:${state.line + 1}")
            dev.opencollab.plugin.ui.PanelNotice.show(project, message("debug.paused", where) + "<br><a href=\"show\">" + message("debug.show") + "</a>") {
                navigate(path, state.line)
            }
        }
    }

    private fun clearExecutionPoint() {
        executionPoint?.dispose()
        executionPoint = null
    }

    // ---- Breakpoints --------------------------------------------------------------------------

    private fun lineOf(breakpoint: XBreakpoint<*>): BreakpointLine? {
        if (breakpoint !is XLineBreakpoint<*>) return null
        val file = VirtualFileManager.getInstance().findFileByUrl(breakpoint.fileUrl) ?: return null
        val path = try { SafePath.relativize(root, file.toNioPath()) } catch (e: Exception) { null } ?: return null
        return BreakpointLine(path, breakpoint.line)
    }

    /** Someone changed a breakpoint here: tell the host, unless it is the host's own being mirrored. */
    private fun localChange(breakpoint: XBreakpoint<*>, add: Boolean) {
        val line = lineOf(breakpoint) ?: return
        if (add == (line in hostBreakpoints)) return
        service.requestBreakpoint(line.path, line.line, add)
    }

    private fun mirrorBreakpoints(lines: List<BreakpointLine>) {
        hostBreakpoints = lines.toSet()
        retries = 0
        mirrorBreakpoints()
    }

    /**
     * Makes the local breakpoints in shared files equal to the host's. Lines whose file has not
     * arrived yet (or cannot hold a breakpoint while indexing) are tried again a little later.
     */
    private fun mirrorBreakpoints() {
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val local = manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().groupBy { lineOf(it) }
        val stale = local.filterKeys { it != null && it !in hostBreakpoints }.values.flatten()
        if (stale.isNotEmpty()) WriteAction.run<RuntimeException> { stale.forEach(manager::removeBreakpoint) }
        val util = XDebuggerUtil.getInstance()
        var missing = false
        for (line in hostBreakpoints - local.keys.filterNotNull().toSet()) {
            val file = fileOf(line.path)
            if (file != null && util.lineBreakpointTypes.any { it.canPutAt(file, line.line, project) }) {
                util.toggleLineBreakpoint(project, file, line.line, false)
            } else {
                missing = true
            }
        }
        if (missing && retries++ < MAX_RETRIES) {
            val expected = hostBreakpoints
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed && hostBreakpoints === expected) mirrorBreakpoints()
                }, ModalityState.nonModal())
            }, RETRY_MS, TimeUnit.MILLISECONDS)
        }
    }

    // ---- Code style -------------------------------------------------------------------------

    /** Formats like the host: its effective code style becomes this (mirror) project's style. */
    private fun applyCodeStyle(xml: String) {
        try {
            val manager = CodeStyleSettingsManager.getInstance(project)
            val settings = manager.createSettings()
            settings.readExternal(JDOMUtil.load(xml))
            manager.setMainProjectCodeStyle(settings)
            manager.USE_PER_PROJECT_SETTINGS = true
            manager.notifyCodeStyleSettingsChanged()
        } catch (e: Exception) {
            service.logLine(message("codestyle.failed", e.message ?: ""))
        }
    }

    private companion object {
        const val MAX_RETRIES = 15
        const val RETRY_MS = 2_000L
    }
}
