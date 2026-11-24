package dev.caraalink.mirror

import dev.caraalink.connection.CarLink
import dev.caraalink.link.Motion
import dev.caraalink.link.ParkDetector
import dev.caraalink.video.AaSidebar
import dev.caraalink.video.CarWidgets
import dev.caraalink.video.MirrorCarPresentation
import dev.caraalink.video.Weather
import dev.caraalink.wireless.CarBluetooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic and decisions behind the car home screen, the side strip and the phone mirror. */
class PhoneMirrorTest {

    @Test
    fun `the capture keeps the phone's shape with its long side at most 1280`() {
        assertEquals(576 to 1280, PhoneMirror.captureSize(1080, 2400))
        assertEquals(1280 to 576, PhoneMirror.captureSize(2400, 1080))
        assertEquals(720 to 1280, PhoneMirror.captureSize(720, 1280))
        // Encoders want even sizes.
        assertEquals(590 to 1280, PhoneMirror.captureSize(1440, 3120))
    }

    @Test
    fun `the picture is fitted whole inside the car screen`() {
        assertEquals(324 to 720, MirrorCarPresentation.fitInside(576, 1280, 1920, 720))
        assertEquals(1600 to 720, MirrorCarPresentation.fitInside(1280, 576, 1920, 720))
        assertEquals(800 to 360, MirrorCarPresentation.fitInside(1280, 576, 800, 480))
    }

    @Test
    fun `with hiding on, the phone screen shows only while the car is known to be stopped`() {
        assertFalse(MirrorCarPresentation.hidden(true, Motion.PARKED))
        assertTrue(MirrorCarPresentation.hidden(true, Motion.MOVING))
        assertTrue(MirrorCarPresentation.hidden(true, Motion.UNKNOWN))
        assertFalse(MirrorCarPresentation.hidden(false, Motion.MOVING))
    }

    @Test
    fun `leaving the car's Bluetooth ends a wireless link and nothing else`() {
        val car = "AA:BB:CC:DD:EE:FF"
        assertTrue(CarBluetooth.shouldStop(car, car.lowercase(), CarLink.WIRELESS))
        assertFalse(CarBluetooth.shouldStop(car, car, CarLink.CABLE))
        assertFalse(CarBluetooth.shouldStop(car, car, CarLink.NONE))
        assertFalse(CarBluetooth.shouldStop(car, "11:22:33:44:55:66", CarLink.WIRELESS))
        assertFalse(CarBluetooth.shouldStop(null, car, CarLink.WIRELESS))
    }

    @Test
    fun `the side strip leaves Android Auto the rest of the width`() {
        assertEquals(1920, AaSidebar.contentWidth(1920, sidebar = false))
        assertEquals(1612, AaSidebar.contentWidth(1920, sidebar = true))
    }

    @Test
    fun `the weather request rounds the position to two decimals`() {
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=35.56&longitude=6.17&current=temperature_2m",
            Weather.url(35.55581, 6.17414),
        )
        assertEquals("18°", Weather.format(18.4))
        assertEquals("-3°", Weather.format(-2.6))
    }

    @Test
    fun `the speed shown is the last fresh fix in whole km per hour`() {
        val detector = ParkDetector()
        assertNull(detector.speedKmh(0))
        detector.onFix(13.9f, null, 1_000)
        assertEquals(50, detector.speedKmh(1_500))
        assertNull(detector.speedKmh(1_000 + ParkDetector.STALE_MS + 1))
        assertEquals("50", CarWidgets.speedText(50))
        assertEquals("–", CarWidgets.speedText(null))
    }
}
