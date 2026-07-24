package io.nekohasekai.sfa.vendor

import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.ktx.unwrap
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
         * has to change to switch the updater on.
         *
         * Empty on purpose while no releases page exists. It used to be hardcoded to
         * `SagerNet/sing-box` — the upstream CORE repo, inherited from SFA — which meant this
         * app offered users an entirely different application: since `applicationId` is now
         * `app.tarnvpn` and the signing key is our own, an upstream APK does not update
         * TarnVPN, it installs a second app beside it. With auto-update plus silent install
         * both on, that would have happened without anyone pressing a button.
         *
         * Requirements on the repository once it exists (an unmet one makes a release be
         * skipped in silence, so they are worth keeping together):
         *  - public — the public GitHub API needs no token, and requiring every user to paste
         *    their own [io.nekohasekai.sfa.database.Settings.githubToken] is not realistic;
         *  - each release carries a `SFA-version-metadata.json` asset, exactly
         *    `{"version_code": <int>, "version_name": "<semver>"}` — without it the release is
         *    skipped (see [downloadMetadata]);
         *  - the APK asset is picked by FIRST match on "ends with .apk, no `play` in the name,
         *    `legacy-android-5` in the name iff the device is pre-M". Publish the *universal*
         *    APK (plus a separate `legacy-android-5` universal for Android 5); uploading the
         *    per-ABI splits alongside can hand an arm64 phone the x86 build.
         */
        private const val TARN_RELEASES_REPO = ""

        private const val METADATA_FILENAME = "SFA-version-metadata.json"

        /** Whether this build knows where to look for its own releases. */
        val isConfigured: Boolean get() = TARN_RELEASES_REPO.isNotBlank()

        private val RELEASES_URL: String
            get() = "https://api.github.com/repos/$TARN_RELEASES_REPO/releases"
    }

    private val client = Libbox.newHTTPClient().apply {
        modernTLS()
        keepAlive()
    }

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

        val isLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        val apkAsset = release.assets.find { asset ->
            asset.name.endsWith(".apk") &&
                !asset.name.contains("play") &&
                asset.name.contains("legacy-android-5") == isLegacy
        }

        return UpdateInfo(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            downloadUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl,
            releaseUrl = release.htmlUrl,
            releaseNotes = release.body,
            isPrerelease = release.prerelease,
            fileSize = apkAsset?.size ?: 0,
        )
    }

    private fun getReleases(githubToken: String): List<GitHubRelease> {
        val request = client.newRequest()
        request.setURL(RELEASES_URL)
        request.setHeader("Accept", "application/vnd.github.v3+json")
        val token = githubToken.trim()
        if (token.isNotEmpty()) {
            request.setHeader("Authorization", "Bearer $token")
        }
        request.setUserAgent(HTTPClient.userAgent)

        val response = request.execute()
        val content = response.content.unwrap

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

        val request = client.newRequest()
        request.setURL(metadataAsset.browserDownloadUrl)
        request.setUserAgent(HTTPClient.userAgent)

        val response = request.execute()
        val content = response.content.unwrap

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
