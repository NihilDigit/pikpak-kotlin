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
    // plus one engine: ktor-client-okhttp (JVM, Android), ktor-client-darwin (iOS),
    // or any other Ktor engine.
}
```

Gradle resolves the per-target artifact from the module metadata. Android ships as an AAR under the `pikpak-kotlin-android` artifactId and is picked up by a KMP consumer declaring `androidTarget()`.

## Platforms

| Target              | Release CI   | HTTP engine |
| ------------------- | ------------ | ----------- |
| `jvm`               | runtime test | OkHttp      |
| `android`           | compile-only | OkHttp      |
| `iosSimulatorArm64` | runtime test | Darwin      |
| `iosArm64`          | compile-only | Darwin      |

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

No bytes move, but the call is charged 15 % of the file's size against the account's monthly upload allowance (`getTransferQuota`). That makes it the fast path for a file or two and the expensive one for a whole pack — see [Offline download](#offline-download).

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
client.getQuota()                                      // storage: GET /drive/v1/about
client.getTransferQuota()                              // monthly offline, downstream and upload allowances
client.getUserProfile()                                // nickname, avatar, masked contact details
client.getVipInfo()                                    // membership tier and expiry

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
client.deleteFile(fileId)                              // permanent; bypasses the trash
client.batchTrash(listOf(id1, id2))                    // recoverable for 30 days
client.batchDelete(listOf(id1, id2))                   // bypasses the trash
client.batchCopy(listOf(id1, id2), toParentId)         // a task; small copies are done on return
client.emptyTrash()

client.starFiles(listOf(id1, id2))
client.unstarFiles(listOf(id1, id2))
client.listStarred()                                   // every starred item on the drive
file.isStarred                                         // from a listing's tags
```

Every file carries its content hash in `FileStat.hash`, offline-download products included, and `params.url` holds the magnet that produced it. A star shows only as a `STAR` entry in a listing's `tags`: the `starred` field of the detail response stays false, so read `FileStat.isStarred`. `listStarred` lists the whole drive when `parentId` is `*`, and a folder's direct children otherwise. Folder ids are memoized on the client; `invalidateFolderId` drops one subtree and `clearFolderIdCache` drops all of them.

## Play history

```kotlin
client.listPlayHistory()                               // newest first, 100 a page
client.reportPlay(fileId, positionSeconds = 754, durationSeconds = 1420)
client.deleteEvents(listOf(eventId))
client.clearEvents(listOf(EventType.PLAY))
client.listEvents(listOf(EventType.UPLOAD, EventType.RESTORE))
```

Play history is the one the official clients keep, so a position reported here resumes there and the other way round. A file has one play event: reporting again overwrites its position, smaller or not, and moves it to the top. The server drops a report that follows the previous one for the same file by less than a few seconds and still answers `{}`, so report no more often than every five seconds, as the web client does. Each event carries the file it refers to as a full `FileStat`. An unfiltered event listing holds uploads and restores but no plays.

## Sharing

```kotlin
val share = client.createShare(listOf(fileId), requirePassCode = true)   // share.shareUrl, share.passCode
client.listMyShares()
client.deleteShares(listOf(share.shareId))

val shareId = shareIdFromUrl("https://mypikpak.com/s/VO...") ?: return
val info = client.getShareInfo(shareId, passCode = "zq47")               // top level, and a pass code token
client.listShareFiles(shareId, info.passCodeToken, parentId = folderId)  // any folder inside
client.restoreShare(shareId, info.passCodeToken, fileIds, toParentId)    // a task; poll getTask
```

Reading a share needs no login. An unreadable one still answers HTTP 200 and says why in its status — pass code missing or wrong, share cancelled — so the read calls throw `ShareUnavailableException` rather than return an empty folder. `GET /share` ignores `parent_id`; folders below the top level go through `listShareFiles` with the token `getShareInfo` returned. A share of your own cannot be restored into your own drive (`file_restore_own`). `restoreShare` follows the request a captured web session sent and has not been run against a live share.

## Archives

```kotlin
client.listArchive(file.id, file.hash, path = "", password = "")         // one level; folder paths end in "/"
val task = client.decompressArchive(file.id, file.hash, toParentId, paths = listOf("Extras/"))
client.getDecompressProgress(task.taskId)                                // 0-100; fileId is the new folder

val tree = file.archiveTree ?: return                                    // after the first decompress
client.listArchiveTreePaged(tree)                                        // browse like a folder
client.getArchiveTreeFile(tree, entryId)                                 // signed link to one entry
client.copyFromArchiveTree(tree, entryIds, toParentId)                   // extract the picked entries
```

zip, encrypted zip, 7z and rar list and extract; tar does not. Both paths want the password even when only the contents are encrypted and the names are not, and throw `ArchivePasswordException` without it. An archive's browsable tree appears only after it has been decompressed once. `packFolder` and `unpackFolder` are not compression: they turn a folder into one `.tar` file in place, same id, so it downloads as a single file, and back.

## Searching

