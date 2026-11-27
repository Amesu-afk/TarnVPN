package io.nekohasekai.sfa.bg

internal object VpnIncludeApplications {
    fun validate(hasRequestedPackages: Boolean, addedPackageCount: Int) {
        require(addedPackageCount >= 0)
        if (hasRequestedPackages) {
            check(addedPackageCount > 0) { "No included applications are installed" }
        }
    }
}
