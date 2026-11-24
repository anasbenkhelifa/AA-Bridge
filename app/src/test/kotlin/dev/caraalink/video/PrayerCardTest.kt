package dev.caraalink.video

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Prayer
import dev.caraalink.diagnostics.DriveLog
import dev.caraalink.ui.reportCarUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PrayerCardTest {

    // Batna, Algeria, on 22 November 2026; Algeria is UTC+1 all year.
    private val latitude = 35.556
    private val longitude = 6.174
    private fun at(hour: Int, minute: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(2026, Calendar.NOVEMBER, 22, hour - 1, minute)
    }.time
    private val method = CalculationMethod.MUSLIM_WORLD_LEAGUE

    @Test
    fun `the next prayer follows the day`() {
        assertEquals(Prayer.DHUHR, PrayerCard.next(latitude, longitude, at(10, 0), method).prayer)
        assertEquals(Prayer.ASR, PrayerCard.next(latitude, longitude, at(14, 0), method).prayer)
        assertEquals(Prayer.MAGHRIB, PrayerCard.next(latitude, longitude, at(16, 30), method).prayer)
    }

    @Test
    fun `after Isha the next prayer is tomorrow's Fajr`() {
        val now = at(22, 0)
        val next = PrayerCard.next(latitude, longitude, now, method)

        assertEquals(Prayer.FAJR, next.prayer)
        val hoursAway = (next.at.time - now.time) / 3_600_000.0
        assertTrue("Fajr $hoursAway h after 22:00", hoursAway > 6 && hoursAway < 9)
    }

    @Test
    fun `the times are sane for the place`() {
        // Solar noon in Batna in late November is about 12:22 local; Dhuhr follows it within minutes.
        val dhuhr = PrayerCard.next(latitude, longitude, at(10, 0), method).at
        val minutesFromNoon = (dhuhr.time - at(12, 22).time) / 60_000
        assertTrue("Dhuhr $minutesFromNoon min from solar noon", minutesFromNoon in -3..8)
    }

    @Test
    fun `the countdown reads in minutes, then hours`() {
        assertEquals("1 min", PrayerCard.countdown(20_000))
        assertEquals("45 min", PrayerCard.countdown(45 * 60_000L))
        assertEquals("2 h 05", PrayerCard.countdown(125 * 60_000L))
        assertEquals("0 min", PrayerCard.countdown(-5_000))
    }

    @Test
    fun `an unknown method name falls back to Muslim World League`() {
        assertEquals(CalculationMethod.EGYPTIAN, PrayerCard.method("EGYPTIAN"))
        assertEquals(CalculationMethod.MUSLIM_WORLD_LEAGUE, PrayerCard.method(null))
        assertEquals(CalculationMethod.MUSLIM_WORLD_LEAGUE, PrayerCard.method("nonsense"))
    }

    @Test
    fun `a drive counts whole minutes, at least one`() {
        assertEquals(1, DriveLog.minutesBetween(0, 20_000))
        assertEquals(42, DriveLog.minutesBetween(1_000, 1_000 + 42 * 60_000L + 30_000))
    }

    @Test
    fun `the car report opens a prefilled issue`() {
        val url = reportCarUrl("samsung", "SM-S938B", "16", "1.2.1")

        assertTrue(url.startsWith("https://github.com/alimorgaan/AA-Bridge/issues/new?title=Car+report"))
        assertTrue(url.contains("samsung+SM-S938B"))
        assertTrue(url.contains("Android+16"))
    }
}
