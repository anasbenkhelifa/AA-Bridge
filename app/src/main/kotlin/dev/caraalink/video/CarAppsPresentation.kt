package dev.caraalink.video

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.caraalink.R
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.shell.ShellBridge

/** The phone's apps as a grid on the car screen. Picking one opens it there, not on the phone. */
class CarAppsPresentation(
    context: Context,
    display: Display,
    private val onPick: (component: String) -> Unit,
    private val onHome: () -> Unit,
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShellBridge.connect(context)
        val state = ShellBridge.state(context)
        val root = FrameLayout(context)
        setContentView(root)
        if (state != ShellBridge.State.READY) {
            root.addView(TextView(context).apply {
                setText(needsText(state))
                textSize = 26f
                setTextColor(CarWidgets.TEXT)
                gravity = Gravity.CENTER
                setPadding(dp(64), dp(32), dp(64), dp(32))
                setBackgroundColor(CarWidgets.BACKGROUND)
            })
            CarNav.attach(root, onHome)
            return
        }
        val apps = ShellBridge.launchableApps(context)
        val grid = GridView(context).apply {
            setBackgroundColor(CarWidgets.BACKGROUND)
            numColumns = COLUMNS
            verticalSpacing = dp(12)
            setPadding(dp(84), dp(20), dp(24), dp(20))
            clipToPadding = false
            adapter = object : BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(position: Int) = apps[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val cell = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(ImageView(context), LinearLayout.LayoutParams(dp(72), dp(72)))
                        addView(TextView(context).apply {
                            textSize = 16f
                            setTextColor(CarWidgets.TEXT)
                            gravity = Gravity.CENTER
                            maxLines = 1
                            setPadding(dp(4), dp(6), dp(4), 0)
                        })
                    }
                    (cell.getChildAt(0) as ImageView).setImageDrawable(apps[position].icon)
                    (cell.getChildAt(1) as TextView).text = apps[position].label
                    return cell
                }
            }
            setOnItemClickListener { _, _, position, _ -> onPick(apps[position].component) }
        }
        root.addView(grid)
        CarNav.attach(root, onHome)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    companion object {
        const val COLUMNS = 7

        fun needsText(state: ShellBridge.State): Int = when (state) {
            ShellBridge.State.NOT_INSTALLED -> R.string.apps_need_install
            ShellBridge.State.NOT_RUNNING -> R.string.apps_need_start
            else -> R.string.apps_need_allow
        }
    }
}

/**
 * One app, running on a screen of its own that is drawn here. The screen is
 * the car's size and shape, so the app lays itself out for the car rather
 * than being a picture of the phone, and the phone is free for something else.
 */
class CarAppPresentation(
    context: Context,
    display: Display,
    private val component: String,
    private val onHome: () -> Unit,
) : Presentation(context, display) {

    private val logger = DiagnosticLogger(context, "car-app")
    private val main = Handler(Looper.getMainLooper())
    private var downAt = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val texture = TextureView(context)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(w, h)
                open(Surface(st), w, h)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                displayId = -1
                val service = ShellBridge.service
                Thread { runCatching { service?.releaseDisplay() } }.start()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        texture.setOnTouchListener { _, ev -> onTouch(ev); true }
        root.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        CarNav.attach(root, onHome, onBack = {
            val id = displayId
            if (id >= 0) runCatching { ShellBridge.service?.key(id, android.view.KeyEvent.KEYCODE_BACK) }
        })
        setContentView(root)
    }

    /** Off the main thread: these are calls into another process. */
    private fun open(surface: Surface, w: Int, h: Int) = Thread({
        val service = ShellBridge.service
        if (service == null) {
            logger.log("no shell helper -- is Shizuku still running?")
            return@Thread
        }
        val id = runCatching { service.createDisplay(surface, w, h, dpiFor(w, h)) }.getOrDefault(-1)
        logger.log("display for $component: id=$id ${w}x$h")
        if (id < 0) return@Thread
        val out = runCatching { service.startApp(id, component) }.getOrElse { "failed: $it" }
        logger.log("start: $out")
        main.post { displayId = id }
    }, "car-app-open").start()

    private fun onTouch(ev: MotionEvent) {
        val id = displayId
        val service = ShellBridge.service ?: return
        if (id < 0) return
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return
        if (action == MotionEvent.ACTION_DOWN) downAt = SystemClock.uptimeMillis()
        // The view is the display's own size, so its coordinates are the display's.
        runCatching { service.touch(id, action, ev.x, ev.y, downAt) }
    }

    companion object {
        /** The screen showing an app right now, or -1. Read by the car session for the Back button. */
        @Volatile
        var displayId = -1
            private set

        /** The same density rule the car screen itself uses, so apps come out a comfortable size from the driver's seat. */
        fun dpiFor(width: Int, height: Int): Int = (minOf(width, height) / 720f * 213).toInt().coerceIn(120, 320)
    }
}
