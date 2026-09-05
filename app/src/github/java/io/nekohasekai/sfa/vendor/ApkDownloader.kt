package io.nekohasekai.sfa.vendor

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

class ApkDownloader : Closeable {
    private val client = HTTPClient()

    suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val cacheDir = File(Application.application.cacheDir, "updates")
        cacheDir.mkdirs()
        require(url.startsWith("https://")) { "APK download requires HTTPS" }
        val apkFile = File.createTempFile("update-", ".apk", cacheDir)
        try {
            apkFile.outputStream().use { output ->
                client.download(url, output, 256L * 1024 * 1024, 10L * 60 * 1000) { progress, total ->
                    UpdateState.downloadProgress.value = if (total > 0) progress.toFloat() / total.toFloat() else null
                }
            }
            ApkValidation.validate(Application.application, apkFile)
        } catch (error: Throwable) {
            apkFile.delete()
            throw error
        }

        UpdateState.saveApkPath(apkFile)
        apkFile
    }

    override fun close() {
        client.close()
    }
}
