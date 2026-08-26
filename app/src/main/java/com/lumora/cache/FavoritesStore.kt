package com.lumora.cache

import android.content.Context
import com.lumora.data.AccountStore

private const val PREFS_NAME = "iptv_prefs"
private const val KEY_FAVORITE_SERIES = "favorite_series_ids"
private const val KEY_FAVORITE_CHANNELS = "favorite_channel_ids"

/** Favorited series (shown on Home) and favorited live channels (Favourites category in Live TV).
 *  Data is isolated per active account: keys are prefixed with `{accountId}_`. */
object FavoritesStore {
    private val lock = Any()

    /**
     * In-memory mirror of the two prefs keys, filled lazily on first read of each and kept in
     * step by every writer below. Nothing outside this object writes those keys, so the map
     * cannot go stale behind it.
     *
     * Without this, [readSet] ran `getStringSet(...).toSet()` - a full copy of the user's
     * favourites - on every call, and the live guide calls [getFavoriteChannelIds] once per
     * row bind. Scrolling a large guide therefore allocated one copy of the whole favourites
     * set per row, per pass. Same reasoning as [ReminderStore], which the guide's other
     * per-row lookup already goes through.
     *
     * Values are immutable snapshots, so a caller holding one can't mutate what the next
     * reader sees; writers replace the entry rather than editing it in place.
     */
    private val cache = mutableMapOf<String, Set<String>>()
    private var cacheAccountId: String? = null

    fun isFavoriteSeries(context: Context, id: String): Boolean = id in getFavoriteSeriesIds(context)

    fun toggleFavoriteSeries(context: Context, id: String): Boolean = toggle(context, KEY_FAVORITE_SERIES, id)

    fun getFavoriteSeriesIds(context: Context): Set<String> = readSet(context, KEY_FAVORITE_SERIES)

    /** Sets membership outright rather than flipping it - for reconciling against a server
     *  that owns the truth (Jellyfin's UserData.IsFavorite), where a toggle can't express
     *  "the server says this is no longer a favourite". Returns true if anything changed. */
    fun setFavoriteSeries(context: Context, id: String, favorite: Boolean): Boolean = synchronized(lock) {
        if (id.isBlank()) return false
        val current = readSet(context, KEY_FAVORITE_SERIES)
        if (favorite == (id in current)) return false
        val updated = if (favorite) current + id else current - id
        write(context, KEY_FAVORITE_SERIES, updated)
        true
    }

    fun toggleFavoriteChannel(context: Context, id: String): Boolean = toggle(context, KEY_FAVORITE_CHANNELS, id)

    fun getFavoriteChannelIds(context: Context): Set<String> = readSet(context, KEY_FAVORITE_CHANNELS)

    /** Wipe ALL accounts' favorites (used by the Settings "clear data" action). */
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        cache.clear()
        cacheAccountId = null
    }

    /** Returns the new membership state (true = now favorited). */
    private fun toggle(context: Context, key: String, id: String): Boolean = synchronized(lock) {
        val current = readSet(context, key)
        val nowFavorite = id !in current
        write(context, key, if (nowFavorite) current + id else current - id)
        nowFavorite
    }

    private fun activeAccountId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AccountStore.activeAccountId(prefs) ?: "_no_account"
    }

    private fun prefixedKey(context: Context, key: String): String =
        "${activeAccountId(context)}_$key"

    private fun write(context: Context, key: String, value: Set<String>) {
        val realKey = prefixedKey(context, key)
        cache[realKey] = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(realKey, value).apply()
    }

    private fun readSet(context: Context, key: String): Set<String> = synchronized(lock) {
        val realKey = prefixedKey(context, key)
        // Invalidate cache if account changed since last read
        val currentAccountId = activeAccountId(context)
        if (cacheAccountId != null && cacheAccountId != currentAccountId) {
            cache.clear()
            cacheAccountId = currentAccountId
        } else if (cacheAccountId == null) {
            cacheAccountId = currentAccountId
        }
        cache.getOrPut(realKey) {
            // Copied out of the pref rather than handed over: SharedPreferences returns its own
            // live instance from getStringSet, and the docs are explicit that mutating it (or
            // reading it after a later write) is undefined.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(realKey, emptySet())?.toSet() ?: emptySet()
        }
    }
}
