package dev.opencollab.plugin.session

import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.SuccessResult
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.task.ProjectTaskManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.opencollab.core.protocol.ConsoleKind
import dev.opencollab.core.protocol.ConsoleStream
import dev.opencollab.core.files.ProjectDirMacro
import dev.opencollab.core.files.SafePath
import dev.opencollab.core.protocol.RunTarget
import dev.opencollab.core.protocol.RunTargetKind
import dev.opencollab.core.session.HostSession
import dev.opencollab.core.session.RunRequestInfo
import dev.opencollab.plugin.OpenCollabBundle.message
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Host side of shared consoles: forwards the output of everything this IDE runs (run
 * configurations, tests, Gradle tasks) and of builds to the guests, with test progress as test
 * events so guests get IntelliJ's test tree, publishes what guests may ask to run, and asks the
 * user before running anything a guest requests (a run configuration, a build or a command).
 */
class HostConsoleSharing(
    private val project: Project,
    private val root: Path,
    private val session: HostSession,
    private val shareOutput: Boolean,
    private val log: (String) -> Unit,
) : Disposable {
    private val ids = AtomicLong()
    /** Our run targets: protocol id → run configuration (null for "build the project"). */
    @Volatile private var targets: Map<String, RunnerAndConfigurationSettings?> = emptyMap()
    /** Participants this session's user allowed to run anything without asking again. */
    private val alwaysAllowed = ConcurrentHashMap.newKeySet<String>()
    /** Run configurations launched for a guest, so their console says who asked. */
    private val launchedFor = ConcurrentHashMap<String, String>()
    private val projectDir = ProjectDirMacro(root)
    /** Shared processes → console id, so test events find their console (weak: handlers end). */
    private val consoleOf = Collections.synchronizedMap(WeakHashMap<ProcessHandler, String>())
    /** Test tree node ids per console (test events are sent with ids, like parallel runners do). */
    private val nodeIds = Collections.synchronizedMap(IdentityHashMap<SMTestProxy, String>())
    private val nextNodeId = AtomicLong()

    // Output is batched per console and flushed every 50 ms, so chatty processes do not send a
    // message per line.
    private val lock = Any()
    /** Per console, in order: output (a stream) or test events (null). */
    private val pending = LinkedHashMap<String, MutableList<Pair<ConsoleStream?, StringBuilder>>>()
    private val flusher = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay(::flush, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS)

    fun install() {
        val bus = project.messageBus.connect(this)
        bus.subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
            override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) = publishTargets()
            override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) = publishTargets()
            override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) = publishTargets()
        })
        publishTargets()
        if (!shareOutput) return
        bus.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) = shareProcess(env, handler)
        })
        project.getService(BuildViewManager::class.java)?.addListener(BuildProgressListener { buildId, event -> shareBuildEvent(buildId, event) }, this)
        bus.subscribe(SMTRunnerEventsListener.TEST_STATUS, TestEvents())
    }

    override fun dispose() {
        flusher.cancel(false)
        flush()
    }

    // ---- Run targets ----------------------------------------------------------------------

    private fun publishTargets() {
        val map = LinkedHashMap<String, RunnerAndConfigurationSettings?>()
        val list = mutableListOf(RunTarget(BUILD_ID, message("console.build.project"), RunTargetKind.BUILD))
        map[BUILD_ID] = null
        RunManager.getInstance(project).allSettings.filter { !it.isTemporary }.forEachIndexed { i, settings ->
            val id = "cfg-$i"
            map[id] = settings
            list += RunTarget(id, settings.name.take(200).ifBlank { "?" }, RunTargetKind.CONFIGURATION)
        }
        targets = map
        session.setRunTargets(list)
    }

    // ---- Run requests -----------------------------------------------------------------

    /** Session thread: a guest editor asks to run something here. */
    fun onRunRequest(request: RunRequestInfo) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val command = request.command
            // A shell command can do anything: it is never allowed without asking.
            if (command == null && request.participantId in alwaysAllowed) {
                grant(request)
                return@invokeLater
            }
            val who = StringUtil.escapeXmlEntities(request.displayName)
            val text = if (command != null) {
                message("command.request", who, StringUtil.escapeXmlEntities(command))
            } else {
                message("console.request", who, StringUtil.escapeXmlEntities(request.label))
            }
            val notification = NotificationGroupManager.getInstance().getNotificationGroup("OpenCollab")
                .createNotification(message(if (command != null) "command.request.title" else "console.request.title"), text, NotificationType.WARNING)
            notification.addAction(NotificationAction.createSimpleExpiring(message("console.request.allow")) { grant(request) })
            if (command == null) notification.addAction(NotificationAction.createSimpleExpiring(message("console.request.always")) {
                alwaysAllowed += request.participantId
                grant(request)
            })
            notification.addAction(NotificationAction.createSimpleExpiring(message("console.request.deny")) {
                session.answerRun(request.participantId, request.requestId, accepted = false)
            })
            notification.notify(project)
        }, ModalityState.nonModal())
    }

    /** EDT. Runs the target only if the request is still open (not expired, requester still here). */
    private fun grant(request: RunRequestInfo) {
        val command = request.command
        val target = request.target
        if (command == null && (target == null || !targets.containsKey(target.id))) {
            session.answerRun(request.participantId, request.requestId, accepted = false)
            return
        }
        if (!session.answerRun(request.participantId, request.requestId, accepted = true)) {
            log(message("console.request.expired", request.displayName, request.label))
            return
        }
        log(message("console.request.running", request.displayName, request.label))
        if (command != null) {
            runCommand(command, request.participantId)
            return
        }
        val settings = targets[target!!.id]
        if (settings == null) {
            ProjectTaskManager.getInstance(project).buildAllModules()
        } else {
            launchedFor[settings.uniqueID] = request.participantId
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }
    }

    // ---- Commands requested by guests ---------------------------------------------------------

    /**
     * EDT. Runs an approved command in a shell in the project folder, in a tab of this IDE's Run
     * window (where the host can stop it), and shares its output like any other run.
     */
    private fun runCommand(command: String, requestedBy: String) {
        val shell = if (SystemInfo.isWindows) listOf("cmd.exe", "/c", command) else listOf("/bin/sh", "-c", command)
        val handler = try {
            KillableColoredProcessHandler(GeneralCommandLine(shell).withWorkingDirectory(root).withCharset(Charsets.UTF_8))
        } catch (e: Exception) {
            log(message("command.failed", command, e.message ?: ""))
            return
        }
        val title = message("command.title", command.take(60))
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        val descriptor = RunContentDescriptor(console, handler, console.component, title)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        if (shareOutput) shareHandler(title, handler, requestedBy)
        handler.startNotify()
    }

    // ---- Processes ------------------------------------------------------------------------

    private fun shareProcess(env: ExecutionEnvironment, handler: ProcessHandler) {
        val requestedBy = env.runnerAndConfigurationSettings?.uniqueID?.let { launchedFor.remove(it) }
        shareHandler(env.runProfile.name, handler, requestedBy)
    }

    private fun shareHandler(title: String, handler: ProcessHandler, requestedBy: String?) {
        val id = "p${ids.incrementAndGet()}"
        consoleOf[handler] = id
        session.consoleStarted(id, title, requestedBy)
        handler.addProcessListener(object : ProcessListener {
            // Test runners report through service messages; guests get them as test events instead.
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = append(id, streamOf(outputType), withoutServiceMessages(event.text))

            override fun processTerminated(event: ProcessEvent) {
                flush()
                session.consoleEnded(id, event.exitCode)
            }
        })
    }

    private fun withoutServiceMessages(text: String): String {
        if (!text.contains(SERVICE_MESSAGE)) return text
        return text.split('\n').filterNot { it.trimStart().startsWith(SERVICE_MESSAGE) }.joinToString("\n")
    }

    // ---- Tests: the host's test tree, as test events for the guests' own test tree ----------

    private inner class TestEvents : SMTRunnerEventsAdapter() {
        override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
            consoleFor(testsRoot)?.let { tests(it, ServiceMessageBuilder("enteredTheMatrix").toString()) }
        }

        override fun onTestsCountInSuite(count: Int) = Unit

        override fun onSuiteStarted(suite: SMTestProxy) = started(suite, ServiceMessageBuilder.testSuiteStarted(suite.name))
        override fun onTestStarted(test: SMTestProxy) = started(test, ServiceMessageBuilder.testStarted(test.name))

        override fun onTestFailed(test: SMTestProxy) = event(test) { id ->
            ServiceMessageBuilder.testFailed(test.name).addAttribute("nodeId", id)
                .addAttribute("message", test.errorMessage.orEmpty())
                .addAttribute("details", test.stacktrace.orEmpty())
        }

        override fun onTestIgnored(test: SMTestProxy) = event(test) { id ->
            ServiceMessageBuilder.testIgnored(test.name).addAttribute("nodeId", id).addAttribute("message", test.errorMessage.orEmpty())
        }

        override fun onTestFinished(test: SMTestProxy) {
            event(test) { id -> ServiceMessageBuilder.testFinished(test.name).addAttribute("nodeId", id).addAttribute("duration", (test.duration ?: 0L).toString()) }
            nodeIds.remove(test)
        }

        override fun onSuiteFinished(suite: SMTestProxy) {
            event(suite) { id -> ServiceMessageBuilder.testSuiteFinished(suite.name).addAttribute("nodeId", id) }
            nodeIds.remove(suite)
        }

        private fun started(proxy: SMTestProxy, builder: ServiceMessageBuilder) {
            val console = consoleFor(proxy.root ?: return) ?: return
            val id = "${nextNodeId.incrementAndGet()}"
            nodeIds[proxy] = id
            val parent = proxy.parent?.let { nodeIds[it] } ?: "0"
            builder.addAttribute("nodeId", id).addAttribute("parentNodeId", parent)
            locationHint(proxy)?.let { builder.addAttribute("locationHint", it) }
            tests(console, builder.toString())
        }

        private fun event(proxy: SMTestProxy, message: (String) -> ServiceMessageBuilder) {
            val console = consoleFor(proxy.root ?: return) ?: return
            val id = nodeIds[proxy] ?: return
            tests(console, message(id).toString())
        }

        private fun consoleFor(root: SMTestProxy.SMRootTestProxy): String? = root.handler?.let { consoleOf[it] }
    }

    /**
     * Where a test is, as `opencollab://path:line` in the shared project (guests resolve it in their
     * copy); null outside the project or while indexing.
     */
    private fun locationHint(proxy: SMTestProxy): String? {
        if (proxy.locationUrl == null || DumbService.isDumb(project)) return null
        return try {
            ApplicationManager.getApplication().runReadAction(Computable<String?> {
                val element = proxy.getLocation(project, GlobalSearchScope.allScope(project))?.psiElement ?: return@Computable null
                val file = element.containingFile ?: return@Computable null
                val path = file.virtualFile?.let { SafePath.relativize(root, it.toNioPath()) } ?: return@Computable null
                val line = PsiDocumentManager.getInstance(project).getDocument(file)?.getLineNumber(element.textOffset) ?: 0
                "$TEST_PROTOCOL://$path:$line"
            })
        } catch (e: Exception) {
            null
        }
    }

    private fun tests(consoleId: String, serviceMessage: String) = append(consoleId, null, serviceMessage + "\n")

    private fun streamOf(type: Key<*>) = when (type) {
        ProcessOutputTypes.STDERR -> ConsoleStream.STDERR
        ProcessOutputTypes.SYSTEM -> ConsoleStream.SYSTEM
        else -> ConsoleStream.STDOUT
    }

    // ---- Builds (Build tool window: compiler and Gradle build output, errors with locations)

    private val builds = ConcurrentHashMap<Any, String>()

    private fun shareBuildEvent(buildId: Any, event: BuildEvent) {
        when (event) {
            is StartBuildEvent -> {
                val id = "b${ids.incrementAndGet()}"
                builds[buildId] = id
                session.consoleStarted(id, event.buildDescriptor.title.ifBlank { event.message }, kind = ConsoleKind.BUILD)
            }
            is OutputBuildEvent -> builds[buildId]?.let { append(it, streamOf(event.outputType), event.message) }
            is FileMessageEvent -> builds[buildId]?.let { id ->
                val pos = event.filePosition
                val where = "${pos.path?.toString() ?: "?"}:${pos.startLine + 1}:${pos.startColumn + 1}"
                append(id, streamOf(event.kind), "${event.kind.name.lowercase()}: $where: ${event.message}\n")
            }
            is MessageEvent -> builds[buildId]?.let { append(it, streamOf(event.kind), "${event.kind.name.lowercase()}: ${event.message}\n") }
            is FinishBuildEvent -> builds.remove(buildId)?.let { id ->
                flush()
                session.consoleEnded(id, when (event.result) {
                    is SuccessResult -> 0
                    is FailureResult -> 1
                    else -> null
                })
            }
        }
    }

    private fun streamOf(kind: MessageEvent.Kind) =
        if (kind == MessageEvent.Kind.ERROR) ConsoleStream.STDERR else ConsoleStream.STDOUT

    // ---- Batching -------------------------------------------------------------------------

    private fun append(consoleId: String, stream: ConsoleStream?, text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            val parts = pending.getOrPut(consoleId) { mutableListOf() }
            val last = parts.lastOrNull()
            if (last != null && last.first == stream) last.second.append(text) else parts += stream to StringBuilder(text)
        }
    }

    private fun flush() {
        val batch = synchronized(lock) {
            if (pending.isEmpty()) return
            LinkedHashMap(pending).also { pending.clear() }
        }
        for ((id, parts) in batch) {
            for ((stream, text) in parts) {
                val shared = projectDir.hide(text.toString())
                if (stream == null) session.consoleTests(id, shared) else session.consoleOutput(id, stream, shared)
            }
        }
    }

    companion object {
        const val BUILD_ID = "build"
        /** Locations in shared test events: `opencollab://relative/path:line`. */
        const val TEST_PROTOCOL = "opencollab"
        private const val SERVICE_MESSAGE = "##teamcity["
        private const val FLUSH_MS = 50L
    }
}
