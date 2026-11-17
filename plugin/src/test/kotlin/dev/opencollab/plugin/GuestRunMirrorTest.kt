package dev.opencollab.plugin

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.opencollab.core.protocol.ConsoleKind
import dev.opencollab.core.protocol.ConsoleStream
import dev.opencollab.plugin.session.CollabSessionService
import dev.opencollab.plugin.session.GuestRunMirror

/** A host run shows on a guest as a Run tab with the host's output, and ends when the host's process ends. */
class GuestRunMirrorTest : HeavyPlatformTestCase() {
    fun testHostRunOutputAppearsInTheGuestsRunTab() {
        val mirror = GuestRunMirror(project, CollabSessionService.getInstance(project), tempDir("oc-run"))
        com.intellij.openapi.util.Disposer.register(testRootDisposable, mirror)
        val console = CollabSessionService.SharedConsole("p1", "main", null, ConsoleKind.RUN)
        mirror.started(console)
        mirror.output(console, ConsoleStream.SYSTEM, "java.exe Main\n")
        mirror.output(console, ConsoleStream.STDOUT, "Hello World\n")

        val descriptor = mirror.runDescriptor("p1")!!
        val view = descriptor.executionConsole as ConsoleViewImpl
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !view.editor!!.document.text.contains("Hello World")) {
            view.flushDeferredText()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertTrue("console shows the host's output: <${view.editor!!.document.text}>", view.editor!!.document.text.contains("Hello World"))
        assertFalse(descriptor.processHandler!!.isProcessTerminated)

        val handler = descriptor.processHandler!!
        mirror.ended(console)
        assertTrue("the tab stops running when the host's process ends", handler.isProcessTerminated)
    }
}
