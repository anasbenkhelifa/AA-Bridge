package dev.caraalink.diagnostics

import android.content.Context
import dev.caraalink.connection.CarLink

/**
 * One line about the last drive, for Settings: how long the car link was up,
 * how it was connected, how often Android Auto had to be restarted, and
 * whether the phone overheated. Kept on the phone only.
 */
object DriveLog {
    private const val PREFS = "drive_log"

    data class Drive(val endedAt: Long, val minutes: Long, val wireless: Boolean, val restarts: Int, val overheated: Boolean)

    @Volatile private var startedAt = 0L
    @Volatile private var restartsAtStart = 0
    @Volatile private var link = CarLink.NONE
    @Volatile private var overheated = false

    /** The car link is streaming. Only the first call of a drive counts. */
    fun onStreaming(link: CarLink) {
        if (startedAt != 0L) return
        startedAt = System.currentTimeMillis()
        restartsAtStart = PerfStats.aaReconnects
        this.link = link
        overheated = false
    }

    fun onOverheated() {
        overheated = true
    }

    /** The link has ended; a drive that never streamed leaves the last one in place. */
    fun onEnded(context: Context) {
        val started = startedAt
        if (started == 0L) return
        startedAt = 0L
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("ended_at", now)
            .putLong("minutes", minutesBetween(started, now))
            .putBoolean("wireless", link == CarLink.WIRELESS)
            .putInt("restarts", (PerfStats.aaReconnects - restartsAtStart).coerceAtLeast(0))
            .putBoolean("overheated", overheated)
            .apply()
    }

    fun last(context: Context): Drive? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val endedAt = p.getLong("ended_at", 0L)
        if (endedAt == 0L) return null
        return Drive(endedAt, p.getLong("minutes", 0), p.getBoolean("wireless", false), p.getInt("restarts", 0), p.getBoolean("overheated", false))
    }

    /** Whole minutes, at least one for anything that streamed at all. */
    fun minutesBetween(startMs: Long, endMs: Long): Long = ((endMs - startMs) / 60_000).coerceAtLeast(1)
}
