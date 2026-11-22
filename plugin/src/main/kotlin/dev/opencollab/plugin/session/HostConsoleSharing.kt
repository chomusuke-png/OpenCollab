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
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JPanel
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
            // Before the process starts: a short program (e.g. Hello World) can print and end before
            // processStarted, and its output and end would be lost.
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) = shareProcess(env, handler)
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                if (!consoleOf.containsKey(handler)) shareProcess(env, handler) // not announced as starting
            }
            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                consoleOf[handler]?.let { endConsole(it, exitCode) }
            }
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
        // Saved configurations first, then the temporary ones the IDE makes when you run a file or
        // a test from the editor (it keeps the last few and replaces older ones).
        val (temporary, saved) = RunManager.getInstance(project).allSettings.partition { it.isTemporary }
        (saved + temporary).forEach { settings ->
            // Stable per configuration: a request keeps meaning the same configuration even if
            // others are added or removed before the host answers.
            val id = targetId(settings)
            if (id in map) return@forEach
            map[id] = settings
            val name = settings.name.take(180).ifBlank { "?" }
            list += RunTarget(id, if (settings.isTemporary) message("console.target.temporary", name) else name, RunTargetKind.CONFIGURATION)
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
            notification.addAction(NotificationAction.createSimpleExpiring(message("console.request.allow")) {
                // A notification can cut a long command short: show all of it before running it.
                if (command == null || confirmCommand(request.displayName, command)) grant(request) else deny(request)
            })
            if (command == null) notification.addAction(NotificationAction.createSimpleExpiring(message("console.request.always")) {
                alwaysAllowed += request.participantId
                grant(request)
            })
            notification.addAction(NotificationAction.createSimpleExpiring(message("console.request.deny")) { deny(request) })
            notification.notify(project)
        }, ModalityState.nonModal())
    }

    private fun deny(request: RunRequestInfo) {
        session.answerRun(request.participantId, request.requestId, accepted = false)
    }

    /** EDT. The full command, in a dialog where nothing can hide; true if the user runs it. */
    private fun confirmCommand(who: String, command: String): Boolean {
        val area = JBTextArea(command).apply {
            isEditable = false
            lineWrap = true
            rows = 6
            columns = 60
        }
        val builder = DialogBuilder(project)
            .title(message("command.request.title"))
            .centerPanel(JPanel(BorderLayout(0, 8)).apply {
                add(JBLabel(message("command.confirm", who, command.length)), BorderLayout.NORTH)
                add(JBScrollPane(area), BorderLayout.CENTER)
            })
        builder.addOkAction().setText(message("console.request.allow"))
        builder.addCancelAction()
        return builder.showAndGet()
    }

    /**
     * EDT. Runs the target only if the session confirms the request is still open (not expired,
     * requester still here and still an editor). The answer comes back asynchronously: the
     * session thread may itself be waiting for the EDT.
     */
    private fun grant(request: RunRequestInfo) {
        val command = request.command
        val target = request.target
        val settings = target?.let { targets[it.id] }
        // The configuration must still exist and be the one the user was shown.
        if (command == null && (target == null || !targets.containsKey(target.id) || (settings != null && settings.name.take(200) != target.name))) {
            deny(request)
            return
        }
        session.answerRun(request.participantId, request.requestId, accepted = true).thenAccept { granted ->
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                if (!granted) {
                    log(message("console.request.expired", request.displayName, request.label))
                    return@invokeLater
                }
                log(message("console.request.running", request.displayName, request.label))
                when {
                    command != null -> runCommand(command, request.participantId)
                    settings == null -> ProjectTaskManager.getInstance(project).buildAllModules()
                    else -> launch(settings, request.participantId)
                }
            }, ModalityState.nonModal())
        }
    }

    /** Runs [settings] with the requester attached to this one launch (not to the configuration). */
    private fun launch(settings: RunnerAndConfigurationSettings, requestedBy: String) {
        val environment = ExecutionEnvironmentBuilder.createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings)?.build()
        if (environment == null) {
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
            return
        }
        environment.putUserData(REQUESTED_BY, requestedBy)
        ProgramRunnerUtil.executeConfiguration(environment, false, true)
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
        val requestedBy = env.getUserData(REQUESTED_BY)
        val title = runCatching { env.runProfile.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"
        shareHandler(title, handler, requestedBy)
    }

    private fun shareHandler(title: String, handler: ProcessHandler, requestedBy: String?) {
        if (consoleOf.containsKey(handler)) return
        val id = "p${ids.incrementAndGet()}"
        consoleOf[handler] = id
        session.consoleStarted(id, title, requestedBy)
        handler.addProcessListener(object : ProcessListener {
            // Test runners report through service messages; guests get them as test events instead.
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = append(id, streamOf(outputType), withoutServiceMessages(event.text))

            override fun processTerminated(event: ProcessEvent) = endConsole(id, event.exitCode)
        })
        // Already over (shared late): end it now, or the guests' tab would keep running.
        if (handler.isProcessTerminated) endConsole(id, handler.exitCode ?: 0)
    }

    /** Consoles whose end was sent (a process end can be reported twice). */
    private val endedConsoles = ConcurrentHashMap.newKeySet<String>()

    private fun endConsole(id: String, exitCode: Int?) {
        if (!endedConsoles.add(id)) return
        flush()
        session.consoleEnded(id, exitCode)
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
        /** Who asked for a run, on the ExecutionEnvironment of that one launch. */
        private val REQUESTED_BY = Key.create<String>("opencollab.requestedBy")

        /** A protocol id for a run configuration that stays the same while it exists. */
        internal fun targetId(settings: RunnerAndConfigurationSettings): String =
            "cfg-" + java.security.MessageDigest.getInstance("SHA-256").digest(settings.uniqueID.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        const val BUILD_ID = "build"
        /** Locations in shared test events: `opencollab://relative/path:line`. */
        const val TEST_PROTOCOL = "opencollab"
        private const val SERVICE_MESSAGE = "##teamcity["
        private const val FLUSH_MS = 50L
    }
}
