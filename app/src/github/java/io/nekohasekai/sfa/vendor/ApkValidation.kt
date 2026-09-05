package io.nekohasekai.sfa.vendor

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** Validate every entry path, including an APK restored from the update cache. */
object ApkValidation {
    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File) {
        require(file.isFile && file.length() in 1..(256L * 1024 * 1024)) { "Invalid APK size" }
        val manager = context.packageManager
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: error("Downloaded file is not an APK")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        validatePackage(candidate, installed, context.packageName)
    }

    @Suppress("DEPRECATION")
    internal fun validatePackage(candidate: PackageInfo, installed: PackageInfo, packageName: String) {
        require(candidate.packageName == packageName) { "APK belongs to another application" }
        val candidateVersion = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val installedVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(candidateVersion > installedVersion) { "APK is not a newer version" }
        if (Build.VERSION.SDK_INT >= 24) {
            require((candidate.applicationInfo?.minSdkVersion ?: 0) <= Build.VERSION.SDK_INT) { "APK requires a newer Android version" }
        }
        val expected = installed.signatures.orEmpty().toSet()
        require(expected.isNotEmpty() && candidate.signatures.orEmpty().toSet() == expected) {
            "APK signing certificate does not match the installed application"
        }
    }
}
