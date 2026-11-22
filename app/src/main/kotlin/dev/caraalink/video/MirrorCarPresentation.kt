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
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.caraalink.R
import dev.caraalink.link.CarMotion
import dev.caraalink.link.Motion
import dev.caraalink.mirror.PhoneMirror
import dev.caraalink.settings.AppSettings

/** The phone's screen on the car screen, fitted whole, with the car's touches played back on the phone. */
class MirrorCarPresentation(context: Context, display: Display, private val onHome: () -> Unit) : Presentation(context, display) {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var texture: TextureView
    private lateinit var message: TextView
    private lateinit var cover: TextView
    private var pictureWidth = 0
    private var pictureHeight = 0
    private var downAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 500)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(context)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                PhoneMirror.attach(Surface(st)) { pw, ph ->
                    st.setDefaultBufferSize(pw, ph)
                    pictureWidth = pw
                    pictureHeight = ph
                    fit(root.width, root.height)
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                PhoneMirror.detach()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        texture.setOnTouchListener { v, ev -> onPictureTouch(v, ev); true }
        root.addView(texture, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> fit(r - l, b - t) }

        message = overlay(CarWidgets.BACKGROUND)
        cover = overlay(Color.BLACK).apply { setText(R.string.mirror_moving) }
        root.addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        CarNav.attach(root, onHome, onBack = {
            runCatching { dev.caraalink.shell.ShellBridge.service?.key(Display.DEFAULT_DISPLAY, android.view.KeyEvent.KEYCODE_BACK) }
        })
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        PhoneMirror.onChanged = { refresh() }
        dev.caraalink.shell.ShellBridge.connect(context)
        if (!PhoneMirror.allowed) PhoneMirror.askForConsent(context)
        tick.run()
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        PhoneMirror.onChanged = null
        super.onStop()
    }

    private fun overlay(background: Int) = TextView(context).apply {
        textSize = 26f
        setTextColor(CarWidgets.TEXT)
        gravity = Gravity.CENTER
        setPadding(64, 32, 64, 32)
        setBackgroundColor(background)
        // Swallows touches, so nothing behind a message or the cover can be pressed.
        isClickable = true
    }

    private fun refresh() {
        message.visibility = if (PhoneMirror.allowed) View.GONE else View.VISIBLE
        if (!PhoneMirror.allowed) message.setText(R.string.mirror_allow_on_phone)
        cover.visibility = if (hidden(AppSettings.mirrorHideMoving(context), CarMotion.motion())) View.VISIBLE else View.GONE
    }

    /** Sizes the picture to sit whole inside [areaW]x[areaH]. */
    private fun fit(areaW: Int, areaH: Int) {
        if (areaW <= 0 || areaH <= 0 || pictureWidth <= 0 || pictureHeight <= 0) return
        val (w, h) = fitInside(pictureWidth, pictureHeight, areaW, areaH)
        val lp = texture.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            texture.post { texture.layoutParams = lp }
        }
    }

    /** The car's touch, placed on the same spot of the phone's own screen. Needs the Shizuku helper; without it the picture is view-only. */
    private fun onPictureTouch(v: View, ev: MotionEvent) {
        val service = dev.caraalink.shell.ShellBridge.service ?: return
        if (v.width <= 0 || v.height <= 0) return
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return
        if (action == MotionEvent.ACTION_DOWN) downAt = SystemClock.uptimeMillis()
        val phone = PhoneMirror.phoneSize(context)
        val (x, y) = PhoneMirror.toPhone(ev.x / v.width, ev.y / v.height, phone.x, phone.y)
        runCatching { service.touch(Display.DEFAULT_DISPLAY, action, x, y, downAt) }
    }

    companion object {
        /** The largest size with the picture's shape that fits the area. */
        fun fitInside(pictureW: Int, pictureH: Int, areaW: Int, areaH: Int): Pair<Int, Int> {
            val scale = minOf(areaW.toFloat() / pictureW, areaH.toFloat() / pictureH)
            return (pictureW * scale).toInt().coerceAtLeast(1) to (pictureH * scale).toInt().coerceAtLeast(1)
        }

        /** With hiding on, the picture shows only while the car is known to be stopped. */
        fun hidden(hideWhileMoving: Boolean, motion: Motion): Boolean = hideWhileMoving && motion != Motion.PARKED
    }
}
