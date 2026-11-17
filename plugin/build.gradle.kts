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
