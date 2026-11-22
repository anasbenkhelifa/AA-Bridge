package dev.caraalink.settings

import dev.caraalink.settings.CarProfiles.CarSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarProfilesTest {

    @Test
    fun `the same car as last time keeps the settings as they are`() {
        // Even with a snapshot stored: anything changed since belongs to this car.
        assertEquals(CarSwitch.SAME_CAR, CarProfiles.carSwitch(lastCarId = "a", carId = "a", remembered = true))
        assertEquals(CarSwitch.SAME_CAR, CarProfiles.carSwitch(lastCarId = "a", carId = "a", remembered = false))
    }

    @Test
    fun `a different car that was seen before gets its own settings back`() {
        assertEquals(CarSwitch.RESTORE, CarProfiles.carSwitch(lastCarId = "a", carId = "b", remembered = true))
    }

    @Test
    fun `a car seen for the first time starts from the current settings`() {
        assertEquals(CarSwitch.NEW_CAR, CarProfiles.carSwitch(lastCarId = "a", carId = "b", remembered = false))
        assertEquals(CarSwitch.NEW_CAR, CarProfiles.carSwitch(lastCarId = null, carId = "b", remembered = false))
    }

    @Test
    fun `a car is named by its head unit id, else its head unit name`() {
        assertEquals(CarProfiles.carId("HU-123", "Android_6f01"), CarProfiles.carId("HU-123", "another name"))
        assertEquals(CarProfiles.carId("", "Android_6f01"), CarProfiles.carId(null, "Android_6f01"))
        assertNotEquals(CarProfiles.carId("HU-123", null), CarProfiles.carId("HU-124", null))
    }

    @Test
    fun `the name never contains what the head unit reported`() {
        val id = CarProfiles.carId("HU-123", null)!!

        assertEquals(12, id.length)
        assertEquals(false, id.contains("HU"))
    }

    @Test
    fun `a head unit that reports nothing has no name`() {
        assertNull(CarProfiles.carId(null, null))
        assertNull(CarProfiles.carId(" ", ""))
    }

    @Test
    fun `only the most recently saved cars are kept`() {
        val savedAt = mapOf("car:a" to 100L, "car:b" to 300L, "car:c" to 200L)

        assertEquals(listOf("car:a"), CarProfiles.snapshotsToDrop(savedAt, keep = 2))
        assertEquals(emptyList<String>(), CarProfiles.snapshotsToDrop(savedAt, keep = 3))
    }
}
