package dev.opencollab.core.files

import dev.opencollab.core.protocol.FileEntry
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.MessageCodec
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object Hashes {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

class FileAccessDenied(message: String) : SecurityException(message)

/** Low-level tree operations with wire paths. Implementations must stay inside their root. */
interface TreeWriter {
    fun writeFile(path: String, bytes: ByteArray)
    fun createDirectory(path: String)
    fun delete(path: String)
    fun rename(from: String, to: String)
}

/** java.nio implementation of [TreeWriter]; every path goes through [SafePath.resolveInside]. */
class NioTreeWriter(private val root: Path) : TreeWriter {
    override fun writeFile(path: String, bytes: ByteArray) {
        val target = SafePath.resolveInside(root, path)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".oc-", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun createDirectory(path: String) {
        Files.createDirectories(SafePath.resolveInside(root, path))
    }

    override fun delete(path: String) {
        val target = SafePath.resolveInside(root, path)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir); return FileVisitResult.CONTINUE
                }
            })
        } else {
            Files.delete(target)
        }
    }

    override fun rename(from: String, to: String) {
        val src = SafePath.resolveInside(root, from)
        val dst = SafePath.resolveInside(root, to)
        Files.createDirectories(dst.parent)
        Files.move(src, dst)
    }
}

/**
 * The host's shared view of its project directory. Lists and reads only shared files,
 * and validates every mutation requested by a guest against [rules] before performing it
 * with [writer] (NIO by default; the IntelliJ plugin supplies a VFS-backed writer).
 */
open class ProjectFiles(
    val root: Path,
    @Volatile var rules: SharingRules,
    private val writer: TreeWriter = NioTreeWriter(root),
) {
    private data class CachedHash(val size: Long, val modified: Long, val sha256: String)
    private val hashCache = ConcurrentHashMap<String, CachedHash>()

    /** Walks the project and returns every shared, regular, non-symlink file within size limits. */
    fun listShared(): List<FileEntry> {
        val result = ArrayList<FileEntry>()
        val absRoot = root.toAbsolutePath().normalize()
        Files.walkFileTree(absRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == absRoot) return FileVisitResult.CONTINUE
                if (attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                val rel = SafePath.relativize(absRoot, dir) ?: return FileVisitResult.SKIP_SUBTREE
                return if (rules.shouldDescend(rel)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || attrs.isSymbolicLink) return FileVisitResult.CONTINUE
                val rel = SafePath.relativize(absRoot, file) ?: return FileVisitResult.CONTINUE
                if (!rules.isShared(rel) || !rules.isSizeAllowed(attrs.size())) return FileVisitResult.CONTINUE
                val hash = try { hashOf(rel, file, attrs) } catch (e: IOException) { return FileVisitResult.CONTINUE }
                result += FileEntry(rel, attrs.size(), hash)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        result.sortBy { it.path }
        return result
    }

    private fun hashOf(rel: String, file: Path, attrs: BasicFileAttributes): String {
        val modified = attrs.lastModifiedTime().toMillis()
        hashCache[rel]?.let { if (it.size == attrs.size() && it.modified == modified) return it.sha256 }
        val sha = Hashes.sha256(file)
        hashCache[rel] = CachedHash(attrs.size(), modified, sha)
        return sha
    }

    /** Reads a shared file; null if it does not exist, is excluded, too large, or unsafe. */
    fun read(path: String): ByteArray? {
        if (!rules.isShared(path)) return null
        val file = try { SafePath.resolveInside(root, path) } catch (e: SecurityException) { return null }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        if (!rules.isSizeAllowed(Files.size(file))) return null
        return Files.readAllBytes(file)
    }

    fun exists(path: String): Boolean = try {
        Files.exists(SafePath.resolveInside(root, path), LinkOption.NOFOLLOW_LINKS)
    } catch (e: SecurityException) { false }

    fun isDirectory(path: String): Boolean = try {
        Files.isDirectory(SafePath.resolveInside(root, path), LinkOption.NOFOLLOW_LINKS)
    } catch (e: SecurityException) { false }

    /**
     * Validates a guest-requested mutation. Throws [FileAccessDenied] or
     * [UnsafePathException] when it must not be applied.
     */
    fun validateGuestMutation(m: FileMutation) {
        val paths = listOfNotNull(m.path, m.newPath)
        for (p in paths) {
            SafePath.resolveInside(root, p)
            if (!rules.isShared(p)) throw FileAccessDenied("Path is not shared: $p")
            if (rules.isGuestReadOnly(p)) throw FileAccessDenied("Path is read-only for guests: $p")
        }
        when (m.kind) {
            FileOpKind.CREATE_FILE, FileOpKind.WRITE -> {
                val bytes = MessageCodec.decodeBase64(m.contentBase64 ?: "")
                if (!rules.isSizeAllowed(bytes.size.toLong())) throw FileAccessDenied("File too large: ${m.path}")
                if (isDirectory(m.path)) throw FileAccessDenied("A directory exists at ${m.path}")
                if (m.kind == FileOpKind.CREATE_FILE && exists(m.path)) throw FileAccessDenied("Already exists: ${m.path}")
            }
            FileOpKind.CREATE_DIRECTORY -> if (exists(m.path) && !isDirectory(m.path)) throw FileAccessDenied("A file exists at ${m.path}")
            FileOpKind.DELETE -> {
                if (!exists(m.path)) throw FileAccessDenied("Does not exist: ${m.path}")
                requireFullyShared(m.path)
            }
            FileOpKind.RENAME -> {
                val dest = m.newPath!!
                if (!exists(m.path)) throw FileAccessDenied("Does not exist: ${m.path}")
                if (exists(dest)) throw FileAccessDenied("Destination exists: $dest")
                if (dest.startsWith(m.path + "/")) throw FileAccessDenied("Cannot move a directory into itself")
                requireFullyShared(m.path)
            }
        }
    }

    /** A directory may only be deleted/moved by a guest if it contains nothing the guest cannot see. */
    private fun requireFullyShared(path: String) {
        val target = SafePath.resolveInside(root, path)
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(target).use { stream ->
            stream.forEach { p ->
                if (p == target) return@forEach
                if (Files.isSymbolicLink(p)) throw FileAccessDenied("Directory contains symbolic links: $path")
                val rel = SafePath.relativize(root, p) ?: throw FileAccessDenied("Unsafe entry in $path")
                if (!rules.isShared(rel) || rules.isGuestReadOnly(rel)) {
                    throw FileAccessDenied("Directory $path contains files that are not shared with guests")
                }
            }
        }
    }

    /** Performs an already-validated mutation. */
    open fun perform(m: FileMutation) {
        when (m.kind) {
            FileOpKind.CREATE_FILE, FileOpKind.WRITE -> writer.writeFile(m.path, MessageCodec.decodeBase64(m.contentBase64!!))
            FileOpKind.CREATE_DIRECTORY -> writer.createDirectory(m.path)
            FileOpKind.DELETE -> writer.delete(m.path)
            FileOpKind.RENAME -> writer.rename(m.path, m.newPath!!)
        }
        hashCache.keys.removeIf { it == m.path || it.startsWith(m.path + "/") }
    }
}
