package io.nekohasekai.sfa.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log

object ConnectivityBinderUtils {
    private const val TAG = "ConnectivityBinderUtils"
    private const val HIDDEN_CONNECTIVITY_API_BLOCKED_SDK = 35

    fun getBinder(context: Context): IBinder? {
        // Android 15 blocks this hidden binder path for apps targeting API 35. The privileged
        // hook is optional, so surface an unavailable service instead of repeatedly reflecting
        // a member that the platform will reject.
        if (Build.VERSION.SDK_INT >= HIDDEN_CONNECTIVITY_API_BLOCKED_SDK) {
            Log.w(TAG, "Connectivity binder bridge is unavailable on Android 15 and newer")
            return null
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return getPreAndroid15Binder(cm)
    }

    /** The bridge has no public SDK equivalent on Android 14 and older. */
    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    private fun getPreAndroid15Binder(cm: ConnectivityManager): IBinder? {
        try {
            val field = cm.javaClass.getDeclaredField("mService")
            field.isAccessible = true
            val service = field.get(cm) as? android.os.IInterface
            if (service != null) {
                return service.asBinder()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ConnectivityManager service binder", e)
        }
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, Context.CONNECTIVITY_SERVICE) as? IBinder
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get binder from ServiceManager", e)
            null
        }
    }

    inline fun <T> withParcel(block: (data: Parcel, reply: Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            block(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
