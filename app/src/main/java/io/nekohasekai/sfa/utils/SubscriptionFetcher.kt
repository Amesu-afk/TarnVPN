package io.nekohasekai.sfa.utils

import android.content.Context
import io.nekohasekai.sfa.R
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal class UnsupportedSubscriptionFormatException : IllegalArgumentException(
    "Subscription returned a sing-box JSON configuration, but no compatible share-link list was available.",
)

/** Requests share links when a provider returns a complete sing-box config by default. */
internal object SubscriptionFetcher {
    private const val SHARE_LINKS_USER_AGENT = "sing-box/1.12.0"

    fun errorMessage(context: Context, error: Throwable): String? =
        if (error is UnsupportedSubscriptionFormatException) {
            context.getString(R.string.tarn_subscription_format_unsupported)
        } else {
            error.message
        }

    fun fetch(url: String): String = chooseResponse { agent ->
        if (agent == null) {
            HTTPClient().use { it.getString(url) }
        } else {
            HTTPClient(agent).use { it.getString(url) }
        }
    }

    internal fun chooseResponse(request: (String?) -> String): String {
        val original = request(null)
        if (!isSingBoxConfig(original)) return original

        // Some providers negotiate a complete config for SFA but share links for sing-box.
        // Preserve the original response if they do not offer a usable alternative.
        val alternative = try {
            request(SHARE_LINKS_USER_AGENT)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return original
        }
        return if (VlessImporter.hasSubscriptionEntries(alternative)) alternative else original
    }

    internal fun isSingBoxConfig(body: String): Boolean {
        val text = body.trimStart().removePrefix("\uFEFF").trimStart()
        if (!text.startsWith('{') || text.length > 1_048_576) return false
        return try {
            JSONObject(text).optJSONArray("outbounds") != null
        } catch (_: org.json.JSONException) {
            false
        }
    }
}
