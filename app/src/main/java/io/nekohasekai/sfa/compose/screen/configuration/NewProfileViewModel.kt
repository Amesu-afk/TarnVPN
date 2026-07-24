package io.nekohasekai.sfa.compose.screen.configuration

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.VlessImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Date

data class NewProfileUiState(
    val name: String = "",
    val profileType: ProfileType = ProfileType.Local,
    val profileSource: ProfileSource = ProfileSource.CreateNew,
    // Remote profile fields
    val remoteUrl: String = "",
    val autoUpdate: Boolean = true,
    val autoUpdateInterval: Int = 60,
    // File import
    val importUri: Uri? = null,
    val importFileName: String? = null,
    // Pasted URI (vless://... turns into a Local profile)
    val pastedUri: String = "",
    // QRS import
    val qrsData: ByteArray? = null,
    // State
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false,
    val createdProfile: Profile? = null,
    // Field errors
    val nameError: String? = null,
    val remoteUrlError: String? = null,
    val importError: String? = null,
)

enum class ProfileType {
    Local,
    Remote,
}

enum class ProfileSource {
    CreateNew,
    Import,
}

class NewProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(NewProfileUiState())
    val uiState: StateFlow<NewProfileUiState> = _uiState.asStateFlow()

    fun initializeFromQRImport(name: String?, url: String?) {
        if (name != null && url != null) {
            val remoteUrlError =
                if (isHttpsUrl(url)) {
                    null
                } else {
                    getApplication<Application>().getString(R.string.profile_url_https_required)
                }
            _uiState.update {
                it.copy(
                    name = name,
                    profileType = ProfileType.Remote,
                    remoteUrl = url,
                    remoteUrlError = remoteUrlError,
                )
            }
        }
    }

    fun initializeFromQRSImport(name: String?, qrsData: ByteArray) {
        _uiState.update {
            it.copy(
                name = name ?: "",
                profileType = ProfileType.Local,
                profileSource = ProfileSource.Import,
                qrsData = qrsData,
            )
        }
    }

    fun updateName(name: String) {
        _uiState.update {
            it.copy(
                name = name,
                nameError = if (name.isNotBlank()) null else it.nameError,
            )
        }
    }

    fun updateProfileType(type: ProfileType) {
        _uiState.update { it.copy(profileType = type) }
    }

    fun updateProfileSource(source: ProfileSource) {
        _uiState.update {
            it.copy(
                profileSource = source,
                importError = null, // Clear import error when changing source
            )
        }
    }

    fun updatePastedUri(uri: String) {
        _uiState.update {
            it.copy(pastedUri = uri, importError = null)
        }
    }

    fun updateRemoteUrl(url: String) {
        _uiState.update {
            it.copy(
                remoteUrl = url,
                remoteUrlError = if (url.isNotBlank()) null else it.remoteUrlError,
            )
        }
    }

    fun updateAutoUpdate(enabled: Boolean) {
        _uiState.update { it.copy(autoUpdate = enabled) }
    }

    fun updateAutoUpdateInterval(interval: String) {
        val intValue = interval.toIntOrNull() ?: 60
        _uiState.update { it.copy(autoUpdateInterval = intValue.coerceAtLeast(15)) }
    }

    fun setImportUri(uri: Uri, fileName: String?) {
        _uiState.update {
            it.copy(
                importUri = uri,
                importFileName = fileName,
                importError = null, // Clear error when file is selected
                name =
                if (it.name.isEmpty()) {
                    fileName?.substringBeforeLast(".") ?: "Imported Profile"
                } else {
                    it.name
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun validateAndCreateProfile(): Boolean {
        val state = _uiState.value
        val context = getApplication<Application>()

        // Clear previous errors
        _uiState.update {
            it.copy(
                nameError = null,
                remoteUrlError = null,
                importError = null,
            )
        }

        var hasError = false

        // Validate name
        if (state.name.isBlank()) {
            _uiState.update { it.copy(nameError = context.getString(R.string.profile_input_required)) }
            hasError = true
        }

        // Validate based on profile type
        when (state.profileType) {
            ProfileType.Local -> {
                val importError = validateLocalImport(state, context)
                if (importError != null) {
                    _uiState.update { it.copy(importError = importError) }
                    hasError = true
                }
            }
            ProfileType.Remote -> {
                if (state.remoteUrl.isBlank()) {
                    _uiState.update {
                        it.copy(remoteUrlError = context.getString(R.string.profile_input_required))
                    }
                    hasError = true
                } else if (!isHttpsUrl(state.remoteUrl)) {
                    _uiState.update {
                        it.copy(remoteUrlError = context.getString(R.string.profile_url_https_required))
                    }
                    hasError = true
                }
            }
        }

        if (hasError) {
            return false
        }

        // If validation passes, create the profile
        createProfile(state)
        return true
    }

    private fun validateLocalImport(state: NewProfileUiState, context: Application): String? {
        if (state.profileSource != ProfileSource.Import) return null

        val pastedUri = state.pastedUri.trim()
        return when {
            pastedUri.isNotEmpty() &&
                (VlessImporter.isVlessUri(pastedUri) || isHttpsUrl(pastedUri)) -> null
            pastedUri.isNotEmpty() -> context.getString(R.string.profile_url_https_required)
            state.qrsData != null -> null
            state.importUri == null -> context.getString(R.string.profile_input_required)
            isSupportedLocalImportUri(state.importUri) -> null
            else -> context.getString(R.string.profile_url_https_required)
        }
    }

    private fun createProfile(state: NewProfileUiState) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            try {
                val profile =
                    withContext(Dispatchers.IO) {
                        when (state.profileType) {
                            ProfileType.Local -> createLocalProfile(state)
                            ProfileType.Remote -> createRemoteProfile(state)
                        }
                    }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isSuccess = true,
                        createdProfile = profile,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = e.message ?: "Unknown error",
                    )
                }
            }
        }
    }

    private suspend fun createLocalProfile(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Local
            }

        val profile =
            Profile(name = state.name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typedProfile.path = configFile.path

        // Get config content
        val configContent =
            when (state.profileSource) {
                ProfileSource.CreateNew -> "{}"
                ProfileSource.Import ->
                    when {
                        state.pastedUri.isNotBlank() -> {
                            require(
                                VlessImporter.isVlessUri(state.pastedUri) || isHttpsUrl(state.pastedUri),
                            ) {
                                context.getString(R.string.profile_url_https_required)
                            }
                            VlessImporter.toSingBoxJson(state.pastedUri) { url ->
                                HTTPClient().use { it.getString(url) }
                            }
                        }
                        state.qrsData != null -> {
                            val content = Libbox.decodeProfileContent(state.qrsData)
                            content.config
                        }
                        state.importUri != null -> {
                            val uri = state.importUri
                            val sourceURL = uri.toString()
                            when {
                                uri.scheme.equals("content", ignoreCase = true) -> {
                                    val inputStream = context.contentResolver.openInputStream(uri) as InputStream
                                    inputStream.use { it.bufferedReader().readText() }
                                }
                                uri.scheme.equals("file", ignoreCase = true) -> {
                                    File(Uri.parse(sourceURL).path!!).readText()
                                }
                                isHttpsUri(uri) -> {
                                    HTTPClient().use { it.getString(sourceURL) }
                                }
                                else -> throw IllegalArgumentException(
                                    context.getString(R.string.profile_url_https_required),
                                )
                            }
                        }
                        else -> throw IllegalArgumentException(
                            context.getString(R.string.profile_input_required),
                        )
                    }
            }

        // Validate config
        Libbox.checkConfig(configContent)
        configFile.writeText(configContent)

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)

        return profile
    }

    private suspend fun createRemoteProfile(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()
        val remoteUrl = state.remoteUrl.trim()
        require(isHttpsUrl(remoteUrl)) {
            context.getString(R.string.profile_url_https_required)
        }
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                remoteURL = remoteUrl
                autoUpdate = state.autoUpdate
                autoUpdateInterval = state.autoUpdateInterval
                lastUpdated = Date()
            }

        val profile =
            Profile(name = state.name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typedProfile.path = configFile.path

        // Fetch initial config - this MUST succeed for remote profiles
        val content = HTTPClient().use { it.getString(remoteUrl) }
        Libbox.checkConfig(content)
        val configContent = content

        configFile.writeText(configContent)

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)

        // Reconfigure updater if auto-update is enabled
        if (state.autoUpdate) {
            UpdateProfileWork.reconfigureUpdater()
        }

        return profile
    }

    private fun isSupportedLocalImportUri(uri: Uri): Boolean =
        uri.scheme.equals("content", ignoreCase = true) ||
            uri.scheme.equals("file", ignoreCase = true) ||
            isHttpsUri(uri)

    private fun isHttpsUrl(url: String): Boolean = isHttpsUri(Uri.parse(url.trim()))

    private fun isHttpsUri(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}
