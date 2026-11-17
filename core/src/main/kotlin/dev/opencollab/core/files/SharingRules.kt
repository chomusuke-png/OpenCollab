package dev.opencollab.core.files

/**
 * Decides which project paths are shared with guests.
 *
 * Order of evaluation:
 *  1. [HARD_EXCLUDES] can never be shared (VCS internals, OpenCollab's own data).
 *  2. Paths matching a user [includes] pattern are shared, even if a default exclude matches.
 *  3. Paths matching [excludes] (defaults + user patterns) are not shared.
 *  4. Everything else is shared.
 *
 * Shared paths matching [guestReadOnly] can be read by guests but never modified by them
 * (IDE run configurations, wrapper scripts, CI workflows: files that make the host execute
 * something without reviewing it).
 *
 * Patterns are gitignore-like globs on '/'-separated relative paths: `*` matches within a
 * segment, a double star matches any number of segments, `?` one character. A pattern without '/'
 * matches a file or directory name at any depth. A trailing slash-double-star suffix matches everything
 * below a directory.
 */
class SharingRules(
    val excludes: List<String> = DEFAULT_EXCLUDES,
    val includes: List<String> = emptyList(),
    val guestReadOnly: List<String> = DEFAULT_GUEST_READ_ONLY,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    private val hard = HARD_EXCLUDES.map(Glob::compile)
    private val exclude = excludes.filter { it.isNotBlank() }.map { Glob.compile(it.trim()) }
    private val include = includes.filter { it.isNotBlank() }.map { Glob.compile(it.trim()) }
    private val readOnly = guestReadOnly.filter { it.isNotBlank() }.map { Glob.compile(it.trim()) }

    fun isGuestReadOnly(path: String): Boolean = readOnly.any { it.matchesPathOrAncestor(path) }

    fun isShared(path: String): Boolean {
        if (!SafePath.isSafe(path)) return false
        if (hard.any { it.matchesPathOrAncestor(path) }) return false
        if (include.any { it.matchesPathOrAncestor(path) }) return true
        return exclude.none { it.matchesPathOrAncestor(path) }
    }

    /** Whether a directory walk should enter [dir] (excluded dirs are entered only if includes exist). */
    fun shouldDescend(dir: String): Boolean {
        if (!SafePath.isSafe(dir)) return false
        if (hard.any { it.matchesPathOrAncestor(dir) }) return false
        return include.isNotEmpty() || isShared(dir)
    }

    fun isSizeAllowed(size: Long): Boolean = size <= maxFileBytes

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 10L * 1024 * 1024

        /** Upper bound for the configurable per-file limit (files are streamed in chunks). */
        const val MAX_ALLOWED_FILE_MB = 2048

        val HARD_EXCLUDES = listOf(".git", ".hg", ".svn", ".opencollab")

        val DEFAULT_GUEST_READ_ONLY = listOf(
            ".idea", "*.iml", ".run", "*.run.xml", ".vscode", "gradlew", "gradlew.bat", "gradle/wrapper",
            "mvnw", "mvnw.cmd", ".mvn/wrapper", ".github/workflows", ".gitlab-ci.yml", ".gitattributes",
            ".gitmodules", ".envrc", ".tool-versions",
        )

        /** Secrets, credentials, local-only IDE state and build outputs. */
        val DEFAULT_EXCLUDES = listOf(
            // Local IDE state
            ".idea/workspace.xml", ".idea/shelf", ".idea/usage.statistics.xml", ".idea/tasks.xml",
            ".idea/dataSources", ".idea/dataSources.local.xml", ".idea/dataSources.xml", ".idea/httpRequests",
            ".idea/sonarlint", ".idea/copilot*", "*.iws", ".DS_Store", "Thumbs.db",
            // Build outputs and dependency caches
            ".gradle", "build", "out", "target", "node_modules", ".venv", "venv", "__pycache__", ".kotlin",
            ".intellijPlatform", "dist", ".next", ".cache",
            // Secrets and credentials
            "local.properties", ".env", ".env.*", "*.env", "*.pem", "*.key", "*.p12", "*.pfx", "*.jks",
            "*.keystore", "*.kdbx", "id_rsa*", "id_dsa*", "id_ecdsa*", "id_ed25519*", ".npmrc", ".pypirc",
            ".netrc", "_netrc", ".git-credentials", ".dockercfg", ".docker", ".aws", ".ssh", ".gnupg",
            "credentials*", "*credentials.json", "secrets*", "*.secret", "*.secrets", "service-account*.json",
            "*.tfstate", "*.tfstate.*", "*.tfvars", "terraform.tfvars.json", "google-services.json",
            "GoogleService-Info.plist", "*.mobileprovision", "keystore.properties", "signing.properties",
        )
    }
}

/** Minimal glob matcher for '/'-separated relative paths. */
class Glob private constructor(val pattern: String, private val regex: Regex) {
    fun matches(path: String): Boolean = regex.matches(path)

    /** True if the path or any of its ancestor directories matches. */
    fun matchesPathOrAncestor(path: String): Boolean {
        if (matches(path)) return true
        var idx = path.indexOf('/')
        while (idx > 0) {
            if (matches(path.substring(0, idx))) return true
            idx = path.indexOf('/', idx + 1)
        }
        return false
    }

    companion object {
        fun compile(raw: String): Glob {
            var p = raw.trim().removePrefix("/").removeSuffix("/")
            if (p.endsWith("/**")) p = p.removeSuffix("/**")
            val anchored = p.contains('/')
            val body = toRegex(p)
            val regex = if (anchored) Regex(body) else Regex("(?:.*/)?$body")
            return Glob(p, regex)
        }

        private fun toRegex(p: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '*' && p.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3; continue }
                    c == '*' && p.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return sb.toString()
        }
    }
}

fun SharingRules.toConfig() = dev.opencollab.core.protocol.SharingConfig(excludes, includes, guestReadOnly, maxFileBytes)

fun dev.opencollab.core.protocol.SharingConfig.toRules() = SharingRules(excludes, includes, guestReadOnly, maxFileBytes)
