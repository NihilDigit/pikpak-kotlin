import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

group = "io.github.nihildigit"
version = "0.1.0"

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // Apple targets
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()

    // Other native
    linuxX64()
    mingwX64()

    // Shared intermediate source sets so target-specific code only goes in
    // the smallest set that needs it. The Apple group covers iOS + macOS;
    // the broader native group covers anything that's not the JVM.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val ktorVersion = "3.0.3"
        val coroutinesVersion = "1.10.1"
        val serializationVersion = "1.7.3"
        val kotlincryptoVersion = "0.5.6"
        val kotlinxIoVersion = "0.5.4"

        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinxIoVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("io.ktor:ktor-client-core:$ktorVersion")
            implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
            implementation("org.kotlincrypto.hash:md:$kotlincryptoVersion")
            implementation("org.kotlincrypto.hash:sha1:$kotlincryptoVersion")
            implementation("org.kotlincrypto.hash:sha2:$kotlincryptoVersion")
            implementation("org.kotlincrypto.macs:hmac-sha1:$kotlincryptoVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
            implementation("io.ktor:ktor-client-mock:$ktorVersion")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
            implementation("org.slf4j:slf4j-simple:2.0.16")
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
        }

        // Apple targets share Ktor's Darwin engine.
        appleMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:$ktorVersion")
        }
        // Linux + Windows native ride on Ktor CIO — pure-Kotlin, no libcurl
        // dependency at runtime.
        val linuxX64Main by getting {
            dependencies { implementation("io.ktor:ktor-client-cio:$ktorVersion") }
        }
        val mingwX64Main by getting {
            dependencies { implementation("io.ktor:ktor-client-cio:$ktorVersion") }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
