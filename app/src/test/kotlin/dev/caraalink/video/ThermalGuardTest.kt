package dev.caraalink.video

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGuardTest {

    @Test
    fun `the video is lowered from severe heat up, not before`() {
        assertFalse(ThermalGuard.shouldLimit(PowerManager.THERMAL_STATUS_NONE))
        assertFalse(ThermalGuard.shouldLimit(PowerManager.THERMAL_STATUS_LIGHT))
        assertFalse(ThermalGuard.shouldLimit(PowerManager.THERMAL_STATUS_MODERATE))
        assertTrue(ThermalGuard.shouldLimit(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(ThermalGuard.shouldLimit(PowerManager.THERMAL_STATUS_CRITICAL))
        assertTrue(ThermalGuard.shouldLimit(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }
}
