package com.lumora.download

import android.content.Context
import android.util.Log
import com.lumora.data.AccountStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "DownloadStore"
private const val FILE_NAME = "downloads.json"

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETE, FAILED }

data class DownloadRecord(
    val id: String,
    val title: String,
    val subtitle: String,
    val posterUrl: String?,
    val mediaType: String,
    val downloadManagerId: Long,
    val status: DownloadStatus,
    val localFilePath: String? = null,
    // Live-only, never persisted - recomputed from DownloadManager each time the list is shown.
    val progressPercent: Int = 0,
    /** Account that owns this download. Filtering is done at read time so switching accounts
     *  shows only that account's downloads. */
    val accountId: String? = null
)

/** Persists which VOD items have been downloaded, so the Downloads tab survives app restarts.
 *  Records are filtered by the active account at read time. */
object DownloadStore {

    @Synchronized
    fun getAll(context: Context): List<DownloadRecord> {
        val activeId = AccountStore.activeAccountId(
            context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        )
        return load(context).filter { it.accountId == null || it.accountId == activeId }
    }

    @Synchronized
    fun get(context: Context, id: String): DownloadRecord? = getAll(context).firstOrNull { it.id == id }

    @Synchronized
    fun add(context: Context, record: DownloadRecord) {
        val activeId = AccountStore.activeAccountId(
            context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        )
        val tagged = if (record.accountId != null) record else record.copy(accountId = activeId)
        val list = load(context).filterNot { it.id == tagged.id }.toMutableList()
        list.add(tagged)
        save(context, list)
    }

    @Synchronized
    fun update(context: Context, record: DownloadRecord) {
        val list = load(context).map { if (it.id == record.id) record else it }
        save(context, list)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    /** Clear ALL accounts' downloads (full data wipe from Settings). */
    @Synchronized
    fun clearAll(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    @Synchronized
    private fun load(context: Context): List<DownloadRecord> = try {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                // A row with no id can't be looked up or removed by key - structurally
                // broken. Skip it rather than let one bad entry wipe the whole store.
                if (id.isBlank()) return@mapNotNull null
                DownloadRecord(
                    id = id,
                    title = obj.optString("title", ""),
                    subtitle = obj.optString("subtitle", ""),
                    posterUrl = obj.optString("posterUrl", "").ifEmpty { null },
                    mediaType = obj.optString("mediaType", "MOVIE"),
                    downloadManagerId = obj.optLong("downloadManagerId"),
                    status = runCatching { DownloadStatus.valueOf(obj.optString("status", "QUEUED")) }
                        .getOrDefault(DownloadStatus.QUEUED),
                    localFilePath = obj.optString("localFilePath", "").ifEmpty { null },
                    accountId = obj.optString("accountId", "").ifEmpty { null }
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to load: ${e.message}")
        emptyList()
    }

    @Synchronized
    private fun save(context: Context, list: List<DownloadRecord>) {
        try {
            val arr = JSONArray()
            for (r in list) {
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("title", r.title)
                    put("subtitle", r.subtitle)
                    put("posterUrl", r.posterUrl ?: "")
                    put("mediaType", r.mediaType)
                    put("downloadManagerId", r.downloadManagerId)
                    put("status", r.status.name)
                    put("localFilePath", r.localFilePath ?: "")
                    r.accountId?.let { put("accountId", it) }
                })
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save: ${e.message}")
        }
    }
}
