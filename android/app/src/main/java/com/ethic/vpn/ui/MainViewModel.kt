package com.ethic.vpn.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ethic.vpn.BuildConfig
import com.ethic.vpn.R
import com.ethic.vpn.core.Changelog
import com.ethic.vpn.core.DeepLink
import com.ethic.vpn.core.GeoIP
import com.ethic.vpn.core.Subscription
import com.ethic.vpn.core.Updates
import com.ethic.vpn.data.AppSettings
import com.ethic.vpn.data.AppUpdater
import com.ethic.vpn.data.ProfileRepository
import com.ethic.vpn.data.ProfilesState
import com.ethic.vpn.vpn.EthicVpnService
import com.ethic.vpn.vpn.VpnState
import com.ethic.vpn.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProfileRepository.get(app)

    val profiles: StateFlow<ProfilesState> = repo.state
    val vpn: StateFlow<VpnState> = VpnStatus.state

    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Profile id to TCP connect time in ms; -1 means unreachable. */
    val pings: StateFlow<Map<String, Long>> = _pings.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _update = MutableStateFlow<Updates.Release?>(null)
    /** A newer release to offer; null hides the dialog. */
    val update: StateFlow<Updates.Release?> = _update.asStateFlow()

    private val _homeLocation = MutableStateFlow<GeoIP.Location?>(null)
    /** Where the phone really is, looked up while the VPN is off. */
    val homeLocation: StateFlow<GeoIP.Location?> = _homeLocation.asStateFlow()

    private val _visibleLocation = MutableStateFlow<GeoIP.Location?>(null)
    /** Where sites see the phone now: the home location, or the server's while connected. */
    val visibleLocation: StateFlow<GeoIP.Location?> = _visibleLocation.asStateFlow()

    private val _serverLocations = MutableStateFlow<Map<String, GeoIP.Location>>(emptyMap())
    /** Server address to its location, for the map pin and the server's flag. */
    val serverLocations: StateFlow<Map<String, GeoIP.Location>> = _serverLocations.asStateFlow()

    /** Download and upload speed in bytes per second and the session's total; zero when not connected. */
    data class Traffic(val down: Long = 0, val up: Long = 0, val session: Long = 0)

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    init {
        // Pick up server list changes made in the panel since the last launch.
        if (repo.state.value.subscriptionUrl.isNotEmpty()) refreshSubscription(silent = true)
        viewModelScope.launch {
            // After the launch animation: first what changed in this version, then any update.
            kotlinx.coroutines.delay(2500)
            _whatsNew.value = pendingWhatsNew()
            if (_whatsNew.value == null) checkForUpdate(manual = false)
        }
        viewModelScope.launch {
            // Look the address up again whenever the VPN settles: the real one when off, the server's when on.
            VpnStatus.state.collect { state ->
                when (state) {
                    is VpnState.Connected -> refreshLocation(connected = true)
                    is VpnState.Disconnected, is VpnState.Error -> refreshLocation(connected = false)
                    else -> {}
                }
            }
        }
        viewModelScope.launch { repo.state.collect { locateSelectedServer() } }
        viewModelScope.launch {
            // Xray runs in this app, so the app's own byte counters are the VPN's traffic.
            VpnStatus.state.collectLatest { state ->
                _traffic.value = Traffic()
                if (state !is VpnState.Connected) return@collectLatest
                val uid = android.os.Process.myUid()
                val startRx = android.net.TrafficStats.getUidRxBytes(uid)
                val startTx = android.net.TrafficStats.getUidTxBytes(uid)
                var lastRx = startRx
                var lastTx = startTx
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val rx = android.net.TrafficStats.getUidRxBytes(uid)
                    val tx = android.net.TrafficStats.getUidTxBytes(uid)
                    _traffic.value = Traffic(rx - lastRx, tx - lastTx, (rx - startRx) + (tx - startTx))
                    lastRx = rx
                    lastTx = tx
                }
            }
        }
    }

    private suspend fun refreshLocation(connected: Boolean) {
        // Right after the tunnel comes up the first request can fail, so try a few times.
        var location: GeoIP.Location? = null
        for (attempt in 0 until 4) {
            if (attempt > 0) kotlinx.coroutines.delay(2000)
            // The VPN may have changed state meanwhile; a stale answer would misplace the pin.
            if (connected != (vpn.value is VpnState.Connected)) return
            location = withContext(Dispatchers.IO) { GeoIP.lookup() }
            if (location != null) break
        }
        if (connected != (vpn.value is VpnState.Connected)) return
        // If no service answers through the VPN, sites see the server's address anyway.
        if (connected && location == null) location = repo.state.value.selected?.address?.let { _serverLocations.value[it] }
        _visibleLocation.value = location
        if (!connected && location != null) _homeLocation.value = location
    }

    private suspend fun locateSelectedServer() {
        val address = repo.state.value.selected?.address ?: return
        if (address in _serverLocations.value) return
        val location = withContext(Dispatchers.IO) {
            runCatching { java.net.InetAddress.getByName(address).hostAddress }.getOrNull()?.let { GeoIP.lookup(it) }
        } ?: return
        _serverLocations.value = _serverLocations.value + (address to location)
    }

    /**
     * The automatic check runs at most every 12 hours and stays quiet about a version the user
     * already put off; the button in settings always asks GitHub and reports the result.
     */
    private val _whatsNew = MutableStateFlow<String?>(null)
    /** What changed in this version, shown once after an update; null hides it. */
    val whatsNew: StateFlow<String?> = _whatsNew.asStateFlow()

    private fun pendingWhatsNew(): String? {
        val version = BuildConfig.VERSION_NAME
        val seen = prefs.getString("whatsNewSeen", null)
        prefs.edit().putString("whatsNewSeen", version).apply()
        // A fresh install has nothing to compare with; someone who already has servers is updating.
        if (seen == version || (seen == null && repo.state.value.profiles.isEmpty())) return null
        val text = runCatching {
            getApplication<Application>().assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return Changelog.section(text, version)
    }

    fun dismissWhatsNew() {
        _whatsNew.value = null
        checkForUpdate(manual = false)
    }

    /**
     * Runs on every launch and offers a newer version; asked from settings it downloads and
     * installs straight away, or says there is nothing new.
     */
    fun checkForUpdate(manual: Boolean) = viewModelScope.launch {
        val release = try {
            val beta = AppSettings.get(getApplication()).betaUpdates.value
            withContext(Dispatchers.IO) { Updates.fetchLatest(includeBeta = beta) }
        } catch (e: Exception) {
            if (manual) toast(str(R.string.update_error, e.message ?: ""))
            return@launch
        }
        when {
            !Updates.isNewer(release.version, BuildConfig.VERSION_NAME) -> if (manual) {
                // On a beta with betas off, "latest" would be misleading: only releases were checked.
                val betaOff = Updates.isBeta(BuildConfig.VERSION_NAME) && !AppSettings.get(getApplication()).betaUpdates.value
                toast(str(if (betaOff) R.string.update_none_beta else R.string.update_none))
            }
            // Asked from settings: download and install straight away.
            manual -> {
                _update.value = release
                installUpdate(release)
            }
            else -> _update.value = release
        }
    }

    private val _updateProgress = MutableStateFlow<Float?>(null)
    /** Download progress of the update from 0 to 1; null when nothing is downloading. */
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()

    /**
     * Downloads the release APK and opens the system installer, which asks the user to confirm.
     * The first time, Android needs permission to install apps from EthicVPN.
     */
    fun installUpdate(release: Updates.Release) = viewModelScope.launch {
        val app = getApplication<Application>()
        if (_updateProgress.value != null) return@launch
        if (!release.downloadUrl.endsWith(".apk")) {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@launch
        }
        if (!AppUpdater.canInstall(app)) {
            toast(str(R.string.update_allow))
            AppUpdater.openInstallPermission(app)
            return@launch
        }
        _updateProgress.value = 0f
        try {
            val apk = withContext(Dispatchers.IO) { AppUpdater.download(app, release.downloadUrl) { _updateProgress.value = it } }
            _update.value = null
            AppUpdater.install(app, apk)
        } catch (e: Exception) {
            toast(str(R.string.update_error, e.message ?: ""))
        } finally {
            _updateProgress.value = null
        }
    }

    fun dismissUpdate() {
        _update.value = null
    }

    fun connect() = EthicVpnService.connect(getApplication())

    fun disconnect() = EthicVpnService.disconnect(getApplication())

    fun select(id: String) {
        repo.select(id)
        reconnectIfActive()
    }

    /** Restarts a running VPN so a new server or split tunneling setting takes effect. */
    fun reconnectIfActive() {
        if (vpn.value !is VpnState.Connected && vpn.value !is VpnState.Connecting) return
        viewModelScope.launch {
            disconnect()
            VpnStatus.state.first { it is VpnState.Disconnected || it is VpnState.Error }
            connect()
        }
    }

    fun remove(id: String) = repo.remove(id)

    fun addInput(text: String) {
        val input = DeepLink.unwrap(text)
        when {
            Subscription.isAllowed(input) -> setSubscription(input)
            // A closed service: single keys and other panels' subscriptions are not taken.
            else -> toast(str(R.string.msg_only_subscription))
        }
    }

    fun setSubscription(url: String) = runBusy {
        repo.setSubscription(url)
        toast(str(R.string.msg_subscription_updated))
    }

    fun refreshSubscription(silent: Boolean = false) = runBusy(silent) {
        repo.refreshSubscription()
        if (!silent) toast(str(R.string.msg_subscription_updated))
    }

    fun removeSubscription() = repo.removeSubscription()

    /** Pings every server and selects the one that answered fastest. */
    suspend fun selectFastest() {
        if (repo.state.value.profiles.size < 2) return
        pingAll().join()
        _pings.value.filterValues { it > 0 }.minByOrNull { it.value }?.key?.let(repo::select)
    }

    fun pingAll() = viewModelScope.launch {
        val list = repo.state.value.profiles.map { it.profile }
        val results = withContext(Dispatchers.IO) {
            list.map { p -> async { p.id to tcpPing(p.address, p.port) } }.awaitAll()
        }
        _pings.value = results.toMap()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun tcpPing(host: String, port: Int): Long = try {
        Socket().use { s ->
            val start = System.nanoTime()
            s.connect(InetSocketAddress(host, port), 3000)
            (System.nanoTime() - start) / 1_000_000
        }
    } catch (_: Exception) {
        -1
    }

    private fun runBusy(silent: Boolean = false, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try {
            block()
        } catch (e: Exception) {
            if (!silent) toast(str(R.string.msg_subscription_error, e.message ?: ""))
        } finally {
            _busy.value = false
        }
    }

    private fun toast(text: String) {
        _message.value = text
    }

    private fun str(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)
}
