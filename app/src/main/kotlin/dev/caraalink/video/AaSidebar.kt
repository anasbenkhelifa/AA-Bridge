package dev.caraalink.video

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import dev.caraalink.R
import dev.caraalink.link.CarMotion

/**
 * What sits beside or on top of Android Auto on the car screen. Android Auto
 * only draws 16:9 shapes, so on a wide screen a strip at the side costs it
 * nothing it could use well: the time, the speed, a way home, and YouTube
 * and Claude one tap from inside Android Auto.
 */
object AaSidebar {
    /** The strip's share of the screen's width. */
    const val FRACTION = 0.16f

    /** The width left for Android Auto on a car screen [carWidth] wide. */
    fun contentWidth(carWidth: Int, sidebar: Boolean): Int = if (sidebar) (carWidth * (1 - FRACTION)).toInt() else carWidth

    fun view(context: Context, onHome: () -> Unit, onOpenWeb: (url: String) -> Unit = {}): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(CarWidgets.BACKGROUND)
        addView(TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 34f
            setTextColor(CarWidgets.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        addView(speedText(context, 44f), spaced(context, 12))
        addView(TextView(context).apply {
            setText(R.string.car_kmh)
            textSize = 16f
            setTextColor(CarWidgets.MUTED)
            gravity = Gravity.CENTER
        })
        addView(button(context, context.getString(R.string.car_home), onHome), spaced(context, 22))
        addView(button(context, "YouTube") { onOpenWeb(CarWebPresentation.YOUTUBE) }, spaced(context, 10))
        addView(button(context, "Claude") { onOpenWeb(CarWebPresentation.CLAUDE) }, spaced(context, 10))
    }

    private fun button(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        setTextColor(CarWidgets.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
        background = GradientDrawable().apply { setColor(CarWidgets.SURFACE); cornerRadius = dp(context, 16).toFloat() }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** A small speed readout for a corner of Android Auto, when there is no strip. */
    fun speedBadge(context: Context): View = speedText(context, 26f).apply {
        setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
        background = GradientDrawable().apply { setColor(0xCC0A0E12.toInt()); cornerRadius = dp(context, 14).toFloat() }
    }

    /** A speed label that keeps itself current for as long as it is on screen. */
    private fun speedText(context: Context, size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(CarWidgets.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        val main = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                text = CarWidgets.speedText(CarMotion.speedKmh())
                main.postDelayed(this, 1_000)
            }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = tick.run()
            override fun onViewDetachedFromWindow(v: View) = main.removeCallbacks(tick)
        })
    }

    private fun spaced(context: Context, top: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, top) }

    private fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