```kotlin
client.searchFiles("ep01", parentId)                   // one folder deep
client.searchFilesRecursive("ep01").collect { hit ->   // the whole subtree, streamed
    println("${hit.path} (${hit.file.id})")
}
client.searchFilesRecursiveList("ep01", limits = RecursiveSearchLimits(maxDepth = 3))
client.listTrash()
```

PikPak has no name search: `name` is refused as a filter field on `/drive/v1/files` under every operator, `q` and `search_text` are accepted and ignored, and no separate search endpoint exists. A subtree search is therefore one listing request per folder, walked breadth-first on the client. `RecursiveSearchLimits` bounds depth, folder count and wall-clock time; exhausting any of them ends the flow with the hits found so far rather than throwing. Each hit carries the folder names between the search root and itself, because a drive-wide search otherwise returns a list of identical names.

## Uploading

```kotlin
client.upload(parentId, sourcePath)
```

The GCID hash goes up first. If PikPak recognises it the upload is over — `UploadResult.instantUpload` is true and no bytes moved. Otherwise the file goes to Aliyun OSS as a signed multipart upload.

Hashing reads the whole file. For content PikPak may already hold, 60 KB is enough: the Xunlei CID samples three 20 KB windows, and `gcidByCid` looks it up in PikPak's index, which covers content the account has never held.

```kotlin
val cid = XunleiCid.of(size) { offset, length -> readAt(offset, length) }
val gcid = client.gcidByCid(cid, size)
    ?: PikPakHash.fromSource(openSource(), size) { hashed -> showHashing(hashed) }
client.upload(parentId, name, size, gcid, open = ::openSource) { sent -> showSending(sent) }
```

A miss has to be hashed, not skipped: PikPak requires a GCID at upload but does not check it, and keeps a wrong one. Content that is not a file-system `Path`, such as an Android `content:` URI, goes through the same overload. A failed or cancelled upload aborts the OSS transfer and deletes the pending file the upload created.

An upload that should survive the process is three calls instead of one. `startUpload` creates the drive file, which stays visible in `PHASE_TYPE_PENDING` until the upload completes, and returns a serializable `UploadSession`; `continueUpload` asks OSS which parts arrived and sends the rest, from any process; `cancelUpload` removes it. The session carries OSS credentials that PikPak issues for 12 hours, after which the upload can only be cancelled.

```kotlin
when (val start = client.startUpload(parentId, name, size, gcid)) {
    is UploadStart.Instant -> done(start.fileId)
    is UploadStart.Pending -> save(start.session)
}
// later, possibly after a restart
client.continueUpload(session, open = { offset -> openSourceAt(offset) }) { sent -> showSending(sent) }
```

## Offline download

This path takes five to ten seconds on content PikPak already holds and minutes on content it has to fetch from the swarm, where `resolveMagnet` plus `instantCreate` answers in about a second. It is still the cheaper one for more than a file or two: an offline download is charged its size against the monthly offline allowance, an instant copy 15 % of its size against the much smaller upload allowance, which comes to about six times more per byte. It keeps the torrent's folder layout, under a subfolder named after the torrent.

```kotlin
when (val r = client.createUrlFile(parentId, "magnet:?xt=...")) {
    is CreateUrlResult.Queued          -> r.task.id
    is CreateUrlResult.InstantComplete -> r.file
}
var task = client.getTask(taskId)
while (task.phase !in TaskPhase.TERMINAL) { delay(3.seconds); task = client.getTask(taskId) }
task.fileId                                            // set once phase == TaskPhase.COMPLETE
client.listOfflineTasks()
client.retryOfflineTask(taskId)                        // back to PENDING, with a new fileId
client.deleteOfflineTasks(listOf(taskId))              // deleteFiles = false by default
client.clearOfflineTasks(listOf(TaskPhase.COMPLETE))   // what the web client's clear buttons do
client.pruneOfflineOutput(task, keep = setOf("01.mkv", "Extras/NCOP.mkv"))
```

A magnet always downloads whole. `pruneOfflineOutput` takes a completed task down to the files you name — paths as `resolveMagnet` reports them, which is how the output is laid out — and deletes the rest permanently. Paths it could not find come back in `missing`: PikPak drops some advertising files on its own.

The SDK deliberately owns no polling loop over these — when a task counts as done is the caller's call.

## Development

Requires JDK 21; the Gradle wrapper is included.

```bash
./gradlew jvmTest                     # unit tests plus the live integration suite
./gradlew jvmTest --tests '*Hash*'    # unit tests only, no network
./gradlew iosSimulatorArm64Test       # native runtime: GCID hash and captcha mock, macOS only
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

The Xunlei CID sampling and the `/drive/v1/resource/cid` lookup behind `gcidByCid` were first seen in [digbug82/PikPak_Enhancement_Master](https://github.com/digbug82/PikPak_Enhancement_Master), a userscript for the PikPak web client.

All code here is written in Kotlin; only the public PikPak API behaviour is shared.

## License

MIT, see [LICENSE](./LICENSE).
