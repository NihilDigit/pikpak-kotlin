# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`pikpak-kotlin` is a Kotlin Multiplatform SDK for the [PikPak](https://mypikpak.com/) cloud-storage HTTP API. HTTP wire format and GCID hash copied from [52funny/pikpakcli](https://github.com/52funny/pikpakcli) + [52funny/pikpakhash](https://github.com/52funny/pikpakhash); all code is fresh Kotlin.

Published as `io.github.nihildigit:pikpak-kotlin` on Maven Central. Source of truth: <https://github.com/NihilDigit/pikpak-kotlin>.

## Architecture

- **Atomic endpoints.** Every PikPak operation is one focused `suspend` extension function on `PikPakClient`. No hidden orchestration.
- **Hard problems abstracted.** Session persistence, `access_token` refresh, captcha re-auth on `error_code=9`, exponential-backoff retry, token-bucket rate limiting, GCID hashing, and OSS HMAC-SHA1 signing all run automatically inside the client.
- **Session externalised.** `SessionStore` is an interface — `FileSessionStore` (default on JVM), `InMemorySessionStore`, or bring your own. A `PikPakClient` is cheap to construct; multi-account scenarios just hold a list of clients.

### Code layout

- `src/commonMain/` — everything non-platform-specific. Depends only on KMP-capable libs: Ktor, kotlinx.serialization, kotlinx.coroutines, kotlinx.datetime, kotlinx-io, KotlinCrypto.
- `src/jvmMain/` — `defaultSessionDir()` actual + OkHttp engine binding.
- `src/nativeMain/` — `defaultSessionDir()` actual for all native targets (posix `getenv`).
- `src/commonTest/` — unit tests that run on every target (gcid vectors, MockEngine-based captcha retry path).
- `src/jvmTest/` — live PikPak API integration tests, opt-in via `.env`.
- `internal/` sub-package — implementation helpers not meant for consumers.

### Request pipeline (read before touching the auth/retry code)

Public endpoints are `suspend` extension functions (`Endpoints.kt`, `FolderEndpoints.kt`, `UploadEndpoint.kt`, `DownloadEndpoint.kt`, `UrlOfflineEndpoint.kt`). They delegate to `PikPakClient.http.request(...)` with an optional `captchaAction`. `HttpEngine` (`internal/HttpEngine.kt`) handles rate-limit acquisition, standard PikPak headers, one-shot captcha refresh on `error_code=9` by calling back into `AuthApi`, and exponential-backoff retry on transient transport errors. `AuthApi` (`internal/AuthApi.kt`) owns the session state machine — cache reuse → refresh_token → full signin — plus the salt-cascade captcha signing flow. `sendRaw` deliberately skips PikPak-specific headers so the OSS CDN (upload + download) doesn't get them and 406 us.

### Shipped targets

`jvm`, `android` (AAR, artifactId `pikpak-kotlin-android`), `linuxX64`, `linuxArm64`, `mingwX64`, `iosX64`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`.

Which targets get runtime-tested in CI follows [Kotlin/Native's tier table](https://kotlinlang.org/docs/native-target-support.html): we run `*Test` for targets whose "Running Tests" column is ✅ upstream (`jvm`, `linuxX64`, `macosArm64`, `iosSimulatorArm64`), and only `compileKotlin<Target>` for the rest (`android` via `:assembleAndroidMain`, `linuxArm64`, `mingwX64`, `iosArm64`, `iosX64`). When adding a target, pick the cell type by what upstream Kotlin itself runs — don't shoulder runtime validation the toolchain vendor won't commit to.

`macosX64` is deliberately not shipped (deprecated upstream from Kotlin 2.3.20). Add a target back only when a real user turns up.

## Conventions

- **Atomic SDK philosophy** (non-negotiable). Stay out of: multi-account pools, sync engines, recursive cleanup heuristics, CLIs. These belong on top of the SDK, not inside. `PikPakClient` is atomic enough that a user can build all of them in a few lines.
- **Don't add machine-specific or region-specific config to the OSS repo.** Hardcoded JDK paths, aliyun/tencent mirrors, personal `~/.m2` credentials — none of these go in `gradle.properties` or `settings.gradle.kts`. Per-contributor overrides live in `~/.gradle/gradle.properties`.
- **No helper facades for Java consumers.** Every public function is `suspend`. Pure-Java callers have to deal with `Continuation` themselves. If a facade is ever needed, it's a follow-up discussion, not a quiet commit.
- **Don't default to `runCatching`/swallowing errors** around SDK calls. Throw `PikPakException` and let the caller decide.
- **Integration tests self-skip via `Assumptions.assumeTrue()`** when `PIKPAK_USERNAME` isn't in `.env`. Keep that pattern for any new live-API test.
- **Destructive tag ops on unreleased tags are OK.** Force-updating a `v*` tag that never made it to Maven Central is acceptable. Once a version is live on Maven Central, it's immutable — bump instead.

## Development loop

JDK 21 required. Gradle 8.11 wrapper included. No separate lint step — `ktlint` and Detekt are not wired in; keep style consistent with the surrounding code.

```bash
./gradlew build                                 # compile + full test suite
./gradlew jvmTest                               # JVM unit + live integration (needs .env)
./gradlew linuxX64Test                          # native runtime: gcid + captcha mock
./gradlew compileTestKotlinLinuxX64             # native commonTest compile only — fastest pre-push KMP check
./gradlew assemble -Pkotlin.native.ignoreDisabledTargets=true   # every target buildable on this host
./gradlew publishToMavenLocal                   # verify publish wiring (no creds needed)

