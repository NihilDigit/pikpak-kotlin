# pikpak-kotlin

A Kotlin Multiplatform SDK for [PikPak](https://mypikpak.com/) cloud storage.

The goal is to give Kotlin/JVM, Android, and (eventually) Kotlin Native consumers a small, atomic, well-typed surface over the PikPak HTTP API, with the painful parts (token lifecycle, captcha refresh, OSS signing, GCID hashing, rate limiting, retry) abstracted away.

## Status

v0.1.0. All public functions verified end-to-end against the live PikPak API on JVM; native targets compile clean but lack runtime test coverage. Surface is not yet stable.

## Design

- **Atomic**: every endpoint is a focused `suspend` extension function on `PikPakClient`. No hidden orchestration unless you opt in.
- **Hard problems abstracted**: session persistence, `access_token` refresh, captcha re-auth on `error_code=9`, exponential-backoff retry, token-bucket rate limiting, GCID content hashing, and OSS HMAC-SHA1 signing all run automatically.
- **Out of scope**: multi-account pools, sync/backup engines, recursive cleanup heuristics, CLIs. These are easy to build on top — the SDK does not bake them in. (For example, multi-account rotation is just `listOf(client1, client2).random()`.)
- **Multiplatform**: core code lives in `commonMain`, depending only on multiplatform libraries (Ktor, kotlinx.serialization, kotlinx.coroutines, kotlinx.datetime, kotlinx-io, KotlinCrypto). Targets shipped: JVM, iOS (x64/arm64/simulatorArm64), macOS (x64/arm64), Linux x64, Windows x64. Each platform uses its native HTTP engine — OkHttp on JVM, Darwin on Apple, CIO on Linux/Windows.

## API surface

```kotlin
val client = PikPakClient(
    account = "you@example.com",
    password = "your-password",
    // sessionStore = FileSessionStore() by default (~/.config/pikpak-kotlin)
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
client.getDeepFolderId(parentId, "a/b/c")    // resolve a path
client.getPathFolderId("/a/b/c")              // shorthand for above from root
client.getOrCreateDeepFolderId(parent, path) // mkdir -p

// Mutations
client.createFolder(parentId, name)
client.rename(fileId, newName)
client.deleteFile(fileId)                     // moves to trash (PikPak is soft-delete)

// Transfer
client.download(fileId, destPath)             // resumable, retries, byte-verified
client.downloadFromUrl(url, dest, expected)   // when you already have a signed URL

client.upload(parentId, sourcePath)           // GCID-hashed, instant-upload aware,
                                              // OSS multipart with HMAC-SHA1 signing

client.createUrlFile(parentId, "https://...")  // PikPak's offline-download queue

client.logout()                               // forget cached tokens
client.close()                                // close internal HTTP client
```

`SessionStore` defaults to a JSON file at `~/.config/pikpak-kotlin/session_<md5(account)>.json` on JVM. Provide your own (`InMemorySessionStore`, an Android-Context-aware one, etc.) by passing `sessionStore = ...` to the client.

## Configuration

For local development and integration tests, copy `.env.example` to `.env`:

```
PIKPAK_USERNAME=you@example.com
PIKPAK_PASSWORD=your-password
```

`.env` is git-ignored. The library itself never reads `.env` — it is only used by the integration test suite.

## Building & testing

Requires JDK 21. The repo includes a Gradle 8.11 wrapper:

```bash
./gradlew build
./gradlew jvmTest                     # unit + integration
./gradlew jvmTest --tests '*Hash*'    # unit only (no network)
```

Integration tests are opt-in via `.env` — they are skipped when `PIKPAK_USERNAME` is unset, so CI without credentials stays green.

The `IntegrationUploadTest` uploads a 4 KB random file to a freshly-created test folder, verifies the upload via listing + download + byte-comparison, then trashes the folder. `CleanupOrphansTest` is a one-shot housekeeping utility for stragglers from interrupted runs.

## Credit

The HTTP wire format, captcha salt cascade, and GCID content hash are derived from the excellent Go reference implementations:
- [52funny/pikpakcli](https://github.com/52funny/pikpakcli) — endpoint URLs, request/response shapes, captcha signing flow, OSS upload protocol
- [52funny/pikpakhash](https://github.com/52funny/pikpakhash) — GCID block-size table

All code in this repo is fresh Kotlin; only the public PikPak API behavior is shared.

## License

MIT — see [LICENSE](./LICENSE).
