package io.nekohasekai.sfa.tarn.data

/** Safety gate for subscription reconciliation; kept pure so it is cheap to regression-test. */
internal object TarnSubscriptionSafety {

    /**
     * Removing a profile is safe only when every URI-shaped entry in the response was understood.
     * A rejected entry may be an endpoint from the previous list whose protocol was changed or
     * temporarily malformed by the provider, so absence alone is not evidence of a real removal.
     */
    fun keepExistingProfiles(missingProfileCount: Int, rejectedLinkCount: Int): Boolean = missingProfileCount > 0 && rejectedLinkCount > 0
}
