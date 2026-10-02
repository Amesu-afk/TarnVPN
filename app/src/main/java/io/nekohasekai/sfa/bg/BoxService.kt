package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.utils.RuDirectDomainUpdater
import io.nekohasekai.sfa.utils.VlessImporter
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
        private const val TAG = "BoxService"

        /**
         * How long a stop waits for the orderly shutdown before forcing the service into
         * [Status.Stopped] anyway. Long enough for a healthy close (which is well under a
         * second) plus a slow one, short enough that a user who pressed disconnect is not left
         * staring at a dead button.
         */
        private const val STOP_GRACE_PERIOD_MS = 5_000L

        private const val OLCRTC_WATCHDOG_INTERVAL_MS = 3_000L
        private const val OLCRTC_RETRY_MIN_MS = 5_000L
        private const val OLCRTC_RETRY_MAX_MS = 60_000L

        fun start() {
            val intent =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        Intent(Application.application, Settings.serviceClass())
                    }
                }
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop() {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ),
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private val olcRtcRuntime = OlcRtcRuntimeController(service)
    private lateinit var commandServer: CommandServer
    private val serviceReloadMutex = Mutex()

    // Reap stale transports without rebuilding the VPN interface. Screen and idle
    // events also cover a lost NAT mapping on an unchanged default Wi-Fi network.
    private val recoveryListenerKey = Any()
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var destroyed = false

    @Volatile
    private var shuttingDown = false

    // Reset on every start: a bound client (the activity binds with BIND_AUTO_CREATE) keeps
    // the Service object alive across stopSelf(), so the same BoxService instance serves the
    // next connection, and a leftover flag would turn its first stop into a forced one.
    @Volatile
    private var stopRequested = false
    private var recoveryListenerRegistered = false
    private var recoveryListenerInitialized = false
    private var recoveryNetwork: Network? = null
    private val wakeRecoveryPolicy = WakeRecoveryPolicy()
    private val networkRecovery = NetworkRecoveryScheduler(recoveryScope, SystemClock::elapsedRealtime) { reason ->
        recoverNetwork(reason)
    }

    @Volatile
    private var olcRtcWatchdogJob: Job? = null

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                    Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON,
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
                    PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED,
                    -> {
                        serviceUpdateIdleMode()
                    }
                }
            }
        }

    private fun startCommandServer() {
        val commandServer = CommandServer(this, platformInterface)
        commandServer.start()
        this.commandServer = commandServer
    }

    private var lastProfileName = ""

    private suspend fun startService() {
        shuttingDown = false
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            val selectedProfileId = Settings.selectedProfile
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            var content = File(profile.typed.path).readText()
            if (content.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            // Profiles are stored as the generator output from the time they were imported.
            // Revalidate the maintained direct list before the tunnel exists, then apply the
            // cached/current settings in memory so this connection receives it immediately.
            val updatedRuDirectDomainCount =
                if (Settings.tarnRuDirect && VlessImporter.isManagedConfig(content)) {
                    RuDirectDomainUpdater.refresh()
                } else {
                    null
                }
            content = VlessImporter.applyCurrentSettings(content) ?: content

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                updatedRuDirectDomainCount?.let { domainCount ->
                    Toast.makeText(
                        service,
                        service.getString(R.string.tarn_ru_direct_database_updated, domainCount),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            try {
                val olcRtcEndpoint = olcRtcRuntime.startForProfile(File(profile.typed.path))
                commandServer.startOrReloadService(
                    OlcRtcRuntimeController.applyLocalEndpoint(content, olcRtcEndpoint),
                    OverrideOptions().apply {
                        autoRedirect = Settings.autoRedirect
                        if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                            val appList = Settings.getEffectivePerAppProxyList()
                            if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                                includePackage =
                                    PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                            } else {
                                excludePackage =
                                    PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                            }
                        }
                    },
                )
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            status.postValue(Status.Started)
            syncNetworkRecovery()
            syncOlcRtcWatchdog()
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    /**
     * The other door into shutdown: libbox calls this when a command client asks the service
     * to stop. It is the same event as the user pressing disconnect and must end in the same
     * place, so it goes through the same code.
     *
     * It used to have a teardown of its own that left the status at `Starting` with
     * `shuttingDown` latched on and no `stopSelf()` — a state with no exit, exactly the trap
     * [stopService] was fixed for. Nothing reaches it today (the libbox command client has no
     * stop call), which is precisely why it was worth removing rather than leaving armed.
     */
    override fun serviceStop() {
        runBlocking {
            withContext(Dispatchers.Main) { stopService() }
        }
    }

    override fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        serviceReloadMutex.withLock { serviceReloadLocked() }
    }

    private suspend fun serviceReloadLocked() {
        if (destroyed || shuttingDown || status.value != Status.Started) return
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        var content = File(profile.typed.path).readText()
        if (content.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        // A recovery reload must retain the cached list that was applied at connection time.
        content = VlessImporter.applyCurrentSettings(content) ?: content
        lastProfileName = profile.name
        try {
            val olcRtcEndpoint = olcRtcRuntime.startForProfile(File(profile.typed.path))
            commandServer.startOrReloadService(
                OlcRtcRuntimeController.applyLocalEndpoint(content, olcRtcEndpoint),
                OverrideOptions().apply {
                    autoRedirect = Settings.autoRedirect
                    if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                        val appList = Settings.getEffectivePerAppProxyList()
                        if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                            includePackage = PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                        } else {
                            excludePackage = PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                        }
                    }
                },
            )
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
        syncNetworkRecovery()
        syncOlcRtcWatchdog()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun syncNetworkRecovery() {
        if (destroyed || shuttingDown || !Settings.tarnNetworkRecovery) {
            stopNetworkRecovery()
            return
        }
        if (recoveryListenerRegistered) return

        recoveryListenerInitialized = false
        recoveryNetwork = null
        recoveryListenerRegistered = true
        try {
            DefaultNetworkListener.start(recoveryListenerKey) listener@{ network ->
                val previous = recoveryNetwork
                recoveryNetwork = network
                if (!recoveryListenerInitialized) {
                    recoveryListenerInitialized = true
                    return@listener
                }
                if (network == null || network == previous) return@listener

                networkRecovery.request("network:$network", 1200L)
            }
        } catch (e: Exception) {
            recoveryListenerRegistered = false
            Log.w(TAG, "network recovery listener failed", e)
        }
    }

    private suspend fun stopNetworkRecovery() {
        networkRecovery.cancel()
        wakeRecoveryPolicy.reset(Application.powerManager.isInteractive, SystemClock.elapsedRealtime())
        recoveryListenerInitialized = false
        recoveryNetwork = null
        if (!recoveryListenerRegistered) return
        recoveryListenerRegistered = false
        withContext(NonCancellable) {
            runCatching { DefaultNetworkListener.stop(recoveryListenerKey) }
                .onFailure { Log.w(TAG, "network recovery listener stop failed", it) }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    private fun serviceUpdateIdleMode() {
        val power = Application.powerManager
        val deepIdle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && power.isDeviceIdleMode
        val lightIdle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && power.isDeviceLightIdleMode
        val needsRecovery = wakeRecoveryPolicy.update(power.isInteractive, deepIdle, lightIdle, SystemClock.elapsedRealtime())
        if (!::commandServer.isInitialized || destroyed || shuttingDown || status.value != Status.Started) return
        if (deepIdle) commandServer.pause() else commandServer.wake()
        if (needsRecovery && Settings.tarnNetworkRecovery) {
            // Closing stale sockets needs no radio reassociation delay. Applications
            // can immediately establish fresh streams when the screen becomes active.
            networkRecovery.request("idle-wake", 0L, wake = true)
        }
    }

    private suspend fun recoverNetwork(reason: String): Boolean = serviceReloadMutex.withLock {
        if (
            destroyed || shuttingDown || !Settings.tarnNetworkRecovery ||
            status.value != Status.Started || !::commandServer.isInitialized
        ) {
            return@withLock false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Application.powerManager.isDeviceIdleMode) return@withLock false
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // An exited external carrier needs reconstruction; ordinary transports
            // and DNS can recover through the existing native network-reset path.
            if (olcRtcRuntime.needsRestart()) {
                olcRtcRuntime.restartIfDead()
            }
            commandServer.resetNetwork()
            Log.i(TAG, "network recovery cause=$reason elapsed=${SystemClock.elapsedRealtime() - startedAt}ms")
            true
        } catch (e: Exception) {
            Log.w(TAG, "network recovery cause=$reason failed after ${SystemClock.elapsedRealtime() - startedAt}ms", e)
            false
        }
    }

    // An olcRTC carrier can exit on its own: the conference ends, or the carrier runs out of
    // reconnect attempts. Its local SOCKS listener goes with it, and sing-box keeps routing
    // into a closed port — the VPN shows Connected with no internet, and nothing but a network
    // change used to bring it back. Polling is enough here: the check is a mutex read, and the
    // carrier's own reconnects already cover everything short of a full exit.
    private fun syncOlcRtcWatchdog() {
        if (!olcRtcRuntime.hasSession) {
            // Not an olcRTC profile: no timer waking the device every few seconds for nothing.
            olcRtcWatchdogJob?.cancel()
            olcRtcWatchdogJob = null
            return
        }
        if (olcRtcWatchdogJob?.isActive == true) return
        olcRtcWatchdogJob = recoveryScope.launch {
            var retryDelay = OLCRTC_RETRY_MIN_MS
            while (isActive) {
                delay(OLCRTC_WATCHDOG_INTERVAL_MS)
                if (destroyed || shuttingDown || status.value != Status.Started) continue
                if (!olcRtcRuntime.needsRestart()) {
                    retryDelay = OLCRTC_RETRY_MIN_MS
                    continue
                }
                Log.w(TAG, "olcRTC carrier exited, restarting it")
                // The reload mutex keeps this from racing a reload or the orderly stop, which
                // both start or stop the same process-wide runtime.
                val result = runCatching {
                    serviceReloadMutex.withLock {
                        if (!shuttingDown) olcRtcRuntime.restartIfDead()
                    }
                }
                result.exceptionOrNull()?.let {
                    // The server side may still be coming back (a systemd restart, a new
                    // conference). Keep the tunnel up and retry, backing off.
                    Log.w(TAG, "olcRTC restart failed, retrying in ${retryDelay}ms", it)
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(OLCRTC_RETRY_MAX_MS)
                }
            }
        }
    }

    private fun stopOlcRtc(failureMessage: String) {
        olcRtcWatchdogJob?.cancel()
        olcRtcWatchdogJob = null
        runCatching { olcRtcRuntime.stop() }
            .onFailure { Log.w(TAG, failureMessage, it) }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        // Anything but Stopped, on purpose. This used to insist on Started, which made every
        // other state a trap: a start wedged inside libbox left the status at Starting with
        // the tunnel up, and then no stop request — button, notification, revoke — did
        // anything at all, because they all end up here.
        if (status.value == Status.Stopped) return
        if (stopRequested) {
            // Asked again while the first stop is still unwinding. The user is telling us the
            // orderly path is not getting there; take the terminal state now rather than
            // waiting out a grace period that has evidently already failed them.
            Log.w(TAG, "stop requested again while stopping, forcing shutdown")
            terminate()
            return
        }
        stopRequested = true
        shuttingDown = true
        status.value = Status.Stopping
        // The receiver stays registered until terminate(): unregistering it here left a
        // second SERVICE_CLOSE with nowhere to go, so a stop that got stuck could not even
        // be repeated.
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            // The graceful shutdown runs as its own job so the wait below can give up on it.
            // It used to be inline, and then a reload holding serviceReloadMutex could keep the
            // shutdown queued forever, with the status stuck at Stopping and no way left to
            // turn the VPN off at all. This app reloads on every settings change and on network
            // handover, so that window is not rare.
            val graceful = GlobalScope.launch(Dispatchers.IO) {
                serviceReloadMutex.withLock {
                    val pfd = fileDescriptor
                    if (pfd != null) {
                        pfd.close()
                        fileDescriptor = null
                    }
                    stopNetworkRecovery()
                    DefaultNetworkMonitor.stop()
                    closeService()
                    stopOlcRtc("olcRTC stop failed")
                    if (::commandServer.isInitialized) {
                        commandServer.close()
                    }
                }
            }
            // join() is what makes the bound real. Wrapping the cleanup itself in
            // withTimeoutOrNull would not: closeService() ends up in a blocking native call,
            // and coroutine cancellation cannot interrupt one — the timeout would only fire
            // after it returned anyway. Waiting on a separate job can be abandoned.
            if (withTimeoutOrNull(STOP_GRACE_PERIOD_MS) { graceful.join() } == null) {
                Log.w(TAG, "graceful stop still running after ${STOP_GRACE_PERIOD_MS}ms, forcing shutdown")
            }
            // Reached on both paths on purpose: whatever happened to the cleanup, the service
            // must end up in a terminal state the UI can act on.
            withContext(Dispatchers.Main) {
                terminate()
            }
        }
    }

    /**
     * The terminal state, and the only place that produces it. Idempotent, main thread only:
     * both the orderly stop and a forced one land here, and a forced one can arrive while the
     * orderly one is still queued behind a wedged reload.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun terminate() {
        runCatching { fileDescriptor?.close() }
        fileDescriptor = null
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        status.value = Status.Stopped
        service.stopSelf()
        recoveryScope.launch {
            stopOlcRtc("forced olcRTC stop failed")
        }
        // Off the main thread: this is a Room write, and it is only read at boot.
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { Settings.startedByUser = false }
        }
    }

    private fun closeService() {
        runCatching {
            commandServer.closeService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        shuttingDown = true
        Settings.startedByUser = false
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        stopNetworkRecovery()
        DefaultNetworkMonitor.stop()
        stopOlcRtc("olcRTC stop after start failure failed")
        if (::commandServer.isInitialized) {
            closeService()
            commandServer.close()
        }
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        if (status.value != Status.Stopped) return Service.START_NOT_STICKY
        status.value = Status.Starting
        stopRequested = false
        wakeRecoveryPolicy.reset(Application.powerManager.isInteractive, SystemClock.elapsedRealtime())

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            Settings.startedByUser = true
            try {
                startCommandServer()
            } catch (e: Exception) {
                stopAndAlert(Alert.StartCommandServer, e.message)
                return@launch
            }
            startService()
        }
        return Service.START_NOT_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        destroyed = true
        shuttingDown = true
        runBlocking(Dispatchers.IO) {
            stopNetworkRecovery()
            runCatching { DefaultNetworkMonitor.stop() }
                .onFailure { Log.w(TAG, "default network monitor stop failed", it) }
            stopOlcRtc("olcRTC destroy stop failed")
        }
        recoveryScope.cancel()
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val builder =
            NotificationCompat.Builder(service, notification.identifier).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        notification.identifier,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.typeID, builder.build())
        }
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}
