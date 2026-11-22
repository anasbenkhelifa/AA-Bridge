package dev.caraalink.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarKeysTest {

    @Test
    fun `with nothing stored, Home closes Android Auto and Back goes back`() {
        val map = CarKeys.decode(null)

        assertEquals(CarKeyAction.CLOSE_AA, map[CarKeys.KEY_HOME])
        assertEquals(CarKeyAction.BACK, map[CarKeys.KEY_BACK])
        assertEquals(2, map.size)
    }

    @Test
    fun `stored choices sit on top of the defaults`() {
        val map = CarKeys.decode("1044=close,77=voice")

        assertEquals(CarKeyAction.CLOSE_AA, map[CarKeys.KEY_BACK])
        assertEquals(CarKeyAction.VOICE, map[77])
        assertEquals(CarKeyAction.CLOSE_AA, map[CarKeys.KEY_HOME])
    }

    @Test
    fun `a default can be switched off`() {
        assertEquals(CarKeyAction.NONE, CarKeys.decode("1042=none")[CarKeys.KEY_HOME])
    }

    @Test
    fun `entries that don't parse are skipped, the rest still load`() {
        val map = CarKeys.decode("abc=voice,12=,13=flying,,14=next")

        assertEquals(CarKeyAction.NEXT, map[14])
        assertEquals(3, map.size)
    }

    @Test
    fun `encode and decode round-trip`() {
        val map = CarKeys.DEFAULTS + mapOf(7 to CarKeyAction.PLAY_PAUSE, 9 to CarKeyAction.END_CALL)

        assertEquals(map, CarKeys.decode(CarKeys.encode(map)))
    }

    @Test
    fun `every action with a key code is offered to Android Auto once`() {
        val codes = CarKeys.AA_KEYCODES

        assertEquals(codes.toSet().size, codes.size)
        assertTrue(codes.containsAll(listOf(3, 4, 5, 6, 84, 85, 87, 88)))
        assertEquals(CarKeyAction.values().count { it.aaKeycode != null }, codes.size)
    }

    @Test
    fun `a press is reported with every button seen so far`() {
        val got = ArrayList<CarKeys.Pressed>()
        val listener: (CarKeys.Pressed) -> Unit = { got += it }
        val before = CarKeys.pressed.count
        CarKeys.addListener(listener)
        try {
            CarKeys.report(501)
            CarKeys.report(502)
        } finally {
            CarKeys.removeListener(listener)
        }
        CarKeys.report(503)

        assertEquals(2, got.size)
        assertEquals(502, got[1].last)
        assertTrue(got[1].seen.containsAll(listOf(501, 502)))
        assertEquals(before + 2, got[1].count)
    }
}
