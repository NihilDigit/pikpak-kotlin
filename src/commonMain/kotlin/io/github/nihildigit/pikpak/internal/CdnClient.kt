package io.github.nihildigit.pikpak.internal

import io.ktor.client.HttpClient

/**
 * Builds the HTTP client used for signed CDN and OSS URLs.
 *
 * It exists separately from the API client because the two workloads want
 * opposite things. API calls are few, small, and must stay under a captcha
 * wall. CDN reads are many, large, and their throughput is a direct function
 * of how many TCP connections the engine is willing to open to one host —
 * PikPak's CDN negotiates HTTP/1.1 only, so concurrency cannot come from
 * multiplexing.
 *
 * Every engine ships a per-host cap far below what a range reader needs:
 * OkHttp allows 5, Darwin 4 on iOS and 6 on macOS, CIO 100 overall but with
 * its own per-route limit. Ktor's shared `HttpClientConfig` cannot reach any
 * of them, hence the expect/actual.
 *
 * [perHostLimit] has to be the account budget, not the per-file one. The gates
 * above already bound how many requests are in flight; an engine cap below them
 * only adds a hidden queue, and two files whose links land on one edge host
 * then sit in it past RangeReader's first-response deadline, which reads the
 * wait as a dead host.
 *
 * Consumers who would rather not have the SDK pick an engine can pass their
 * own client for this role; see the PikPakClient constructor.
 */
internal expect fun defaultCdnHttpClient(perHostLimit: Int): HttpClient

internal const val CDN_CONNECT_TIMEOUT_MS = 15_000L

/**
 * Inter-byte timeout on a CDN read. The measured CDN sustains ~0.8 MB/s per
 * connection and answers a cold 64 KB range in ~205 ms, so a full minute of
 * silence means the connection is gone, not slow.
 */
internal const val CDN_SOCKET_TIMEOUT_MS = 60_000L
