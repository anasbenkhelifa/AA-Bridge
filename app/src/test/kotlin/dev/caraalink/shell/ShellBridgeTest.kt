package dev.caraalink.shell

import dev.caraalink.R
import dev.caraalink.shell.ShellBridge.State
import dev.caraalink.video.CarAppPresentation
import dev.caraalink.video.CarAppsPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellBridgeTest {

    @Test
    fun `the state names the next thing the user has to do`() {
        assertEquals(State.NOT_INSTALLED, ShellBridge.stateOf(installed = false, running = false, granted = false))
        assertEquals(State.NOT_RUNNING, ShellBridge.stateOf(installed = true, running = false, granted = false))
        assertEquals(State.NEEDS_PERMISSION, ShellBridge.stateOf(installed = true, running = true, granted = false))
        assertEquals(State.READY, ShellBridge.stateOf(installed = true, running = true, granted = true))
        // Shizuku started over adb can be running without its manager app being visible to us.
        assertEquals(State.READY, ShellBridge.stateOf(installed = false, running = true, granted = true))
    }

    @Test
    fun `each missing step has its own words on the car screen`() {
        assertEquals(R.string.apps_need_install, CarAppsPresentation.needsText(State.NOT_INSTALLED))
        assertEquals(R.string.apps_need_start, CarAppsPresentation.needsText(State.NOT_RUNNING))
        assertEquals(R.string.apps_need_allow, CarAppsPresentation.needsText(State.NEEDS_PERMISSION))
    }

    @Test
    fun `the app screen is public and closes its apps with it, with the newer flags only where they exist`() {
        val public = 1
        val destroyContent = 1 shl 8
        val trusted = 1 shl 10
        val ownFocus = 1 shl 14
        for (sdk in listOf(29, 33, 34)) {
            val flags = ShellDisplayService.flags(sdk)
            assertTrue(flags and public != 0)
            assertTrue(flags and destroyContent != 0)
            assertEquals(sdk >= 33, flags and trusted != 0)
            assertEquals(sdk >= 34, flags and ownFocus != 0)
        }
    }

    @Test
    fun `apps are laid out at the car screen's own density`() {
        assertEquals(213, CarAppPresentation.dpiFor(1920, 720))
        assertEquals(142, CarAppPresentation.dpiFor(800, 480))
    }
}
