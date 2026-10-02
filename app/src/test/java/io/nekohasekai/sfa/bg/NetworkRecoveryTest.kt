package io.nekohasekai.sfa.bg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryTest {
    @Test
    fun longScreenSleepRecoversOnUnchangedWifiWithoutDozeBroadcast() {
        val policy = WakeRecoveryPolicy()
        policy.reset(true, 0)
        assertFalse(policy.update(false, false, false, 100))
        assertTrue(policy.update(true, false, false, 60_100))
        assertFalse(policy.update(true, false, false, 60_101))
    }

    @Test
    fun shortScreenTogglePreservesConnections() {
        val policy = WakeRecoveryPolicy()
        policy.reset(true, 0)
        assertFalse(policy.update(false, false, false, 100))
        assertFalse(policy.update(true, false, false, 1_000))
    }

    @Test
    fun maintenanceWindowWaitsForInteractiveWake() {
        val policy = WakeRecoveryPolicy()
        policy.reset(false, 0)
        assertFalse(policy.update(false, true, false, 1_000))
        assertFalse(policy.update(false, false, false, 2_000))
        assertFalse(policy.update(false, true, false, 3_000))
        assertFalse(policy.update(false, false, false, 4_000))
        assertTrue(policy.update(true, false, false, 5_000))
        assertFalse(policy.update(true, false, false, 5_001))
    }

    @Test
    fun lightIdleRecoversEvenAfterShortScreenSleep() {
        val policy = WakeRecoveryPolicy()
        policy.reset(false, 0)
        assertFalse(policy.update(false, false, true, 1_000))
        assertTrue(policy.update(true, false, false, 5_000))
    }

    @Test
    fun screenOnBeforeIdleExitRetainsPendingWake() {
        val policy = WakeRecoveryPolicy()
        policy.reset(false, 0)
        assertFalse(policy.update(false, true, true, 1_000))
        assertFalse(policy.update(true, true, true, 2_000))
        assertTrue(policy.update(true, false, false, 2_100))
    }

    @Test
    fun newServiceSessionDoesNotInheritPreviousIdle() {
        val policy = WakeRecoveryPolicy()
        policy.reset(false, 0)
        policy.update(false, true, false, 1_000)
        policy.reset(true, 2_000)
        assertFalse(policy.update(true, false, false, 2_100))
    }

    @Test
    fun wakeExpeditesAndCoalescesQueuedNetworkRecovery() = runBlocking {
        val completed = CompletableDeferred<String>()
        var calls = 0
        val scheduler = NetworkRecoveryScheduler(this, { 0L }) { cause ->
            calls++
            completed.complete(cause)
            true
        }
        scheduler.request("network:wifi", 1_200)
        scheduler.request("idle-wake", 0, wake = true)
        val cause = withTimeout(1_000) { completed.await() }
        assertEquals("network:wifi+idle-wake", cause)
        scheduler.request("idle-wake", 0, wake = true)
        yield()
        assertEquals(1, calls)
        scheduler.cancel()
    }

    @Test
    fun cancellationPreventsRecoveryAfterDisconnect() = runBlocking {
        var calls = 0
        val scheduler = NetworkRecoveryScheduler(this, { 0L }) {
            calls++
            true
        }
        scheduler.request("network:wifi", 30)
        scheduler.cancel()
        delay(60)
        assertEquals(0, calls)
    }

    @Test
    fun failedRecoveryAllowsAnotherWakeAttempt() = runBlocking {
        var calls = 0
        val scheduler = NetworkRecoveryScheduler(this, { 0L }) {
            calls++
            false
        }
        scheduler.request("idle-wake", 0, wake = true)
        yield()
        scheduler.request("idle-wake", 0, wake = true)
        yield()
        assertEquals(2, calls)
        scheduler.cancel()
    }

    @Test
    fun newHandoverIsNotSuppressedByWakeCooldown() = runBlocking {
        var calls = 0
        val scheduler = NetworkRecoveryScheduler(this, { 0L }) {
            calls++
            true
        }
        scheduler.request("idle-wake", 0, wake = true)
        yield()
        scheduler.request("network:mobile", 0)
        yield()
        assertEquals(2, calls)
        scheduler.cancel()
    }

    @Test
    fun overlappingWakeDoesNotQueueSecondRecovery() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val scheduler = NetworkRecoveryScheduler(this, { 0L }) {
            calls++
            entered.complete(Unit)
            finish.await()
            true
        }
        scheduler.request("network:wifi", 0)
        withTimeout(1_000) { entered.await() }
        scheduler.request("idle-wake", 0, wake = true)
        finish.complete(Unit)
        yield()
        assertEquals(1, calls)
        scheduler.cancel()
    }
}
