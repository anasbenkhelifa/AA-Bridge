package dev.caraalink.video

import android.content.Context
import android.os.PowerManager
import dev.caraalink.aa.AaReconnectBus
import dev.caraalink.aa.AaServiceDiscovery
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.settings.AppSettings

/**
 * Steps the Android Auto video down to 720p at 30 fps when the phone reports
 * severe heat, and keeps it there until the car link ends.
 *
 * Every frame is encoded by Android Auto, decoded here, drawn, and encoded
 * again for the car, on a phone that is usually charging in the sun. The
 * resolution and frame rate Android Auto is asked for are the only real
 * levers on that work, and they can't change mid-session, so stepping down
 * costs one reconnect. It never steps back up during a drive: a phone that
 * cools at 720p would heat again at 1080p, and each swing is another restart.
 */
object ThermalGuard {

    /** True from the first severe reading until [stop]. Read by AaServiceDiscovery.configFromSettings(). */
    @Volatile
    var limited = false
        private set

    /** The phone's last reported `PowerManager.THERMAL_STATUS_*`, for the drive log. */
    @Volatile
    var status = PowerManager.THERMAL_STATUS_NONE
        private set

    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /** Severe is where Android says the experience is already largely affected by throttling. */
    internal fun shouldLimit(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_SEVERE

    /** Watches the phone's temperature for the length of a car link. [onLimited] runs once, when the video was lowered. */
    @Synchronized
    fun start(context: Context, onLimited: () -> Unit) {
        if (listener != null) return
        val app = context.applicationContext
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        val l = PowerManager.OnThermalStatusChangedListener { onStatus(app, it, onLimited) }
        listener = l
        pm.addThermalStatusListener(l)
        onStatus(app, pm.currentThermalStatus, onLimited)
    }

    @Synchronized
    fun stop(context: Context) {
        val l = listener ?: return
        listener = null
        runCatching { context.applicationContext.getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) }
        limited = false
    }

    @Synchronized
    private fun onStatus(context: Context, newStatus: Int, onLimited: () -> Unit) {
        // A status that was already on its way when the link ended must not cap the next drive.
        if (listener == null) return
        val logger = DiagnosticLogger(context, "thermal")
        if (newStatus != status) logger.log("thermal status $status -> $newStatus")
        status = newStatus
        if (limited || !shouldLimit(newStatus) || !AppSettings.thermalGuard(context)) return
        val before = AaServiceDiscovery.configFromSettings(context)
        limited = true
        val after = AaServiceDiscovery.configFromSettings(context)
        if (before.resolution == after.resolution && before.frameRate == after.frameRate) {
            logger.log("severe heat, but the video is already at its lowest: ${AaServiceDiscovery.describe(after)}")
            return
        }
        logger.log("severe heat -- lowering video for this drive: ${AaServiceDiscovery.describe(before)} -> ${AaServiceDiscovery.describe(after)}")
        // With no session on the car screen yet, the next one simply starts lower and there is nothing to announce.
        val restart = AaReconnectBus.onVideoSettingsChanged ?: return
        restart()
        onLimited()
    }
}
