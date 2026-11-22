package dev.caraalink.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import dev.caraalink.diagnostics.DiagnosticLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The outside temperature for the car home screen, from open-meteo.com (free,
 * no account). Asking sends the phone's position, rounded to about a
 * kilometre, to that service, so it is off until switched on in Advanced.
 */
object Weather {
    private const val REFRESH_MS = 30 * 60 * 1000L

    @Volatile private var temperatureC: Double? = null
    @Volatile private var fetchedAt = 0L
    @Volatile private var fetching = false

    /** "18°", or empty when off or not known yet. */
    fun line(context: Context): String {
        if (!dev.caraalink.settings.AppSettings.carWeather(context)) return ""
        return temperatureC?.let { format(it) } ?: ""
    }

    internal fun format(celsius: Double): String = "${Math.round(celsius)}°"

    /** The request URL for a position, rounded to two decimals so it names a neighbourhood and not a street. */
    internal fun url(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m", latitude, longitude)

    /** `{"current":{"temperature_2m":18.4}}` */
    internal fun parse(json: String): Double? =
        runCatching { JSONObject(json).getJSONObject("current").getDouble("temperature_2m") }.getOrNull()

    fun refresh(context: Context) {
        if (fetching || System.currentTimeMillis() - fetchedAt < REFRESH_MS) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = context.getSystemService(LocationManager::class.java)
        val here = runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }
            .getOrNull() ?: return
        fetching = true
        Thread({
            try {
                val connection = URL(url(here.latitude, here.longitude)).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(body)?.let { temperatureC = it; fetchedAt = System.currentTimeMillis() }
            } catch (e: Exception) {
                DiagnosticLogger(context, "weather").log("fetch failed: $e")
            } finally {
                fetching = false
            }
        }, "weather").apply { isDaemon = true }.start()
    }
}
