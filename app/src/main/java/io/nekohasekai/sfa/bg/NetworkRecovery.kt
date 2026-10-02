package io.nekohasekai.sfa.bg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Screen broadcasts cover sleep on the same Wi-Fi, including pre-API-33 devices
// without light-idle broadcasts. Maintenance windows must not consume the wake.
internal class WakeRecoveryPolicy(private val minimumSleepMs: Long = 60_000L) {
    private var screenOffAt: Long? = null
    private var idleObserved = false

    @Synchronized
    fun reset(interactive: Boolean, now: Long) {
        screenOffAt = if (interactive) null else now
        idleObserved = false
    }

    @Synchronized
    fun update(interactive: Boolean, deepIdle: Boolean, lightIdle: Boolean, now: Long): Boolean {
        val idle = deepIdle || lightIdle
        if (idle) idleObserved = true
        if (!interactive) {
            if (screenOffAt == null) screenOffAt = now
            return false
        }
        if (idle) return false
        val longSleep = screenOffAt?.let { now - it >= minimumSleepMs } == true
        screenOffAt = null
        val recover = idleObserved || longSleep
        idleObserved = false
        return recover
    }
}

// All recovery causes share one job. A wake can expedite a queued handover;
// events arriving during the reset are covered by that reset, rather than
// queuing a second full reload. Failed attempts do not enter the cooldown.
internal class NetworkRecoveryScheduler(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val cooldownMs: Long = 3_000L,
    private val recover: suspend (String) -> Boolean,
) {
    private val lock = Any()
    private val reasons = linkedSetOf<String>()
    private var job: Job? = null
    private var running = false
    private var deadline: Long? = null
    private var wakePending = false
    private var lastSuccess: Long? = null
    private var generation = 0L

    fun request(reason: String, delayMs: Long, wake: Boolean = false) {
        synchronized(lock) {
            if (running) return
            if (wake && lastSuccess?.let { now() - it < cooldownMs } == true) return
            reasons.add(reason)
            wakePending = wakePending || wake
            val requestedDeadline = now() + delayMs
            // A radio handover keeps its debounce unless an immediate wake is pending.
            deadline = if (wakePending) minOf(deadline ?: requestedDeadline, requestedDeadline) else requestedDeadline
            job?.cancel()
            val ticket = ++generation
            val waitMs = ((deadline ?: requestedDeadline) - now()).coerceAtLeast(0)
            job = scope.launch {
                delay(waitMs)
                val cause = synchronized(lock) {
                    if (ticket != generation) return@launch
                    running = true
                    deadline = null
                    wakePending = false
                    reasons.joinToString("+").also { reasons.clear() }
                }
                var success = false
                try {
                    success = recover(cause)
                } finally {
                    synchronized(lock) {
                        running = false
                        if (success && ticket == generation) lastSuccess = now()
                    }
                }
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            generation++
            job?.cancel()
            job = null
            reasons.clear()
            deadline = null
            wakePending = false
            lastSuccess = null
        }
    }
}
