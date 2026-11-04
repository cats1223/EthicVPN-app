package com.techteam.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.techteam.vpn.R
import com.techteam.vpn.core.ServerProfile
import com.techteam.vpn.core.LanRoutes
import com.techteam.vpn.core.XrayConfig
import com.techteam.vpn.core.XrayCore
import com.techteam.vpn.data.AppSettings
import com.techteam.vpn.data.ProfileRepository
import com.techteam.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TechTeamVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var watchdog: Job? = null
    private var trafficJob: Job? = null
    private var lastBytes: Pair<Long, Long>? = null
    /** Xray's access log while ad blocking is on; read and emptied with each traffic save. */
    private var accessLog: java.io.File? = null

    override fun onCreate() {
        super.onCreate()
        VpnStatus.appContext = applicationContext
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> scope.launch { disconnect() }
            // null action: the system restarted us for always-on VPN.
            else -> {
                startForegroundCompat()
                scope.launch { connect() }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        scope.launch { disconnect() }
    }

    override fun onDestroy() {
        stopIfRunning()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun connect() = lock.withLock {
        if (tun != null) return@withLock
        val profile = ProfileRepository.get(this).state.value.selected
        if (profile == null) {
            VpnStatus.set(VpnState.Error(getString(R.string.error_no_server)))
            stopSelfCompletely()
            return@withLock
        }
        VpnStatus.set(VpnState.Connecting(profile.name))
        // A failed start (no network for a moment, the core not ready) is retried before giving up.
        var error: Exception? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            try {
                start(profile)
                VpnStatus.set(VpnState.Connected(profile.name, System.currentTimeMillis()))
                updateNotification(getString(R.string.notification_connected, profile.name))
                watchCore()
                return@withLock
            } catch (e: Exception) {
                Log.e(TAG, "connect attempt $attempt failed", e)
                stopCore()
                error = e
                if (attempt < CONNECT_ATTEMPTS) delay(3000)
            }
        }
        VpnStatus.set(VpnState.Error(error?.message ?: error?.javaClass?.simpleName.orEmpty()))
        stopSelfCompletely()
    }

    private fun start(profile: ServerProfile) {
        val settings = AppSettings.get(this)
        val fd = establish(profile, settings.bypassApps.value)
        tun = fd
        // The detailed Xray log is only kept in developer mode.
        val log = java.io.File(filesDir, LOG_FILE).apply { writeText("") }
        val logPath = if (settings.devMode.value) log.path else null
        val russiaDirect = settings.russiaDirect.value
        val blockAds = settings.blockAds.value
        val dns = XrayConfig.upstreamDns(settings.dns.value, settings.customDns.value)
        accessLog = if (blockAds) java.io.File(cacheDir, "access.log").apply { writeText("") } else null
        val assetDir = if (russiaDirect || blockAds) installGeoData() else null
        val config = XrayConfig.build(
            profile, fd.fd, logPath, russiaDirect, assetDir,
            directSites = settings.directSites.value, proxySites = settings.proxySites.value, blockAds = blockAds, dns = dns, accessLogPath = accessLog?.path,
        )
        XrayCore.start(config, "${XrayConfig.DNS_SERVERS.first()}:53") { protect(it) }
        countTraffic()
    }

    /**
     * Adds the VPN's traffic to the per-day history every 10 s. Xray's own sockets carry it all
     * and belong to this app, so the app's byte counters are the VPN's traffic.
     */
    private fun countTraffic() {
        trafficJob?.cancel()
        lastBytes = appBytes()
        trafficJob = scope.launch {
            while (true) {
                delay(10_000)
                saveTraffic()
            }
        }
    }

    private fun saveTraffic() {
        val now = appBytes()
        val last = lastBytes
        if (now != null) lastBytes = now
        // Xray appends to the file, so emptying it after reading loses nothing that matters.
        val blocked = accessLog?.let { file ->
            runCatching { file.readText().also { file.writeText("") } }.getOrNull()?.let(XrayConfig::countBlocked)
        } ?: 0
        val down = if (now != null && last != null) now.first - last.first else 0
        val up = if (now != null && last != null) now.second - last.second else 0
        AppSettings.get(this).addTraffic(down, up, blocked)
    }

    private fun appBytes(): Pair<Long, Long>? {
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        return if (rx < 0 || tx < 0) null else rx to tx
    }

    /** If the core stops on its own while the VPN is meant to be up, start it again. */
    private fun watchCore() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                delay(10_000)
                if (tun == null) return@launch
                if (runCatching { XrayCore.isRunning() }.getOrDefault(true)) continue
                Log.w(TAG, "Xray stopped on its own, reconnecting")
                lock.withLock { stopCore() }
                connect()
                return@launch
            }
        }
    }

    private suspend fun disconnect() = lock.withLock {
        watchdog?.cancel()
        stopCore()
        VpnStatus.set(VpnState.Disconnected)
        stopSelfCompletely()
    }

    /** Xray reads geoip.dat and geosite.dat from a directory, so copy them out of the APK. */
    private fun installGeoData(): String {
        val dir = java.io.File(filesDir, "geodata").apply { mkdirs() }
        for (name in listOf("geoip.dat", "geosite.dat")) {
            assets.open(name).use { input -> java.io.File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        return dir.path
    }

    private fun establish(profile: ServerProfile, bypassApps: Set<String>): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(profile.name)
            .setMtu(XrayConfig.TUN_MTU)
            .addAddress("172.19.0.1", 30)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)
            .setBlocking(false)
            .setConfigureIntent(mainActivityIntent())
        // All of IPv4 except the home network, so a PC, printer or console on Wi-Fi stays reachable.
        LanRoutes.routes().forEach { (address, prefix) -> builder.addRoute(address, prefix) }
        XrayConfig.DNS_SERVERS.forEach { builder.addDnsServer(it) }
        // Our own sockets (Xray's outbound connections) must bypass the tunnel.
        builder.addDisallowedApplication(packageName)
        // Apps chosen in split tunneling never see the tunnel: their default network stays the
        // phone's own, so they connect from the real address. Uninstalled ones are skipped.
        for (app in bypassApps - packageName) {
            try {
                builder.addDisallowedApplication(app)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder.establish() ?: throw IllegalStateException(getString(R.string.error_vpn_permission))
    }

    private fun stopCore() {
        trafficJob?.cancel()
        trafficJob = null
        if (lastBytes != null) runCatching { saveTraffic() }
        lastBytes = null
        runCatching { XrayCore.stop() }.onFailure { Log.w(TAG, "stopXray", it) }
        runCatching { tun?.close() }
        tun = null
    }

    private fun stopIfRunning() {
        if (tun != null) {
            stopCore()
            VpnStatus.set(VpnState.Disconnected)
        }
    }

    private fun stopSelfCompletely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(getString(R.string.status_connecting)), type)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TechTeamVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(mainActivityIntent())
            .addAction(0, getString(R.string.action_disconnect), stop)
            .build()
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "TechTeamVPN"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_ATTEMPTS = 3
        const val LOG_FILE = "xray.log"
        const val ACTION_CONNECT = "com.techteam.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.techteam.vpn.DISCONNECT"

        /** The caller must have obtained consent through [VpnService.prepare] first. */
        fun connect(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TechTeamVpnService::class.java).setAction(ACTION_CONNECT),
            )
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, TechTeamVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
