import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// The OpenCollab IntelliJ IDEA plugin. Depends only on the IntelliJ Platform core
// (com.intellij.modules.platform): no Ultimate-only plugins, no Remote Development.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    // IntelliJ IDEA 2026.2 runs on (and is compiled for) Java 25.
    jvmToolchain(25)
    compilerOptions {
        // Inherit platform interface default methods instead of generating bridges that
        // re-invoke deprecated/experimental defaults (flagged by the Plugin Verifier).
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    implementation(project(":transport"))

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // IntelliJ's test runner (test trees for the host's test runs). Part of the platform at
        // runtime; only needed on the compile classpath.
        bundledModule("intellij.platform.smRunner")
        bundledModule("intellij.platform.testRunner")
        // Branch names of the host's repositories (any VCS); also part of the platform at runtime.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(libs.junit4)
}

// The IDE ships the Kotlin standard library, coroutines and SLF4J; bundling our own copies
// would cause class-loading conflicts.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.slf4j", module = "slf4j-api")
}

// What's new: CHANGELOG.md at the repository root, one "## [version] - date" section per release
// (and "## [Unreleased]" for what is not released yet). Parsed here, at configuration time, into
// plain strings, so the tasks below keep no reference to the script.
val releaseRepository = "chomusuke-png/OpenCollab"
val changelogSections: Map<String, String> = run {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("CHANGELOG.md")).asText.orNull.orEmpty()
        .replace("\r\n", "\n").replace(Regex("(?s)<!--.*?-->"), "")
    val headings = Regex("""^## \[([^\]]+)]""", RegexOption.MULTILINE).findAll(text).toList()
    headings.mapIndexed { i, m ->
        val end = headings.getOrNull(i + 1)?.range?.first ?: text.length
        m.groupValues[1] to text.substring(m.range.last + 1, end).substringAfter('\n', "").trim()
    }.toMap()
}

/** Links relative to the repository (docs/USER_GUIDE.md) point to GitHub, where they also work from the IDE. */
fun absoluteLinks(markdown: String): String =
    markdown.replace(Regex("""\]\((?!https?://)([^)]+)\)""")) { "](https://github.com/$releaseRepository/blob/main/${it.groupValues[1]})" }

/** The small part of Markdown CHANGELOG.md uses ("### " groups, "- " items, inline styles) as HTML. */
fun changelogHtml(markdown: String): String {
    fun inline(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("""\[([^\]]+)]\(([^)]+)\)""")) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        .replace(Regex("""\*\*(.+?)\*\*"""), "<b>$1</b>")
        .replace(Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])"""), "<i>$1</i>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
    val html = StringBuilder()
    val item = StringBuilder()
    var inList = false
    fun endItem() { if (item.isNotEmpty()) { html.append("<li>").append(inline(item.toString())).append("</li>"); item.clear() } }
    fun endList() { endItem(); if (inList) { html.append("</ul>"); inList = false } }
    for (line in absoluteLinks(markdown).lines()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("### ") -> { endList(); html.append("<p><b>").append(inline(trimmed.removePrefix("### "))).append("</b></p>") }
            trimmed.startsWith("- ") -> { endItem(); if (!inList) { html.append("<ul>"); inList = true }; item.append(trimmed.removePrefix("- ")) }
            trimmed.isEmpty() -> endList()
            item.isNotEmpty() -> item.append(' ').append(trimmed)
            else -> { endList(); html.append("<p>").append(inline(trimmed)).append("</p>") }
        }
    }
    endList()
    return html.toString()
}

// "What's New" on the plugin's page in the IDE: what is not released yet (in development builds),
// then the latest releases.
val changeNotesHtml: String = run {
    val released = changelogSections.filterKeys { it != "Unreleased" }.entries.take(4)
    val unreleased = changelogSections["Unreleased"]?.takeIf { it.isNotBlank() && project.version.toString() !in changelogSections }
    val sections = listOfNotNull(unreleased?.let { "Next version" to it }) + released.map { it.key to it.value }
    sections.joinToString("") { (title, body) -> "<h3>${title}</h3>" + changelogHtml(body) } +
        "<p><a href=\"https://github.com/$releaseRepository/blob/main/CHANGELOG.md\">All changes</a></p>"
}

intellijPlatform {
    projectName = "OpenCollab"
    pluginConfiguration {
        id = "dev.opencollab"
        name = "OpenCollab"
        version = project.version.toString()
        description = """
            Real-time collaboration for IntelliJ IDEA without Remote Development: a host shares
            the authoritative project, guests edit together from their own IDE installations,
            with live cursors, selections, file synchronisation and host-enforced permissions.
            Direct TLS connections or a self-hosted relay with end-to-end TLS.
        """.trimIndent()
        changeNotes = changeNotesHtml
        vendor {
            name = "chomusuke-png & hi444"
        }
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
        }
    }
    buildSearchableOptions = false
    // Optional short sandbox location (-PsandboxDir=C:/oc-sandbox). On Windows a checkout in a
    // deep folder makes the sandbox paths too long to start the IDE's compile server.
    providers.gradleProperty("sandboxDir").orNull?.let { sandboxContainer = file(it) }
}

// A second sandbox IDE (own config/system/plugins directories) to try a host and a guest on
// one machine: run `:plugin:runIde` and `:plugin:runIdeGuest` in two terminals.
intellijPlatformTesting {
    runIde {
        register("runIdeGuest")
    }
}

tasks {
    test {
        systemProperty("idea.force.use.core.classloader", "true")
    }
    // The plugin's version for its diagnostics (the IDE's own lookups are internal API).
    processResources {
        val version = project.version.toString()
        inputs.property("version", version)
        filesMatching("opencollab-build.properties") { expand("version" to version) }
    }
}

// Releases live on GitHub. IDEs find updates through a custom plugin repository: the
// updatePlugins.xml attached to the latest release, at a URL that always points to the newest
// one. Keep in sync with UpdateRepositoryActivity.URL in the plugin.
val pluginZip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }

val updatePluginsXml = tasks.register("updatePluginsXml") {
    group = "publishing"
    description = "Writes the updatePlugins.xml that IDEs read to find this release."
    val version = project.version.toString()
    val repo = releaseRepository
    val output = layout.buildDirectory.file("release/updatePlugins.xml")
    val notes = changeNotesHtml
    inputs.property("version", version)
    inputs.property("changeNotes", notes)
    outputs.file(output)
    doLast {
        val zipUrl = "https://github.com/$repo/releases/download/v$version/OpenCollab-$version.zip"
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugins>
                <plugin id="dev.opencollab" url="$zipUrl" version="$version">
                    <idea-version since-build="262"/>
                    <name>OpenCollab</name>
                    <vendor>chomusuke-png &amp; hi444</vendor>
                    <description>Real-time collaboration for IntelliJ IDEA without Remote Development.</description>
                    <change-notes><![CDATA[$notes]]></change-notes>
                </plugin>
            </plugins>
        """.trimIndent() + "\n"
        output.get().asFile.apply { parentFile.mkdirs(); writeText(xml) }
    }
}

