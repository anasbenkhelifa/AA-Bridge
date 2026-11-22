package dev.caraalink.link

import org.junit.Assert.assertEquals
import org.junit.Test

class ParkDetectorTest {

    private val detector = ParkDetector()

    /** One trusted fix a second from [fromMs], all at [speed]. Returns the time of the last one. */
    private fun fixes(speed: Float, fromMs: Long, seconds: Int): Long {
        var t = fromMs
        repeat(seconds) { detector.onFix(speed, null, t); t += 1_000 }
        return t - 1_000
    }

    @Test
    fun `nothing is known before the first fix`() {
        assertEquals(Motion.UNKNOWN, detector.motion(1_000))
    }

    @Test
    fun `a car counts as parked only after four seconds of slow fixes`() {
        val last = fixes(0.1f, 0, 4)
        assertEquals(3_000, last)
        assertEquals(Motion.MOVING, detector.motion(3_999))

        detector.onFix(0.0f, null, 4_000)
        assertEquals(Motion.PARKED, detector.motion(4_000))
    }

    @Test
    fun `one slow fix is never enough`() {
        detector.onFix(0.1f, null, 0)

        assertEquals(Motion.MOVING, detector.motion(2_000))
        // Four seconds on, the hold has run out but so has the fix.
        assertEquals(Motion.UNKNOWN, detector.motion(4_000))
        assertEquals(Motion.UNKNOWN, detector.motion(60_000))
    }

    @Test
    fun `parked lapses two and a half seconds after the last fix`() {
        val last = fixes(0f, 0, 10)

        assertEquals(Motion.PARKED, detector.motion(last + 2_500))
        assertEquals(Motion.UNKNOWN, detector.motion(last + 2_501))
    }

    @Test
    fun `a gap in the fixes starts the wait over`() {
        val last = fixes(0f, 0, 10)
        assertEquals(Motion.PARKED, detector.motion(last))

        // A tunnel, then one slow fix: what was true a minute ago proves nothing.
        detector.onFix(0.3f, null, last + 60_000)
        assertEquals(Motion.MOVING, detector.motion(last + 60_000))

        val after = fixes(0.3f, last + 61_000, 4)
        assertEquals(Motion.PARKED, detector.motion(after))
    }

    @Test
    fun `the first fix at driving speed ends parked at once`() {
        val last = fixes(0f, 0, 10)

        detector.onFix(ParkDetector.MOVE_MPS, null, last + 1_000)

        assertEquals(Motion.MOVING, detector.motion(last + 1_000))
    }

    @Test
    fun `movement is believed even from a rough fix`() {
        val last = fixes(0f, 0, 10)

        detector.onFix(6f, 3f, last + 1_000)

        assertEquals(Motion.MOVING, detector.motion(last + 1_000))
    }

    @Test
    fun `a rough fix cannot vouch for standing still`() {
        val last = fixes(0f, 0, 10)

        detector.onFix(0.1f, 2f, last + 1_000)
        assertEquals(Motion.MOVING, detector.motion(last + 1_000))

        // Rough fixes never add up to parked, however many there are.
        var t = last + 2_000
        repeat(10) { detector.onFix(0.1f, 2f, t); t += 1_000 }
        assertEquals(Motion.MOVING, detector.motion(t - 1_000))
    }

    @Test
    fun `stopping again starts the wait over`() {
        val last = fixes(0f, 0, 10)
        detector.onFix(8f, null, last + 1_000)
        val stopped = fixes(0f, last + 2_000, 4)

        assertEquals(Motion.MOVING, detector.motion(stopped))
        detector.onFix(0f, null, stopped + 1_000)
        assertEquals(Motion.PARKED, detector.motion(stopped + 1_000))
    }

    @Test
    fun `one reading between the two speeds is noise, two in a row is creeping`() {
        val last = fixes(0f, 0, 10)

        detector.onFix(1.2f, null, last + 1_000)
        assertEquals(Motion.PARKED, detector.motion(last + 1_000))
        detector.onFix(0.1f, null, last + 2_000)
        detector.onFix(1.2f, null, last + 3_000)
        assertEquals(Motion.PARKED, detector.motion(last + 3_000))

        detector.onFix(1.2f, null, last + 4_000)
        assertEquals(Motion.MOVING, detector.motion(last + 4_000))
    }

    @Test
    fun `a car creeping below driving speed never counts as parked`() {
        val last = fixes(1.0f, 0, 30)

        assertEquals(Motion.MOVING, detector.motion(last))
    }

    @Test
    fun `reset forgets everything`() {
        val last = fixes(0f, 0, 10)
        detector.reset()

        assertEquals(Motion.UNKNOWN, detector.motion(last))
    }
}
