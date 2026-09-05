package io.nekohasekai.sfa.vendor

import android.os.Build
import androidx.annotation.VisibleForTesting
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.update.UpdateCheckException
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable

class GitHubUpdateChecker : Closeable {
    companion object {
        /**
         * The `owner/repo` whose GitHub releases hold TarnVPN's own APKs. THE ONLY LINE that
         * has to change to point the updater somewhere else.
         *
         * It used to be hardcoded to `SagerNet/sing-box` — the upstream CORE repo, inherited
         * from SFA — which meant this app offered users an entirely different application:
         * since `applicationId` is now `app.tarnvpn` and the signing key is our own, an
         * upstream APK does not update TarnVPN, it installs a second app beside it. With
         * auto-update plus silent install both on, that would have happened without anyone
         * pressing a button. Never point this at a repository whose releases are not built
         * from this source and signed with this key.
         *
         * Requirements on every release (an unmet one makes the release be skipped in silence,
         * so they are worth keeping together):
         *  - the repository is public — the public GitHub API needs no token, and requiring
         *    every user to paste their own
         *    [io.nekohasekai.sfa.database.Settings.githubToken] is not realistic;
         *  - it carries a [METADATA_FILENAME] asset, exactly
         *    `{"version_code": <int>, "version_name": "<semver>"}` — without it the release is
         *    skipped (see [downloadMetadata]);
         *  - the APK asset is picked by FIRST match on "ends with .apk, no `play` in the name,
         *    `legacy-android-5` in the name iff the device is pre-M". Publish the *universal*
         *    APK (plus a separate `legacy-android-5` universal for Android 5); uploading the
         *    per-ABI splits alongside can hand an arm64 phone the x86 build.
         */
        private const val TARN_RELEASES_REPO = "Amesu-afk/TarnVPN"

        /**
         * Named after this app, not after SFA. The inherited name was `SFA-version-metadata.json`,
         * which would have put another project's name in every TarnVPN release; renaming it cost
         * nothing because no build that reads the old name was ever distributed — until this
         * commit [TARN_RELEASES_REPO] was empty, so no shipped build ever looked for a metadata
         * file at all.
         */
        private const val METADATA_FILENAME = "tarn-version-metadata.json"

        /** Whether this build knows where to look for its own releases. */
        val isConfigured: Boolean get() = TARN_RELEASES_REPO.isNotBlank()

        private val RELEASES_URL: String
            get() = "https://api.github.com/repos/$TARN_RELEASES_REPO/releases"

        /**
         * Picks the APK this device should actually download.
         *
         * The rule used to be "the FIRST asset that ends with `.apk`, has no `play` in the name and
         * carries `legacy-android-5` iff the device is pre-M". That is fine when a release holds one
         * APK per track and wrong the moment the per-ABI splits are attached alongside the universal
         * build: GitHub returns assets ordered by name, `…-arm64-v8a.apk` sorts before
         * `…-universal.apk`, so *every* device — armeabi-v7a and x86 included — was handed the arm64
         * split, which then refuses to install with INSTALL_FAILED_NO_MATCHING_ABIS. Release
         * `v1.14.0-alpha.47` shipped exactly that way.
         *
         * So match the device's own ABIs first, in [Build.SUPPORTED_ABIS] order (which is the
         * device's preference order, 64-bit before 32-bit), and fall back to the universal build.
         * The payoff is size: the arm64 split is ~31 MB against ~105 MB for universal.
         *
         * ABIs are matched as a whole `-<abi>.apk` suffix rather than with `contains`, because
         * `x86` is a prefix of `x86_64`: a 32-bit x86 device asking for "x86" would otherwise
         * happily accept the `x86_64` APK.
         *
         * NOTE for release-building: copies already in users' hands run the OLD first-match rule,
         * so a release may only carry the splits once the installed base is past this build. See
         * `RELEASING.md`.
         */
        @VisibleForTesting
        fun pickApkAsset(
            assets: List<GitHubAsset>,
            deviceAbis: List<String>,
            isLegacy: Boolean,
        ): GitHubAsset? {
            val candidates = assets.filter { asset ->
                asset.name.endsWith(".apk") &&
                    !asset.name.contains("play") &&
                    asset.name.contains("legacy-android-5") == isLegacy
            }
            if (candidates.isEmpty()) return null

            for (abi in deviceAbis) {
                val match = candidates.find { it.name.endsWith("-$abi.apk") }
                if (match != null) return match
            }

            // No split for this device (or a release that ships only the universal build).
            return candidates.find { it.name.endsWith("-universal.apk") }
        }
    }

