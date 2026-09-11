# pikpak-kotlin

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fnihildigit%2Fpikpak-kotlin%2Fmaven-metadata.xml&label=Maven%20Central&logo=kotlin&logoColor=white&color=7F52FF)](https://central.sonatype.com/artifact/io.github.nihildigit/pikpak-kotlin)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/NihilDigit/pikpak-kotlin)

A Kotlin Multiplatform SDK for [PikPak](https://mypikpak.com/) cloud storage.

PikPak stores content by hash, and both halves of that are reachable: a magnet can be resolved to the hash of every file inside it without creating anything, and a hash creates a file object in one request and no bytes transferred. So a magnet reaches playable bytes in about a second, where an offline-download task takes five to ten on content PikPak already holds.

```kotlin
val client = PikPakClient(account = "you@example.com", password = "...")

// Parses the magnet against PikPak's content index. No task is created and
// nothing lands in the drive. Null means PikPak has never seen this content,
// which takes about 150 ms to find out — use another source.
val resource = client.resolveMagnet("magnet:?xt=urn:btih:...") ?: return
val episode = resource.files.first { it.name.endsWith(".mkv") }
val gcid = episode.gcid ?: return

// The hash becomes a file, in the folder and under the name you choose.
val folder = client.getOrCreateDeepFolderId("", "anime/2026")
val fileId = client.instantCreate(episode, parentId = folder)

// Read it at any offset, over eight connections, with an expiring signature
// and a swept-away file object both healed underneath.
val handle = PikPakFileHandle(client, gcid, episode.size, episode.name, initialFileId = fileId)
val buffer = ByteArray(64 * 1024)
handle.openStream().use { stream ->
    stream.seekTo(0)
    stream.read(buffer, 0, buffer.size)
}
```

Every endpoint is a `suspend` extension function on `PikPakClient`. Session persistence, token refresh, captcha re-authentication, retry, rate limiting, GCID hashing and OSS upload signing happen inside the client; account pools, sync engines and CLIs are left to the caller.

The full API reference is generated from this repository on [DeepWiki](https://deepwiki.com/NihilDigit/pikpak-kotlin). The measurements behind the design decisions are in [DESIGN-NOTES.md](./DESIGN-NOTES.md).

## Installation

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.nihildigit:pikpak-kotlin:0.6.0")

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

## From a magnet to a file

```kotlin
val resource = client.resolveMagnet(magnet)            // null when PikPak has never seen it
resource.name                                          // the torrent's own name
resource.files                                         // every leaf, nested paths kept

val file = resource.files.first { it.path == "specials/S00E01.mkv" }
file.gcid                                              // null when this one file is unindexed
client.instantCreate(file, parentId, name = file.name) // returns the new file id
```

`resolveMagnet` returns `null` rather than throwing, because "PikPak does not have this" is an ordinary answer to a query. It arrives in about 150 ms, so trying it and falling back to another source costs almost nothing. Network and authentication failures still throw `PikPakException`.

`files` keeps entries whose `gcid` is `null` — you need the whole tree to match an episode by name, and hiding the unindexed ones would turn "not in the index" into "not in the torrent".

`instantCreate` needs PikPak to already hold the content, which is exactly what a non-null `gcid` says. Name and parent are yours, so nothing creates the pack subfolder an offline task would.

## Reading a file

`RangeSource` is the type the SDK is built around: one remote file, readable at any offset, with priority honoured when connections are contended.

```kotlin
// Fixed URL, healed only for signature expiry.
val source = client.rangeReader(fileId).asRangeSource()

// Or a handle, where the gcid is the identity and the file id is a cache of
// it, so a file object deleted under you is rebuilt rather than fatal.
val handle = PikPakFileHandle(client, gcid, size, name, initialFileId = fileId)

source.readBytes(start = 0, length = 1 shl 20, priority = 10)
source.read(start, length, priority = 10) { channel -> /* live, nothing buffered */ }
```

Reads share a per-file budget of 8 connections and an account-wide budget of 16, both measured rather than chosen: one signed URL answers its ninth concurrent connection with 503, and an account that asks for 32 gets about 20. A higher `priority` wins a contended slot at both levels, so a playback read outranks a background download without either side knowing the other exists.

Per-connection throughput is bounded by the round trip, not by a server-side limit. On a throttled link, one connection carried 0.27 MB/s and eight carried 1.34; on a short route one connection already saturates the line and the fan-out wins nothing. That is why the budget is a constant instead of something estimated at runtime — guessing low on a bad link is the failure the fan-out exists to prevent.

Because every Ktor engine's default per-host limit is lower than 8 (OkHttp 5, Darwin 4 or 6), the SDK builds its own CDN client per platform. Pass `cdnHttpClient = PikPakClient.tunedCdnClient()` when you inject your own API client and still want that pool.

### Playback

`openStream` adds an LRU block cache and read-ahead on top of a source. Blocks are keyed by offset rather than held in a sliding window, because a player opening a Matroska file reads the head, seeks to the tail for the Cues, then seeks back — a window discards one end on each of those steps.

```kotlin
val stream = handle.openStream()                       // size probed for transcodes
stream.seekTo(position)
stream.read(buffer, offset, length)                    // at most one block per call
stream.bytesRemaining
stream.lastSeekLatency
stream.close()                                         // safe from any context
```

`read` and `seekTo` suspend; `close` does not, so a player can release its input from a lifecycle callback that has no coroutine. A seek cancels only the requests outside the new window.

### Downloading to disk

```kotlin
handle.downloadTo(dest, totalSize = file.size, priority = 1)
client.downloadFromUrl(url, dest)                      // same, with the reader built for you
```

Blocks are written in order, so the file is at every instant a valid prefix and its length is the progress: an interrupted download resumes from what is on disk and needs no bitmap. Only the writes are ordered — the fetches slide, so a slow block delays the write rather than the next request.

A single connection is available but has to be named, because it is rarely what you want:

```kotlin
client.downloadSingleConnection(fileId, dest)
client.downloadSingleConnectionFromUrl(url, dest, expectedSize)
```

These two take no slot from the account budget and do not refresh an expiring signature.

### Transcoded variants

A video exists as the uploaded original plus, once PikPak has transcoded it, MPEG-TS variants at 1080P, 720P and 480P. Each is a separate resource with its own signed link and byte count, so the choice is made once and then locked by `mediaId`.

```kotlin
handle.variants()                                      // empty beyond the original means no transcode
val v = client.resolveVariant(fileId, VariantPreference.Resolution("720P"))
val size = v.sizeBytes ?: client.remoteSize(v.link.url)
val reader = client.rangeReader(fileId, v.mediaId)     // reopens the same bytes later
```

Transcodes belong to the content, not to the file object: an instant-created file whose hash has been transcoded before arrives with every variant already present. Nothing schedules one, though, so a caller that finds only the original should read it rather than wait.

`resolveVariant` falls back to the original when the requested resolution is absent or still transcoding, and that is the only place the fallback happens. Refreshing an expired link keeps the same `mediaId` and fails when that variant is gone, rather than serving another variant's bytes at an offset the caller has committed to.

Transcodes carry no embedded subtitles and a lower audio bitrate than the original, and their length is absent from the file metadata — `remoteSize` derives it from a one-byte range probe.

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

Every file carries its content hash in `FileStat.hash`, offline-download products included, and `params.url` holds the magnet that produced it. Folder ids are memoized on the client; `invalidateFolderId` drops one subtree and `clearFolderIdCache` drops all of them.

## Uploading

```kotlin
client.upload(parentId, sourcePath)
```

The GCID hash goes up first. If PikPak recognises it the upload is over — `UploadResult.instantUpload` is true and no bytes moved. Otherwise the file goes to Aliyun OSS as a signed multipart upload.

## Offline download

The endpoints are here, but reach for `resolveMagnet` first: this path takes five to ten seconds on content PikPak already holds, minutes on content it has to fetch from the swarm, and it drops the torrent into a subfolder of its own naming.

```kotlin
when (val r = client.createUrlFile(parentId, "magnet:?xt=...")) {
    is CreateUrlResult.Queued          -> r.task.id
    is CreateUrlResult.InstantComplete -> r.file
}
var task = client.getTask(taskId)
while (task.phase !in TaskPhase.TERMINAL) { delay(3.seconds); task = client.getTask(taskId) }
task.fileId                                            // set once phase == TaskPhase.COMPLETE
client.listOfflineTasks()
```

The SDK deliberately owns no polling loop over these — when a task counts as done is the caller's call.

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

Three tests go beyond the API suite, each opt-in:

- `RangeReaderSmokeTest` exercises the playback path end to end against the live CDN. It defaults to a freely redistributable Arch Linux ISO; `PIKPAK_SMOKE_MAGNET` and `PIKPAK_SMOKE_FOLDER` change the source and the destination folder.
- `WeakLinkSmokeTest` measures what the fan-out is worth on the current link — one connection against eight, alternating, plus cold-open and seek latency. Needs `PIKPAK_WEAKLINK=1`, and is meant to be run while deliberately throttling the connection. It asserts nothing about the ratio: on a short route "no gain" is the correct answer.
- `CdnNetworkProbeTest` measures the CDN itself (HTTP version, per-connection rate, concurrency cap, idle survival) and writes `build/cdn-probe-report.txt`. Needs `PIKPAK_PROBE=1`.

Releases are cut by pushing a `v*.*.*` tag; see [RELEASING.md](./RELEASING.md). [`release.yml`](./.github/workflows/release.yml) runs the platform matrix above and publishes signed artifacts to Maven Central through the Sonatype Central Portal only if every cell passes. There is no CI on push.

## Credit

The HTTP wire format, captcha salt cascade and GCID content hash follow the Go reference implementations:

- [52funny/pikpakcli](https://github.com/52funny/pikpakcli): endpoint URLs, request and response shapes, captcha signing flow, OSS upload protocol
- [52funny/pikpakhash](https://github.com/52funny/pikpakhash): GCID block-size table

All code here is written in Kotlin; only the public PikPak API behaviour is shared.

## License

MIT, see [LICENSE](./LICENSE).
