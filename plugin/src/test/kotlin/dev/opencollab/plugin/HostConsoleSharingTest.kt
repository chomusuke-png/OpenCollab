package dev.opencollab.plugin

import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.opencollab.core.files.ProjectFiles
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.session.ClientHandler
import dev.opencollab.core.session.ClientState
import dev.opencollab.core.session.CollabClient
import dev.opencollab.core.session.DocumentSource
import dev.opencollab.core.session.HostConfig
import dev.opencollab.core.session.HostSession
import dev.opencollab.core.transport.Connector
import dev.opencollab.core.transport.InMemoryTransport
import dev.opencollab.core.util.SerialExecutor
import dev.opencollab.core.util.SystemScheduler
import dev.opencollab.plugin.session.HostConsoleSharing
import java.util.concurrent.ConcurrentHashMap

/** Everything the host runs reaches guests, including programs that end at once. */
class HostConsoleSharingTest : HeavyPlatformTestCase() {
    fun testAProgramThatEndsAtOnceStillReachesGuestsWithItsOutputAndEnd() {
        val root = tempDir("oc-console")
        val scheduler = SystemScheduler()
        val session = HostSession(HostConfig("demo"), ProjectFiles(root, SharingRules()), DocumentSource { null }, scheduler)
        Disposer.register(testRootDisposable) { session.close(); scheduler.close() }
        val sharing = HostConsoleSharing(project, root, session, shareOutput = true) {}
        Disposer.register(testRootDisposable, sharing)
        sharing.install()

        val output = ConcurrentHashMap<String, StringBuffer>()
        val exits = ConcurrentHashMap<String, Int>()
        val guest = object : ClientHandler {
            override fun onConsoleStarted(started: Message.ConsoleStarted) { output[started.consoleId] = StringBuffer() }
            override fun onConsoleOutput(output: Message.ConsoleOutput) { output(output.consoleId).append(output.text) }
            override fun onConsoleEnded(ended: Message.ConsoleEnded) { exits[ended.consoleId] = ended.exitCode ?: -1 }
            private fun output(id: String) = output.getOrPut(id) { StringBuffer() }
        }
        val (guestEnd, sessionEnd) = InMemoryTransport.pair("guest", "host<-guest")
        session.accept(sessionEnd)
        val client = CollabClient(Connector { guestEnd }, "Guest", session.config.editorToken, guest, SerialExecutor("guest-docs"), scheduler, mirror = null, reconnectPolicy = null)
        Disposer.register(testRootDisposable) { client.close() }
        client.start()
        waitFor("connected") { client.state == ClientState.CONNECTED }

        // As IntelliJ runs a configuration: "starting", the process starts (and here ends at once), then "started".
        val command = if (SystemInfo.isWindows) GeneralCommandLine("cmd.exe", "/c", "echo Hello World") else GeneralCommandLine("/bin/sh", "-c", "echo Hello World")
        val handler = OSProcessHandler(command)
        val profile = object : RunProfile {
            override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
            override fun getName() = "Hello"
            override fun getIcon() = null
        }
        val runner = object : ProgramRunner<RunnerSettings> {
            override fun getRunnerId() = "opencollab-test"
            override fun canRun(executorId: String, profile: RunProfile) = true
            override fun execute(environment: ExecutionEnvironment) = Unit
        }
        val env = ExecutionEnvironmentBuilder(project, DefaultRunExecutor.getRunExecutorInstance()).runProfile(profile).runner(runner).build()
        val events = project.messageBus.syncPublisher(ExecutionManager.EXECUTION_TOPIC)
        val executor = DefaultRunExecutor.EXECUTOR_ID
        events.processStarting(executor, env, handler)
        handler.startNotify()
        assertTrue(handler.waitFor(10_000))
        events.processStarted(executor, env, handler)
        events.processTerminated(executor, env, handler, handler.exitCode ?: 0)

        waitFor("the guest saw the end") { exits.isNotEmpty() }
        assertEquals(1, exits.size)
        assertEquals(0, exits.values.single())
        assertTrue("output reached the guest: <${output.values.single()}>", output.values.single().contains("Hello World"))
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Timed out: $what")
    }
}
