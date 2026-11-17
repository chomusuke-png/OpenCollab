rootProject.name = "OpenCollab"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// core          - IntelliJ-independent protocol, OT engine, file sync, permissions, sessions
// transport     - TLS WebSocket transports (direct host server, relay client) built on Java-WebSocket
// relay-server  - self-hosted signaling/relay server (standalone executable JAR)
// plugin        - the IntelliJ IDEA plugin
include("core", "transport", "relay-server", "plugin")
