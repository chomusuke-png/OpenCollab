// Self-hosted OpenCollab signaling/relay server. Forwards opaque (end-to-end TLS) frames
// between a host and its guests. Build a runnable fat JAR with `:relay-server:shadowJar`.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":transport"))
    runtimeOnly(libs.slf4j.simple)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("dev.opencollab.relay.RelayMainKt")
    applicationName = "opencollab-relay"
}

tasks.shadowJar {
    archiveBaseName.set("opencollab-relay")
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
