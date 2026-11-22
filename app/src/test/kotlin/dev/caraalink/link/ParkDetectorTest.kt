package dev.caraalink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkDetectorTest {

    private val detector = ParkDetector()

    /** One fix a second from [fromMs], all at [speed]. Returns the time of the last one. */
    private fun fixes(speed: Float, fromMs: Long, seconds: Int): Long {
        var t = fromMs
        repeat(seconds) { detector.onFix(speed, t); t += 1_000 }
        return t - 1_000
    }

    @Test
    fun `nothing is known before the first fix`() {
        assertEquals(Motion.UNKNOWN, detector.motion(1_000))
    }

    @Test
    fun `a car only counts as parked after staying stopped for four seconds`() {
        detector.onFix(0.1f, 0)
        assertEquals(Motion.MOVING, detector.motion(0))

        detector.onFix(0.2f, 3_000)
        assertEquals(Motion.MOVING, detector.motion(3_999))

        detector.onFix(0.0f, 4_000)
        assertEquals(Motion.PARKED, detector.motion(4_000))
    }

    @Test
    fun `the first fix at driving speed ends parked at once`() {
        val last = fixes(0f, 0, 10)
        assertEquals(Motion.PARKED, detector.motion(last))

        detector.onFix(ParkDetector.MOVE_MPS, last + 1_000)

        assertEquals(Motion.MOVING, detector.motion(last + 1_000))
    }

    @Test
    fun `stopping again starts the wait over`() {
        val last = fixes(0f, 0, 10)
        detector.onFix(8f, last + 1_000)
        detector.onFix(0f, last + 2_000)

        assertEquals(Motion.MOVING, detector.motion(last + 5_000))
        detector.onFix(0f, last + 6_000)
        assertEquals(Motion.PARKED, detector.motion(last + 6_000))
    }

    @Test
    fun `a wobble between the two speeds changes nothing either way`() {
        val last = fixes(0f, 0, 6)
        detector.onFix(1.2f, last + 1_000)
        assertEquals(Motion.PARKED, detector.motion(last + 1_000))

        detector.onFix(9f, last + 2_000)
        detector.onFix(1.2f, last + 3_000)
        detector.onFix(1.2f, last + 9_000)
        assertEquals(Motion.MOVING, detector.motion(last + 9_000))
    }

    @Test
    fun `a parked car with no fix for five seconds is unknown again`() {
        val last = fixes(0f, 0, 10)

        assertEquals(Motion.PARKED, detector.motion(last + 5_000))
        assertEquals(Motion.UNKNOWN, detector.motion(last + 5_001))
    }

    @Test
    fun `reset forgets everything`() {
        val last = fixes(0f, 0, 10)
        detector.reset()

        assertEquals(Motion.UNKNOWN, detector.motion(last))
    }

    @Test
    fun `a fix is used only with a speed, and a speed error no worse than 1point5 metres a second`() {
        assertTrue(ParkDetector.usable(hasSpeed = true, speedErrorMps = null))
        assertTrue(ParkDetector.usable(hasSpeed = true, speedErrorMps = 1.5f))
        assertFalse(ParkDetector.usable(hasSpeed = true, speedErrorMps = 1.6f))
        assertFalse(ParkDetector.usable(hasSpeed = false, speedErrorMps = 0.1f))
    }
}
