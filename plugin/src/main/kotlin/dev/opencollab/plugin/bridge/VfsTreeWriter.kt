package dev.opencollab.plugin.bridge

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.opencollab.core.files.NioTreeWriter
import dev.opencollab.core.files.SafePath
import dev.opencollab.core.files.TreeWriter
import java.io.IOException
import java.nio.file.Path

/**
 * Applies remote filesystem changes through the IntelliJ VFS inside write actions, so
 * editors, indexes and documents stay consistent. While [bulk] is set (the initial join
 * snapshot), files are written with java.nio and the caller refreshes the VFS once
 * afterwards, which is the supported pattern for large batches of external changes.
 *
 * Every path is validated with [SafePath.resolveInside] before any VFS lookup.
 */
class VfsTreeWriter(
    private val root: Path,
    private val suppression: RemoteChangeSuppression,
) : TreeWriter {
    @Volatile var bulk = false
    private val nio = NioTreeWriter(root)

    private fun rootDir(): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: throw IOException("Project root not found: $root")

    private fun <T> write(block: () -> T): T = WriteAction.computeAndWait<T, IOException> {
        suppression.whileApplyingRemote(block)
    }

    override fun writeFile(path: String, bytes: ByteArray) {
        val target = SafePath.resolveInside(root, path)
        if (bulk) {
            suppression.suppressPath(path)
            nio.writeFile(path, bytes)
            return
        }
        write {
            val parentRel = SafePath.parentOf(path)
            val parent = if (parentRel == null) rootDir() else VfsUtil.createDirectoryIfMissing(rootDir(), parentRel)
                ?: throw IOException("Cannot create ${target.parent}")
            val name = SafePath.nameOf(path)
            val file = parent.findChild(name) ?: parent.createChildData(REQUESTOR, name)
            if (file.isDirectory) throw IOException("$path is a directory")
            file.setBinaryContent(bytes)
        }
    }

    /**
     * Large received files are moved into place with java.nio (never loaded into memory) and the
     * VFS is refreshed for that file; small ones go through [writeFile] and a write action.
     */
    override fun writeFileFrom(path: String, source: Path) {
        val target = SafePath.resolveInside(root, path)
        if (!bulk && java.nio.file.Files.size(source) <= dev.opencollab.core.protocol.MessageCodec.MAX_INLINE_FILE_BYTES) {
            writeFile(path, java.nio.file.Files.readAllBytes(source))
            return
        }
        suppression.suppressPath(path)
        nio.writeFileFrom(path, source)
        if (!bulk) {
            // Synchronous refresh from this background thread so editors and indexes see the new content.
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.refresh(false, false)
        }
    }

    override fun createDirectory(path: String) {
        SafePath.resolveInside(root, path)
        if (bulk) {
            suppression.suppressPath(path)
            nio.createDirectory(path)
            return
        }
        write { VfsUtil.createDirectoryIfMissing(rootDir(), path) ?: throw IOException("Cannot create $path") }
    }

    override fun delete(path: String) {
        SafePath.resolveInside(root, path)
        if (bulk) {
            suppression.suppressPath(path)
            nio.delete(path)
            return
        }
        write { rootDir().findFileByRelativePath(path)?.delete(REQUESTOR) }
    }

    override fun rename(from: String, to: String) {
        SafePath.resolveInside(root, from)
        SafePath.resolveInside(root, to)
        if (bulk) {
            suppression.suppressPath(from)
            suppression.suppressPath(to)
            nio.rename(from, to)
            return
        }
        write {
            val file = rootDir().findFileByRelativePath(from) ?: throw IOException("$from does not exist")
            val toParentRel = SafePath.parentOf(to)
            val toParent = if (toParentRel == null) rootDir() else VfsUtil.createDirectoryIfMissing(rootDir(), toParentRel)
                ?: throw IOException("Cannot create parent of $to")
            if (file.parent != toParent) file.move(REQUESTOR, toParent)
            val newName = SafePath.nameOf(to)
            if (file.name != newName) file.rename(REQUESTOR, newName)
        }
    }

    companion object {
        val REQUESTOR = Any()
    }
}

/** Lets the bridge ignore VFS/document events caused by applying remote changes. */
interface RemoteChangeSuppression {
    fun <T> whileApplyingRemote(block: () -> T): T
    fun suppressPath(path: String)
}
