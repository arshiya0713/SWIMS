package com.swims.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Small key/value store for online-feature settings and cached weather.
 * Backed by EncryptedSharedPreferences (same hardware-bound key as the DB),
 * so it stays encrypted at rest. Kept separate from the Room DB so adding
 * these fields needs no schema migration.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "swims_online_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── Weather-smart goal ──
    var weatherEnabled: Boolean
        get() = prefs.getBoolean(K_WEATHER_ON, true)
        set(v) = prefs.edit().putBoolean(K_WEATHER_ON, v).apply()

    var cityName: String?
        get() = prefs.getString(K_CITY, null)
        set(v) = prefs.edit().putString(K_CITY, v).apply()

    var lat: Double
        get() = Double.fromBits(prefs.getLong(K_LAT, 0L))
        set(v) = prefs.edit().putLong(K_LAT, v.toRawBits()).apply()

    var lon: Double
        get() = Double.fromBits(prefs.getLong(K_LON, 0L))
        set(v) = prefs.edit().putLong(K_LON, v.toRawBits()).apply()

    fun hasLocation(): Boolean = prefs.contains(K_LAT) && prefs.contains(K_LON)

    /**
     * Wipes every online-feature setting: city/coordinates, cached weather,
     * sync flags and achievement state. Used by "Delete all my data".
     * The database key lives in a *different* prefs file and is untouched,
     * so the encrypted database stays readable afterwards.
     */
    fun clearAll() {
        prefs.edit().clear().commit()
    }

    /** Last fetched max temp (°C) and when — used as an offline fallback. */
    var lastMaxTempC: Float
        get() = prefs.getFloat(K_TEMP, Float.NaN)
        set(v) = prefs.edit().putFloat(K_TEMP, v).apply()

    var lastWeatherAtMs: Long
        get() = prefs.getLong(K_TEMP_AT, 0L)
        set(v) = prefs.edit().putLong(K_TEMP_AT, v).apply()

    // ── Achievements ──

    /** Ids of achievements whose unlock has already been announced (toasted). */
    var announcedAchievements: Set<String>
        get() = prefs.getStringSet(K_ACH, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_ACH, v).apply()

    // ── Cloud sync ──
    var cloudSyncEnabled: Boolean
        get() = prefs.getBoolean(K_SYNC_ON, false)
        set(v) = prefs.edit().putBoolean(K_SYNC_ON, v).apply()

    var lastSyncAtMs: Long
        get() = prefs.getLong(K_SYNC_AT, 0L)
        set(v) = prefs.edit().putLong(K_SYNC_AT, v).apply()

    companion object {
        private const val K_WEATHER_ON = "weather_enabled"
        private const val K_CITY = "city_name"
        private const val K_LAT = "lat_bits"
        private const val K_LON = "lon_bits"
        private const val K_TEMP = "last_max_temp"
        private const val K_TEMP_AT = "last_weather_at"
        private const val K_ACH = "announced_achievements"
        private const val K_SYNC_ON = "cloud_sync_enabled"
        private const val K_SYNC_AT = "last_sync_at"

        @Volatile private var INSTANCE: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
