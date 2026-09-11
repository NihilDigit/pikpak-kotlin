# pikpak-kotlin

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fnihildigit%2Fpikpak-kotlin%2Fmaven-metadata.xml&label=Maven%20Central&logo=kotlin&logoColor=white&color=7F52FF)](https://central.sonatype.com/artifact/io.github.nihildigit/pikpak-kotlin)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/NihilDigit/pikpak-kotlin)

A Kotlin Multiplatform SDK for [PikPak](https://mypikpak.com/) cloud storage.

Every endpoint is a `suspend` extension function on `PikPakClient`. Session persistence, token refresh, captcha re-authentication, retry, rate limiting, GCID hashing and OSS upload signing happen inside the client; account pools, sync engines and CLIs are left to the caller.

```kotlin
val client = PikPakClient(account = "you@example.com", password = "...")

client.listFiles(parentId = "")                       // root folder, follows pagination
val id = client.getOrCreateDeepFolderId("", "anime/2026")
client.upload(id, sourcePath)                         // GCID-hashed, instant when PikPak has the bytes
client.rangeReader(fileId).asRangeSource()
    .downloadTo(destPath, totalSize)                  // parallel, resumable, byte-verified

when (val r = client.createUrlFile(id, "magnet:?xt=...")) {
    is CreateUrlResult.Queued -> r.task.id            // poll with getTask()
    is CreateUrlResult.InstantComplete -> r.file      // PikPak already had this URL
}
```

The full API reference is generated from this repository on [DeepWiki](https://deepwiki.com/NihilDigit/pikpak-kotlin).

## Installation

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.nihildigit:pikpak-kotlin:0.5.2")

    // Ktor is compileOnly in the SDK so it never changes the Ktor version you pinned.
    implementation("io.ktor:ktor-client-core:<your-ktor-version>")
    implementation("io.ktor:ktor-client-content-negotiation:<your-ktor-version>")
    implementation("io.ktor:ktor-serialization-kotlinx-json:<your-ktor-version>")
    // plus one engine: ktor-client-okhttp (JVM, Android), ktor-client-darwin (Apple),
    // ktor-client-cio (Linux, Windows), or any other Ktor engine.
}
```

Gradle resolves the per-target artifact from the module metadata. Android ships as an AAR under the `pikpak-kotlin-android` artifactId and is picked up by a KMP consumer declaring `androidTarget()`.

## Platforms

| Target              | Release CI   | HTTP engine |
| ------------------- | ------------ | ----------- |
| `jvm`               | runtime test | OkHttp      |
| `android`           | compile-only | OkHttp      |
| `linuxX64`          | runtime test | CIO         |
| `linuxArm64`        | compile-only | CIO         |
| `mingwX64`          | compile-only | CIO         |
| `macosArm64`        | runtime test | Darwin      |
| `iosSimulatorArm64` | runtime test | Darwin      |
| `iosArm64`          | compile-only | Darwin      |
| `iosX64`            | compile-only | Darwin      |

A release is published only after every cell of this matrix passes. Runtime tests run on the targets that [Kotlin/Native's tier table](https://kotlinlang.org/docs/native-target-support.html) also tests upstream; the others have to compile.

`commonMain` depends only on multiplatform libraries: Ktor, kotlinx.serialization, kotlinx.coroutines, kotlinx.datetime, kotlinx-io and KotlinCrypto.

## Client

```kotlin
val client = PikPakClient(
    account = "you@example.com",
    passwordSupplier = { vault.read("pikpak") },  // called only when a full sign-in is needed
    // password = "..."                            // the simple form
    // sessionStore = FileSessionStore()           // ~/.config/pikpak-kotlin/session_<md5(account)>.json on JVM
    // rateLimiter = RateLimiter.Default           // 5 req/s, burst 5
    // retryPolicy = RetryPolicy.Default           // 3 attempts, exponential backoff
    // connectionBudget = 8                        // concurrent CDN connections per signed URL
)
client.prewarm()          // login plus one cheap call, so the captcha handshake is over before the user waits
client.sessionFlow        // StateFlow<Session?> for sign-in state
client.logout()           // forget cached tokens
client.close()
```

Calling `login()` first is optional: every request signs in on demand and re-authenticates once on a 401. A captcha rejection (`error_code=9`) is retried with a fresh captcha token, an invalid refresh token falls back to a password sign-in, and concurrent callers that hit the same expiry share one refresh.

On Android the default session directory is best-effort; pass `FileSessionStore(dir = Path(context.filesDir.absolutePath, "pikpak-kotlin"))` for a stable location. `InMemorySessionStore` is available for tests.

Every `PikPakException` raised at the HTTP layer carries `errorCode`, `httpStatus`, `headers` and `rawBody`, parsed from the server's error envelope.

## Files and folders

```kotlin
client.getQuota()                                      // GET /drive/v1/about

client.listFiles(parentId = "")
client.listFilesPaged(parentId, pageToken = "", extraFilters = mapOf(FileFilter.kind(FileKind.FOLDER)))
client.getFile(fileId)                                 // FileDetail with download links
client.getFile(fileId).octetStream.expiresAt           // when the signed link stops working
client.getFolderId(parentId, name)                     // immediate child folder by name
client.getDeepFolderId(parentId, "a/b/c")
client.getPathFolderId("/a/b/c")                       // same, from the root
client.getOrCreateDeepFolderId(parentId, path)         // mkdir -p

client.createFolder(parentId, name)
client.rename(fileId, newName)
client.deleteFile(fileId)                              // to trash; PikPak is soft-delete
client.batchTrash(listOf(id1, id2))                    // recoverable for 30 days
client.batchDelete(listOf(id1, id2))                   // bypasses the trash
```

## Transfer

Downloading goes through a `RangeSource`, so it fans out across connections, honours the account budget and competes on priority with whatever else is reading:

```kotlin
source.downloadTo(dest, totalSize, priority = 1)       // resumable, parallel, ordered writes
client.downloadFromUrl(url, dest)                      // the same, with the reader built for you

client.streamRangeFromUrl(url, start, length) { range -> range.channel }   // live, not buffered

client.upload(parentId, sourcePath)                    // GCID hash, instant upload when PikPak has the bytes,
                                                       // otherwise OSS multipart with HMAC-SHA1 signing
```

One connection is available but has to be asked for by name, because it is rarely what you want — a single connection to this CDN is bounded by the round trip, not by the link:

```kotlin
client.downloadSingleConnection(fileId, dest)
client.downloadSingleConnectionFromUrl(url, dest, expectedSize)
```

These two take no slot from the account budget and do not refresh an expiring signature.

CDN and OSS bodies are consumed as they arrive; a range read costs no memory until it is read.

## Offline download

```kotlin
when (val r = client.createUrlFile(parentId, "magnet:?xt=...")) {
    is CreateUrlResult.Queued          -> r.task.id
    is CreateUrlResult.InstantComplete -> r.file
}
var task = client.getTask(taskId)                      // one task, without listing the table
while (task.phase !in TaskPhase.TERMINAL) { delay(3.seconds); task = client.getTask(taskId) }
task.fileId                                            // set once phase == TaskPhase.COMPLETE
client.listOfflineTasks()
```

## Random access

`RangeReader` serves arbitrary byte ranges of one remote file, which is what playback needs and what a download call cannot give. It keeps concurrency within the connections a signed URL accepts, hands a contended slot to the highest-priority read, refreshes the URL when its signature expires, waits out a 503, and resumes from the last delivered byte when a body stops early. It caches nothing; scheduling and disk layout stay with the caller.

```kotlin
val reader = client.rangeReader(fileId)               // refreshes its own URL through getFile
reader.read(start = 0, length = 1 shl 20, priority = 10) { channel ->
    // live channel; nothing has been buffered
}
reader.stats.value                                    // active reads, bytes, refreshes, throttles
reader.close()
```

Construct `RangeReader` directly with your own `urlProvider` when the URL does not come from `getFile`. `downloadFromUrl` is a thin wrapper over the same class. A range past the end of the file ends at EOF. A URL the CDN rejects with 401 or 403 surfaces as `UrlExpiredException` from the single-request calls; `RangeReader` asks `urlProvider` for a fresh one instead.

The connection budget of 8 is a measured property of PikPak's CDN: one signed URL accepts 8 concurrent connections and answers the ninth with 503. Because every engine's default per-host limit is lower (OkHttp 5, Darwin 4 or 6), the SDK builds a separate CDN client per platform whose limit matches the budget. Pass `cdnHttpClient = PikPakClient.tunedCdnClient()` when you inject your own API client and still want the tuned pool.

Per-connection throughput is bounded by the round trip rather than by a server-side rate limit: the same file on the same account served 0.94 MB/s per connection over one route and 4.7 MB/s over a shorter one. Opening 8 is the difference between unusable and fine on a distant route, and merely redundant on a close one, which is why the budget is a constant rather than something measured at runtime. Wider fan-out means more links, not more connections per link.

A second, wider limit sits above it. `accountConnectionBudget`, 16 by default, caps concurrent connections across every file one client reads. Measured 2026-09-10 against a 54-file pack on a dozen edge hosts: 16 were admitted without a refusal, while 32, 64 and 96 requested all settled at exactly 20 admitted, refused either with 503 or by dropping the TLS handshake. Priority is honoured at both levels, so a playback read outranks a background download without either side knowing about the other, and nothing needs to reserve slots — the per-URL cap already stops one file from taking more than half.

### Transcoded variants

A video file exists as the uploaded original plus, once PikPak has transcoded it, MPEG-TS variants at 1080P, 720P and 480P. Each is a separate resource with its own signed link and byte count, so the choice is made once and then locked by `mediaId`.

```kotlin
val v = client.resolveVariant(fileId, VariantPreference.Resolution("720P"))
v.mediaId                                             // null for the original; persist this
val size = v.sizeBytes ?: client.remoteSize(v.link.url)

val reader = client.rangeReader(fileId, v.mediaId)    // reopens the same bytes later
```

`resolveVariant` falls back to the original when the requested resolution is absent or still transcoding. The fallback happens at resolve time only: `rangeReader(fileId, mediaId)` refreshes an expired link with the same `mediaId` and fails with `PikPakException` when that variant is gone, rather than reading another variant's bytes at an offset the caller committed to. `FileDetail.variant(mediaId)` is the same lookup without a network call.

Transcodes carry no embedded subtitles and a lower audio bitrate than the original. Their length is absent from the file metadata; `remoteSize` derives it from a one-byte range probe's `Content-Range`.

## Playing a magnet

A magnet reaches playable bytes without an offline download. PikPak stores content by hash, and both halves of that are reachable: a magnet can be resolved to the gcid of every file inside it, and a gcid creates a file object in one request and no bytes.

```kotlin
// 1. Resolve. No task is created, nothing lands in the drive. 150-300 ms.
val resource = client.resolveMagnet(magnet)
    ?: return NotOnPikPak            // not in PikPak's index; use another source

val episode = resource.files.first { it.name.contains("[01]") }
val gcid = episode.gcid ?: return NotOnPikPak   // this one file is not indexed

// 2. Turn the gcid into a file object. Zero bytes transferred, ~200 ms.
//    Name and location are yours; nothing creates the pack subfolder an
//    offline task would.
val fileId = client.instantCreate(episode, parentId = folderId)

// 3. A handle. The gcid is the identity, so an expired signature and a file
//    object that has been swept away are both absorbed without the caller.
val handle = PikPakFileHandle(
    client = client,
    gcid = gcid,
    size = episode.size,
    name = episode.name,
    initialFileId = fileId,          // optional: saves the first instantCreate
)

// 4. Play. Blocks are cached by offset and read ahead of the read position;
//    a seek cancels only the requests outside the new window.
val stream = handle.openStream()
stream.seekTo(0)
stream.read(buffer, 0, buffer.size)
```

Measured end to end on 2026-09-11 — magnet in, first CDN byte out — at 1.0 to 1.2 seconds across a 48-file BD pack, an 11-episode season pack and a single episode. An offline download reaches the same files in five to ten seconds when PikPak already holds the content, and in minutes when it has to fetch from the swarm.

Content PikPak has never seen fails in 146 ms: `resolveMagnet` returns null, or the one file you want has a null `gcid`. There is no fallback to an offline download here, because a caller that has another source should use it rather than wait — `createUrlFile` and `getTask` are still there for one that does not.

`instantCreate` leaves a real file object in the drive. Deleting it after reading is possible — a signed link outlives the file it came from — but a kept object is the cheaper path: refreshing an expired link is then one request rather than two, and a second playback of the same episode skips resolution entirely. Eviction is the caller's policy.

### Transcodes

`handle.variants()` reports what PikPak holds. A transcode exists only if someone has played that content on PikPak before; it belongs to the gcid, so an instant upload of an already-transcoded gcid arrives with every variant present. Nothing schedules one — a fresh file measured at t=0, +15 s, +60 s and +120 s, including after its bytes were read, still had only the original. A caller that finds no transcode should read the original rather than wait.

### Downloading to disk

`downloadTo` is the same handle, written to a file. Progress is the file's own length, so an interrupted download resumes from what is on disk and needs no bitmap. Writes are ordered but the fetches slide: the connections stay busy instead of draining at a round boundary, which matters most on the weak links this fan-out exists for.

```kotlin
handle.downloadTo(localPath, totalSize = episode.size, priority = 1)
```

It is a suspend function that throws; whether a background cache keeps retrying is the caller's policy. Playback and a download compete for the account budget, not for each other's slots — give the download a lower `priority` (1 against a playback read's 10) and the gate does the rest.

Both consumers take a `RangeSource` rather than a `RangeReader`, which is what lets the handle swap the reader underneath them. `reader.asRangeSource()` is the other accepted shape, for a file whose id will not move.

## Development

Requires JDK 21; the Gradle wrapper is included.

```bash
./gradlew jvmTest                     # unit tests plus the live integration suite
./gradlew jvmTest --tests '*Hash*'    # unit tests only, no network
./gradlew linuxX64Test                # native runtime: GCID hash and captcha mock
./gradlew macosArm64Test              # same, on Apple silicon
./gradlew mingwX64Test                # same, on Windows
```

The live integration tests read credentials from a git-ignored `.env` (copy `.env.example`); the library itself never reads it. Without `PIKPAK_USERNAME` they skip themselves, so the release workflow stays green.

```
PIKPAK_USERNAME=you@example.com
PIKPAK_PASSWORD=your-password
```

Two tests go beyond the API suite:

- `RangeReaderSmokeTest` exercises the playback path end to end: it submits a magnet, polls the task, then reads the produced file with one and with eight connections and asserts the fan-out wins. It defaults to a freely redistributable Arch Linux ISO; `PIKPAK_SMOKE_MAGNET` and `PIKPAK_SMOKE_FOLDER` change the source and the destination folder.
- `CdnNetworkProbeTest` measures the CDN itself (HTTP version, per-connection rate, concurrency cap, idle survival) and writes `build/cdn-probe-report.txt`. It runs only with `PIKPAK_PROBE=1`.

Releases are cut by pushing a `v*.*.*` tag; see [RELEASING.md](./RELEASING.md). [`release.yml`](./.github/workflows/release.yml) runs the platform matrix above and publishes signed artifacts to Maven Central through the Sonatype Central Portal only if every cell passes. There is no CI on push.

## Credit

The HTTP wire format, captcha salt cascade and GCID content hash follow the Go reference implementations:

- [52funny/pikpakcli](https://github.com/52funny/pikpakcli): endpoint URLs, request and response shapes, captcha signing flow, OSS upload protocol
- [52funny/pikpakhash](https://github.com/52funny/pikpakhash): GCID block-size table

All code here is written in Kotlin; only the public PikPak API behaviour is shared.

## License

MIT, see [LICENSE](./LICENSE).
