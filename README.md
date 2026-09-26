# pikpak-kotlin

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fnihildigit%2Fpikpak-kotlin%2Fmaven-metadata.xml&label=Maven%20Central&logo=kotlin&logoColor=white&color=7F52FF)](https://central.sonatype.com/artifact/io.github.nihildigit/pikpak-kotlin)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Wiki](https://img.shields.io/badge/docs-wiki-7F52FF)](https://github.com/NihilDigit/pikpak-kotlin/wiki)

A Kotlin Multiplatform SDK for [PikPak](https://mypikpak.com/) cloud storage — JVM, Android and iOS.

- **Every endpoint is one `suspend` call.** Sessions, token refresh, captcha re-authentication, retries, rate limiting, content hashing and OSS upload signing happen inside the client.
- **Playback-grade reads.** A remote file read at any offset over as many connections as PikPak allows, with expiring links, vanished file objects and silent edge hosts handled underneath; streams share a block cache, prefetch in play order, and can keep what matters on disk.
- **A magnet to a playable file in about a second.** PikPak stores content by hash, and the SDK uses both halves of that: resolve a magnet to the hash of every file in it, and turn a hash into a file with no bytes transferred.
- **Built on measurements.** Connection limits, host behaviour, costs and quirks of the API were measured, dated, and written down in the [wiki](https://github.com/NihilDigit/pikpak-kotlin/wiki/Measurements).

```kotlin
val client = PikPakClient(account = "you@example.com", password = "...")

// A magnet against PikPak's content index: no task, nothing in the drive. Null means PikPak has never seen it.
val resource = client.resolveMagnet("magnet:?xt=urn:btih:...") ?: return
val episode = resource.files.first { it.name.endsWith(".mkv") }
val gcid = episode.gcid ?: return

// The hash becomes a file, in the folder and under the name you choose.
val folder = client.getOrCreateDeepFolderId("", "anime/2026")
val fileId = client.instantCreate(episode, parentId = folder)

// Read it at any offset, over eight connections.
val handle = PikPakFileHandle(client, gcid, episode.size, episode.name, initialFileId = fileId)
val buffer = ByteArray(64 * 1024)
handle.openStream().use { stream ->
    stream.seekTo(0)
    stream.read(buffer, 0, buffer.size)
}
```

## Installation

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.nihildigit:pikpak-kotlin:1.0.0")
    // Ktor is compileOnly in the SDK, so it never changes the Ktor you pinned: bring the core and one engine.
    implementation("io.ktor:ktor-client-core:<your-ktor-version>")
    implementation("io.ktor:ktor-client-okhttp:<your-ktor-version>")   // or ktor-client-darwin on iOS
}
```

Targets: `jvm`, `android` (AAR `pikpak-kotlin-android`), `iosArm64`, `iosSimulatorArm64`. On Android, pass a `SessionStore` — there is no default location there.

## Documentation

The [project wiki](https://github.com/NihilDigit/pikpak-kotlin/wiki) is the manual:

| | |
| --- | --- |
| [Getting Started](https://github.com/NihilDigit/pikpak-kotlin/wiki/Getting-Started) | Clients, sessions, logging in, injected HTTP clients, budgets |
| [Best Practices](https://github.com/NihilDigit/pikpak-kotlin/wiki/Best-Practices) | What works, learned building a real client, with the reasons |
| [Playback](https://github.com/NihilDigit/pikpak-kotlin/wiki/Playback) | Range reads, handles, streams, priorities, prefetch, disk caching, transcodes |
| [Magnets and Instant Create](https://github.com/NihilDigit/pikpak-kotlin/wiki/Magnets-and-Instant-Create) | The hash path, and what it costs |
| [Drive Operations](https://github.com/NihilDigit/pikpak-kotlin/wiki/Drive-Operations) | Files, trash, stars, search, shares, play history, archives |
| [Uploads and Offline Tasks](https://github.com/NihilDigit/pikpak-kotlin/wiki/Uploads-and-Offline-Tasks) | Resumable uploads, content hashes, offline downloads and pruning |
| [Errors and Retries](https://github.com/NihilDigit/pikpak-kotlin/wiki/Errors-and-Retries) | What throws what, and what the client retries by itself |
| [Architecture](https://github.com/NihilDigit/pikpak-kotlin/wiki/Architecture) · [Measurements](https://github.com/NihilDigit/pikpak-kotlin/wiki/Measurements) · [History](https://github.com/NihilDigit/pikpak-kotlin/wiki/History) | How it fits together, what PikPak was measured to do, and what was tried |

## Built with it

**[Piko](https://github.com/NihilDigit/piko)** — a PikPak client for Android, Windows and macOS, by the same author, and the SDK's most demanding user: a player that streams through a loopback proxy, a random-clip feed that starts in under two seconds and from disk in a quarter of one, downloads, resumable uploads, magnets and offline packs, shares and server-side archives. If you want to see any page of the wiki in working code, it is there.

## Development

JDK 21, Gradle wrapper included. `./gradlew jvmTest` runs the unit tests and, with PikPak credentials in a git-ignored `.env`, the live integration suite; without them the live tests skip. Details, probes and the release process are in [Development](https://github.com/NihilDigit/pikpak-kotlin/wiki/Development).

## Credit

The HTTP wire format, captcha salt cascade and content hash follow the Go reference implementations [52funny/pikpakcli](https://github.com/52funny/pikpakcli) (endpoints, request and response shapes, captcha signing, OSS upload) and [52funny/pikpakhash](https://github.com/52funny/pikpakhash) (the hash's block-size table). The Xunlei CID sampling and the `/drive/v1/resource/cid` lookup were first seen in [digbug82/PikPak_Enhancement_Master](https://github.com/digbug82/PikPak_Enhancement_Master). All code here is Kotlin; only PikPak's public API behaviour is shared.

## License

MIT, see [LICENSE](./LICENSE).
