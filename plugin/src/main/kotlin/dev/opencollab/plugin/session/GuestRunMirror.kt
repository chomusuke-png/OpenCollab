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
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import dev.opencollab.core.protocol.ConsoleKind
import dev.opencollab.core.protocol.ConsoleStream
import dev.opencollab.plugin.OpenCollabBundle.message
import dev.opencollab.plugin.session.CollabSessionService.SharedConsole
import java.io.OutputStream
import java.nio.file.Path
import javax.swing.Icon

/**
 * Guest side of shared consoles: the host's runs appear as tabs in IntelliJ's Run tool window
 * (test runs with IntelliJ's test tree, whose entries open the test in the mirror) and its builds
 * in the Build tool window (errors with a location become entries that open the file in the
 * mirror), just like local runs and builds. EDT only.
 */
class GuestRunMirror(
    private val project: Project,
    private val service: CollabSessionService,
    private val root: Path,
) : CollabSessionService.ConsoleListener, Disposable {
    private class Run(val title: String, var handler: RemoteProcessHandler, var descriptor: RunContentDescriptor) {
        var tests = false
        /** Output so far, replayed when the tab turns into a test view. */
        val transcript = ArrayList<Pair<ConsoleStream, String>>()
        var transcriptChars = 0
    }
    private val runs = HashMap<String, Run>()
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
        runs[console.id]?.let { run ->
            run.handler.notifyTextAvailable(text, outputType(stream))
            if (!run.tests && run.transcriptChars < MAX_TRANSCRIPT_CHARS) {
                run.transcript += stream to text
                run.transcriptChars += text.length
            }
        }
        builds[console.id]?.let { build -> buildOutput(build, stream, text) }
    }

    /** Test events (service messages): the first ones turn the run's tab into IntelliJ's test view. */
    override fun tests(console: SharedConsole, text: String) {
        val run = runs[console.id] ?: return
        if (!run.tests && !showAsTests(run)) return
        run.handler.notifyTextAvailable(text, ProcessOutputTypes.STDOUT)
    }

    override fun ended(console: SharedConsole) {
        runs.remove(console.id)?.handler?.finish(console.exitCode)
        builds.remove(console.id)?.let { build ->
            flushLine(build)
            val failed = console.exitCode != null && console.exitCode != 0
            val result = if (failed) FailureResultImpl() else SuccessResultImpl()
            viewManager()?.onEvent(build.id, FinishBuildEvent.builder(build.id, message(if (failed) "console.build.failed" else "console.build.done"), result).build())
        }
    }

    /** Leaving the session: consoles still running are detached (their tabs stay to be read). */
    override fun dispose() {
        runs.values.forEach { it.handler.detach() }
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
        val descriptor = RunContentDescriptor(console, handler, console.component, message("console.on.host", title))
        runs[consoleId] = Run(title, handler, descriptor)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
    }

    /** Replaces the run's plain console with a test console (same tab), replaying what was shown. */
    private fun showAsTests(run: Run): Boolean {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val properties = SharedTestProperties(project, SharedRunProfile(run.title), executor, SharedTestLocator(root))
        val handler = RemoteProcessHandler()
        val console = try {
            SMTestRunnerConnectionUtil.createAndAttachConsole(TEST_FRAMEWORK, handler, properties)
        } catch (e: Exception) {
            run.tests = true // keep the plain console; test events are not shown as text
            return false
        }
        handler.startNotify()
        run.transcript.forEach { (stream, text) -> handler.notifyTextAvailable(text, outputType(stream)) }
        run.transcript.clear()
        val descriptor = RunContentDescriptor(console, handler, console.component, run.descriptor.displayName)
        RunContentManager.getInstance(project).showRunContent(executor, descriptor, run.descriptor)
        run.handler.detach()
        run.handler = handler
        run.descriptor = descriptor
        run.tests = true
        return true
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
    internal class RemoteProcessHandler : ProcessHandler() {
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
        const val TEST_FRAMEWORK = "OpenCollab"
        const val MAX_TRANSCRIPT_CHARS = 2_000_000
    }
}

/** Stands for the host's run configuration in a guest's test view (it is never run here). */
internal class SharedRunProfile(private val title: String) : RunProfile {
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
    override fun getName(): String = title
    override fun getIcon(): Icon = AllIcons.RunConfigurations.Junit
}

/** Test console properties for host tests: test events carry node ids; locations are mirror paths. */
internal class SharedTestProperties(
    project: Project,
    profile: RunProfile,
    executor: Executor,
    private val locator: SMTestLocator,
) : SMTRunnerConsoleProperties(project, profile, "OpenCollab", executor) {
    init {
        isIdBasedTestTree = true
    }

    override fun getTestLocator(): SMTestLocator = locator
}

/** Resolves `opencollab://relative/path:line` (as the host sends test locations) in the mirror. */
internal class SharedTestLocator(private val root: Path) : SMTestLocator {
    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != HostConsoleSharing.TEST_PROTOCOL) return emptyList()
        val separator = path.lastIndexOf(':')
        val relative = if (separator > 0) path.substring(0, separator) else path
        val line = if (separator > 0) path.substring(separator + 1).toIntOrNull() ?: 0 else 0
        val target = try { dev.opencollab.core.files.SafePath.resolveInside(root, relative) } catch (e: SecurityException) { return emptyList() }
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(target) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        val offset = if (document != null && line in 0 until document.lineCount) {
            val start = document.getLineStartOffset(line)
            val text = document.charsSequence
            var at = start
            while (at < document.getLineEndOffset(line) && text[at].isWhitespace()) at++
            at
        } else 0
        val element = psiFile.findElementAt(offset) ?: psiFile
        return listOf(PsiLocation.fromPsiElement(element) ?: return emptyList())
    }
}
