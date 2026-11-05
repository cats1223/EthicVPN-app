package com.ethic.vpn.data

import android.content.Context
import com.ethic.vpn.core.TrafficHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * Theme, split tunneling and developer mode. The VPN service reads devMode to decide whether
 * to keep the Xray log, and the split tunneling settings when it connects.
 */
class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _lightTheme = MutableStateFlow(prefs.getBoolean("lightTheme", false))
    val lightTheme: StateFlow<Boolean> = _lightTheme.asStateFlow()

    private val _devMode = MutableStateFlow(prefs.getBoolean("devMode", false))
    val devMode: StateFlow<Boolean> = _devMode.asStateFlow()

    /** Russian sites and IP ranges go past the VPN. */
    private val _russiaDirect = MutableStateFlow(prefs.getBoolean("russiaDirect", true))
    val russiaDirect: StateFlow<Boolean> = _russiaDirect.asStateFlow()

    /** Ad and tracker domains are blocked; off by default, since a few sites break without them. */
    private val _blockAds = MutableStateFlow(prefs.getBoolean("blockAds", false))
    val blockAds: StateFlow<Boolean> = _blockAds.asStateFlow()

    /** Upstream DNS: "" (Cloudflare and Google), "cloudflare", "google", "adguard" or "custom". */
    private val _dns = MutableStateFlow(prefs.getString("dns", "").orEmpty())
    val dns: StateFlow<String> = _dns.asStateFlow()
    private val _customDns = MutableStateFlow(prefs.getString("customDns", "").orEmpty())
    val customDns: StateFlow<String> = _customDns.asStateFlow()

    /** The welcome slides were shown. */
    var onboardingSeen: Boolean
        get() = prefs.getBoolean("onboardingSeen", false)
        set(value) = prefs.edit().putBoolean("onboardingSeen", value).apply()

    /** Traffic per day, written by the VPN service. */
    private val _traffic = MutableStateFlow(TrafficHistory.decode(prefs.getString("traffic", null)))
    val traffic: StateFlow<TrafficHistory> = _traffic.asStateFlow()

    /** Packages excluded from the VPN: their traffic goes straight to the network. */
    private val _bypassApps = MutableStateFlow(prefs.getStringSet("bypassApps", null).orEmpty().toSet())
    val bypassApps: StateFlow<Set<String>> = _bypassApps.asStateFlow()

    /** Offer betas in the update check too, not only full releases. */
    private val _betaUpdates = MutableStateFlow(prefs.getBoolean("betaUpdates", false))
    val betaUpdates: StateFlow<Boolean> = _betaUpdates.asStateFlow()

    fun setBetaUpdates(enabled: Boolean) {
        _betaUpdates.value = enabled
        prefs.edit().putBoolean("betaUpdates", enabled).apply()
    }

    /** Before connecting, ping the servers and take the fastest. */
    private val _autoFastest = MutableStateFlow(prefs.getBoolean("autoFastest", false))
    val autoFastest: StateFlow<Boolean> = _autoFastest.asStateFlow()

    fun setAutoFastest(enabled: Boolean) {
        _autoFastest.value = enabled
        prefs.edit().putBoolean("autoFastest", enabled).apply()
    }

    /** The user's sites that always go past the VPN, and those that always go through it. */
    private val _directSites = MutableStateFlow(prefs.getStringSet("directSites", null).orEmpty().sorted())
    val directSites: StateFlow<List<String>> = _directSites.asStateFlow()
    private val _proxySites = MutableStateFlow(prefs.getStringSet("proxySites", null).orEmpty().sorted())
    val proxySites: StateFlow<List<String>> = _proxySites.asStateFlow()

    fun setDirectSites(sites: List<String>) {
        _directSites.value = sites
        prefs.edit().putStringSet("directSites", sites.toSet()).apply()
    }

    fun setProxySites(sites: List<String>) {
        _proxySites.value = sites
        prefs.edit().putStringSet("proxySites", sites.toSet()).apply()
    }

    fun setRussiaDirect(enabled: Boolean) {
        _russiaDirect.value = enabled
        prefs.edit().putBoolean("russiaDirect", enabled).apply()
    }

    fun setBlockAds(enabled: Boolean) {
        _blockAds.value = enabled
        prefs.edit().putBoolean("blockAds", enabled).apply()
    }

    fun setDns(choice: String, custom: String = _customDns.value) {
        _dns.value = choice
        _customDns.value = custom
        prefs.edit().putString("dns", choice).putString("customDns", custom).apply()
    }

    @Synchronized
    fun addTraffic(down: Long, up: Long, blocked: Int = 0) {
        if (down <= 0 && up <= 0 && blocked <= 0) return
        val history = TrafficHistory.decode(prefs.getString("traffic", null)).apply {
            add(down, up)
            addBlocked(blocked)
        }
        prefs.edit().putString("traffic", history.encode()).apply()
        _traffic.value = history
    }

    fun setBypassApps(packages: Set<String>) {
        _bypassApps.value = packages
        prefs.edit().putStringSet("bypassApps", packages).apply()
    }

    fun setLightTheme(light: Boolean) {
        _lightTheme.value = light
        prefs.edit().putBoolean("lightTheme", light).apply()
    }

    /** Returns false for a wrong password. */
    fun unlockDevMode(password: String): Boolean {
        if (sha256(password) != DEV_PASSWORD_HASH) return false
        setDevMode(true)
        return true
    }

    fun setDevMode(enabled: Boolean) {
        _devMode.value = enabled
        prefs.edit().putBoolean("devMode", enabled).apply()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** SHA-256 of the developer password, so the password itself is not in the source. */
        private const val DEV_PASSWORD_HASH = "ebf975deece2e0b3b0f84f343e7b235ec1bcc459910dfb6ebace4ec35a56df6d"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
