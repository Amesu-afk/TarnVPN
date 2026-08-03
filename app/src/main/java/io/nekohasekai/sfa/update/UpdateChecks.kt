package io.nekohasekai.sfa.update

import android.util.Log
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one place that decides *when* the app asks GitHub whether a newer build exists.
 *
 * Two callers, on purpose:
 *  - app start (`MainActivity.onCreate`), which is how it always worked;
 *  - the tunnel coming up (`TarnShell`), which is the one that actually reaches GitHub for the
 *    users this app is built for. `api.github.com` is not dependably reachable from a Russian
 *    network without the VPN, so a check fired at cold start — before anything is connected —
 *    lands in the `catch` and the user is told nothing. Re-checking once the tunnel is up sends
 *    the request through it.
 */
object UpdateChecks {
    private const val TAG = "UpdateChecks"

    /**
     * Floor between two automatic checks. Connecting is something people do several times a day
     * and the answer changes at most a few times a month, so anything shorter is just load on the
     * GitHub API (which is rate-limited per IP and unauthenticated here — and behind the VPN that
     * IP is shared with every other user on the same server).
     */
    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val mutex = Mutex()

    /**
     * Runs a check unless one ran recently. [force] is for the button in settings, where the user
     * asked explicitly and a silent no-op would read as the button being broken.
     *
     * Never throws: a failed check is not something to interrupt anyone over.
     */
    suspend fun runIfDue(force: Boolean = false) {
        if (!Settings.checkUpdateEnabled) return

        mutex.withLock {
            val now = System.currentTimeMillis()
            val last = Settings.lastUpdateCheckAt
            // `now < last` means the clock moved backwards (timezone fix, manual change). Without
            // this the stored future timestamp would suppress every check until it passes.
            val checkedRecently = last != 0L && now >= last && now - last < MIN_INTERVAL_MS
            if (!force && checkedRecently) return

            UpdateState.isChecking.value = true
            try {
                val info = withContext(Dispatchers.IO) { Vendor.checkUpdateAsync() }
                Settings.lastUpdateCheckAt = now
                UpdateState.setUpdate(info)
            } catch (e: Exception) {
                // Deliberately NOT setUpdate(null): that clears the cached update as a side
                // effect, so one failed check — no network, GitHub blocked, rate limit — would
                // erase an update we had already found and told nobody about yet. Leave the
                // timestamp alone too, so the next connect retries instead of waiting out the
                // interval.
                Log.d(TAG, "update check failed", e)
            } finally {
                UpdateState.isChecking.value = false
            }
        }
    }
}
