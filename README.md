# pikpak-kotlin

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fnihildigit%2Fpikpak-kotlin%2Fmaven-metadata.xml&label=Maven%20Central&logo=kotlin&logoColor=white&color=7F52FF)](https://central.sonatype.com/artifact/io.github.nihildigit/pikpak-kotlin)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)

A Kotlin Multiplatform SDK for [PikPak](https://mypikpak.com/) cloud storage.

Small, atomic, well-typed surface over the PikPak HTTP API. The painful parts — token lifecycle, captcha refresh, OSS multipart signing, GCID hashing, rate limiting, retry — run automatically.

> 📖 **Full API reference + architectural walkthroughs**
>
> [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/NihilDigit/pikpak-kotlin)
>
> Auto-generated from this repo and kept in sync on every push.

## Installation

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.nihildigit:pikpak-kotlin:0.4.2")

    // Ktor is compileOnly in the SDK so it never upgrades the Ktor
    // version you've already pinned. Declare the pieces the SDK uses plus
    // an engine for your platform explicitly:
    implementation("io.ktor:ktor-client-core:<your-ktor-version>")
    implementation("io.ktor:ktor-client-content-negotiation:<your-ktor-version>")
    implementation("io.ktor:ktor-serialization-kotlinx-json:<your-ktor-version>")
    // then one engine: ktor-client-okhttp (jvm/android), ktor-client-darwin
    // (apple), ktor-client-cio (linux/mingw), or any other Ktor engine.
}
```

Gradle picks the right per-target artifact automatically based on your consumer build. No extra coordinates needed beyond Ktor.

## Platforms

Every shipped target is verified on a real runner before each release. Tag push triggers `release.yml`, which fans out across a platform matrix and only publishes if every cell passes. Runtime tests are run on targets that [Kotlin/Native's own tier table](https://kotlinlang.org/docs/native-target-support.html) also runs upstream; the rest only have to compile cleanly.

| Target               | Release-gated CI      | HTTP engine |
| -------------------- | --------------------- | ----------- |
| `jvm`                | runtime test          | OkHttp      |
| `android`            | compile-only          | OkHttp      |
| `linuxX64`           | runtime test          | CIO         |
| `linuxArm64`         | compile-only          | CIO         |
| `mingwX64`           | compile-only          | CIO         |
| `macosArm64`         | runtime test          | Darwin      |
| `iosSimulatorArm64`  | runtime test          | Darwin      |
| `iosArm64`           | compile-only          | Darwin      |
| `iosX64`             | compile-only          | Darwin      |

`android` ships as an AAR with the `pikpak-kotlin-android` artifactId — KMP consumers declaring `androidTarget()` pick it up automatically via Gradle metadata. The Android-default session dir is best-effort; wire your own `FileSessionStore(dir = Path(context.filesDir.absolutePath, "pikpak-kotlin"))` if you care about a stable on-disk location.

## Design

- **Atomic**: every endpoint is a focused `suspend` extension function on `PikPakClient`. No hidden orchestration unless you opt in.
- **Hard problems abstracted**: session persistence, `access_token` refresh, captcha re-auth on `error_code=9`, exponential-backoff retry, token-bucket rate limiting, GCID content hashing, and OSS HMAC-SHA1 signing all run automatically.
- **Out of scope**: multi-account pools, sync/backup engines, recursive cleanup heuristics, CLIs. These are easy to build on top — the SDK does not bake them in. (For example, multi-account rotation is just `listOf(client1, client2).random()`.)
- **Multiplatform**: `commonMain` depends only on multiplatform libraries (Ktor, kotlinx.serialization, kotlinx.coroutines, kotlinx.datetime, kotlinx-io, KotlinCrypto). Each platform uses its native HTTP engine.

## API surface

```kotlin
val client = PikPakClient(
    account = "you@example.com",
    password = "your-password",
    // sessionStore = FileSessionStore() by default (~/.config/pikpak-kotlin on JVM)
    // rateLimiter = RateLimiter.Default (5 req/s, burst 5) by default
    // retryPolicy = RetryPolicy.Default (3 attempts, exp backoff) by default
)
client.login()                                // reuses cached session if still valid

// Quota
client.getQuota()                             // GET /drive/v1/about

// Listing & lookup
client.listFiles(parentId = "")               // root folder, follows pagination
client.getFile(fileId)                        // full FileDetail incl. download links
client.getFolderId(parentId, name)            // immediate child folder by name
client.getDeepFolderId(parentId, "a/b/c")     // resolve a path
client.getPathFolderId("/a/b/c")              // shorthand for above from root
client.getOrCreateDeepFolderId(parent, path)  // mkdir -p

// Mutations
client.createFolder(parentId, name)
client.rename(fileId, newName)
client.deleteFile(fileId)                     // moves to trash (PikPak is soft-delete)
client.batchTrash(listOf(id1, id2))           // bulk soft-delete (30-day recoverable)
client.batchDelete(listOf(id1, id2))          // bulk hard-delete (bypasses trash)

// Transfer
client.download(fileId, destPath)             // resumable, retries, byte-verified
client.downloadFromUrl(url, dest, expected)   // when you already have a signed URL

client.upload(parentId, sourcePath)           // GCID-hashed, instant-upload aware,
                                              // OSS multipart with HMAC-SHA1 signing

// Offline-download queue
when (val r = client.createUrlFile(parentId, "https://...")) {
    is CreateUrlResult.Queued           -> r.task.id          // poll via listOfflineTasks()
    is CreateUrlResult.InstantComplete  -> { /* PikPak already had this URL */ }
}
client.listOfflineTasks()                     // inspect running/errored tasks

client.logout()                               // forget cached tokens
client.close()                                // close internal HTTP client
```

`SessionStore` defaults to a JSON file at `~/.config/pikpak-kotlin/session_<md5(account)>.json` on JVM. Provide your own (`InMemorySessionStore`, an Android-Context-aware one, etc.) by passing `sessionStore = ...` to the client.

## Local development

Copy `.env.example` to `.env` for the live integration tests:

```
PIKPAK_USERNAME=you@example.com
PIKPAK_PASSWORD=your-password
```

`.env` is git-ignored. The library itself never reads `.env` — only the integration test suite does.

Requires JDK 21. The repo includes a Gradle 8.11 wrapper:

```bash
./gradlew jvmTest                     # JVM unit + live PikPak integration (opt-in via .env)
./gradlew linuxX64Test                # native runtime: gcid hash + captcha mock
./gradlew macosArm64Test              # same, on an Apple-silicon Mac
./gradlew mingwX64Test                # same, on Windows
./gradlew jvmTest --tests '*Hash*'    # unit only, no network
```

Integration tests `Assumptions.assumeTrue()` themselves out when `PIKPAK_USERNAME` isn't set, so the release workflow stays green without access to your `.env`.

## Releasing

See [RELEASING.md](./RELEASING.md). Tagging `v*.*.*` triggers [`.github/workflows/release.yml`](./.github/workflows/release.yml): it runs the full platform test matrix and, only if every platform passes, publishes signed artifacts to Maven Central via the Sonatype Central Portal. There's no separate "CI on push" workflow — dev-time quality verification happens locally.

## Credit

The HTTP wire format, captcha salt cascade, and GCID content hash are derived from the excellent Go reference implementations:
- [52funny/pikpakcli](https://github.com/52funny/pikpakcli) — endpoint URLs, request/response shapes, captcha signing flow, OSS upload protocol
- [52funny/pikpakhash](https://github.com/52funny/pikpakhash) — GCID block-size table

All code in this repo is fresh Kotlin; only the public PikPak API behavior is shared.

## License

MIT — see [LICENSE](./LICENSE).
