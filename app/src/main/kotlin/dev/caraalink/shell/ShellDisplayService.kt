package dev.caraalink.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Runs outside the app, as the `shell` user, started by Shizuku. An ordinary
 * app may only put its own windows on a display it creates; adb's user may
 * create a display any app can be opened on, and may send it touches. This
 * is the same route scrcpy's "new display" takes (Apache-2.0; the flag
 * values and the fake shell context below follow its server).
 *
 * Nothing here is reachable from another app: Shizuku only binds it for the
 * app it belongs to, after the user has granted that app access.
 */
class ShellDisplayService() : IShellDisplay.Stub() {

    /** Shizuku 13 passes a context; older versions use the no-argument constructor. */
    @Suppress("unused")
    constructor(context: Context) : this() {
        given = context
    }

    private var given: Context? = null
    private var display: VirtualDisplay? = null

    private val context: Context by lazy { ShellContext(given ?: systemContext()) }

    override fun destroy() {
        releaseDisplay()
        exitProcess(0)
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        releaseDisplay()
        return try {
            // DisplayManager's constructor is hidden; this process is not an app, so reflection reaches it.
            val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            ctor.isAccessible = true
            val vd = ctor.newInstance(context).createVirtualDisplay("AA Bridge apps", width, height, dpi, surface, flags(Build.VERSION.SDK_INT))
            display = vd
            vd.display.displayId
        } catch (e: Throwable) {
            Log.e(TAG, "createDisplay failed", e)
            -1
        }
    }

    override fun releaseDisplay() {
        runCatching { display?.release() }
        display = null
    }

    override fun startApp(displayId: Int, component: String): String = try {
        // `am` as the shell user is the one launcher that is the same on every Android version.
        val process = ProcessBuilder(
            "am", "start", "--display", displayId.toString(), "-n", component,
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "--activity-reorder-to-front",
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.trim()
    } catch (e: Throwable) {
        "failed: $e"
    }

    override fun touch(displayId: Int, action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        inject(event, displayId)
        event.recycle()
    }

    override fun key(displayId: Int, keycode: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, keycode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            inject(event, displayId)
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun inject(event: InputEvent, displayId: Int) {
        try {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
            val manager = context.getSystemService(Context.INPUT_SERVICE)
            manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .invoke(manager, event, INJECT_ASYNC)
        } catch (e: Throwable) {
            Log.e(TAG, "inject failed", e)
        }
    }

    /** `ActivityThread.systemMain().getSystemContext()`, for when Shizuku hands over no context. */
    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** The system checks that the calling package belongs to the calling user, and this process is the shell user. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    }

    companion object {
        private const val TAG = "ShellDisplayService"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val SHELL_UID = 2000
        private const val INJECT_ASYNC = 0

        // Hidden DisplayManager.VIRTUAL_DISPLAY_FLAG_* values.
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val ROTATES_WITH_CONTENT = 1 shl 7
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15

        /** Public, so any app can be opened on it; its apps close with it instead of jumping to the phone. */
        fun flags(sdk: Int): Int {
            var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= 33) flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
            if (sdk >= 34) flags = flags or OWN_FOCUS or DEVICE_DISPLAY_GROUP
            return flags
        }
    }
}
