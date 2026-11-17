package dev.opencollab.plugin

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.opencollab.plugin.session.GuestRunMirror
import dev.opencollab.plugin.session.HostConsoleSharing
import dev.opencollab.plugin.session.SharedRunProfile
import dev.opencollab.plugin.session.SharedTestLocator
import dev.opencollab.plugin.session.SharedTestProperties
import java.nio.file.Files
import java.nio.file.Path

/** Test events as the host sends them build IntelliJ's test tree on a guest, with locations in the mirror. */
class SharedTestTreeTest : HeavyPlatformTestCase() {
    fun testHostTestEventsBecomeTheGuestsTestTree() {
        val root = Files.createTempDirectory("opencollab-tests")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/CalcTest.kt"), "class CalcTest {\n    fun adds() {}\n\n    fun divides() {}\n}\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }

        val properties = SharedTestProperties(project, SharedRunProfile("All tests"), DefaultRunExecutor.getRunExecutorInstance(), SharedTestLocator(root))
        val handler = GuestRunMirror.RemoteProcessHandler()
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole("OpenCollab", handler, properties) as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        handler.startNotify()

        fun send(message: ServiceMessageBuilder) = handler.notifyTextAvailable("$message\n", ProcessOutputTypes.STDOUT)
        fun node(builder: ServiceMessageBuilder, id: String, parent: String? = null) =
            builder.addAttribute("nodeId", id).also { if (parent != null) it.addAttribute("parentNodeId", parent) }
        val location = "${HostConsoleSharing.TEST_PROTOCOL}://src/CalcTest.kt"

        send(ServiceMessageBuilder("enteredTheMatrix"))
        send(node(ServiceMessageBuilder.testSuiteStarted("CalcTest"), "1", "0").addAttribute("locationHint", "$location:0"))
        send(node(ServiceMessageBuilder.testStarted("adds"), "2", "1").addAttribute("locationHint", "$location:1"))
        handler.notifyTextAvailable("plain output from the test\n", ProcessOutputTypes.STDOUT)
        send(node(ServiceMessageBuilder.testFinished("adds"), "2").addAttribute("duration", "5"))
        send(node(ServiceMessageBuilder.testStarted("divides"), "3", "1").addAttribute("locationHint", "$location:3"))
        send(node(ServiceMessageBuilder.testFailed("divides"), "3").addAttribute("message", "boom").addAttribute("details", "at CalcTest.divides"))
        send(node(ServiceMessageBuilder.testFinished("divides"), "3"))
        send(node(ServiceMessageBuilder.testSuiteFinished("CalcTest"), "1"))
        handler.finish(1)

        val tree = console.resultsViewer.testsRootNode
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && (tree.children.singleOrNull()?.children?.size ?: 0) < 2) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        val suite = tree.children.single()
        assertEquals("CalcTest", suite.name)
        assertEquals(listOf("adds", "divides"), suite.children.map { it.name })
        assertTrue(suite.children[0].isPassed)
        assertTrue(suite.children[1].isDefect)
        assertEquals("boom", suite.children[1].errorMessage)

        // Each entry opens the test in the guest's copy of the project, on its line.
        val found = runReadAction { suite.children[1].getLocation(project, GlobalSearchScope.allScope(project)) }
        assertNotNull("the failing test is located in the mirror", found)
        val element = found!!.psiElement
        assertEquals(root.resolve("src/CalcTest.kt").toRealPath(), Path.of(element.containingFile.virtualFile.path).toRealPath())
        val line = runReadAction { PsiDocumentManager.getInstance(project).getDocument(element.containingFile)!!.getLineNumber(element.textOffset) }
        assertEquals("on the line the host reported", 3, line)
    }
}
