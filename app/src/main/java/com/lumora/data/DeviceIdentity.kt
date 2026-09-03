package com.lumora.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object DeviceIdentity {
    private const val PREFS_NAME = "device_identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_KEY = "device_key"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDeviceId(context: Context): String {
        val prefs = prefs(context)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getKey(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_KEY, null)

    fun saveKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_DEVICE_KEY, key).apply()
    }

    fun isRegistered(context: Context): Boolean =
        getKey(context) != null

    /** Formats the device ID as a MAC-like string (XX:XX:XX:XX:XX:XX).
     *  Uses the last 12 hex characters of the UUID without dashes. */
    fun formatAsMac(deviceId: String): String {
        val raw = deviceId.replace("-", "").takeLast(12)
        return raw.chunked(2).joinToString(":").uppercase()
    }
}