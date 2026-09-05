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
    implementation("io.github.nihildigit:pikpak-kotlin:0.5.2")

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
- **Hard problems abstracted**: session persistence, `access_token` refresh, re-authentication on a server-side 401, captcha re-auth on `error_code=9`, exponential-backoff retry, token-bucket rate limiting, GCID content hashing, and OSS HMAC-SHA1 signing all run automatically. Concurrent callers that trip the same expiry share one refresh.
- **Streams, not buffers**: CDN and OSS bodies are consumed as they arrive. A range read costs no memory until you read it, and a `RangeReader` keeps concurrent reads inside the connection budget a signed URL accepts.
- **Out of scope**: multi-account pools, sync/backup engines, recursive cleanup heuristics, CLIs. These are easy to build on top — the SDK does not bake them in. (For example, multi-account rotation is just `listOf(client1, client2).random()`.)
- **Multiplatform**: `commonMain` depends only on multiplatform libraries (Ktor, kotlinx.serialization, kotlinx.coroutines, kotlinx.datetime, kotlinx-io, KotlinCrypto). Each platform uses its native HTTP engine.

## API surface

```kotlin
val client = PikPakClient(
    account = "you@example.com",
    passwordSupplier = { vault.read("pikpak") },  // called only when a full sign-in is needed
    // password = "..." is still accepted for the simple case
    // sessionStore = FileSessionStore() by default (~/.config/pikpak-kotlin on JVM)
    // rateLimiter = RateLimiter.Default (5 req/s, burst 5) by default
    // retryPolicy = RetryPolicy.Default (3 attempts, exp backoff) by default
    // connectionBudget = 8: concurrent CDN connections per signed URL
)
client.login()                                // optional: every call logs in on demand
client.prewarm()                              // login + one cheap call, so the captcha
                                              // handshake happens before the user waits
client.sessionFlow                            // StateFlow<Session?> for sign-in state UI

// Quota
client.getQuota()                             // GET /drive/v1/about

// Listing & lookup
client.listFiles(parentId = "")               // root folder, follows pagination
client.listFilesPaged(parentId, pageToken = "", extraFilters = mapOf(FileFilter.kind(FileKind.FOLDER)))
client.getFile(fileId)                        // full FileDetail incl. download links
client.getFile(fileId).octetStream.expiresAt  // when the signed link stops working
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
client.parallelDownloadFromUrl(url, dest, partCount = 8)
client.streamRangeFromUrl(url, start, length) { range -> range.channel /* live */ }

client.upload(parentId, sourcePath)           // GCID-hashed, instant-upload aware,
                                              // OSS multipart with HMAC-SHA1 signing

// Offline-download queue
when (val r = client.createUrlFile(parentId, "magnet:?xt=...")) {
    is CreateUrlResult.Queued           -> r.task.id          // poll via getTask()
    is CreateUrlResult.InstantComplete  -> r.file             // PikPak already had this URL
}
var task = client.getTask(taskId)             // one task, without pulling the table
while (task.phase !in TaskPhase.TERMINAL) { delay(3.seconds); task = client.getTask(taskId) }
task.fileId                                   // set once phase == TaskPhase.COMPLETE
client.listOfflineTasks()                     // inspect running/errored tasks

client.logout()                               // forget cached tokens
client.close()                                // close internal HTTP client
```

### Random access

`RangeReader` serves arbitrary byte ranges from one remote file, which is what playback needs and what a download call cannot give. It keeps concurrency inside the 8 connections a signed PikPak URL accepts, hands a contended slot to the highest-priority read, refreshes the URL when its signature expires, waits out a 503 instead of failing, and resumes from the last delivered byte when a body stops early. Nothing is cached — piece scheduling and disk layout stay with the caller.

```kotlin
val reader = client.rangeReader(fileId)       // refreshes its own URL via getFile
reader.read(start = 0, length = 1 shl 20, priority = 10) { channel ->
    // channel is live: the bytes have not been buffered anywhere
}
reader.stats.value                            // active reads, bytes, refreshes, throttles
reader.close()
```

Construct `RangeReader` directly with your own `urlProvider` when the URL comes from somewhere other than `getFile`. `parallelDownloadFromUrl` is a thin wrapper over the same machinery.

A range that runs past the end of the file ends at EOF. A signed URL that the CDN rejects with 401 or 403 surfaces as `UrlExpiredException` from the single-request calls; `RangeReader` handles it by asking `urlProvider` for a fresh one. Every `PikPakException` raised at the HTTP layer carries `httpStatus`, `headers` and `rawBody`.

