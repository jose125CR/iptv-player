package com.lumora.data

import android.content.Context
import android.content.SharedPreferences

object DeviceIdentity {
    private const val PREFS_NAME = "device_identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_KEY = "device_key"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDeviceId(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_ID, null)

    fun saveDeviceId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_DEVICE_ID, id).apply()
    }

    fun getKey(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_KEY, null)

    fun saveKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_DEVICE_KEY, key).apply()
    }

    fun isRegistered(context: Context): Boolean =
        getDeviceId(context) != null && getKey(context) != null
}