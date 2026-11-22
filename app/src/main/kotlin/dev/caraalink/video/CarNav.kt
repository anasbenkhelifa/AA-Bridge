package dev.caraalink.video

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The way out of every car screen. Some cars keep their own Back and Home
 * buttons for themselves and never send them to the phone, so nothing here
 * may depend on them: each screen carries a small pill at its edge with Home,
 * and Back where there is something to go back to.
 */
object CarNav {
    const val HOME = "⌂"
    const val BACK = "←"

    /** Adds the pill to [root], halfway down its starting edge, above whatever else is in it. */
    fun attach(root: FrameLayout, onHome: () -> Unit, onBack: (() -> Unit)? = null) {
        val context = root.context
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(PILL); cornerRadius = dp(context, 22).toFloat() }
            setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6))
            for ((symbol, action) in buttons(onHome, onBack)) addView(button(context, symbol, action))
        }
        root.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL,
        ).apply { marginStart = dp(context, 8) })
    }

    /** Home always; Back above it only when the screen has one. */
    fun buttons(onHome: () -> Unit, onBack: (() -> Unit)?): List<Pair<String, () -> Unit>> =
        listOfNotNull(onBack?.let { BACK to it }, HOME to onHome)

    private fun button(context: Context, symbol: String, action: () -> Unit): View = TextView(context).apply {
        text = symbol
        textSize = 26f
        setTextColor(CarWidgets.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(context, 52)
        minHeight = dp(context, 52)
        isClickable = true
        setOnClickListener { action() }
    }

    private const val PILL = 0xD90A0E12.toInt()

    private fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}