# Single test (FQCN, or a wildcard on method name)
./gradlew jvmTest --tests 'io.github.nihildigit.pikpak.IntegrationUploadTest'
./gradlew jvmTest --tests '*CaptchaRetry*'

# Single native test (uses kotlin-test, same pattern)
./gradlew linuxX64Test --tests 'io.github.nihildigit.pikpak.PikPakHashTest.hello matches reference'
```

Live integration tests create/delete folders in the test account. `IntegrationUploadTest` + `CleanupOrphansTest` handle their own cleanup; if a run crashes mid-test, `CleanupOrphansTest` trashes leftover `pikpak-kotlin-*` folders on the next run.

## CI philosophy

CI here is a **release gate, not a per-commit quality check** — dev-time testing happens locally. `release.yml` triggers only on `v*.*.*` tag push; platform verification is a `strategy.matrix` over every shipped target (one cell per target, one Gradle task per cell), and `publish` declares `needs: [test]` so Maven Central never sees an artifact whose matrix cell didn't pass. When you add or remove a target, the matrix `include:` list is the single place to edit.

There is intentionally no `ci.yml` on push/PR. If you find yourself adding one, raise it with the maintainer first.

## Releasing

Full step-by-step in [RELEASING.md](./RELEASING.md). Short version:

1. Bump `version` in `build.gradle.kts` (drop `-SNAPSHOT`).
2. Commit, tag `vX.Y.Z`, push both.
3. Watch `.github/workflows/release.yml` — it verifies tag matches version, runs every platform's runtime tests, then publishes.
4. Bump back to `vX.Y.(Z+1)-SNAPSHOT` for ongoing dev.

GPG key + Sonatype Central Portal namespace + GitHub repo secrets are already configured. If any of those need re-setup, RELEASING.md covers it.

## Don't

- Don't introduce a dependency without checking it ships a klib for every target we support.
- Don't expand the CI matrix on push/PR.
- Don't add `@Volatile` from `kotlin.jvm` — use `kotlin.concurrent.Volatile` (KMP-safe).
- Don't use JVM-only APIs in `commonMain` or `commonTest` — `kotlin.synchronized`, `java.util.concurrent.*`, `Thread.*`, `AtomicLong` from `j.u.c.atomic`, etc. JVM tests will pass; the release matrix's native cells (`linuxX64`, `macosArm64`, `iosSimulatorArm64`) fail at `compileTestKotlin<Target>`. Use `kotlinx.coroutines.sync.Mutex` for cross-coroutine sync, atomicfu for atomics, or move the test to `jvmTest` if JVM-specific.
- Don't put `,` (and likely other punctuation beyond `=`, `-`, space) in backtick-quoted test names. Kotlin/JVM accepts anything between backticks, but Kotlin/Native rejects `,` — same failure mode as the previous bullet, surfaces at native commonTest compile time only.
- Don't vendor the `pikpakcli/` Go reference into git; it's in `.gitignore` intentionally and linked from the README.
