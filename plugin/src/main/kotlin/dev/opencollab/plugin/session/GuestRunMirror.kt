package dev.opencollab.plugin.session

import com.intellij.build.BuildDescriptor
import com.intellij.build.BuildViewManager
import com.intellij.build.FilePosition
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import dev.opencollab.core.protocol.ConsoleKind
import dev.opencollab.core.protocol.ConsoleStream
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.session.CollabSessionService.SharedConsole
import java.io.OutputStream
import java.nio.file.Path

/**
 * Guest side of shared consoles: the host's runs appear as tabs in IntelliJ's Run tool window
 * and its builds in the Build tool window (errors with a location become entries that open the
 * file in the mirror), just like local runs and builds. EDT only.
 */
class GuestRunMirror(
    private val project: Project,
    private val service: CollabSessionService,
) : CollabSessionService.ConsoleListener, Disposable {
    private val runs = HashMap<String, RemoteProcessHandler>()
    private class Build(val id: Any) {
        /** Output not ending in a line break yet: error lines are recognised once complete. */
        val partialLine = StringBuilder()
    }
    private val builds = HashMap<String, Build>()

    override fun started(console: SharedConsole) {
        val who = console.requestedBy?.let { id -> service.participants.firstOrNull { it.id == id }?.displayName }
        val title = if (who != null) message("console.title.requested", console.title, who) else console.title
        when (console.kind) {
            ConsoleKind.RUN -> startRun(console.id, title)
            ConsoleKind.BUILD -> startBuild(console.id, title)
        }
    }

    override fun output(console: SharedConsole, stream: ConsoleStream, text: String) {
        runs[console.id]?.notifyTextAvailable(text, outputType(stream))
        builds[console.id]?.let { build -> buildOutput(build, stream, text) }
    }

    override fun ended(console: SharedConsole) {
        runs.remove(console.id)?.finish(console.exitCode)
        builds.remove(console.id)?.let { build ->
            flushLine(build)
            val failed = console.exitCode != null && console.exitCode != 0
            val result = if (failed) FailureResultImpl() else SuccessResultImpl()
            viewManager()?.onEvent(build.id, FinishBuildEvent.builder(build.id, message(if (failed) "console.build.failed" else "console.build.done"), result).build())
        }
    }

    /** Leaving the session: consoles still running are detached (their tabs stay to be read). */
    override fun dispose() {
        runs.values.forEach { it.detach() }
        runs.clear()
        builds.keys.toList().forEach { id -> builds.remove(id)?.let { viewManager()?.onEvent(it.id, FinishBuildEvent.builder(it.id, message("console.build.detached"), SuccessResultImpl()).build()) } }
    }

    // ---- Runs: a tab in the Run tool window -------------------------------------------------

    private fun startRun(consoleId: String, title: String) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply {
            // Shared output names mirror paths ("…/src/Main.kt:12"): make them clickable.
            addFilter(RegexpFilter(project, "${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}"))
        }.console
        val handler = RemoteProcessHandler()
        console.attachToProcess(handler)
        handler.startNotify()
        runs[consoleId] = handler
        val descriptor = RunContentDescriptor(console, handler, console.component, message("console.on.host", title))
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
    }

    private fun outputType(stream: ConsoleStream) = when (stream) {
        ConsoleStream.STDOUT -> ProcessOutputTypes.STDOUT
        ConsoleStream.STDERR -> ProcessOutputTypes.STDERR
        ConsoleStream.SYSTEM -> ProcessOutputTypes.SYSTEM
    }

    /**
     * Stands for a process running on the host. Stop only detaches this view: the host decides
     * about its own processes.
     */
    private class RemoteProcessHandler : ProcessHandler() {
        fun finish(exitCode: Int?) {
            if (!isProcessTerminated) notifyProcessTerminated(exitCode ?: 0)
        }

        fun detach() {
            if (!isProcessTerminated && !isProcessTerminating) notifyProcessDetached()
        }

        override fun destroyProcessImpl() = notifyProcessDetached()
        override fun detachProcessImpl() = notifyProcessDetached()
        override fun detachIsDefault() = true
        override fun getProcessInput(): OutputStream? = null
    }

    // ---- Builds: the Build tool window --------------------------------------------------

    private fun viewManager(): BuildViewManager? = project.getService(BuildViewManager::class.java)

    private fun startBuild(consoleId: String, title: String) {
        val build = Build(Any())
        builds[consoleId] = build
        val descriptor = object : BuildDescriptor {
            private val startTime = System.currentTimeMillis()
            override fun getId(): Any = build.id
            override fun getTitle(): String = message("console.on.host", title)
            override fun getWorkingDir(): String = project.basePath ?: ""
            override fun getStartTime(): Long = startTime
        }
        viewManager()?.onEvent(build.id, StartBuildEvent.builder(message("console.build.running"), descriptor).build())
    }

    private fun buildOutput(build: Build, stream: ConsoleStream, text: String) {
        val type = (if (stream == ConsoleStream.STDERR) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT) as ProcessOutputType
        viewManager()?.onEvent(build.id, OutputBuildEvent.builder(text).withParentId(build.id).withOutputType(type).build())
        build.partialLine.append(text)
        while (true) {
            val end = build.partialLine.indexOf("\n")
            if (end < 0) break
            val line = build.partialLine.substring(0, end).trimEnd('\r')
            build.partialLine.delete(0, end + 1)
            fileMessage(build, line)
        }
    }

    private fun flushLine(build: Build) {
        if (build.partialLine.isNotEmpty()) fileMessage(build, build.partialLine.toString())
        build.partialLine.setLength(0)
    }

    /** "error: /path/File.kt:12:5: message" (as the host sends compiler messages) → an entry in the build tree. */
    private fun fileMessage(build: Build, line: String) {
        val match = FILE_MESSAGE.matchEntire(line) ?: return
        val (kindName, path, lineNo, column, text) = match.destructured
        val kind = runCatching { MessageEvent.Kind.valueOf(kindName.uppercase()) }.getOrNull() ?: return
        val file = runCatching { Path.of(path) }.getOrNull() ?: return
        val position = FilePosition(file, lineNo.toInt() - 1, column.toInt() - 1)
        viewManager()?.onEvent(build.id, MessageEvent.builder(text, kind).withFilePosition(position).withParentId(build.id).withGroup(GROUP).build())
    }

    private companion object {
        val FILE_MESSAGE = Regex("""^(error|warning|info): (.+?):(\d+):(\d+): (.*)$""")
        const val GROUP = "OpenCollab"
    }
}
