package com.lumora.data.update

import android.content.Context
import android.util.Log
import com.lumora.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks for app updates via GitHub Releases API.
 * Auto-detects the latest release and compares with the installed version.
 */

class AppUpdateChecker(private val context: Context) {

    private val TAG = "AppUpdate"
    private val UPDATE_URL = BuildConfig.UPDATE_URL
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val isUpdateAvailable: Boolean
    )

    /**
     * Check for updates by fetching the latest version from the configured backend.
     */
    suspend fun checkForUpdate(): UpdateInfo? {
        return try {
            val url = UPDATE_URL
            val request = Request.Builder().url(url)
                .header("User-Agent", "Lumora/2.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Update API: HTTP ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val json = org.json.JSONObject(body)

            val latestVersion = json.optString("version", "")
            val downloadUrl = json.optString("downloadUrl", "")
            val releaseNotes = json.optString("releaseNotes", "")
            val currentVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            } catch (e: Exception) { "1.0.0" }

            val isUpdate = latestVersion.isNotBlank() && isNewerVersion(latestVersion, currentVersion)

            UpdateInfo(
                latestVersion = latestVersion.ifBlank { currentVersion },
                currentVersion = currentVersion,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes.take(500),
                isUpdateAvailable = isUpdate
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** Numeric, part-by-part comparison - a plain string ">" breaks past single digits
     *  ("1.10" < "1.9" lexically, even though 1.10 is the newer release). */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}
