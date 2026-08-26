package com.lumora.data

import android.content.SharedPreferences
import com.lumora.model.AccountConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persists the list of configured IPTV accounts and which one is active. One account is
 *  active at a time — switching replaces the entire catalog. */
object AccountStore {
    private const val KEY = "iptv_providers_json"
    private const val ACTIVE_KEY = "active_account_id"
    private const val LEGACY_ENABLED_KEY = "iptv_provider_enabled"

    fun load(prefs: SharedPreferences): List<AccountConfig> {
        val raw = prefs.getString(KEY, null)
        if (raw == null) return migrateLegacy(prefs)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(prefs: SharedPreferences, list: List<AccountConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(prefs: SharedPreferences, config: AccountConfig): List<AccountConfig> {
        val current = load(prefs).toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        if (idx >= 0) current[idx] = config else current.add(config)
        save(prefs, current)
        // First account added becomes active automatically.
        if (current.size == 1 && activeAccountId(prefs) == null) {
            prefs.edit().putString(ACTIVE_KEY, config.id).apply()
        }
        return current
    }

    fun remove(prefs: SharedPreferences, id: String): List<AccountConfig> {
        val current = load(prefs).filterNot { it.id == id }
        save(prefs, current)
        // If the removed account was active, fall back to the first remaining one.
        if (activeAccountId(prefs) == id) {
            val newActive = current.firstOrNull()?.id
            prefs.edit().putString(ACTIVE_KEY, newActive).apply()
        }
        return current
    }

    /** Returns the id of the currently active account, or null if none is set. */
    fun activeAccountId(prefs: SharedPreferences): String? =
        prefs.getString(ACTIVE_KEY, null)

    /** Returns the active account's config, or null. */
    fun activeAccount(prefs: SharedPreferences): AccountConfig? {
        val id = activeAccountId(prefs) ?: return null
        return load(prefs).firstOrNull { it.id == id }
    }

    fun setActiveAccount(prefs: SharedPreferences, id: String) {
        prefs.edit().putString(ACTIVE_KEY, id).apply()
    }

    fun setContentFlags(
        prefs: SharedPreferences,
        id: String,
        live: Boolean? = null,
        movies: Boolean? = null,
        series: Boolean? = null
    ): List<AccountConfig> {
        val current = load(prefs).map {
            if (it.id != id) it
            else it.copy(
                liveEnabled = live ?: it.liveEnabled,
                moviesEnabled = movies ?: it.moviesEnabled,
                seriesEnabled = series ?: it.seriesEnabled
            )
        }
        save(prefs, current)
        return current
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** One-time upgrade from the old single-provider pref scheme into a one-entry list.
     *  The first enabled entry becomes the active account. */
    private fun migrateLegacy(prefs: SharedPreferences): List<AccountConfig> {
        val type = prefs.getString("provider_type", null) ?: return emptyList()
        val enabled = prefs.getBoolean(LEGACY_ENABLED_KEY, true)
        val config = when (type) {
            "xtream" -> {
                val url = prefs.getString("xtream_url", null)
                if (url.isNullOrBlank()) null else AccountConfig(
                    id = newId(), type = "xtream", name = prefs.getString("provider_name", "Xtream") ?: "Xtream",
                    url = url, username = prefs.getString("xtream_user", null), password = prefs.getString("xtream_pass", null)
                )
            }
            "stalker" -> {
                val url = prefs.getString("stalker_url", null)
                if (url.isNullOrBlank()) null else AccountConfig(
                    id = newId(), type = "stalker", name = prefs.getString("provider_name", "Stalker") ?: "Stalker",
                    url = url, userAgent = prefs.getString("stalker_mac", null)
                )
            }
            "m3u" -> {
                val url = prefs.getString("m3u_url", null)
                if (url.isNullOrBlank()) null else AccountConfig(
                    id = newId(), type = "m3u", name = prefs.getString("provider_name", "M3U") ?: "M3U",
                    url = url, userAgent = prefs.getString("user_agent", null)
                )
            }
            else -> null
        }
        val migrated = listOfNotNull(config)
        if (migrated.isNotEmpty()) {
            save(prefs, migrated)
            if (enabled) prefs.edit().putString(ACTIVE_KEY, config!!.id).apply()
        }
        // Clear legacy keys so this doesn't re-run
        prefs.edit().remove("provider_type").remove(LEGACY_ENABLED_KEY)
            .remove("xtream_url").remove("xtream_user").remove("xtream_pass")
            .remove("stalker_url").remove("stalker_mac")
            .remove("m3u_url").remove("provider_name").remove("user_agent")
            .apply()
        return migrated
    }

    private fun toJson(c: AccountConfig): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("type", c.type)
        put("name", c.name)
        put("liveEnabled", c.liveEnabled)
        put("moviesEnabled", c.moviesEnabled)
        put("seriesEnabled", c.seriesEnabled)
        c.url?.let { put("url", it) }
        c.username?.let { put("username", it) }
        c.password?.let { put("password", it) }
        c.userAgent?.let { put("userAgent", it) }
    }

    private fun fromJson(o: JSONObject): AccountConfig {
        val legacyVodOff = o.optBoolean("disableVod", false)
        fun flag(key: String) = if (o.has(key)) o.optBoolean(key, true) else !legacyVodOff
        return AccountConfig(
            id = o.optString("id").ifBlank { newId() },
            type = o.optString("type", "m3u"),
            name = o.optString("name", "Provider"),
            liveEnabled = o.optBoolean("liveEnabled", true),
            moviesEnabled = flag("moviesEnabled"),
            seriesEnabled = flag("seriesEnabled"),
            url = o.optString("url").takeIf { it.isNotBlank() },
            username = o.optString("username").takeIf { it.isNotBlank() },
            password = o.optString("password").takeIf { it.isNotBlank() },
            userAgent = o.optString("userAgent").takeIf { it.isNotBlank() }
        )
    }
}
