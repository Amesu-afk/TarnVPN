package io.nekohasekai.sfa.compose.screen.configuration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.ProfileContent
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.Date

private const val MAX_PROFILE_IMPORT_BYTES = 16L * 1024 * 1024

class ProfileImportHandler(private val context: Context) {
    sealed class ImportResult {
        data class Success(val profile: Profile) : ImportResult()

        data class Error(val message: String) : ImportResult()
    }

    sealed class QRCodeParseResult {
        data class RemoteProfile(val name: String, val host: String, val url: String) : QRCodeParseResult()

        data class LocalProfile(val name: String) : QRCodeParseResult()

        data class Error(val message: String) : QRCodeParseResult()
    }

    sealed class QRSParseResult {
        data class Success(val name: String) : QRSParseResult()

        data class Error(val message: String) : QRSParseResult()
    }

    sealed class UriParseResult {
        data class Success(val name: String) : UriParseResult()

        data class Error(val message: String) : UriParseResult()
    }

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val data =
                readBytesLimited(uri)
                    ?: return@withContext ImportResult.Error(context.getString(R.string.error_empty_file))

            // Get the filename from the URI
            val filename = getFileNameFromUri(uri)

            // Try to detect if it's a JSON configuration file
            val dataString = String(data)
            if (isJsonConfiguration(dataString)) {
                // It's a JSON configuration, import it directly as a local profile
                return@withContext importJsonConfiguration(dataString, filename)
            }

            // Try to decode as ProfileContent (the old way)
            val content =
                try {
                    Libbox.decodeProfileContent(data)
                } catch (e: Exception) {
                    // If it fails, try one more time as JSON
                    if (dataString.trimStart().startsWith("{") || dataString.trimStart().startsWith("[")) {
                        return@withContext importJsonConfiguration(dataString, filename)
                    }
                    return@withContext ImportResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }

