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
import dev.opencollab.core.protocol.RunTarget
import dev.opencollab.core.protocol.RunTargetKind
import dev.opencollab.core.session.HostSession
import dev.opencollab.core.session.RunRequestInfo
import dev.opencollab.plugin.OpenCollabBundle.message
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Host side of shared consoles: forwards the output of everything this IDE runs (run
 * configurations, tests, Gradle tasks) and of builds to the guests, publishes what guests may
 * ask to run, and asks the user before running anything a guest requests.
 */
class HostConsoleSharing(
    private val project: Project,
    root: Path,
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

    // Output is batched per console and flushed every 50 ms, so chatty processes do not send a
    // message per line.
    private val lock = Any()
    private val pending = LinkedHashMap<String, MutableList<Pair<ConsoleStream, StringBuilder>>>()
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
            if (request.participantId in alwaysAllowed) {
                grant(request)
                return@invokeLater
            }
            val text = message("console.request", StringUtil.escapeXmlEntities(request.displayName), StringUtil.escapeXmlEntities(request.target.name))
            val notification = NotificationGroupManager.getInstance().getNotificationGroup("OpenCollab")
                .createNotification(message("console.request.title"), text, NotificationType.WARNING)
            notification.addAction(NotificationAction.createSimpleExpiring(message("console.request.allow")) { grant(request) })
            notification.addAction(NotificationAction.createSimpleExpiring(message("console.request.always")) {
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
        if (!targets.containsKey(request.target.id)) {
            session.answerRun(request.participantId, request.requestId, accepted = false)
            return
        }
        if (!session.answerRun(request.participantId, request.requestId, accepted = true)) {
            log(message("console.request.expired", request.displayName, request.target.name))
            return
        }
        log(message("console.request.running", request.displayName, request.target.name))
        val settings = targets[request.target.id]
        if (settings == null) {
            ProjectTaskManager.getInstance(project).buildAllModules()
        } else {
            launchedFor[settings.uniqueID] = request.participantId
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }
    }

    // ---- Processes ------------------------------------------------------------------------

    private fun shareProcess(env: ExecutionEnvironment, handler: ProcessHandler) {
        val id = "p${ids.incrementAndGet()}"
        val requestedBy = env.runnerAndConfigurationSettings?.uniqueID?.let { launchedFor.remove(it) }
        session.consoleStarted(id, env.runProfile.name, requestedBy)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = append(id, streamOf(outputType), event.text)

            override fun processTerminated(event: ProcessEvent) {
                flush()
                session.consoleEnded(id, event.exitCode)
            }
        })
    }

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

    private fun append(consoleId: String, stream: ConsoleStream, text: String) {
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
            for ((stream, text) in parts) session.consoleOutput(id, stream, projectDir.hide(text.toString()))
        }
    }

    companion object {
        const val BUILD_ID = "build"
        private const val FLUSH_MS = 50L
    }
}
