package com.swims.app.network

import android.content.Context
import com.swims.app.data.SettingsStore
import com.swims.app.ml.WeatherAdjuster

/**
 * Online-first, offline-tolerant weather goal helper.
 *
 * Online:  fetches today's max temperature and bumps the goal on hot days.
 * Offline: reuses the last fetched temperature if it's still fresh (< 12 h),
 *          otherwise applies no bonus. Either way the app keeps working.
 */
class WeatherManager(context: Context) {

    private val appCtx = context.applicationContext
    private val store = SettingsStore.get(appCtx)

    data class GoalResult(
        val goalMl: Int,        // base goal + weather bonus
        val bonusMl: Int,
        val tempC: Double?,     // max temp used (null if unknown)
        val city: String?,
        val online: Boolean,
        val stale: Boolean,     // true when a cached temp was used offline
    )

    /** Resolves a typed city to coordinates and saves it. Returns the place or null. */
    suspend fun setCity(city: String): GeoPlace? {
        val place = WeatherService.geocode(city) ?: return null
        store.cityName = if (place.country.isNotBlank()) "${place.name}, ${place.country}" else place.name
        store.lat = place.lat
        store.lon = place.lon
        // Prime the cache immediately so the goal updates without waiting.
        WeatherService.fetch(place.lat, place.lon)?.let {
            store.lastMaxTempC = it.maxTodayC.toFloat()
            store.lastWeatherAtMs = System.currentTimeMillis()
        }
        return place
    }

    /** Computes today's weather-adjusted goal from [baseGoal]. */
    suspend fun todayGoal(baseGoal: Int): GoalResult {
        val online = NetworkUtil.isOnline(appCtx)
        if (!store.weatherEnabled || !store.hasLocation()) {
            return GoalResult(baseGoal, 0, null, store.cityName, online, false)
        }

        var tempC: Double? = null
        var stale = false

        if (online) {
            WeatherService.fetch(store.lat, store.lon)?.let {
                tempC = it.maxTodayC
                store.lastMaxTempC = it.maxTodayC.toFloat()
                store.lastWeatherAtMs = System.currentTimeMillis()
            }
        }

        if (tempC == null) {
            val ageMs = System.currentTimeMillis() - store.lastWeatherAtMs
            val cached = store.lastMaxTempC
            if (!cached.isNaN() && ageMs in 0 until 12 * 3_600_000L) {
                tempC = cached.toDouble()
                stale = true
            }
        }

        val bonus = tempC?.let { WeatherAdjuster.bonusMl(it) } ?: 0
        val goal = (baseGoal + bonus).coerceIn(1500, 5000)
        return GoalResult(goal, bonus, tempC, store.cityName, online, stale)
    }
}
