package dev.caraalink.link

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock

/** Whether the car is stopped, as far as the phone's GPS can tell. */
enum class Motion { UNKNOWN, PARKED, MOVING }

/**
 * Decides stopped or moving from GPS speed alone. The car's own parking brake
 * and gear never reach the phone over CarbitLink or QDLink, so speed is all
 * there is.
 *
 * Every doubt resolves away from PARKED:
 * - Parked takes an unbroken run of trusted fixes under [STOP_MPS] lasting
 *   [HOLD_MS]. One slow fix is never enough, and a gap in the fixes starts
 *   the run over.
 * - It ends on the first fix at [MOVE_MPS] or more, however rough that fix
 *   is, on the second fix in a row between the two speeds, or on a slow fix
 *   whose own speed error is too large to vouch for standing still.
 * - With no fix for [STALE_MS] (a tunnel, a garage, location switched off)
 *   nothing is known at all.
 */
class ParkDetector {
    private var lastFixAt = NEVER
    private var stillSince = NEVER
    private var wobbles = 0
    private var lastSpeedMps = 0f

    /** The last fix's speed in whole km/h, or null with nothing fresh. For display only. */
    @Synchronized
    fun speedKmh(nowMs: Long): Int? =
        if (lastFixAt == NEVER || nowMs - lastFixAt > STALE_MS) null else Math.round(lastSpeedMps * 3.6f)

    /** One GPS fix at [atMs], on the same clock as [motion]. [speedErrorMps] is the fix's own speed accuracy, if it has one. */
    @Synchronized
    fun onFix(speedMps: Float, speedErrorMps: Float?, atMs: Long) {
        // A gap in the fixes is a gap in the evidence: what was true before it proves nothing now.
        if (lastFixAt != NEVER && atMs - lastFixAt > STALE_MS) notStill()
        lastFixAt = maxOf(lastFixAt, atMs)
        lastSpeedMps = speedMps
        val trusted = speedErrorMps == null || speedErrorMps <= MAX_SPEED_ERROR_MPS
        when {
            speedMps >= MOVE_MPS -> notStill()
            !trusted -> notStill()
            speedMps <= STOP_MPS -> {
                wobbles = 0
                if (stillSince == NEVER) stillSince = atMs
            }
            // Between the two speeds: one reading is GPS noise, two in a row is a car creeping forward.
            else -> if (++wobbles >= MAX_WOBBLES) notStill()
        }
    }

    private fun notStill() {
        stillSince = NEVER
        wobbles = 0
    }

    @Synchronized
    fun motion(nowMs: Long): Motion = when {
        lastFixAt == NEVER || nowMs - lastFixAt > STALE_MS -> Motion.UNKNOWN
        stillSince != NEVER && nowMs - stillSince >= HOLD_MS -> Motion.PARKED
        else -> Motion.MOVING
    }

    @Synchronized
    fun reset() {
        lastFixAt = NEVER
        notStill()
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /** 2.5 km/h. A stopped car's GPS speed sits well under this with a clear sky. */
        const val STOP_MPS = 0.7f
        /** 5.4 km/h. Walking pace: anything at or above this is driving. */
        const val MOVE_MPS = 1.5f
        const val HOLD_MS = 4_000L
        /** GPS reports once a second, so this allows for one missed fix. Shorter than [HOLD_MS] on purpose. */
        const val STALE_MS = 2_500L
        /** A slow fix whose own speed error is worse than this says nothing about being stopped. */
        const val MAX_SPEED_ERROR_MPS = 1.5f
        const val MAX_WOBBLES = 2
    }
}

/** The car's motion right now: fed by the car session's location updates, read by Android Auto's sensor channel. */
object CarMotion {
    private val detector = ParkDetector()

    fun onLocation(location: Location) {
        // Only the GPS chip measures speed. Other providers pass it on late, estimate it, or report zero.
        if (location.provider != LocationManager.GPS_PROVIDER || !location.hasSpeed()) return
        val speedError = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
        detector.onFix(location.speed, speedError, location.elapsedRealtimeNanos / 1_000_000)
    }

    fun motion(): Motion = detector.motion(SystemClock.elapsedRealtime())

    fun speedKmh(): Int? = detector.speedKmh(SystemClock.elapsedRealtime())

    fun reset() = detector.reset()
}
