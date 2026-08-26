package com.lumora.cache

import android.content.Context
import android.util.Log
import com.lumora.data.AccountStore
import org.json.JSONArray
import java.io.File

private const val TAG = "RecentlyPlayedStore"
private const val FILE_PREFIX = "recently_played"
private const val MAX_ENTRIES = 20

/** Recently-played live channel ids, most recent first, for the Home tab's "Recently Played" shelf.
 *  Data is isolated per active account: each account gets its own JSON file. */
object RecentlyPlayedStore {

    private fun fileName(context: Context): String {
        val id = AccountStore.activeAccountId(
            context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        ) ?: "_none"
        return "${FILE_PREFIX}_${id}.json"
    }

    @Synchronized
    fun recordPlayed(context: Context, channelId: String) {
        if (channelId.isBlank()) return
        val entries = load(context).toMutableList()
        entries.removeAll { it == channelId }
        entries.add(0, channelId)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        save(context, entries)
    }

    @Synchronized
    fun getRecentIds(context: Context): List<String> = load(context)

    @Synchronized
    fun clear(context: Context) {
        runCatching { File(context.filesDir, fileName(context)).delete() }
    }

    /** Clear ALL accounts' recently played (full data wipe from Settings). */
    @Synchronized
    fun clearAll(context: Context) {
        context.filesDir.listFiles()?.filter { it.name.startsWith("${FILE_PREFIX}_") && it.name.endsWith(".json") }
            ?.forEach { runCatching { it.delete() } }
    }

    @Synchronized
    private fun load(context: Context): List<String> = try {
        val file = File(context.filesDir, fileName(context))
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load: ${e.message}")
        emptyList()
    }

    @Synchronized
    private fun save(context: Context, ids: List<String>) {
        try {
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            val file = File(context.filesDir, fileName(context))
            val tempFile = File(context.filesDir, "${fileName(context)}.tmp")
            tempFile.writeText(arr.toString())
            tempFile.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save: ${e.message}")
        }
    }
}
