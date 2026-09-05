package io.nekohasekai.sfa.tarn.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarnSubscriptionSafetyTest {

    @Test
    fun `keeps old profiles when a response has rejected links and would remove servers`() {
        assertTrue(TarnSubscriptionSafety.keepExistingProfiles(missingProfileCount = 3, rejectedLinkCount = 1))
    }

    @Test
    fun `permits a clean provider removal`() {
        assertFalse(TarnSubscriptionSafety.keepExistingProfiles(missingProfileCount = 3, rejectedLinkCount = 0))
    }

    @Test
    fun `permits a partial report that changes no existing profile`() {
        assertFalse(TarnSubscriptionSafety.keepExistingProfiles(missingProfileCount = 0, rejectedLinkCount = 2))
    }
}
