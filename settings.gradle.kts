rootProject.name = "pikpak-kotlin"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// jvmToolchain(21) is resolved by this build's own settings even when Piko includes the SDK as a
// composite build, so a machine or CI runner with some other JDK downloads 21 instead of failing.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
