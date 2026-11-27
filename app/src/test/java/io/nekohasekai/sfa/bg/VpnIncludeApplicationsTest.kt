package io.nekohasekai.sfa.bg

import org.junit.Assert.assertThrows
import org.junit.Test

class VpnIncludeApplicationsTest {
    @Test
    fun `nonempty include list fails closed when every package is missing`() {
        assertThrows(IllegalStateException::class.java) {
            VpnIncludeApplications.validate(hasRequestedPackages = true, addedPackageCount = 0)
        }
    }

    @Test
    fun `one installed package is enough for include mode`() {
        VpnIncludeApplications.validate(hasRequestedPackages = true, addedPackageCount = 1)
    }

    @Test
    fun `global mode does not require an included package`() {
        VpnIncludeApplications.validate(hasRequestedPackages = false, addedPackageCount = 0)
    }
}
