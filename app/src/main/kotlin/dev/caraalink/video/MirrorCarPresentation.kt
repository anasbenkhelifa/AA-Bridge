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
import dev.caraalink.mirror.MirrorTouchService
import dev.caraalink.mirror.PhoneMirror
import dev.caraalink.settings.AppSettings

/** The phone's screen on the car screen, fitted whole, with the car's touches played back on the phone. */
class MirrorCarPresentation(context: Context, display: Display) : Presentation(context, display) {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var texture: TextureView
    private lateinit var message: TextView
    private lateinit var cover: TextView
    private var pictureWidth = 0
    private var pictureHeight = 0
    private val stroke = ArrayList<Pair<Float, Float>>()
    private var strokeStartedAt = 0L

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
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        PhoneMirror.onChanged = { refresh() }
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

    /** One finger down to up becomes one gesture on the phone, with the same path and duration. */
    private fun onPictureTouch(v: View, ev: MotionEvent) {
        if (v.width <= 0 || v.height <= 0) return
        val phone = PhoneMirror.phoneSize(context)
        val point = PhoneMirror.toPhone(ev.x / v.width, ev.y / v.height, phone.x, phone.y)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stroke.clear()
                stroke += point
                strokeStartedAt = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_MOVE -> stroke += point
            MotionEvent.ACTION_UP -> {
                stroke += point
                MirrorTouchService.play(ArrayList(stroke), SystemClock.uptimeMillis() - strokeStartedAt)
                stroke.clear()
            }
        }
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
