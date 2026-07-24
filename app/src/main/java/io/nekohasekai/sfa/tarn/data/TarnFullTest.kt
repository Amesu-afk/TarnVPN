package io.nekohasekai.sfa.tarn.data

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.Collections

/** Result of a real HTTP request routed through a running sing-box outbound. */
sealed interface TarnFullTestResult {
    data object Testing : TarnFullTestResult

    data class Success(
        val millis: Int,
        val attempts: Int,
    ) : TarnFullTestResult

    data class Failed(
        val message: String,
        val attempts: Int,
    ) : TarnFullTestResult

    data object Cancelled : TarnFullTestResult
}

data class TarnFullTestTarget(
    val profileId: Long,
    val outboundTag: String,
)

/**
 * Owns the command clients used by one manual full-test run.
 *
 * The gomobile call is synchronous, so cancelling its Kotlin coroutine is not enough to
 * abort a dial already executing in Go. Each target therefore gets a dedicated standalone
 * command client and [cancel] disconnects those clients from another coroutine. The RPC
 * parents URL-test to that connection context, so closing it reaches the in-flight dial.
 */
class TarnFullTestSession {
    private val activeClients = Collections.synchronizedSet(mutableSetOf<CommandClient>())

    @Volatile
    private var cancelled = false

    suspend fun run(
        targets: List<TarnFullTestTarget>,
        link: String,
        timeoutMs: Int,
        attempts: Int,
    ): Map<Long, TarnFullTestResult> = supervisorScope {
        val uri = runCatching { URI(link) }.getOrNull()
        require(
            uri != null &&
                uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null,
        ) { "Full test requires an HTTPS URL without user info" }
        val boundedTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val boundedAttempts = attempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)
        val semaphore = Semaphore(MAX_CONCURRENCY)

        targets
            .distinctBy { it.profileId }
            .map { target ->
                async(Dispatchers.IO) {
                    target.profileId to semaphore.withPermit {
                        testTarget(target, link, boundedTimeout, boundedAttempts)
                    }
                }
            }
            .awaitAll()
            .toMap()
    }

    /** Safe to call concurrently with [run]. */
    fun cancel() {
        cancelled = true
        val clients = synchronized(activeClients) { activeClients.toList() }
        clients.forEach { client ->
            runCatching { client.disconnect() }
        }
    }

    private suspend fun testTarget(
        target: TarnFullTestTarget,
        link: String,
        timeoutMs: Int,
        attempts: Int,
    ): TarnFullTestResult {
        var lastError = "URL-test failed"
        repeat(attempts) { attemptIndex ->
            currentCoroutineContext().ensureActive()
            if (cancelled) throw CancellationException("Full test cancelled")

            val client = Libbox.newStandaloneCommandClient()
            activeClients += client
            try {
                val result = client.urlTestOutbound(target.outboundTag, link, timeoutMs)
                currentCoroutineContext().ensureActive()
                if (result.error.isBlank()) {
                    return TarnFullTestResult.Success(
                        millis = result.delay.coerceAtLeast(0),
                        attempts = attemptIndex + 1,
                    )
                }
                lastError = result.error.toDisplayError()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                currentCoroutineContext().ensureActive()
                lastError = (exception.message ?: exception.javaClass.simpleName).toDisplayError()
            } finally {
                activeClients -= client
                runCatching { client.disconnect() }
            }
        }
        return TarnFullTestResult.Failed(lastError, attempts)
    }

    private fun String.toDisplayError(): String =
        lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "URL-test failed" }.take(240)

    private companion object {
        const val MAX_CONCURRENCY = 3
        const val MIN_TIMEOUT_MS = 3_000
        const val MAX_TIMEOUT_MS = 15_000
        const val MIN_ATTEMPTS = 1
        const val MAX_ATTEMPTS = 5
    }
}
