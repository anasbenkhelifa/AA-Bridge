package dev.caraalink.link

import android.location.Location
import android.os.SystemClock

/** Whether the car is stopped, as far as the phone's GPS can tell. */
enum class Motion { UNKNOWN, PARKED, MOVING }

/**
 * Decides stopped or moving from GPS speed alone. The car's own parking brake
 * and gear never reach the phone over CarbitLink or QDLink, so speed is all
 * there is.
 *
 * Built to fail towards MOVING: the car counts as parked only after the speed
 * has stayed under [STOP_MPS] for [HOLD_MS], it counts as moving again on the
 * first fix at [MOVE_MPS] or more, and with no fix for [STALE_MS] (a tunnel, a
 * garage, location switched off) nothing is known at all.
 */
class ParkDetector {
    private var lastFixAt = NEVER
    private var stillSince = NEVER

    /** One GPS fix: [speedMps] at [atMs], on the same clock as [motion]. */
    @Synchronized
    fun onFix(speedMps: Float, atMs: Long) {
        lastFixAt = atMs
        when {
            speedMps >= MOVE_MPS -> stillSince = NEVER
            speedMps <= STOP_MPS -> if (stillSince == NEVER) stillSince = atMs
            // Between the two, keep what we had: a GPS wobble shouldn't flip the answer either way.
        }
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
        stillSince = NEVER
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /** 2.5 km/h. A stopped car's GPS speed sits well under this with a clear sky. */
        const val STOP_MPS = 0.7f
        /** 5.4 km/h. Walking pace: anything at or above this is driving. */
        const val MOVE_MPS = 1.5f
        const val HOLD_MS = 4_000L
        const val STALE_MS = 5_000L
        /** A fix whose own speed error is worse than this says nothing about being stopped. */
        const val MAX_SPEED_ERROR_MPS = 1.5f

        /** True when [speedErrorMps], the fix's reported speed accuracy if it has one, is good enough to use. */
        fun usable(hasSpeed: Boolean, speedErrorMps: Float?): Boolean =
            hasSpeed && (speedErrorMps == null || speedErrorMps <= MAX_SPEED_ERROR_MPS)
    }
}

/** The car's motion right now: fed by the car session's location updates, read by Android Auto's sensor channel. */
object CarMotion {
    private val detector = ParkDetector()

    fun onLocation(location: Location) {
        val speedError = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
        if (!ParkDetector.usable(location.hasSpeed(), speedError)) return
        detector.onFix(location.speed, location.elapsedRealtimeNanos / 1_000_000)
    }

    fun motion(): Motion = detector.motion(SystemClock.elapsedRealtime())

    fun reset() = detector.reset()
}
