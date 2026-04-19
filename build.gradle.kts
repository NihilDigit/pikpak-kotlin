import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    // AGP 9 dropped support for `com.android.library` in KMP projects;
    // this is the replacement that integrates directly into the `kotlin {}` DSL.
    id("com.android.kotlin.multiplatform.library") version "9.1.1"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.github.nihildigit"
version = "0.3.1-SNAPSHOT"

@OptIn(ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(21)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    android {
        namespace = "io.github.nihildigit.pikpak"
        compileSdk = 36
        minSdk = 21
        // Android's R8/D8 accepts up to JVM 11 bytecode today; pin to 11
        // here so Kotlin features that require >1.8 bytecode (e.g. certain
        // inline-class call sites) don't get blocked.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // Apple targets
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    // Other native
    linuxX64()
    mingwX64()

    // Shared intermediate source sets so target-specific code only goes in
    // the smallest set that needs it. The Apple group covers iOS + macOS;
    // the broader native group covers anything that's not the JVM.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val ktorVersion = "3.4.2"
        val coroutinesVersion = "1.10.2"
        val serializationVersion = "1.11.0"
        val kotlincryptoVersion = "0.8.0"
        val kotlinxIoVersion = "0.9.0"

        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinxIoVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
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
            implementation("org.slf4j:slf4j-simple:2.0.17")
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation("org.junit.jupiter:junit-jupiter:6.0.3")
            implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
        }

        // Android rides on the same OkHttp engine as JVM. Ktor's okhttp
        // artifact is pure-JVM and runs fine on Android's Dalvik/ART.
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
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

mavenPublishing {
    // New Sonatype Central Portal (replaces legacy OSSRH). The vanniktech
    // plugin handles staging upload, GPG signing of every artifact, POM
    // validation, and (with automaticRelease=true) the staging→release promotion.
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    pom {
        name.set("pikpak-kotlin")
        description.set(
            "Atomic Kotlin Multiplatform SDK for PikPak cloud storage. " +
                "Bakes in session persistence, OAuth refresh, captcha re-auth, rate limiting, " +
                "GCID hashing, and OSS multipart upload signing — leaves higher-level orchestration " +
                "(account pools, sync engines) to the caller.",
        )
        url.set("https://github.com/NihilDigit/pikpak-kotlin")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("nihildigit")
                name.set("nihildigit")
                url.set("https://github.com/NihilDigit")
            }
        }
        scm {
            url.set("https://github.com/NihilDigit/pikpak-kotlin")
            connection.set("scm:git:git://github.com/NihilDigit/pikpak-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/NihilDigit/pikpak-kotlin.git")
        }
        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/NihilDigit/pikpak-kotlin/issues")
        }
    }
}
