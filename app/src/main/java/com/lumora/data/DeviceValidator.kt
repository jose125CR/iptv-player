package com.lumora.data

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ValidationResult(
    val valid: Boolean,
    val expired: Boolean,
    val key: String?,
    val providerUrl: String?,
    val message: String?
)

class DeviceValidator(private val context: Context) {

    private val TAG = "DeviceValidator"
    private val VALIDATE_URL = "https://reseller-be.vercel.app/api/validate"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun validate(): ValidationResult? {
        return try {
            val deviceId = DeviceIdentity.getDeviceId(context)
            val deviceKey = DeviceIdentity.getKey(context)

            val bodyJson = JSONObject().apply {
                put("deviceId", deviceId)
                deviceKey?.let { put("key", it) }
            }
            val requestBody = bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(VALIDATE_URL)
                .header("User-Agent", "Lumora/2.0")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Validate API: HTTP ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val valid = json.optBoolean("valid", false)
            val expired = json.optBoolean("expired", false)
            val key = json.optString("key", null as String?)
            val providerUrl = json.optString("providerUrl", null as String?)
            val message = json.optString("message", null as String?)

            if (key != null) DeviceIdentity.saveKey(context, key)

            ValidationResult(
                valid = valid,
                expired = expired,
                key = key,
                providerUrl = providerUrl,
                message = message
            )
        } catch (e: Exception) {
            Log.w(TAG, "Validation failed: ${e.message}")
            null
        }
    }
}