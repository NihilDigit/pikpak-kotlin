# pikpak-kotlin

A Kotlin Multiplatform SDK for [PikPak](https://mypikpak.com/) cloud storage.

The goal is to give Kotlin/JVM, Android, and (later) Kotlin Native consumers a small, atomic, well-typed surface over the PikPak HTTP API, with the painful parts (token lifecycle, captcha refresh, rate limiting, retry) abstracted away.

## Status

Pre-alpha. Surface is not stable.

## Design

- **Atomic**: every endpoint maps to a small, focused `suspend` function on `PikPakClient`. No hidden orchestration unless you opt in.
- **Hard problems abstracted**: session persistence, `access_token` refresh, captcha re-auth on `error_code=9`, exponential-backoff retry, and a token-bucket rate limiter run automatically.
- **Out of scope**: multi-account pools, sync/backup engines, CLIs. These are easy to build on top — the SDK does not bake them in.
- **Multiplatform**: core code lives in `commonMain`, depending only on multiplatform libraries (Ktor, kotlinx.serialization, kotlinx.coroutines, kotlinx-io, KotlinCrypto). MVP ships JVM only; iOS/native targets are a build-script change away.

## Quick start

```kotlin
val client = PikPakClient(
    account = "you@example.com",
    password = "your-password",
)
client.login()                 // reuses cached session if still valid
val quota = client.getQuota()  // GET /drive/v1/about
val files = client.listFiles() // root folder
```

`SessionStore` defaults to a JSON file at `~/.config/pikpak-kotlin/session_<md5(account)>.json` on JVM. Provide your own (`InMemorySessionStore`, `AndroidContextSessionStore`, etc.) by passing `sessionStore = ...` to the client.

## Configuration

For local development, copy `.env.example` to `.env`:

```
PIKPAK_USERNAME=you@example.com
PIKPAK_PASSWORD=your-password
```

`.env` is git-ignored. The library itself never reads `.env` — it is only used by integration tests.

## Building

Requires JDK 21. The repo includes a Gradle build:

```bash
./gradlew build
```

Integration tests run against the real PikPak API and are opt-in via `.env` — they are skipped if `PIKPAK_USERNAME` is unset.

## Credit

The HTTP wire format and endpoint contracts in this SDK are derived from the excellent Go reference implementation [52funny/pikpakcli](https://github.com/52funny/pikpakcli). All code in this repo is fresh Kotlin; only the public PikPak API behavior is shared.

## License

MIT — see [LICENSE](./LICENSE).