            importProfile(content)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun parseUri(uri: Uri): UriParseResult = withContext(Dispatchers.IO) {
        try {
            val data =
                readBytesLimited(uri)
                    ?: return@withContext UriParseResult.Error(context.getString(R.string.error_empty_file))

            val filename = getFileNameFromUri(uri)
            val dataString = String(data)

            if (isJsonConfiguration(dataString)) {
                return@withContext UriParseResult.Success(name = filename)
            }

            val content =
                try {
                    Libbox.decodeProfileContent(data)
                } catch (e: Exception) {
                    if (dataString.trimStart().startsWith("{") || dataString.trimStart().startsWith("[")) {
                        return@withContext UriParseResult.Success(name = filename)
                    }
                    return@withContext UriParseResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }

            UriParseResult.Success(name = content.name)
        } catch (e: Exception) {
            UriParseResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun parseQRCode(data: String): QRCodeParseResult = withContext(Dispatchers.IO) {
        try {
            // Check if it's a sing-box remote profile import link
            if (data.startsWith("sing-box://import-remote-profile")) {
                try {
                    val profileInfo = Libbox.parseRemoteProfileImportLink(data)
                    return@withContext QRCodeParseResult.RemoteProfile(
                        name = profileInfo.name,
                        host = profileInfo.host,
                        url = requireHttpsSubscription(profileInfo.url),
                    )
                } catch (e: Exception) {
                    return@withContext QRCodeParseResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }
            }

            // Check if it's a direct URL
            if (data.startsWith("http://", ignoreCase = true) || data.startsWith("https://", ignoreCase = true)) {
                val profileName = extractProfileNameFromUrl(data)
                return@withContext QRCodeParseResult.RemoteProfile(
                    name = profileName,
                    host = extractHostFromUrl(data),
                    url = requireHttpsSubscription(data),
                )
            }

            // Try to decode as profile content
            val content =
                try {
                    Libbox.decodeProfileContent(data.toByteArray())
                } catch (e: Exception) {
                    return@withContext QRCodeParseResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }

            return@withContext QRCodeParseResult.LocalProfile(name = content.name)
        } catch (e: Exception) {
            QRCodeParseResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun importFromQRCode(data: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            // Check if it's a sing-box remote profile import link
            if (data.startsWith("sing-box://import-remote-profile")) {
                try {
                    val profileInfo = Libbox.parseRemoteProfileImportLink(data)
                    return@withContext importRemoteProfile(profileInfo.name, profileInfo.url)
                } catch (e: Exception) {
                    return@withContext ImportResult.Error(
                        context.getString(R.string.error_decode_profile, e.message),
                    )
                }
            }

            // Check if it's a URL or direct profile content
            if (data.startsWith("http://", ignoreCase = true) || data.startsWith("https://", ignoreCase = true)) {
                // Handle remote profile URL
                val profileName = extractProfileNameFromUrl(data)
                importRemoteProfile(profileName, requireHttpsSubscription(data))
            } else {
                // Try to decode as profile content
                val content =
                    try {
                        Libbox.decodeProfileContent(data.toByteArray())
                    } catch (e: Exception) {
                        return@withContext ImportResult.Error(
                            context.getString(R.string.error_decode_profile, e.message),
                        )
                    }
                importProfile(content)
            }
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun parseQRSData(data: ByteArray): QRSParseResult = withContext(Dispatchers.IO) {
        try {
            val content = try {
                Libbox.decodeProfileContent(data)
            } catch (e: Exception) {
                return@withContext QRSParseResult.Error(
                    context.getString(R.string.error_decode_profile, e.message),
                )
            }
            QRSParseResult.Success(name = content.name)
        } catch (e: Exception) {
            QRSParseResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun importFromQRSData(data: ByteArray): ImportResult = withContext(Dispatchers.IO) {
        try {
            val content = try {
                Libbox.decodeProfileContent(data)
            } catch (e: Exception) {
                return@withContext ImportResult.Error(
                    context.getString(R.string.error_decode_profile, e.message),
                )
            }
            importProfile(content)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun importProfile(content: ProfileContent): ImportResult {
        val typedProfile = TypedProfile()
        val profile = Profile(name = content.name, typed = typedProfile)
        profile.userOrder = ProfileManager.nextOrder()

        when (content.type) {
            Libbox.ProfileTypeLocal -> {
                typedProfile.type = TypedProfile.Type.Local
            }
            Libbox.ProfileTypeiCloud -> {
                return ImportResult.Error(context.getString(R.string.icloud_profile_unsupported))
            }
            Libbox.ProfileTypeRemote -> {
                typedProfile.type = TypedProfile.Type.Remote
                typedProfile.remoteURL = requireHttpsSubscription(content.remotePath)
                typedProfile.autoUpdate = content.autoUpdate
                typedProfile.autoUpdateInterval = content.autoUpdateInterval
                typedProfile.lastUpdated = Date(content.lastUpdated)
            }
        }

        // Save config file
        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        configFile.writeText(content.config)
        typedProfile.path = configFile.path

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)

        return ImportResult.Success(profile)
    }

    private suspend fun importRemoteProfile(name: String, url: String): ImportResult {
        val validatedUrl = requireHttpsSubscription(url)
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                remoteURL = validatedUrl
                autoUpdate = true
                autoUpdateInterval = 60
                lastUpdated = Date()
            }

        val profile =
            Profile(name = name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        // Create empty config file for remote profile
        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        configFile.writeText("{}")
        typedProfile.path = configFile.path

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)

        return ImportResult.Success(profile)
    }

    private fun readBytesLimited(uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use(::readProfileBytes)
    }

    private fun requireHttpsSubscription(url: String): String {
        val parsed = URL(url)
        require(parsed.protocol.equals("https", ignoreCase = true) && !parsed.host.isNullOrEmpty()) {
            context.getString(R.string.profile_url_https_required)
        }
        return url
    }

    private fun extractProfileNameFromUrl(url: String): String {
        // Extract name from URL or use default
        return url.substringAfterLast("/")
            .substringBeforeLast(".")
            .takeIf { it.isNotEmpty() }
            ?: "Remote Profile"
    }

    private fun extractHostFromUrl(url: String): String = try {
        val uri = Uri.parse(url)
        uri.host ?: url
    } catch (e: Exception) {
        url
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var filename = "Imported Profile"

        // Try to get filename from content resolver
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                filename = cursor.getString(nameIndex)
                    ?.substringBeforeLast(".") // Remove extension
                    ?.takeIf { it.isNotEmpty() }
                    ?: filename
            }
        }

        // Fallback to getting from URI path
        if (filename == "Imported Profile") {
            uri.lastPathSegment?.let { segment ->
                filename = segment
                    .substringBeforeLast(".")
                    .takeIf { it.isNotEmpty() }
                    ?: filename
            }
        }

        return filename
    }

    private fun isJsonConfiguration(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false
        }

        return try {
            // Try to parse as JSON and check for sing-box configuration fields
            val json = JSONObject(content)
            // Check for common sing-box configuration fields
            json.has("inbounds") ||
                json.has("outbounds") ||
                json.has("route") ||
                json.has("dns") ||
                json.has("experimental")
        } catch (e: Exception) {
            // If it's an array, it might still be valid
            trimmed.startsWith("[") && trimmed.endsWith("]")
        }
    }

    private suspend fun importJsonConfiguration(jsonContent: String, profileName: String): ImportResult {
        return try {
            // Validate the JSON configuration using sing-box
            try {
                // Try to check the configuration
                Libbox.checkConfig(jsonContent)
            } catch (e: Exception) {
                // Configuration validation failed
                return ImportResult.Error(
                    context.getString(R.string.error_invalid_configuration, e.message),
                )
            }

            // Create a local profile with the JSON configuration
            val typedProfile =
                TypedProfile().apply {
                    type = TypedProfile.Type.Local
                }

            val profile =
                Profile(
                    name = profileName.ifEmpty { "Imported Profile" },
                    typed = typedProfile,
                ).apply {
                    userOrder = ProfileManager.nextOrder()
                }

            // Save the configuration file
            val fileID = ProfileManager.nextFileID()
            val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
            val configFile = File(configDirectory, "$fileID.json")
            configFile.writeText(jsonContent)
            typedProfile.path = configFile.path

            // Create profile in database and select it
            ProfileManager.create(profile, andSelect = true)

            ImportResult.Success(profile)
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error importing JSON configuration")
        }
    }
}

internal fun readProfileBytes(input: InputStream, maxBytes: Long = MAX_PROFILE_IMPORT_BYTES): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 64L * 1024).toInt())
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        require(output.size().toLong() + read <= maxBytes) {
            "Profile file exceeds the 16 MiB limit"
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
