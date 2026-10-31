package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.FileReactivatingException
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakConstants
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.UrlExpiredException
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One wake exchange per handle, shared even by readers retired during a URL refresh. */
internal class FileReactivationCoordinator(
    private val client: PikPakClient,
    private val pollInterval: Duration = 8.seconds,
    private val timeout: Duration = 5.minutes,
) {
    init {
        require(pollInterval > Duration.ZERO && pollInterval.isFinite())
        require(timeout > Duration.ZERO && timeout.isFinite())
    }

    private val mutex = Mutex()
    private var waiting: CompletableDeferred<Unit>? = null
    private val active = MutableStateFlow(false)
    val isReactivating: StateFlow<Boolean> = active.asStateFlow()

    // A late cold response from the previous wave must not start another wake GET.
    @Volatile var generation: Long = 0L
        private set

    suspend fun awaitReady(
        observedGeneration: Long,
        url: String,
        refresh: suspend (String) -> String,
    ) {
        var leader = false
        val pending = mutex.withLock {
            if (generation != observedGeneration) return
            waiting ?: CompletableDeferred<Unit>().also {
                waiting = it
                active.value = true
                leader = true
            }
        }
        if (!leader) {
            pending.await()
            return
        }
        try {
            val ready = withTimeoutOrNull(timeout) {
                poll(url, refresh)
                true
            } ?: false
            if (!ready) throw FileReactivatingException("file reactivation exceeded $timeout")
            withContext(NonCancellable) {
                mutex.withLock {
                    generation++
                    active.value = false
                    waiting = null
                    pending.complete(Unit)
                }
            }
        } catch (t: Throwable) {
            // The leader owns its socket and cancellation; followers share the same result,
            // rather than leaving a detached five-minute poll after a seek or close.
            withContext(NonCancellable) {
                mutex.withLock {
                    active.value = false
                    waiting = null
                    pending.completeExceptionally(t)
                }
            }
            throw t
        }
    }

    private suspend fun poll(initialUrl: String, refresh: suspend (String) -> String) {
        var url = initialUrl
        var wakeSent = false
        while (!wakeSent) {
            try {
                // The caller already owns an account slot. Reacquiring it here could
                // deadlock when all sixteen workers are waiting for this wake exchange.
                client.signedUrlGates.withSlot(url) {
                    client.http.sendRaw(HttpMethod.Get, url, configure = {
                        headers[HttpHeaders.UserAgent] = PikPakConstants.USER_AGENT
                        headers.remove(HttpHeaders.Range)
                    }) { response ->
                        response.bodyAsChannel().cancel(null)
                        if (response.status.value !in 200..299) {
                            throw PikPakException(-1, "reactivation wake failed", httpStatus = response.status.value)
                        }
                    }
                }
                wakeSent = true
            } catch (e: UrlExpiredException) {
                url = refresh(url)
            }
        }
        while (true) {
            delay(pollInterval)
            try {
                client.streamRangeFromUrl(url, 0, 1) { stream ->
                    val byte = ByteArray(1)
                    check(stream.channel.readAvailable(byte, 0, 1) == 1) { "empty reactivation probe" }
                }
                return
            } catch (_: FileReactivatingException) {
                // This protocol status neither spends transport retries nor changes host health.
            } catch (e: UrlExpiredException) {
                url = refresh(url)
            }
        }
    }
}
