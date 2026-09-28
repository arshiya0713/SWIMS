package com.swims.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A geocoded place. */
data class GeoPlace(val name: String, val country: String, val lat: Double, val lon: Double)

/** Current + forecast-max temperature in °C. */
data class Weather(val currentC: Double, val maxTodayC: Double)

/**
 * Talks to Open-Meteo — a free, no-API-key, HTTPS weather service.
 * All calls are suspend + run on IO. Any failure returns null so callers can
 * fall back to offline behaviour cleanly.
 *
 *   Geocoding:  https://geocoding-api.open-meteo.com/v1/search?name=London&count=1
 *   Forecast:   https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..
 *               &current=temperature_2m&daily=temperature_2m_max&timezone=auto
 */
object WeatherService {

    private const val TIMEOUT_MS = 8000

    /** Resolves a typed city name into coordinates, or null if not found / offline. */
    suspend fun geocode(city: String): GeoPlace? = withContext(Dispatchers.IO) {
        if (city.isBlank()) return@withContext null
        val q = URLEncoder.encode(city.trim(), "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"
        val json = getJson(url) ?: return@withContext null
        val results = json.optJSONArray("results") ?: return@withContext null
        if (results.length() == 0) return@withContext null
        val r = results.getJSONObject(0)
        GeoPlace(
            name = r.optString("name"),
            country = r.optString("country", ""),
            lat = r.getDouble("latitude"),
            lon = r.getDouble("longitude"),
        )
    }

    /** Fetches current + today's max temperature for coordinates, or null if offline. */
    suspend fun fetch(lat: Double, lon: Double): Weather? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m&daily=temperature_2m_max&timezone=auto&forecast_days=1"
        val json = getJson(url) ?: return@withContext null
        val current = json.optJSONObject("current")?.optDouble("temperature_2m")
            ?: return@withContext null
        val maxArr = json.optJSONObject("daily")?.optJSONArray("temperature_2m_max")
        val max = if (maxArr != null && maxArr.length() > 0) maxArr.getDouble(0) else current
        Weather(currentC = current, maxTodayC = max)
    }

    private fun getJson(urlStr: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null // offline, DNS failure, timeout, malformed — all fall back to offline
        } finally {
            conn?.disconnect()
        }
    }
}
