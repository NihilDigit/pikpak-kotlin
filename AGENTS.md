# AGENTS.md

This file provides architecture, invariants, and development guidance when working with code in `pikpak-kotlin`.

## What this is

`pikpak-kotlin` is a Kotlin Multiplatform SDK for the [PikPak](https://mypikpak.com/) cloud-storage HTTP API. HTTP wire format and GCID hash follow [52funny/pikpakcli](https://github.com/52funny/pikpakcli) and [52funny/pikpakhash](https://github.com/52funny/pikpakhash); Xunlei CID sampling follows [digbug82/PikPak_Enhancement_Master](https://github.com/digbug82/PikPak_Enhancement_Master); all code here is fresh Kotlin.

Published as `io.github.nihildigit:pikpak-kotlin` on Maven Central. Source of truth: <https://github.com/NihilDigit/pikpak-kotlin>.

## Architecture

- **Atomic endpoints.** Every PikPak operation is one focused `suspend` extension function on `PikPakClient`. No hidden multi-account pools, local databases, background sync loops, or CLIs.
- **Hard problems abstracted.** Session persistence, `access_token` refresh, captcha re-auth on `error_code=9`, exponential-backoff retry, priority-ordered token-bucket rate limiting (`ApiRequestPriority`), GCID hashing, OSS HMAC-SHA1 signing, account quota projection (`AccountState`), cold-archive reactivation (`FileReactivationCoordinator`), and multi-connection playback transport all run inside the client.
- **Session externalised.** `SessionStore` is an interface — `FileSessionStore` (default on JVM and iOS; explicit path required on Android), `InMemorySessionStore`, or bring your own. Construction does no I/O; multi-account callers hold one `PikPakClient` per account.

### Code layout

- `src/commonMain/` — all non-platform-specific SDK code. Depends only on KMP-capable libraries: Ktor (`compileOnly` against ABI baseline `3.1.1` for forward compatibility across Ktor `3.x`), `kotlinx.serialization`, `kotlinx.coroutines`, `kotlinx.datetime`, `kotlinx-io`, `KotlinCrypto`.
- `src/jvmMain/`, `src/androidMain/`, `src/jvmAndroidMain/`, `src/appleMain/` — platform actuals:
  - `defaultSessionDir()` (`SessionStorePlatform.*.kt`)
  - `defaultCdnHttpClient()` & `defaultApiHttpClient()` (`internal/CdnClient.*.kt`): per-host connection caps, OkHttp `ConnectionSpec` TLS 1.2 fallback (`RESILIENT_CONNECTION_SPECS` for mobile TUN/proxy middleboxes that drop oversized TLS 1.3 + ML-KEM `ClientHello`s), `PikPakOkHttpDns` static fallback routing, call connection-reuse telemetry (`CdnConnectionListener`), and `ActiveResponseGuard` (closes OkHttp `ResponseBody`s even if cancelled at header arrival before Ktor's body writer starts).
  - `failedBeforeSending()` (`internal/SendFailure.*.kt`): decides whether a failed `POST` never reached the server and may be replayed.
  - `jvmAndroidMain` shares OkHttp code across JVM and Android; `appleMain` covers `iosArm64` and `iosSimulatorArm64` via Ktor Darwin.
- `src/commonTest/` — deterministic multiplatform unit tests (`MockEngine` and virtual time): auth/captcha/401 flows, GCID vectors, `StreamingPlanAndOwnershipTest` (VFS byte claims, `Continuation`, epoch isolation, progressive striping, `waitingReadAheadBytes`), `RecoveryPolicyTest` (ETA switchover & continuation vs reposition), `DemandTransportTest`, `HostHealthTest` (service-pool isolation, warm vs cold TTFB deadlines, `dl-a10b` non-steering), `SignedUrlGatesTest` (atomic admission, single-block quantum, replacement permit handoff), `TransferSchedulerTest`, `AccountStateMockTest`, `FileReactivationTest`, `RangeReaderMockTest`, `RangeReaderRobustnessTest`.
- `src/jvmTest/` — JVM-specific regression tests (`OkHttpResponseLeakRegressionTest`, `CdnConnectionTelemetryTest`), deterministic virtual-network simulation (`simulation/TransportSloTest.kt`, `benchmark/LoopbackTransportBenchmarkTest.kt`), and opt-in live PikPak API integration/probe tests gated on `.env` (`RangeReaderSmokeTest`, `CdnNetworkProbeTest`, `WeakLinkSmokeTest`, `MirrorProbeTest`, `IncrementalSyncProbeTest`, `SingleFileMultiSignLadderProbeTest`, etc.).
- `internal/` sub-package — implementation machinery not part of the public API:
  - Request & account state: `HttpEngine`, `AuthApi`, `AccountState`, `FolderIdCache`, `PikPakDnsFallback`, `OssSign`, `RetryAfter`.
  - Playback & transport: `SignedUrlGates`, `PriorityGate`, `TransferScheduler`, `TransportMemoryBudget`, `HostHealth`, `RecoveryPolicy`, `ReadTelemetry`, `FileReactivationCoordinator`, `ForegroundStreams`, `RequestOrder`.

## Documentation

User-facing documentation lives in the GitHub wiki (`git@github.com:NihilDigit/pikpak-kotlin.wiki.git`, cloned beside this repo as `../pikpak-kotlin.wiki`), not in this repo: `README.md` is a concise introduction linking into the wiki.

- Measured properties of PikPak go on the wiki's `Measurements.md` page with their date (`YYYY-MM-DD`). Statements marked **Fact** must not be contradicted without new measurements.
- Removed or reshaped APIs and discarded transport designs go on `History.md` with the reason, so future changes do not re-introduce them.
- KDoc explains why a piece of code is shaped the way it is; the wiki explains how the pieces fit together and how callers use them.

## Request pipeline & account state (read before touching auth, retry, or drive mutations)

Public endpoints are `suspend` extension functions grouped by API domain (`Endpoints.kt`, `FolderEndpoints.kt`, `SyncEndpoint.kt`, `UploadEndpoint.kt`, `UrlOfflineEndpoint.kt`, `OfflinePruneEndpoint.kt`, `Tasks.kt`, `RangeStreamEndpoint.kt`, `RangeDownloadEndpoint.kt`, `VariantEndpoint.kt`, `MagnetResolveEndpoint.kt`, `ArchiveEndpoint.kt`, `ShareEndpoint.kt`, `StarEndpoint.kt`, `EventEndpoint.kt`, `SearchEndpoint.kt`, `UserEndpoint.kt`, `TransferQuotaEndpoint.kt`, `WebDavEndpoint.kt`, `CidEndpoint.kt`, `PikPakDomain.kt`).

- **`HttpEngine` (`internal/HttpEngine.kt`)** handles priority-aware rate-limit acquisition (`ApiRequestPriority`: `INTERACTIVE = 10`, `DEFAULT = 0`, `BACKGROUND = -10`; buckets >= 8 reserve `min(8, capacity / 3)` tokens from background scans; queued requests attached to a `TransferDemand` track its live priority), standard PikPak headers, root-domain rewriting (`PikPakClient.domain` for `api-drive.*`, `user.*`, `api-dav.*`), on-demand login, one-shot captcha refresh on `error_code=9`, one-shot re-auth on HTTP `401`, and exponential-backoff retry on transient transport errors and `5xx`/`429` (`Retry-After` honoured up to `30 s`).
- **Two concurrency invariants in `HttpEngine` / `AuthApi`:**
  1. **Snapshot before the request, not after the failure.** `HttpEngine.request` captures the `Session` and `captchaToken` sent on the request and passes those snapshots to `reauthenticateLocked(previous)` / `refreshCaptchaToken(action, rejected)`. When the live value already differs from the snapshot, the refresh is skipped, collapsing `N` concurrent failures into one handshake.
  2. **Never retry once the response block has started, and never replay a POST of unknown effect.** `execute` retries before handing the response to `block`, and rethrows anything after. `POST` requests are replayed only on `429`/`503` or `failedBeforeSending` errors (DNS, connect refuse, pre-send TLS failure), never after bytes may have reached the server.
- **`sendRaw` (CDN / OSS)** streams via `prepareRequest { }.execute { }` on the CDN client without PikPak API headers (which trigger `406` on OSS/CDN) and without API rate limiting. `401`/`403` on a signed URL become `UrlExpiredException` before `block` runs (unless `X-Xos-Err-Desc` marks a concurrency/range restriction, surfaced via `PikPakException.isConcurrencyRestriction`).
- **`AccountState` (`internal/AccountState.kt`)** maintains `quotaFlow`, `transferQuotaFlow`, `userProfileFlow`, and `isFreeAccountFlow` on `PikPakClient`, deduplicating single-flight refreshes, projecting local file/folder byte deltas across `/drive/v1/about` lag, auto-sizing `leaseBudget` to 90% of free storage when `autoLeaseBudget` is enabled, and reconciling in the background when observed.
- **Delta sync & batch completion (`SyncEndpoint.kt`, `FolderEndpoints.kt`, `Tasks.kt`):**
  - `consumeFileChanges(sinceSyncTime)` / `scanFilesPages()` stream `parent_id=*`, `with_audit=true`, completed-phase rows ordered by `params.db_modify_time` (without `trashed=false`, so trash and permanent-delete markers arrive). Persist `checkpoint.syncTime` (`lastSyncTime`) only after every page succeeds; reset to a full scan on `FileSyncVersionOutdatedException`.
  - `batchMove`, `batchCopy`, `batchTrash`, `batchUntrash`, and `batchDelete` chunk by `BATCH_ID_LIMIT = 500` and **await server task completion by default** (`awaitCompletion = true`); callers needing crash-durable receipts before polling use `submitFileBatch` + `awaitTasks`.
  - `getFile(id, usage = FILE_USAGE_FETCH)` is fast (`~200–470 ms`) for raw download links, **omits `medias` completely**, and is strictly isolated from `getFile(id, usage = null)` in prefetch caching. For bulk media discovery use `batchGetFileDetails(ids, usage = "PLAY")` (`100` IDs per request).
  - `normalizeOssEndpoint` in `UploadEndpoint.kt` rewrites `upload-*.pikpakdrive.com` (unpublished NXDOMAIN in public DNS) to `.mypikpak.com` so uploads succeed across all `PikPakDomain` roots.

## Playback & demand-driven VFS transport (read before touching `BlockCache`, `RangeReader`, `HostHealth`, or `SignedUrlGates`)

The playback stack separates **demand ownership**, **byte-claim planning**, **admission/preemption**, and **route health / ETA recovery** into four orthogonal layers:

```
TransferDemand + ReadTicket / PikPakStreamReader   consumer intent, epoch, per-range byte cursor
                    │
PikPakFileCache (BlockCache)                       256 KiB slots, 5 ByteClaim kinds, fair-share striping, BlockStore
                    │
PikPakFileHandle                                   GCID identity, candidate URLs, 10-min refresh margin, leases, cold wake
                    │
RangeReader + SignedUrlGates + HostHealth          atomic 16/8/8 gates, warm/cold deadlines, RecoveryPolicy ETA switchover
                    │
streamRangeFromUrl (HttpEngine.sendRaw)            HTTP/1.1 206 streaming on tuned CDN client
```

### 1. Demand ownership & read tickets (`TransferDemand.kt`, `PikPakFileCache.kt`, `PikPakStreamReader.kt`)

- **`TransferDemand`** (`client.openTransferDemand()`) owns one `ConsumerIntent(epoch, service, bufferBudget, playbackSample)`:
  - `ServiceIntent` maps directly to priority: `WAITING` (`100`, `BLOCKING_PRIORITY`), `PLAYING` (`50`, `STREAMING_PRIORITY`), `PREFETCH` (`8`, `WARM_PRIORITY`), `PAUSED` (`1`, `BACKGROUND_READ_AHEAD_PRIORITY`). Container index prefetches use `INDEX_PRIORITY` (`101`); short `<= 64 KiB` direct ranges default to `MICRO_PROBE_PRIORITY` (`12`); comfortable far read-ahead demotes to `FAR_READ_AHEAD_PRIORITY` (`6`); background stream blocking reads use `BACKGROUND_BLOCKING_PRIORITY` (`5`).
  - `BufferBudget(targetBuffer, maxReadAheadBytes, waitingReadAheadBytes)` caps speculative look-ahead **per logical owner**, never multiplied when a player opens overlapping HTTP Range connections.
  - Advancing `epoch` withdraws stale speculation and drops stale `PlaybackBufferSnapshot`s without closing the owner or killing active `ReadTicket`s. An idle `TransferDemand` holds `0` connections and issues `0` requests.
- **`ReadTicket`** (`cache.beginRead(owner, startOffset, endExclusive)`) serves one byte range via `readSome` (returning arrived sub-block prefixes immediately without waiting for a full `256 KiB` block) and non-suspending `endRead(ReadEndReason)` (`RangeCompleted`, `ConsumerClosed`, `Repositioned`, `Cancelled`, `SourceFailed`). `PikPakStreamReader` is a sequential seekable single-cursor facade built on `TransferDemand` + `ReadTicket`.

### 2. VFS byte-claim planner & progressive striping (`BlockCache.kt`)

- **`planLocked()`** projects all cache work into five explicit `ByteClaim` kinds:
  1. `Required`: a caller currently parked inside `readSome` (`ReadWaiter`). May start new HTTP requests, retry failures, and publish a finite `recoveryGoalEndOffset` for ETA rescue.
  2. `Continuation`: an open, unended `ReadTicket` between `readSome` calls on its current `256 KiB` block (`exchangeId`). Starts **zero** new HTTP requests, performs **zero** retries, and stops cleanly at `(curSlot + 1) * 256 KiB` (`BoundaryStop`) if no further claim follows — eliminating sub-block self-cancellation loops on chunked reads while ensuring an abandoned `Range: bytes=0-` probe stops after at most one `256 KiB` block.
  3. `LookAhead`: sequential read-ahead advancing **only** from actual consumer reads, never self-rolling on background fetch completion. Remains `0` until a `ReadLane` proves sequential reading across a `256 KiB` block boundary (`crossedBlockBoundary || contiguousBytesRead >= blockSize`), **unless** the caller explicitly sets `BufferBudget.waitingReadAheadBytes > 0` in `ServiceIntent.WAITING` (allowing startup/seeks on high-RTT routes to fan out across `256 KiB` stripes in parallel before the first block finishes).
  4. `FinishInFlight`: when a ticket ends with `RangeCompleted` or `ConsumerClosed` and its single active block has already buffered `> 0` network bytes, allows up to `4` slots per owner at priority `-1` (`0` new requests, `0` retries, `0 ms` preemption) to finish the block instead of discarding nearly complete bytes.
  5. `StoreRange`: explicit `prefetch` warms and durable `download` ranges (`DurableBlockStore`, compact interval/bitmap tracking, bounded async write pipeline, `TransportMemoryBudget` accounting).
- **Superseded-epoch ticket isolation:** When a newer native ticket exists at `owner.intent.epoch` (e.g. a loopback player opens a post-seek HTTP connection before closing the pre-seek socket), `planLocked()` step `2b` withholds `Required` and `Continuation` claims from older-epoch tickets unless that slot already has a delivering in-flight fetch (`hasDeliveringInFlight`). Pre-seek sockets therefore never spawn `prio=5` fetches that steal connection slots from the seek target, while an already-streaming intra-session seek block still finishes in one HTTP request.
- **Progressive fair-share striping:** Non-durable fetches start at `1` block (`256 KiB`, safely inside congested CDN edges' `128–256 KiB` burst window), step to `2` blocks (`512 KiB`) once the urgent head block is cached or in flight (`allowContinuation`), and reach `4` blocks (`1 MiB`, `TransferScheduler.spanFor`) once near look-ahead is ready, dividing contiguous gaps evenly across available workers (`readAheadConcurrency = 4`, up to `8` cache workers). Durable downloads can span up to `1 GiB` while charging `transportMemoryBudgetBytes` (`64 MiB` default) only for live resident blocks, persistence queues, and active pump pages.
- **Split `RevisionSignal`s:** `scheduleSignal`, `readSignal`, and `foregroundIdleSignal` wake coroutines via compact atomic counters without `StateFlow` `AbortFlowException` churn when `16 KiB` TCP chunks stream into partial blocks.

### 3. Atomic admission, service pools, warm/cold deadlines & ETA switchover (`SignedUrlGates.kt`, `HostHealth.kt`, `RecoveryPolicy.kt`, `RangeReader.kt`)

- **`SignedUrlGates`** enforces the account ceiling (`16`), per-file ceiling (`8`), and per-signature ceiling (`8`, keyed by normalized path + query across readers, retries, probes, and sibling-steered URLs) in one mutex-guarded ledger:
  - Tracks live priority (`LiveSignedPriority` / `TransferDemand.priorityFlow`) and fair foreground turns across files.
  - **Single-block execution quantum (`inBlockQuantum`):** protects an active single-block `Required`/`Continuation` fetch from same-tier preemption (`>= 100` vs `>= 100`, `50..99` vs `50..99`) so two equal-priority readers never ping-pong mid-block; strictly higher service tiers (`100 > 50`, `>= 50 > 1..49`) or explicit withdrawal preempt in `0 ms`.
  - **`ReplacementPermitHandoff`:** 1-shot slot reservation during `OptimizedReplacement` so the replacement request inherits the active slot atomically without losing it to background waiters or opening a 9th connection on the signature.
- **`HostHealth` routing pools & candidate links:**
  - Candidates are learned dynamically from `FileDetail` (`DEFAULT_DL_Z01A_SEEDS` `0041..0050` used only as fallbacks when a primary `dl-z01a` host is congested or bad). Routing state is partitioned by `<family>.<root>|<pool>` (`download`, `original`, `transcoded`).
  - `PikPakFileHandle` expands candidate URLs for `dl-z01a-*` media by placing fast `octet-stream` hosts (`0041..0050`) ahead of congested transcode nodes (`0026..0027`, `0051..0055`, `0063..0064`), and learns exact `streamSize` from the first `206 Content-Range` header (`knownStreamSize`).
  - **Hard invariant — never steer `dl-a10b-*`:** `dl-a10b-*` is a `1500+`-node content-sharded cold/archive cluster, not a replicated front-end CDN pool; rewriting a `dl-a10b` URL onto another `dl-a10b` node returns `0`-byte `206` bodies. Both `HostHealth.Edge.parse` and `canRewriteTranscodeHost` exclude `dl-a10b-*`.
- **Separate warm vs cold TTFB & reactive deadlines:**
  - `ReadTelemetry.connectionFlow` reports OkHttp socket reuse (`connectionAcquired`) immediately before headers arrive. `HostHealth` records warm pooled header waits (`cleanLatencies`, which also drive route-epoch reset on `> 2.5×` / `< 0.4×` RTT shifts >= `250 ms`) separately from cold TCP+TLS handshakes (`coldLatencies`, `hostColdFirstByteLatencies`).
  - On high-RTT routes (e.g. US direct or trans-Pacific proxies where cold TLS takes `1.5–2.2 s` while pooled sockets answer in `0.4 s`), `RangeReader`'s reactive `deadline` coroutine re-evaluates `urgentFirstResponseDeadline` / `firstResponseDeadline` as priority or `connectionFlow` updates, never applying warm-socket P90 cutoffs to a cold handshake.
  - Body stall (`bodySilenceDeadline`: `2 s` at `>= 100`, `3 s` otherwise, scaled to `3 × median` clean RTT up to `15 s` below `READ_AHEAD_PRIORITY`) watches true zero-byte silence via a single reused `bodyWatchdog` coroutine and `64 KiB` pump buffer.
- **`RecoveryPolicy` ETA switchover:**
  - Replaces fixed rate floors (`24 KiB/s`) and blind wall-clock `ByteDeadline`s. At safe forwarded boundaries (`writeFully + flush` complete), `RangeReader` compares `T_keep = (G - F) / R_a` against `T_replace = C + Q_b + L_b + (G - F) / R_b`, where `L_b` uses cold TTFB P75 (`hostColdFirstByteLatencies` / `coldLatencies`) whenever `!hostHealth.isWarm(candUrl)`, and the short-window rate `R_a` spans `max(250 ms, min(cleanTtfbMs, 1000 ms))` excluding `sinkWait`.
  - `OptimizedReplacement` fires at most once per goal revision only after `2` consecutive qualifying checks with `T_keep - T_replace >= max(500 ms, 0.25 * T_keep)`. Non-fault stops (`Withdrawn`, `Preempted`, `BoundaryStop`, `OptimizedReplacement`) record clean body rates without marking the host bad.
- **Variant & GCID invariants:**
  - A variant is chosen once (`resolveVariant`) and locked by `mediaId`; refreshes never reselect another variant.
  - `PikPakFileHandle` treats **GCID as content identity** and `fileId` as a replaceable cache (`Expired` -> `getFile`; `Rejected`/404 -> `instantCreate` rebuild; cold archive empty body / `X-Xos-Storage-Class` -> single-flight `FileReactivationCoordinator` wake GET + poll).

## Shipped targets

`jvm`, `android` (AAR, artifactId `pikpak-kotlin-android`), `iosArm64`, `iosSimulatorArm64`.

Runtime test execution in release CI follows [Kotlin/Native's tier table](https://kotlinlang.org/docs/native-target-support.html): `*Test` runs on `jvm` and `iosSimulatorArm64`; `android` (`:assembleAndroidMain`) and `iosArm64` (`compileKotlinIosArm64`) are compile-verified. `linuxX64`, `linuxArm64`, `mingwX64`, `macosArm64`, and `iosX64` were dropped in `0.6.9` to conserve Maven Central file quota (`io.github.nihildigit` monthly namespace cap).

## Development loop

JDK 21 required. Gradle wrapper included. Keep formatting consistent with surrounding Kotlin files.

```bash
./gradlew compileCommonMainKotlinMetadata       # fast KMP stdlib check (catches JVM-only leaks on any host)
./gradlew jvmTest                               # JVM unit tests + live integration tests (if .env present)
./gradlew jvmTest --tests '*StreamingPlanAndOwnershipTest*'   # single test class
./gradlew jvmTest --tests '*RecoveryPolicyTest*'
./gradlew build                                 # compile + full test suite on current host
./gradlew iosSimulatorArm64Test                 # native runtime tests (macOS host only)
./gradlew assemble -Pkotlin.native.ignoreDisabledTargets=true # compile all targets supported on host
PIKPAK_PROBE=1 ./gradlew jvmTest --tests '*CdnNetworkProbe*' --rerun
PIKPAK_WEAKLINK=1 ./gradlew jvmTest --tests '*WeakLinkSmoke*' --rerun
```

- **Cross-platform local check off macOS:** Always run `./gradlew compileCommonMainKotlinMetadata` alongside `./gradlew jvmTest` (not `compileKotlinMetadata`, which does not catch JVM-only stdlib calls in `commonMain`).
- **Composite build with Piko:** When `../piko/local.properties` sets `pikpak.sdk.dir=../pikpak-kotlin`, Piko compiles directly against this working tree. Keep Kotlin, AGP, and Gradle versions aligned between both repositories, and never run `publishToMavenLocal` without a distinct version lest you overwrite cached Maven Central release artifacts in `~/.m2`.
- **Simulation vs live probes:** `TransportSloTest` (`src/jvmTest/.../simulation/`) verifies deterministic state-machine invariants (byte splicing, memory budget accounting, 8-slot gate compliance, rescue timing), **not** real-world CDN throughput (PikPak edges clamp TCP windows to `~64 KiB` and throttle bursts past `128–256 KiB`; see `simulation/README.md`). Validate transport tuning against real multi-region logs and live probes.

## CI & releasing

CI is a **release gate, not a per-commit check** — `.github/workflows/release.yml` triggers only on `v*.*.*` tags, runs every target matrix cell, and publishes to Maven Central (`automaticRelease = true`, stripping redundant `.sha256`/`.sha512`/`.asc.md5`/`.asc.sha1` files to stay within Central's file quota). Do not add push/PR workflows without asking the maintainer.

Full release steps in [RELEASING.md](./RELEASING.md):

1. **Update `../pikpak-kotlin.wiki` first:** review `git diff` since the last tag, update affected pages (`Home.md`, `Getting-Started.md`, `_Sidebar.md`, `Playback.md`, `Original-Link-Pools.md`, `Delta-Sync.md`, `Architecture.md`, `Measurements.md`, `History.md`, etc.), and show the maintainer the wiki diff before pushing.
2. Drop `-SNAPSHOT` in `build.gradle.kts` and update the install version in `README.md`.
3. Commit, tag `vX.Y.Z`, and push after maintainer confirmation; wait for Maven Central sync before bumping downstream consumers.
4. Bump `build.gradle.kts` to `vX.Y.(Z+1)-SNAPSHOT`.

## Don't

- Don't introduce dependencies that lack artifacts/klibs for all four shipped targets (`jvm`, `android`, `iosArm64`, `iosSimulatorArm64`), or leak Ktor runtime dependencies (`ktor-client-*` must stay `compileOnly` in main source sets).
- Don't use `@Volatile` from `kotlin.jvm` — always use `kotlin.concurrent.Volatile`.
- Don't use JVM-only APIs in `commonMain` or `commonTest` (`kotlin.synchronized`, `java.util.concurrent.*`, `Thread.*`, `RegexOption.DOT_MATCHES_ALL`, `toSortedMap`). Use `Mutex`, `MutableStateFlow` CAS loops, or move JVM-specific tests to `jvmTest`.
- Don't put `,` (or exotic punctuation beyond `=`, `-`, `_`, space) inside backtick-quoted test names in `commonTest` — Kotlin/Native rejects `,` at compile time.
- Don't let a `commonTest` `@Test` function return anything other than `Unit` (use `= runTest { ... }` ending with a statement/assertion, or `= runBlocking<Unit> { ... }`).
- Don't run suspending slot/budget/guard cleanup without `withContext(NonCancellable)` (`PriorityGate`, `SignedUrlGates`, `LeaseBudget`, `ActiveResponseGuard`).
- Don't mix warm pooled RTT samples with cold TLS handshake samples in first-response deadlines or `RecoveryPolicy` candidate TTFB estimates, and never enable sibling host steering on `dl-a10b-*`.
- Don't swallow errors with `runCatching` in endpoint code; throw `PikPakException` (or let raw transport `IOException` and `CancellationException` propagate unchanged).
- Don't hardcode copyrighted magnets or files in tests (`TestFixtures.ARCH_ISO_MAGNET` only; custom inputs via `.env`).
