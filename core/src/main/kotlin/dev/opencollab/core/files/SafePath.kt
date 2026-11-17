package dev.opencollab.core.files

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Project-relative paths as they appear on the wire: forward slashes, no leading slash,
 * no `.`/`..` segments, no drive letters, no characters that are dangerous on any OS.
 * Every path received from a peer must pass [normalizeOrNull] AND be resolved with
 * [resolveInside] before touching the filesystem.
 */
object SafePath {
    private const val MAX_PATH_LENGTH = 1024
    private const val MAX_SEGMENT_LENGTH = 255
    private val windowsReserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private val forbiddenChars = setOf('\\', ':', '*', '?', '"', '<', '>', '|')

    /** Returns the canonical form of [raw] or null if it is not a safe relative path. */
    fun normalizeOrNull(raw: String): String? {
        if (raw.isEmpty() || raw.length > MAX_PATH_LENGTH) return null
        if (raw.startsWith("/") || raw.endsWith("/")) return null
        for (segment in raw.split('/')) {
            if (!isSafeSegment(segment)) return null
        }
        return raw
    }

    fun isSafe(raw: String): Boolean = normalizeOrNull(raw) == raw

    private fun isSafeSegment(segment: String): Boolean {
        if (segment.isEmpty() || segment == "." || segment == "..") return false
        if (segment.length > MAX_SEGMENT_LENGTH) return false
        if (segment.any { it.code < 0x20 || it.code == 0x7f || it in forbiddenChars }) return false
        if (segment.endsWith(".") || segment.endsWith(" ")) return false
        val stem = segment.substringBefore('.').uppercase()
        return stem !in windowsReserved
    }

    /** Converts a path below [root] to wire form, or null if it is outside the root. */
    fun relativize(root: Path, file: Path): String? {
        val absRoot = root.toAbsolutePath().normalize()
        val absFile = file.toAbsolutePath().normalize()
        if (!absFile.startsWith(absRoot) || absFile == absRoot) return null
        val rel = absRoot.relativize(absFile).joinToString("/") { it.toString() }
        return normalizeOrNull(rel)
    }

    /**
     * Resolves a wire path below [root], refusing anything that would escape it, including
     * through symbolic links in the file itself or any existing ancestor.
     */
    fun resolveInside(root: Path, relative: String): Path {
        val safe = normalizeOrNull(relative) ?: throw UnsafePathException("Unsafe path: $relative")
        val absRoot = root.toAbsolutePath().normalize()
        var resolved = absRoot
        for (segment in safe.split('/')) resolved = resolved.resolve(segment)
        resolved = resolved.normalize()
        if (!resolved.startsWith(absRoot) || resolved == absRoot) throw UnsafePathException("Path escapes root: $relative")

        val realRoot = try { absRoot.toRealPath() } catch (e: IOException) { absRoot }
        var probe: Path? = resolved
        while (probe != null && probe.startsWith(absRoot) && probe != absRoot) {
            if (Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(probe)) throw UnsafePathException("Symbolic links are not shared: $relative")
                val real = try { probe.toRealPath() } catch (e: IOException) { throw UnsafePathException("Cannot resolve $relative") }
                if (!real.startsWith(realRoot)) throw UnsafePathException("Path escapes root: $relative")
            }
            probe = probe.parent
        }
        return resolved
    }

    fun parentOf(path: String): String? = path.substringBeforeLast('/', "").ifEmpty { null }
    fun nameOf(path: String): String = path.substringAfterLast('/')
}

class UnsafePathException(message: String) : SecurityException(message)
