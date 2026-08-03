package io.nekohasekai.sfa.vendor

import io.nekohasekai.sfa.vendor.GitHubUpdateChecker.GitHubAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the asset-picking rule, which is where release `v1.14.0-alpha.47` went wrong: the
 * per-ABI splits were attached alongside the universal build and the old "first match" rule
 * handed the arm64 split to every device.
 */
class GitHubUpdateCheckerApkPickTest {
    private val version = "1.15.0"

    private fun asset(name: String) = GitHubAsset(name = name, browserDownloadUrl = "https://x/$name", size = 1)

    /** A release built the way RELEASING.md describes once splits are allowed. */
    private fun fullRelease() = listOf(
        asset("tarn-version-metadata.json"),
        asset("TarnVPN-$version-arm64-v8a.apk"),
        asset("TarnVPN-$version-armeabi-v7a.apk"),
        asset("TarnVPN-$version-legacy-android-5-universal.apk"),
        asset("TarnVPN-$version-universal.apk"),
        asset("TarnVPN-$version-x86.apk"),
        asset("TarnVPN-$version-x86_64.apk"),
    )

    @Test
    fun `arm64 device gets the arm64 split, not the alphabetically first asset`() {
        val picked = GitHubUpdateChecker.pickApkAsset(
            assets = fullRelease(),
            deviceAbis = listOf("arm64-v8a", "armeabi-v7a"),
            isLegacy = false,
        )
        assertEquals("TarnVPN-$version-arm64-v8a.apk", picked?.name)
    }

    /** The regression: a 32-bit arm phone used to be handed `-arm64-v8a.apk` and could not install it. */
    @Test
    fun `armeabi-v7a device gets its own split`() {
        val picked = GitHubUpdateChecker.pickApkAsset(
            assets = fullRelease(),
            deviceAbis = listOf("armeabi-v7a"),
            isLegacy = false,
        )
        assertEquals("TarnVPN-$version-armeabi-v7a.apk", picked?.name)
    }

    /** `x86` is a prefix of `x86_64`, so a `contains` match would take the 64-bit APK here. */
    @Test
    fun `32-bit x86 device does not match the x86_64 asset`() {
        val picked = GitHubUpdateChecker.pickApkAsset(
            assets = fullRelease(),
            deviceAbis = listOf("x86"),
            isLegacy = false,
        )
        assertEquals("TarnVPN-$version-x86.apk", picked?.name)
    }

    @Test
    fun `x86_64 device prefers x86_64 over the x86 fallback in its ABI list`() {
        val picked = GitHubUpdateChecker.pickApkAsset(
            assets = fullRelease(),
            deviceAbis = listOf("x86_64", "x86"),
            isLegacy = false,
        )
        assertEquals("TarnVPN-$version-x86_64.apk", picked?.name)
    }

    @Test
    fun `unknown ABI falls back to universal rather than to whatever sorts first`() {
        val picked = GitHubUpdateChecker.pickApkAsset(
            assets = fullRelease(),
            deviceAbis = listOf("riscv64"),
            isLegacy = false,
        )
        assertEquals("TarnVPN-$version-universal.apk", picked?.name)
    }

    /** A pre-M device must never be offered a non-legacy APK, split or not. */
    @Test
    fun `legacy device gets the legacy build`() {
        val picked = GitHubUpdateChecker.pickApkAsset(
            assets = fullRelease(),
            deviceAbis = listOf("armeabi-v7a"),
            isLegacy = true,
        )
        assertEquals("TarnVPN-$version-legacy-android-5-universal.apk", picked?.name)
    }

    /** Today's shape: universal only. Every device has to end up on it. */
    @Test
    fun `universal-only release works for every device`() {
        val assets = listOf(
            asset("tarn-version-metadata.json"),
            asset("TarnVPN-$version-legacy-android-5-universal.apk"),
            asset("TarnVPN-$version-universal.apk"),
        )
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
            val picked = GitHubUpdateChecker.pickApkAsset(assets, listOf(abi), isLegacy = false)
            assertEquals("TarnVPN-$version-universal.apk", picked?.name)
        }
    }

    @Test
    fun `play asset is never offered`() {
        val picked = GitHubUpdateChecker.pickApkAsset(
            assets = listOf(asset("TarnVPN-$version-play-universal.apk")),
            deviceAbis = listOf("arm64-v8a"),
            isLegacy = false,
        )
        assertNull(picked)
    }

    @Test
    fun `release without any APK yields null rather than the metadata file`() {
        val picked = GitHubUpdateChecker.pickApkAsset(
            assets = listOf(asset("tarn-version-metadata.json")),
            deviceAbis = listOf("arm64-v8a"),
            isLegacy = false,
        )
        assertNull(picked)
    }

    /** Non-legacy devices must not be handed the Android 5 build when no other APK matches. */
    @Test
    fun `legacy asset is not offered to a modern device`() {
        val picked = GitHubUpdateChecker.pickApkAsset(
            assets = listOf(asset("TarnVPN-$version-legacy-android-5-universal.apk")),
            deviceAbis = listOf("arm64-v8a"),
            isLegacy = false,
        )
        assertNull(picked)
    }
}
