package io.github.nihildigit.pikpak

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import org.junit.jupiter.api.Assumptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Measures the real API throughput and rate/captcha ceiling on the live account once OkHttp's
 * default `maxRequestsPerHost = 5` bottleneck is lifted.
 *
 * Confined strictly to a fresh `piko-probe-rate-<millis>` folder in root, moved to trash in `finally`.
 * Stops immediately if any 429 or human-verification captcha appears.
 */
class ApiRateWallProbeTest {
    private val env = dotenv {
        directory = "."
        ignoreIfMissing = true
        ignoreIfMalformed = true
    }
    private val username = env["PIKPAK_USERNAME"]?.takeIf { it.isNotBlank() && !it.contains("@example.com") }
    private val password = env["PIKPAK_PASSWORD"]?.takeIf { it.isNotBlank() && it != "your-password" }
    private val enabled = (env["PIKPAK_PROBE"] ?: System.getenv("PIKPAK_PROBE")) == "1"

    @Test
    fun `measure real api read and write ceiling without okhttp 5-per-host bottleneck`() = runBlocking {
        Assumptions.assumeTrue(enabled, "set PIKPAK_PROBE=1 to run")
        Assumptions.assumeTrue(username != null && password != null, "no .env credentials")

        val sent = AtomicInteger()
        val error9 = AtomicInteger()
        val status429 = AtomicInteger()
        val captchaRefreshes = AtomicInteger()
        val humanRequired = AtomicBoolean(false)
        val protocols = ConcurrentHashMap<String, AtomicInteger>()
        val statuses = ConcurrentHashMap<Int, AtomicInteger>()

        val http = HttpClient(OkHttp) {
            engine {
                config {
                    connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
                    dispatcher(
                        Dispatcher().apply {
                            maxRequestsPerHost = 128
                            maxRequests = 256
                        },
                    )
                }
                addInterceptor { chain ->
                    val path = chain.request().url.encodedPath
                    val response = chain.proceed(chain.request())
                    protocols.computeIfAbsent(response.protocol.toString()) { AtomicInteger() }.incrementAndGet()
                    statuses.computeIfAbsent(response.code) { AtomicInteger() }.incrementAndGet()
                    if (response.code == 429) status429.incrementAndGet()
                    val body = response.peekBody(4096).string()
                    when {
                        path.endsWith("/shield/captcha/init") -> {
                            captchaRefreshes.incrementAndGet()
                            if (Regex(""""url"\s*:\s*"http""").containsMatchIn(body)) humanRequired.set(true)
                        }
                        path.startsWith("/drive/v1/") -> {
                            sent.incrementAndGet()
                            if (Regex(""""error_code"\s*:\s*9\b""").containsMatchIn(body)) error9.incrementAndGet()
                        }
                    }
                    response
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            expectSuccess = false
        }

        val client = PikPakClient(
            account = username!!,
            password = password!!,
            sessionStore = InMemorySessionStore(),
            httpClient = http,
            rateLimiter = RateLimiter.unlimited(),
        )

        client.login()
        val probeRoot = client.createFolder("", "piko-probe-rate-${System.currentTimeMillis()}")
        println("[rate-probe] created isolated folder=$probeRoot")

        try {
            // Warm up connection pool
            repeat(8) { client.getFile(probeRoot) }
            println("[rate-probe] protocols after warmup: ${protocols.mapValues { it.value.get() }}")

            // 1. Paced Read Ladder: 30 -> 50 -> 80 -> 120 req/s (4 seconds per tier)
            for (targetRate in listOf(30, 50, 80, 120)) {
                if (humanRequired.get() || status429.get() > 0) break
                val durationSec = 4
                val totalCalls = targetRate * durationSec
                val s0 = sent.get()
                val e0 = error9.get()
                val r0 = captchaRefreshes.get()
                val c429 = status429.get()
                val failures = AtomicInteger()
                val latenciesMs = CopyOnWriteArrayList<Long>()
                val started = TimeSource.Monotonic.markNow()

                coroutineScope {
                    repeat(totalCalls) { i ->
                        val due = (1000L * i / targetRate).milliseconds - started.elapsedNow()
                        if (due.isPositive()) delay(due)
                        launch {
                            val t0 = TimeSource.Monotonic.markNow()
                            runCatching {
                                if (i % 2 == 0) client.listFilesPaged(parentId = probeRoot, pageSize = 100)
                                else client.getFile(probeRoot)
                            }.onSuccess {
                                latenciesMs.add(t0.elapsedNow().inWholeMilliseconds)
                            }.onFailure {
                                failures.incrementAndGet()
                            }
                        }
                    }
                }
                val elapsedMs = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
                val sorted = latenciesMs.sorted()
                val p50 = if (sorted.isNotEmpty()) sorted[sorted.size / 2] else -1
                val p90 = if (sorted.isNotEmpty()) sorted[(sorted.size * 9) / 10] else -1
                val actualRps = (sent.get() - s0) * 1000.0 / elapsedMs
                println(
                    "[rate-probe][READ target=${targetRate}/s] sent=${sent.get() - s0}, actual=${"%.1f".format(actualRps)} req/s, " +
                        "p50=${p50}ms, p90=${p90}ms, 429=${status429.get() - c429}, err9=${error9.get() - e0}, " +
                        "captchaInit=${captchaRefreshes.get() - r0}, failed=${failures.get()}, elapsed=${elapsedMs}ms",
                )
                if (humanRequired.get() || status429.get() - c429 > 0 || error9.get() - e0 > 0) {
                    println("[rate-probe] wall signal observed at target=$targetRate/s, stopping read ladder")
                    break
                }
                delay(2.seconds)
            }

            // 2. Concurrent Write Burst: createFolder & rename at concurrency 8, 16, 24
            if (!humanRequired.get() && status429.get() == 0) {
                val createdIds = CopyOnWriteArrayList<String>()
                for (conc in listOf(8, 16)) {
                    val sem = Semaphore(conc)
                    val s0 = sent.get()
                    val e0 = error9.get()
                    val c429 = status429.get()
                    val failures = AtomicInteger()
                    val batchCount = 24
                    val t0 = TimeSource.Monotonic.markNow()
                    coroutineScope {
                        (0 until batchCount).map { idx ->
                            async {
                                sem.withPermit {
                                    runCatching {
                                        client.createFolder(probeRoot, "sub-c${conc}-$idx")
                                    }.onSuccess { createdIds.add(it) }
                                        .onFailure { failures.incrementAndGet() }
                                }
                            }
                        }.awaitAll()
                    }
                    val elapsedMs = t0.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
                    val actualRps = (sent.get() - s0) * 1000.0 / elapsedMs
                    println(
                        "[rate-probe][WRITE-CREATE conc=$conc, n=$batchCount] actual=${"%.1f".format(actualRps)} req/s, " +
                            "429=${status429.get() - c429}, err9=${error9.get() - e0}, failed=${failures.get()}, elapsed=${elapsedMs}ms",
                    )
                    if (humanRequired.get() || status429.get() - c429 > 0 || error9.get() - e0 > 0) break
                    delay(1.seconds)
                }

                // Batch rename at concurrency 16 & 24 across the created folders
                for (conc in listOf(16, 24)) {
                    if (humanRequired.get() || status429.get() > 0 || createdIds.isEmpty()) break
                    val sem = Semaphore(conc)
                    val s0 = sent.get()
                    val e0 = error9.get()
                    val c429 = status429.get()
                    val failures = AtomicInteger()
                    val targets = createdIds.toList()
                    val t0 = TimeSource.Monotonic.markNow()
                    coroutineScope {
                        targets.mapIndexed { idx, id ->
                            async {
                                sem.withPermit {
                                    runCatching {
                                        client.rename(id, "renamed-c${conc}-$idx")
                                    }.onFailure { failures.incrementAndGet() }
                                }
                            }
                        }.awaitAll()
                    }
                    val elapsedMs = t0.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
                    val actualRps = (sent.get() - s0) * 1000.0 / elapsedMs
                    println(
                        "[rate-probe][WRITE-RENAME conc=$conc, n=${targets.size}] actual=${"%.1f".format(actualRps)} req/s, " +
                            "429=${status429.get() - c429}, err9=${error9.get() - e0}, failed=${failures.get()}, elapsed=${elapsedMs}ms",
                    )
                    if (humanRequired.get() || status429.get() - c429 > 0 || error9.get() - e0 > 0) break
                    delay(1.seconds)
                }
            }

            println("[rate-probe] final protocols=${protocols.mapValues { it.value.get() }}, statuses=${statuses.mapValues { it.value.get() }}, human=${humanRequired.get()}")
        } finally {
            runCatching { client.batchTrash(listOf(probeRoot)) }
                .onSuccess { println("[rate-probe] moved probe folder $probeRoot to trash") }
                .onFailure { println("[rate-probe] cleanup failed for $probeRoot: ${it.message}") }
            client.close()
            http.close()
        }
    }
}
