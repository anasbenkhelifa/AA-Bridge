package dev.caraalink.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Surface
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import dev.caraalink.R
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.service.UsbLinkForegroundService

/**
 * The phone's own screen on the car screen. Android lets an app put only its
 * own windows on a display it creates, so any other app (YouTube, a Quran
 * app, a chat) can reach the car one way: capture the phone's screen with
 * MediaProjection and draw that. The phone has to stay on and show the app.
 *
 * Touch goes back through [MirrorTouchService], an accessibility service the
 * driver turns on once; without it the picture still shows.
 */
object PhoneMirror {
    private const val MAX_SIDE = 1280
    private const val CHANNEL_ID = "mirror_consent"
    private const val CONSENT_NOTIFICATION_ID = 7

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var onSize: ((Int, Int) -> Unit)? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var appContext: Context? = null

    /** True once the driver has allowed screen sharing for this drive. */
    val allowed: Boolean get() = projection != null

    /** Told when sharing is allowed or ends, on the main thread. */
    var onChanged: (() -> Unit)? = null

    /** The phone screen's size as it is turned right now. */
    fun phoneSize(context: Context): Point {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return size
    }

    /** [size] scaled so its long side is at most [maxSide], even numbers. */
    internal fun captureSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxSide) return (width and 1.inv()) to (height and 1.inv())
        return (width * maxSide / long and 1.inv()) to (height * maxSide / long and 1.inv())
    }

    /** A point on the mirrored picture, as fractions of it, in phone pixels. */
    internal fun toPhone(fx: Float, fy: Float, phoneWidth: Int, phoneHeight: Int): Pair<Float, Float> =
        (fx.coerceIn(0f, 1f) * (phoneWidth - 1)) to (fy.coerceIn(0f, 1f) * (phoneHeight - 1))

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // The phone was turned: the capture follows, so a video fills the car screen instead of sitting sideways.
            if (displayId == Display.DEFAULT_DISPLAY) resize()
        }
    }

    /** Called by the service, already foreground with the mediaProjection type, with the consent result. */
    fun onConsent(context: Context, resultCode: Int, data: Intent) {
        val app = context.applicationContext
        appContext = app
        val log = DiagnosticLogger(app, "mirror")
        stop()
        val p = runCatching { app.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data) }
            .onFailure { log.log("getMediaProjection failed: $it") }.getOrNull() ?: return
        // Android 14 wants a callback registered before the capture starts, and ends the capture itself at times.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                log.log("screen sharing ended")
                release()
            }
        }, main)
        projection = p
        app.getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        app.getSystemService(NotificationManager::class.java).cancel(CONSENT_NOTIFICATION_ID)
        log.log("screen sharing allowed")
        surface?.let { start(it) }
        onChanged?.invoke()
    }

    /** The car view is ready to draw the phone. [onSize] gets the picture's size, again whenever the phone is turned. */
    fun attach(target: Surface, onSize: (Int, Int) -> Unit) {
        surface = target
        this.onSize = onSize
        if (projection != null) start(target)
    }

    fun detach() {
        surface?.release()
        surface = null
        onSize = null
        virtualDisplay?.release()
        virtualDisplay = null
        releaseScreen()
    }

    /** The phone's screen has to be on to have anything to show; dim is enough. Only while the car is showing it. */
    private fun holdScreen(app: Context) {
        if (wakeLock?.isHeld == true) return
        @Suppress("DEPRECATION")
        wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "caraalink:mirror").apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseScreen() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun start(target: Surface) {
        val app = appContext ?: return
        val p = projection ?: return
        val phone = phoneSize(app)
        val (w, h) = captureSize(phone.x, phone.y)
        onSize?.invoke(w, h)
        holdScreen(app)
        virtualDisplay?.release()
        virtualDisplay = runCatching {
            p.createVirtualDisplay("AA Bridge mirror", w, h, app.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, target, null, main)
        }.onFailure { DiagnosticLogger(app, "mirror").log("createVirtualDisplay failed: $it") }.getOrNull()
    }

    private fun resize() {
        val app = appContext ?: return
        val vd = virtualDisplay ?: return
        val phone = phoneSize(app)
        val (w, h) = captureSize(phone.x, phone.y)
        onSize?.invoke(w, h)
        runCatching { vd.resize(w, h, app.resources.displayMetrics.densityDpi) }
    }

    /** Ends screen sharing; the next use asks again. */
    fun stop() {
        val p = projection ?: return
        release()
        runCatching { p.stop() }
    }

    private fun release() {
        if (projection == null) return
        projection = null
        virtualDisplay?.release()
        virtualDisplay = null
        appContext?.let { runCatching { it.getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) } }
        releaseScreen()
        main.post { onChanged?.invoke() }
    }

    /** Asks on the phone for screen sharing; the car can't show Android's own dialog. */
    fun askForConsent(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.mirror_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MirrorConsentActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(context.getString(R.string.mirror_notif_title))
            .setContentText(context.getString(R.string.mirror_notif_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(CONSENT_NOTIFICATION_ID, n) }
    }
}

/** Shows Android's screen sharing dialog and hands the answer to the service. Has no screen of its own. */
class MirrorConsentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                // The service has to hold the mediaProjection type before the capture may start.
                startService(
                    Intent(this, UsbLinkForegroundService::class.java)
                        .setAction(UsbLinkForegroundService.ACTION_MIRROR_CONSENT)
                        .putExtra(UsbLinkForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(UsbLinkForegroundService.EXTRA_RESULT_DATA, data),
                )
            }
            finish()
        }
        if (savedInstanceState == null) {
            ask.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }
}

/**
 * Plays the car's touches on the phone. Dispatching a gesture is the only way
 * an ordinary app can touch another app, and Android reserves it for
 * accessibility services. It reads nothing from the screen.
 */
class MirrorTouchService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile
        var instance: MirrorTouchService? = null
            private set

        /** One finger's path in phone pixels, played over [durationMs]. False when the service is off. */
        fun play(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
            val service = instance ?: return false
            if (points.isEmpty()) return false
            val path = Path().apply {
                moveTo(points[0].first, points[0].second)
                for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 5_000))
            return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }

        fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
    }
}
