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
        vendor {
            name = "OpenCollab contributors"
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
}

// Releases live on GitHub. IDEs find updates through a custom plugin repository: the
// updatePlugins.xml attached to the latest release, at a URL that always points to the newest
// one. Keep in sync with UpdateRepositoryActivity.URL in the plugin.
val releaseRepository = "chomusuke-png/OpenCollab"
val pluginZip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }

val updatePluginsXml = tasks.register("updatePluginsXml") {
    group = "publishing"
    description = "Writes the updatePlugins.xml that IDEs read to find this release."
    val version = project.version.toString()
    val repo = releaseRepository
    val output = layout.buildDirectory.file("release/updatePlugins.xml")
    inputs.property("version", version)
    outputs.file(output)
    doLast {
        val zipUrl = "https://github.com/$repo/releases/download/v$version/OpenCollab-$version.zip"
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugins>
                <plugin id="dev.opencollab" url="$zipUrl" version="$version">
                    <idea-version since-build="262"/>
                    <name>OpenCollab</name>
                    <vendor>OpenCollab contributors</vendor>
                    <description>Real-time collaboration for IntelliJ IDEA without Remote Development.</description>
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
    doLast {
        // The version comes from the commits since the last tag (root build.gradle.kts).
        if (newCommits == 0) throw GradleException("Nothing to release: no commits since $lastTag.")
        fun run(vararg command: String, capture: Boolean = false): String {
            val process = ProcessBuilder(*command).directory(workDir).apply {
                if (capture) redirectErrorStream(true) else inheritIO()
            }.start()
            val out = if (capture) process.inputStream.bufferedReader().readText().trim() else ""
            if (process.waitFor() != 0) throw GradleException("Failed: ${command.joinToString(" ")}\n$out")
            return out
        }
        // The release is tagged at HEAD, so what it ships must be committed and on GitHub.
        // gradle.properties is exempt: buildPlugin has just raised the revision in it.
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
            "--title", "OpenCollab $version", "--generate-notes",
        )
        run("git", "fetch", "--tags", "--quiet") // the new tag was created on GitHub
        logger.lifecycle("Released OpenCollab $version: https://github.com/$repo/releases/tag/v$version")
    }
}
