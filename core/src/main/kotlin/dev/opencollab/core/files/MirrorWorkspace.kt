package dev.opencollab.core.files

import dev.opencollab.core.protocol.FileEntry
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.MessageCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * A guest's local copy of the host's shared tree.
 *
 * The mirror remembers which files it received from the host (in `.opencollab/mirror.json`)
 * so that a later resync only deletes files that came from the host, never files the
 * guest created locally (e.g. its own `.idea/workspace.xml`). A non-empty directory that is
 * not already a mirror is never adopted, so joining cannot overwrite unrelated data.
 */
class MirrorWorkspace(val root: Path, private val writer: TreeWriter = NioTreeWriter(root)) {
    @Serializable
    data class State(val sessionId: String, val projectName: String, val files: Map<String, String>, val fileEventSeq: Long)

    private val stateFile: Path = root.resolve(".opencollab").resolve("mirror.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val tracked = HashMap<String, String>()
    /** Files being received, by request and path: two requests may send the same file at once. */
    private val assembling = HashMap<Pair<String, String>, Partial>()
    private val tmpDir: Path = root.resolve(".opencollab").resolve("tmp")

    var sessionId: String = ""
        private set
    var projectName: String = ""
        private set
    var fileEventSeq: Long = 0
        private set

    init {
        loadState()?.let {
            tracked.putAll(it.files)
            sessionId = it.sessionId
            projectName = it.projectName
            fileEventSeq = it.fileEventSeq
        }
    }

    fun loadState(): State? = try {
        if (Files.isRegularFile(stateFile)) json.decodeFromString(State.serializer(), Files.readString(stateFile)) else null
    } catch (e: Exception) {
        null
    }

    fun isMirror(): Boolean = Files.isRegularFile(stateFile)

    /** True if the directory is empty (ignoring IDE metadata) or already an OpenCollab mirror. */
    fun canAdopt(): Boolean {
        if (isMirror()) return true
        if (!Files.exists(root)) return true
        Files.list(root).use { stream ->
            return stream.allMatch { p ->
                val name = p.fileName.toString()
                name == ".idea" || name == ".opencollab" || name.endsWith(".iml") || name == ".DS_Store"
            }
        }
    }

    fun bind(sessionId: String, projectName: String) {
        this.sessionId = sessionId
        this.projectName = projectName
        saveState()
    }

    fun trackedPaths(): Set<String> = tracked.keys.toSet()

    fun isTracked(path: String): Boolean = tracked.containsKey(path)

    /** Paths whose local content differs from the manifest and must be fetched. */
    fun pathsToFetch(manifest: List<FileEntry>): List<String> = manifest.filter { entry ->
        val local = localHash(entry.path)
        local == null || local != entry.sha256
    }.map { it.path }

    /** Host-originated files that disappeared from the host's tree. */
    fun stalePaths(manifest: List<FileEntry>): List<String> {
        val present = manifest.mapTo(HashSet()) { it.path }
        return tracked.keys.filter { it !in present }.sorted()
    }

    fun localHash(path: String): String? = try {
        val file = SafePath.resolveInside(root, path)
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Hashes.sha256(file) else null
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    fun readLocal(path: String): ByteArray? = try {
        val file = SafePath.resolveInside(root, path)
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.readAllBytes(file) else null
    } catch (e: Exception) {
        null
    }

    /** A file being received: streamed to a temporary file, hashed incrementally. */
    private class Partial(val file: Path, val out: java.io.OutputStream, val digest: MessageDigest) {
        var size = 0L
    }

    /** A fully received, verified file waiting to be moved into place. */
    class Received(val path: String, val file: Path, val sha256: String, val size: Long)

    /**
     * Accepts a file chunk. Data is streamed to a temporary file under `.opencollab/tmp`, so even
     * very large files never sit in memory. Returns the verified file once the last chunk
     * arrived and its SHA-256 matched, or null while incomplete.
     */
    fun acceptChunk(requestId: String, path: String, offset: Long, data: ByteArray, last: Boolean, sha256: String, maxBytes: Long): Received? {
        val key = requestId to path
        val partial = assembling.getOrPut(key) {
            Files.createDirectories(tmpDir)
            val file = Files.createTempFile(tmpDir, "recv-", ".part")
            Partial(file, Files.newOutputStream(file), MessageDigest.getInstance("SHA-256"))
        }
        try {
            if (offset != partial.size) throw IOException("Out-of-order chunk for $path")
            if (partial.size + data.size > maxBytes) throw IOException("File exceeds size limit: $path")
            partial.out.write(data)
            partial.digest.update(data)
            partial.size += data.size
            if (!last) return null
            partial.out.close()
            assembling.remove(key)
            val actual = partial.digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha256) throw IOException("Checksum mismatch for $path")
            return Received(path, partial.file, actual, partial.size)
        } catch (e: IOException) {
            discard(key, partial)
            throw e
        }
    }

    private fun discard(key: Pair<String, String>, partial: Partial) {
        assembling.remove(key)
        runCatching { partial.out.close() }
        runCatching { Files.deleteIfExists(partial.file) }
    }

    /** Moves a verified file into the mirror. */
    fun writeReceived(received: Received) {
        try {
            writer.writeFileFrom(received.path, received.file)
            tracked[received.path] = received.sha256
        } finally {
            Files.deleteIfExists(received.file)
        }
    }

    /** Drops partially received files (e.g. after a disconnect). */
    fun discardPartials() {
        assembling.entries.toList().forEach { (key, partial) -> discard(key, partial) }
    }

    fun writeReceived(path: String, bytes: ByteArray) {
        writer.writeFile(path, bytes)
        tracked[path] = Hashes.sha256(bytes)
    }

    fun deleteStale(path: String) {
        writer.delete(path)
        tracked.remove(path)
    }

    /** Applies a host file event to the mirror. */
    fun apply(m: FileMutation, seq: Long) {
        when (m.kind) {
            FileOpKind.CREATE_FILE, FileOpKind.WRITE -> {
                val bytes = MessageCodec.decodeBase64(m.contentBase64 ?: throw IOException("Content of ${m.path} must be fetched"))
                writer.writeFile(m.path, bytes)
                tracked[m.path] = Hashes.sha256(bytes)
            }
            FileOpKind.CREATE_DIRECTORY -> writer.createDirectory(m.path)
            FileOpKind.DELETE -> {
                writer.delete(m.path)
                tracked.keys.removeIf { it == m.path || it.startsWith(m.path + "/") }
            }
            FileOpKind.RENAME -> {
                val dest = m.newPath!!
                writer.rename(m.path, dest)
                val moved = tracked.filterKeys { it == m.path || it.startsWith(m.path + "/") }
                moved.forEach { (k, v) ->
                    tracked.remove(k)
                    tracked[dest + k.removePrefix(m.path)] = v
                }
            }
        }
        if (seq > fileEventSeq) fileEventSeq = seq
    }

    /** Records a locally originated change that the host accepted. */
    fun recordLocal(m: FileMutation) {
        when (m.kind) {
            FileOpKind.CREATE_FILE, FileOpKind.WRITE ->
                m.contentBase64?.let { tracked[m.path] = Hashes.sha256(MessageCodec.decodeBase64(it)) }
            FileOpKind.DELETE -> tracked.keys.removeIf { it == m.path || it.startsWith(m.path + "/") }
            FileOpKind.RENAME -> {
                val dest = m.newPath!!
                tracked.filterKeys { it == m.path || it.startsWith(m.path + "/") }.forEach { (k, v) ->
                    tracked.remove(k); tracked[dest + k.removePrefix(m.path)] = v
                }
            }
            FileOpKind.CREATE_DIRECTORY -> Unit
        }
    }

    fun setFileEventSeq(seq: Long) {
        fileEventSeq = seq
    }

    fun saveState() {
        Files.createDirectories(stateFile.parent)
        Files.writeString(stateFile, json.encodeToString(State.serializer(), State(sessionId, projectName, tracked.toSortedMap(), fileEventSeq)))
    }
}
