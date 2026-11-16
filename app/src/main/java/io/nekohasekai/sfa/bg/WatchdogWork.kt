package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.database.Settings
import java.util.concurrent.TimeUnit

/**
 * A last-resort backstop for the tunnel dying with nobody watching: a core crash that took
 * the process down, the system reclaiming it under memory pressure, a sticky restart that
 * never managed to come up. [android.app.Service.START_STICKY] covers clean system kills;
 * this periodic task covers the rest, within the schedule WorkManager allows.
 *
 * It acts only when the user last had the tunnel up ([Settings.startedByUser]) — a
 * deliberate disconnect must stay down — and only when no live service instance reports a
 * status ([BoxService.tunnelStatus] is null): a service that is alive owns its own
 * lifecycle, including every in-process recovery path. It never acts quietly: every
 * restore attempt posts a notification, because the minutes the tunnel was gone are
 * minutes the traffic ran unprotected and the user is entitled to know that. It is not a
 * kill switch — the gap before the next run stays unprotected.
 */
class WatchdogWork {
    companion object {
        private const val TAG = "WatchdogWork"
        private const val WORK_NAME = "TunnelWatchdog"
        private const val NOTIFICATION_ID = 0x5744
        const val CHANNEL_ID = "watchdog"

        /** Re-read [Settings.tarnWatchdog] into the periodic schedule. */
        fun reconfigure() {
            runCatching {
                val manager = WorkManager.getInstance(Application.application)
                if (Settings.tarnWatchdog) {
                    manager.enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        PeriodicWorkRequest.Builder(WatchdogTask::class.java, 15L, TimeUnit.MINUTES)
                            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                            .build(),
                    )
                } else {
                    manager.cancelUniqueWork(WORK_NAME)
                }
            }.onFailure {
                Log.e(TAG, "reconfigure watchdog", it)
            }
        }
    }

    class WatchdogTask(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            if (!Settings.tarnWatchdog || !Settings.startedByUser) return Result.success()
            // A non-null mirror means a live service instance in this process — started,
            // starting, stopped or mid-stop, every one of those states is that instance's
            // own business. Null is the watchdog's case: the process died with the flag up.
            if (BoxService.tunnelStatus != null) return Result.success()
            return try {
                BoxService.start()
                notify(
                    R.string.tarn_watchdog_recovered_title,
                    R.string.tarn_watchdog_recovered_body,
                )
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "watchdog restart failed", e)
                notify(
                    R.string.tarn_watchdog_failed_title,
                    R.string.tarn_watchdog_failed_body,
                )
                Result.retry()
            }
        }

        private fun notify(titleRes: Int, bodyRes: Int) {
            val context = Application.application
            val manager = Application.notificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.tarn_watchdog_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_menu)
                .setContentTitle(context.getString(titleRes))
                .setContentText(context.getString(bodyRes))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(bodyRes)))
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        ServiceNotification.flags,
                    ),
                )
            runCatching {
                manager.notify(NOTIFICATION_ID, builder.build())
            }.onFailure { Log.w(TAG, "watchdog notification failed", it) }
        }
    }
}
