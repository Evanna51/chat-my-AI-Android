package com.example.aichat.sync

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Stable per-install device id used as `deviceId` in sync push requests.
 * Strategy: prefer Settings.Secure.ANDROID_ID (per-app-signing-key on API 26+),
 * fall back to a random UUID persisted in SharedPreferences.
 */
object DeviceIdProvider {

    private const val PREFS = "wi_sync"
    private const val KEY_DEVICE_ID = "device_id"

    @SuppressLint("HardwareIds")
    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_DEVICE_ID, null)
        if (!cached.isNullOrEmpty()) return cached

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }

        val id = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
            "android-$androidId"
        } else {
            "android-${UUID.randomUUID()}"
        }
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }
}