The 8-connection budget is a measured property of PikPak's CDN, not a tuning knob: one signed URL accepts 8 concurrent connections and answers the 9th with 503, each connection sustains under 1 MB/s, and throughput scales linearly with connection count up to that cap. The SDK builds a separate CDN client per platform whose per-host limit matches the budget, because every engine's default (OkHttp 5, Darwin 4 or 6) is lower. Pass `cdnHttpClient = PikPakClient.tunedCdnClient()` when you inject your own API client and still want the tuned pool.

#### Variants

A video file on PikPak exists as the uploaded original plus, once PikPak has transcoded it, a set of MPEG-TS variants at 1080P, 720P and 480P. Each is a separate resource with its own signed link and its own byte count, so the choice is made once and then locked by `mediaId`.

```kotlin
val v = client.resolveVariant(fileId, VariantPreference.Resolution("720P"))
v.mediaId                                     // null for the original; persist this
val size = v.sizeBytes ?: client.remoteSize(v.link.url)

val reader = client.rangeReader(fileId, v.mediaId)   // reopens the same bytes later
```

`resolveVariant` falls back to the original when the requested resolution is absent or still transcoding. That fallback happens at resolve time only: `rangeReader(fileId, mediaId)` refreshes an expired link by taking the same `mediaId` from a fresh file detail, and fails with `PikPakException` when that variant is gone, rather than reading another variant's bytes at an offset the caller committed to. `FileDetail.variant(mediaId)` is the same lookup without a network call.

Transcodes carry no embedded subtitles and a lower audio bitrate than the original. Their length is not in the file metadata; `remoteSize` derives it from a one-byte range probe's `Content-Range`.

`SessionStore` defaults to a JSON file at `~/.config/pikpak-kotlin/session_<md5(account)>.json` on JVM. Provide your own (`InMemorySessionStore`, an Android-Context-aware one, etc.) by passing `sessionStore = ...` to the client.

## Upgrading to 0.5.2

No API change. A non-2xx response now has its error envelope parsed, so `PikPakException` carries the real `errorCode` and `httpStatus` instead of `-1` and `HTTP 400`. A captcha rejection delivered with HTTP 400 is retried once with a fresh captcha token, and an invalid refresh token falls back to a password sign-in, as 0.4.x did; 0.5.0 and 0.5.1 surfaced both as failures.

## Upgrading to 0.5.1

Additive; nothing breaks. `VariantPreference`, `ResolvedVariant`, `FileDetail.resolveVariant`, `FileDetail.variant`, `PikPakClient.resolveVariant`, `PikPakClient.remoteSize` and the `rangeReader(fileId, mediaId)` overload are new. The existing `rangeReader(fileId)` is unchanged and equivalent to `mediaId = null`.

## Upgrading to 0.5.0

- `FileDetail.links` is a `Map<String, DownloadLink>` keyed by content type. `detail.octetStream` and `detail.downloadUrl` cover the previous `links.octetStream` use.
- `streamRangeFromUrl` takes a block and hands it a live `RangeStream`; the body is no longer read into memory before returning. `RangeStream` sizes are nullable instead of `-1`.
- `PikPakClient`'s primary constructor takes `passwordSupplier`; the `password: String` overload remains.
- `login()` before the first call is no longer required: every request logs in on demand and re-authenticates once on 401.
- `createUrlFile` returns `InstantComplete(raw, file)` instead of a singleton.
- Errors from `SessionStore.save` propagate instead of being swallowed.

## Local development

Copy `.env.example` to `.env` for the live integration tests:

```
PIKPAK_USERNAME=you@example.com
PIKPAK_PASSWORD=your-password
```

`.env` is git-ignored. The library itself never reads `.env` — only the integration test suite does.

Two tests go further than the API integration suite and are worth knowing about:

- `RangeReaderSmokeTest` exercises the playback path end to end: it submits a magnet, polls the task, then reads the produced file with one and with eight connections and asserts the fan-out wins. It defaults to a freely redistributable Arch Linux ISO; set `PIKPAK_SMOKE_MAGNET` to read from something else and `PIKPAK_SMOKE_FOLDER` to change where it lands.
- `CdnNetworkProbeTest` measures the CDN itself (HTTP version, per-connection rate, concurrency cap, idle survival) and writes `build/cdn-probe-report.txt`. It only runs with `PIKPAK_PROBE=1`.

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
