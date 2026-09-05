package io.nekohasekai.sfa.vendor

import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("DEPRECATION")
class ApkValidationTest {
    private fun info(version: Int = 2, name: String = "test.app", signer: String = "1234") = PackageInfo().apply {
        packageName = name
        versionCode = version
        signatures = arrayOf(Signature(signer))
    }

    @Test
    fun `newer package with matching signer is accepted`() {
        ApkValidation.validatePackage(info(), info(version = 1), "test.app")
    }

    @Test
    fun `foreign package mismatched signer and downgrade are rejected`() {
        listOf(info(name = "other.app"), info(signer = "5678"), info(version = 1)).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                ApkValidation.validatePackage(candidate, info(version = 1), "test.app")
            }
        }
    }
}
