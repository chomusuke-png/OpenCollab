plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.intellij.platform) apply false
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.opencollab"
    version = providers.gradleProperty("opencollabVersion").get()
}