    private val client = HTTPClient()

    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(track: UpdateTrack, githubToken: String): UpdateInfo? {
        // Before any network call: with no repository there is nothing to ask, and asking the
        // wrong one is exactly the bug this guards (see TARN_RELEASES_REPO).
        if (!isConfigured) throw UpdateCheckException.NotConfigured()

        val releases = getReleases(githubToken)
        var selected: ReleaseCandidate? = null

        for (release in releases) {
            if (!isReleaseInTrack(release, track)) {
                continue
            }
            if (pickApkAsset(release.assets, Build.SUPPORTED_ABIS.orEmpty().toList(), Build.VERSION.SDK_INT < Build.VERSION_CODES.M) == null) {
                continue
            }
            val metadata = runCatching { downloadMetadata(release) }.getOrNull() ?: continue
            if (!isNewerThanCurrent(metadata.versionName)) {
                continue
            }
            val currentBest = selected
            if (currentBest == null || isBetterVersion(metadata, currentBest.metadata)) {
                selected = ReleaseCandidate(release, metadata)
            }
        }

        val release = selected?.release ?: return null
        val metadata = selected.metadata

        val apkAsset = pickApkAsset(
            assets = release.assets,
            deviceAbis = Build.SUPPORTED_ABIS.orEmpty().toList(),
            isLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.M,
        ) ?: return null

        return UpdateInfo(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            downloadUrl = apkAsset.browserDownloadUrl,
            releaseUrl = release.htmlUrl,
            releaseNotes = release.body,
            isPrerelease = release.prerelease,
            fileSize = apkAsset.size,
        )
    }

    private fun getReleases(githubToken: String): List<GitHubRelease> {
        val headers = mutableMapOf("Accept" to "application/vnd.github.v3+json")
        val token = githubToken.trim()
        if (token.isNotEmpty()) {
            headers["Authorization"] = "Bearer $token"
        }
        val content = client.getString(RELEASES_URL, headers)

        return json.decodeFromString(content)
    }

    private fun isReleaseInTrack(release: GitHubRelease, track: UpdateTrack): Boolean {
        if (release.draft) {
            return false
        }
        return when (track) {
            UpdateTrack.STABLE -> !release.prerelease
            UpdateTrack.BETA -> true
        }
    }

    private fun isNewerThanCurrent(versionName: String): Boolean = Libbox.compareSemver(versionName, BuildConfig.VERSION_NAME)

    private fun isBetterVersion(version: VersionMetadata, other: VersionMetadata): Boolean {
        if (Libbox.compareSemver(version.versionName, other.versionName)) {
            return true
        }
        if (Libbox.compareSemver(other.versionName, version.versionName)) {
            return false
        }
        return version.versionCode > other.versionCode
    }

    private fun downloadMetadata(release: GitHubRelease): VersionMetadata? {
        val metadataAsset = release.assets.find { it.name == METADATA_FILENAME }
            ?: return null

        require(metadataAsset.browserDownloadUrl.startsWith("https://"))
        val content = client.getString(metadataAsset.browserDownloadUrl)

        return json.decodeFromString<VersionMetadata>(content)
    }

    override fun close() {
        client.close()
    }

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class VersionMetadata(
        @SerialName("version_code") val versionCode: Int = 0,
        @SerialName("version_name") val versionName: String = "",
    )

    private data class ReleaseCandidate(
        val release: GitHubRelease,
        val metadata: VersionMetadata,
    )
}