tasks.register("publishRelease") {
    group = "publishing"
    description = "Builds the plugin and publishes it as GitHub release v<version> with its updatePlugins.xml."
    dependsOn("buildPlugin", updatePluginsXml)
    val version = project.version.toString()
    val repo = releaseRepository
    val zip = pluginZip
    val xml = layout.buildDirectory.file("release/updatePlugins.xml")
    val workDir = rootProject.rootDir
    val lastTag = rootProject.extra["lastReleaseTag"] as String
    val newCommits = rootProject.extra["commitsSinceRelease"] as Int
    val releaseNotes = changelogSections[version]?.takeIf { it.isNotBlank() }?.let { absoluteLinks(it) }
    val notesFile = layout.buildDirectory.file("release/notes.md")
    doLast {
        // The version comes from the commits since the last tag (root build.gradle.kts).
        if (newCommits == 0) throw GradleException("Nothing to release: no commits since $lastTag.")
        // What's new comes from CHANGELOG.md, written before releasing.
        if (releaseNotes == null) {
            throw GradleException("CHANGELOG.md has no section for $version: rename \"## [Unreleased]\" to \"## [$version] - <date>\" and commit it.")
        }
        val notes = notesFile.get().asFile.apply { parentFile.mkdirs(); writeText(releaseNotes + "\n") }
        fun run(vararg command: String, capture: Boolean = false): String {
            val process = ProcessBuilder(*command).directory(workDir).apply {
                if (capture) redirectErrorStream(true) else inheritIO()
            }.start()
            val out = if (capture) process.inputStream.bufferedReader().readText().trim() else ""
            if (process.waitFor() != 0) throw GradleException("Failed: ${command.joinToString(" ")}\n$out")
            return out
        }
        // The release is tagged at HEAD, so what it ships must be committed and on GitHub.
        val dirty = run("git", "status", "--porcelain", "--untracked-files=no", "--", ".", ":(exclude)gradle.properties", capture = true)
        if (dirty.isNotEmpty()) throw GradleException("Commit these changes before releasing:\n$dirty")
        val head = run("git", "rev-parse", "HEAD", capture = true)
        if (run("git", "branch", "-r", "--contains", head, capture = true).isEmpty()) {
            throw GradleException("Push $head to GitHub before releasing.")
        }
        run(
            "gh", "release", "create", "v$version",
            zip.get().asFile.path, xml.get().asFile.path,
            "--repo", repo, "--target", head,
            "--title", "OpenCollab $version", "--notes-file", notes.path, "--generate-notes",
        )
        run("git", "fetch", "--tags", "--quiet") // the new tag was created on GitHub
        logger.lifecycle("Released OpenCollab $version: https://github.com/$repo/releases/tag/v$version")
    }
}
