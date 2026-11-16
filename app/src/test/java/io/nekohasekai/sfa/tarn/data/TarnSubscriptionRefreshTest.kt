package io.nekohasekai.sfa.tarn.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A one-server subscription whose share link was rotated (the provider issues a new uuid,
 * port or sni) still owns exactly one profile row. Treating that as "old server removed +
 * new server added" would delete the selected profile and refuse the refresh with "the
 * selected server is missing from the update" — a dead end, because there is no other
 * server to select. These cases pin the in-place replacement rule.
 */
class TarnSubscriptionRefreshTest {

    @Test
    fun `a rotated single server is replaced in place`() {
        assertTrue(TarnServerRepository.shouldReplaceInPlace(setOf("old-key"), setOf("new-key")))
    }

    @Test
    fun `an unchanged single server is left alone`() {
        assertFalse(TarnServerRepository.shouldReplaceInPlace(setOf("same"), setOf("same")))
    }

    @Test
    fun `multi-server subscriptions keep the selected-server guard`() {
        assertFalse(TarnServerRepository.shouldReplaceInPlace(setOf("a", "b"), setOf("b", "c")))
        assertFalse(TarnServerRepository.shouldReplaceInPlace(setOf("a", "b"), setOf("a", "b")))
    }

    @Test
    fun `a subscription that gains or loses servers is not an in-place replacement`() {
        assertFalse(TarnServerRepository.shouldReplaceInPlace(setOf("a"), setOf("a", "b")))
        assertFalse(TarnServerRepository.shouldReplaceInPlace(setOf("a", "b"), setOf("a")))
    }
}
