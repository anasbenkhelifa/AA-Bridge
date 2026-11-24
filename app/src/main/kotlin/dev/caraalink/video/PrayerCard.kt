package dev.caraalink.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Prayer
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import dev.caraalink.R
import dev.caraalink.settings.AppSettings
import java.util.Date

/**
 * The next prayer for the car home screen, worked out on the phone from its
 * position with the adhan library; nothing is sent anywhere. The times follow
 * the method chosen in Advanced, which should match the one your mosque or
 * prayer app uses.
 */
object PrayerCard {

    class Next(val prayer: Prayer, val at: Date)

    /** The next prayer after [now] at a place: one of today's, or tomorrow's Fajr once Isha has passed. */
    fun next(latitude: Double, longitude: Double, now: Date, method: CalculationMethod): Next {
        val place = Coordinates(latitude, longitude)
        val today = PrayerTimes(place, DateComponents.from(now), method.parameters)
        val upcoming = today.nextPrayer(now)
        if (upcoming != Prayer.NONE) return Next(upcoming, today.timeForPrayer(upcoming))
        val tomorrow = PrayerTimes(place, DateComponents.from(Date(now.time + DAY_MS)), method.parameters)
        return Next(Prayer.FAJR, tomorrow.fajr)
    }

    /** "45 min", "2 h 05", or "now" inside the last minute. */
    fun countdown(untilMs: Long): String {
        val minutes = (untilMs + 59_999) / 60_000
        return when {
            minutes <= 0 -> "0 min"
            minutes < 60 -> "$minutes min"
            else -> "%d h %02d".format(minutes / 60, minutes % 60)
        }
    }

    fun method(name: String?): CalculationMethod =
        CalculationMethod.values().firstOrNull { it.name == name } ?: CalculationMethod.MUSLIM_WORLD_LEAGUE

    fun nameOf(prayer: Prayer): Int = when (prayer) {
        Prayer.FAJR -> R.string.prayer_fajr
        Prayer.SUNRISE -> R.string.prayer_sunrise
        Prayer.DHUHR -> R.string.prayer_dhuhr
        Prayer.ASR -> R.string.prayer_asr
        Prayer.MAGHRIB -> R.string.prayer_maghrib
        else -> R.string.prayer_isha
    }

    /** The next prayer where the phone last knew it was, or null when the card is off or there is no position. */
    fun current(context: Context): Next? {
        if (!AppSettings.carPrayer(context)) return null
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        // The application's own location service: a car screen's context is a different object with the same rights.
        val lm = context.applicationContext.getSystemService(LocationManager::class.java)
        val here = runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }
            .getOrNull() ?: return null
        return runCatching { next(here.latitude, here.longitude, Date(), method(AppSettings.prayerMethod(context))) }.getOrNull()
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
