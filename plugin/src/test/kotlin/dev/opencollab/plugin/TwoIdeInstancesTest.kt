package dev.opencollab.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.opencollab.core.session.ClientState
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.settings.OpenCollabSettings
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * End-to-end check with TWO independent IntelliJ Platform instances: this JVM hosts with
 * the OpenCollab plugin; a second JVM process (its own IDE application, config and system
 * directories) joins with the plugin over a real TLS connection. Both type concurrently
 * into the same shared document through IntelliJ Document APIs, then the final texts of
 * both IDEs are compared.
 */
open class TwoIdeInstancesTest : HeavyPlatformTestCase() {
    private val tokens = 40

    open fun testSimultaneousEditingAcrossTwoIdeProcesses() {
        if (System.getProperty(E2E_DIR) != null) return // we are the child process
        val exchange = tempDir("oc-e2e")
        val root = Path.of(project.basePath!!)
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Shared.kt"), "// shared\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!.refresh(false, true)
        OpenCollabSettings.getInstance().state.apply {
            enableDirect = true; enableRelay = false; bindAddress = "127.0.0.1"; port = 0; advertisedHosts = ""; displayName = "HostIDE"; approveJoins = false
        }
        val service = CollabSessionService.getInstance(project)
        service.startHosting()
        await("hosting") { service.mode is CollabSessionService.Mode.Hosting }
        Files.writeString(exchange.resolve("invite.txt"), (service.mode as CollabSessionService.Mode.Hosting).editorInvite)

        val child = startGuestIde(exchange)
        try {
            await("guest IDE ready", 120_000) { Files.exists(exchange.resolve("guest-ready")) || !child.isAlive }
            assertTrue("guest IDE process died early, see ${exchange.resolve("guest.log")}", child.isAlive)
            await("two participants") { service.participants.size == 2 }
            val document = document(root, "src/Shared.kt")
            Files.writeString(exchange.resolve("go"), "")
            typeConcurrently(document, "H")
            Files.writeString(exchange.resolve("host-done"), "")
            await("guest done", 60_000) { Files.exists(exchange.resolve("guest-done")) }
            val hostFinal = awaitStable(document)
            Files.writeString(exchange.resolve("host-final.txt"), hostFinal)
            await("guest final", 60_000) { Files.exists(exchange.resolve("guest-final.txt")) }
            val guestFinal = Files.readString(exchange.resolve("guest-final.txt"))
            assertTrue("guest IDE exited with error", child.waitFor(60, TimeUnit.SECONDS) && child.exitValue() == 0)

            assertEquals("both IDE instances converge to the same text", hostFinal, guestFinal)
            val lines = hostFinal.lines()
            for (i in 0 until tokens) {
                assertTrue("host line H$i missing", "H$i" in lines)
                assertTrue("guest line G$i missing", "G$i" in lines)
            }
            assertEquals(hostFinal, (service.mode as CollabSessionService.Mode.Hosting).session.documentText("src/Shared.kt"))
        } finally {
            if (child.isAlive) child.destroyForcibly()
            service.leave()
        }
    }

    /** Runs in the second IDE process (started by the host test). */
    fun runGuestSide() {
        val dir = System.getProperty(E2E_DIR)?.let(Path::of) ?: return
        OpenCollabSettings.getInstance().state.displayName = "GuestIDE"
        val service = CollabSessionService.getInstance(project)
        val invite = Files.readString(dir.resolve("invite.txt"))
        assertNull(service.checkCanJoin(invite))
        service.join(invite)
        await("connected", 60_000) { (service.mode as? CollabSessionService.Mode.Joined)?.client?.state == ClientState.CONNECTED }
        val root = Path.of(project.basePath!!)
        await("snapshot") { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/Shared.kt")) != null }
        val document = document(root, "src/Shared.kt")
        Files.writeString(dir.resolve("guest-ready"), "")
        await("go", 60_000) { Files.exists(dir.resolve("go")) }
        typeConcurrently(document, "G")
        Files.writeString(dir.resolve("guest-done"), "")
        await("host done", 60_000) { Files.exists(dir.resolve("host-done")) }
        Files.writeString(dir.resolve("guest-final.txt"), awaitStable(document))
        await("host final", 60_000) { Files.exists(dir.resolve("host-final.txt")) }
        service.leave()
    }

    private fun document(root: Path, path: String): Document {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(path))!!
        return FileDocumentManager.getInstance().getDocument(file)!!
    }

    /** Inserts "<prefix><i>\n" at random line starts, interleaved with the other IDE. */
    private fun typeConcurrently(document: Document, prefix: String) {
        val rnd = Random(prefix.hashCode())
        for (i in 0 until tokens) {
            WriteCommandAction.runWriteCommandAction(project) {
                val lineCount = document.lineCount
                val line = rnd.nextInt(lineCount)
                document.insertString(document.getLineStartOffset(line), "$prefix$i\n")
            }
            pump(rnd.nextLong(2, 15))
        }
    }

    private fun awaitStable(document: Document): String {
        var last = document.text
        var stableSince = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            pump(50)
            val now = document.text
            if (now != last) { last = now; stableSince = System.currentTimeMillis() }
            if (System.currentTimeMillis() - stableSince > 3_000) return now
        }
        return last
    }

    private fun pump(ms: Long) {
        val until = System.currentTimeMillis() + ms
        do {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(5)
        } while (System.currentTimeMillis() < until)
    }

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for $what")
    }

    /** Starts a second JVM running its own IntelliJ Platform instance with this plugin. */
    private fun startGuestIde(exchange: Path): Process {
        val home = Files.createDirectories(exchange.resolve("guest-ide"))
        val overridden = setOf("idea.system.path", "idea.config.path", "idea.log.path")
        val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments
            .filterNot { arg -> arg.startsWith("-agentlib:jdwp") || overridden.any { arg.startsWith("-D$it=") } }
        val command = mutableListOf(Path.of(System.getProperty("java.home"), "bin", "java").toString())
        command += jvmArgs
        command += listOf(
            "-Didea.system.path=${home.resolve("system")}",
            "-Didea.config.path=${home.resolve("config")}",
            "-Didea.log.path=${home.resolve("log")}",
            "-D$E2E_DIR=$exchange",
        )
        val classpathFile = exchange.resolve("classpath.txt")
        Files.writeString(classpathFile, "-cp \"" + System.getProperty("java.class.path").replace("\\", "/") + "\"")
        command += "@$classpathFile"
        command += listOf("org.junit.runner.JUnitCore", "dev.opencollab.plugin.TwoIdeInstancesGuest")
        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(File(exchange.resolve("guest.log").toString()))
            .start()
    }

    companion object {
        const val E2E_DIR = "opencollab.e2e.dir"
    }
}

/** Entry point for the second IDE process: runs only the guest side. */
class TwoIdeInstancesGuest : TwoIdeInstancesTest() {
    override fun testSimultaneousEditingAcrossTwoIdeProcesses() = runGuestSide()
}
