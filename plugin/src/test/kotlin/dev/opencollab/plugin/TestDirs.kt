package dev.opencollab.plugin

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.UsefulTestCase
import java.nio.file.Files
import java.nio.file.Path

/** A fresh temporary folder outside the test project, deleted when the test ends. */
internal fun UsefulTestCase.tempDir(prefix: String): Path =
    Files.createTempDirectory(prefix).also { dir -> Disposer.register(testRootDisposable) { dir.toFile().deleteRecursively() } }
