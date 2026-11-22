plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.intellij.platform) apply false
    alias(libs.plugins.shadow) apply false
}

// ---- Version (Semantic Versioning, from git) ----------------------------------------------
// The last release tag vMAJOR.MINOR.PATCH plus the Conventional Commits since it decide the
// next version: a breaking change ("type!:" or a "BREAKING CHANGE:" footer) raises MAJOR, a
// "feat" raises MINOR, anything else PATCH. `publishRelease` builds exactly that version; every
// other build is "<next>-dev.<commits since the tag>", so local builds never use up a number.

// Without git or a checkout (a Docker build, a source archive) there are no tags: pass the
// version with -PbuildVersion=X.Y.Z.
fun git(vararg args: String): String = try {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (_: Exception) {
    ""
}

val releaseTag = Regex("""v(\d+)\.(\d+)\.(\d+)""")
val lastTag: String? = git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*.[0-9]*.[0-9]*")
    .takeIf { releaseTag.matches(it) }
val commitsSinceTag: List<String> =
    git("log", *listOfNotNull(lastTag?.let { "$it..HEAD" }).toTypedArray(), "--format=%B%x1e")
        .split('\u001e').map { it.trim() }.filter { it.isNotEmpty() }

val nextVersion: String = run {
    val (major, minor, patch) = lastTag?.let { releaseTag.matchEntire(it)!!.destructured.toList().map(String::toInt) } ?: listOf(0, 0, 0)
    val breaking = commitsSinceTag.any { message ->
        Regex("""^\w+(\([^)]*\))?!:""").containsMatchIn(message.lineSequence().first()) ||
            message.lineSequence().any { it.startsWith("BREAKING CHANGE:") || it.startsWith("BREAKING-CHANGE:") }
    }
    val feature = commitsSinceTag.any { Regex("""^feat(\([^)]*\))?:""").containsMatchIn(it) }
    when {
        commitsSinceTag.isEmpty() -> "$major.$minor.$patch"
        breaking -> "${major + 1}.0.0"
        feature -> "$major.${minor + 1}.0"
        else -> "$major.$minor.${patch + 1}"
    }
}
val releasing = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "publishRelease" }
/** Uncommitted changes: even on a release tag, this build is not that release. */
val dirty = git("status", "--porcelain").isNotEmpty()

val buildVersion: String? = providers.gradleProperty("buildVersion").orNull?.takeIf { it.isNotBlank() }

allprojects {
    group = "dev.opencollab"
    version = if (buildVersion != null) buildVersion else if (releasing || (commitsSinceTag.isEmpty() && !dirty)) nextVersion else "$nextVersion-dev.${commitsSinceTag.size}"
}

extra["lastReleaseTag"] = lastTag ?: ""
extra["commitsSinceRelease"] = commitsSinceTag.size
