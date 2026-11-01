package com.techteam.vpn.data

import android.content.Context
import com.techteam.vpn.core.Subscription
import com.techteam.vpn.core.SubscriptionInfo
import com.techteam.vpn.core.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class StoredProfile(val profile: ServerProfile, val fromSubscription: Boolean)

data class ProfilesState(
    val profiles: List<StoredProfile> = emptyList(),
    val selectedId: String? = null,
    val subscriptionUrl: String = "",
    val subscriptionInfo: SubscriptionInfo? = null,
) {
    val selected: ServerProfile?
        get() = (profiles.firstOrNull { it.profile.id == selectedId } ?: profiles.firstOrNull())?.profile
}

/** Keeps servers and the subscription in SharedPreferences; the VPN service reads the selection from here. */
class ProfileRepository private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ProfilesState> = _state.asStateFlow()

    /** Adds every server link found in [text]; returns how many were added. */
    fun addLinks(text: String): Int {
        val parsed = Subscription.parseLinks(text)
        if (parsed.isEmpty()) return 0
        update { s ->
            s.copy(
                profiles = s.profiles + parsed.map { StoredProfile(it, fromSubscription = false) },
                selectedId = s.selectedId ?: parsed.first().id,
            )
        }
        return parsed.size
    }

    suspend fun setSubscription(url: String) {
        update { it.copy(subscriptionUrl = url.trim()) }
        refreshSubscription()
    }

    /** Replaces subscription servers with a fresh copy, keeping the selection when the server still exists. */
    suspend fun refreshSubscription() {
        val url = _state.value.subscriptionUrl
        if (url.isEmpty()) return
        val result = withContext(Dispatchers.IO) { Subscription.fetch(url, deviceHeaders()) }
        update { s ->
            val previous = s.profiles.firstOrNull { it.profile.id == s.selectedId }?.profile
            // Reuse old ids for the same link so the selection survives a refresh.
            val oldIds = s.profiles.filter { it.fromSubscription }.associate { it.profile.link to it.profile.id }
            val fresh = result.profiles.map { p -> StoredProfile(p.copy(id = oldIds[p.link] ?: p.id), true) }
            val manual = s.profiles.filterNot { it.fromSubscription }
            val all = fresh + manual
            val selected = previous?.id?.takeIf { id -> all.any { it.profile.id == id } } ?: all.firstOrNull()?.profile?.id
            s.copy(profiles = all, selectedId = selected, subscriptionInfo = result.info)
        }
        ExpiryReminders.schedule(context, result.info.expire)
    }

    fun removeSubscription() = update { s ->
        val left = s.profiles.filterNot { it.fromSubscription }
        s.copy(
            profiles = left,
            subscriptionUrl = "",
            subscriptionInfo = null,
            selectedId = s.selectedId?.takeIf { id -> left.any { it.profile.id == id } },
        )
    }

    fun select(id: String) = update { it.copy(selectedId = id) }

    fun remove(id: String) = update { s ->
        s.copy(profiles = s.profiles.filterNot { it.profile.id == id }, selectedId = s.selectedId.takeIf { it != id })
    }

    /** What panels with per-device keys (HWID binding) expect, as Happ and v2RayTun send it. */
    /** Stable per install, so the panel keeps treating this phone as the same device. */
    val hwid: String
        get() = prefs.getString("hwid", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("hwid", it).apply()
        }

    private fun deviceHeaders(): Map<String, String> {
        return mapOf(
            "x-hwid" to hwid,
            "x-device-os" to "Android",
            "x-ver-os" to android.os.Build.VERSION.RELEASE,
            "x-device-model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )
    }

    private fun update(block: (ProfilesState) -> ProfilesState) {
        synchronized(this) {
            val next = block(_state.value)
            _state.value = next
            save(next)
        }
    }

    private fun save(s: ProfilesState) {
        val arr = JSONArray()
        s.profiles.forEach {
            arr.put(JSONObject().put("id", it.profile.id).put("link", it.profile.link).put("sub", it.fromSubscription))
        }
        val info = s.subscriptionInfo?.let {
            JSONObject().put("title", it.title).put("upload", it.upload).put("download", it.download)
                .put("total", it.total).put("expire", it.expire).put("updatedAt", it.updatedAt).put("announce", it.announce)
        }
        prefs.edit()
            .putString("profiles", arr.toString())
            .putString("selected", s.selectedId)
            .putString("subUrl", s.subscriptionUrl)
            .putString("subInfo", info?.toString())
            .apply()
    }

    private fun load(): ProfilesState {
        val arr = runCatching { JSONArray(prefs.getString("profiles", "[]")) }.getOrDefault(JSONArray())
        val profiles = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            ServerProfile.parse(o.getString("link"))?.copy(id = o.getString("id"))
                ?.let { StoredProfile(it, o.optBoolean("sub")) }
        }
        val info = prefs.getString("subInfo", null)?.let {
            runCatching {
                val o = JSONObject(it)
                SubscriptionInfo(
                    o.optString("title"), o.optLong("upload"), o.optLong("download"),
                    o.optLong("total"), o.optLong("expire"), o.optLong("updatedAt"), o.optString("announce"),
                )
            }.getOrNull()
        }
        return ProfilesState(profiles, prefs.getString("selected", null), prefs.getString("subUrl", "") ?: "", info)
    }

    companion object {
        @Volatile private var instance: ProfileRepository? = null

        fun get(context: Context): ProfileRepository =
            instance ?: synchronized(this) {
                instance ?: ProfileRepository(context.applicationContext).also { instance = it }
            }
    }
}
