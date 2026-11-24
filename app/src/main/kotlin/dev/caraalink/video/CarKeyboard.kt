package dev.caraalink.video

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A keyboard drawn on the car screen. Android puts its own keyboard on the
 * phone, never on a display an app creates, so typing in the car needs one
 * of ours: large keys, English and Arabic letters, numbers and symbols.
 * It only reports what was pressed; whoever shows it decides where that goes.
 */
class CarKeyboard(
    context: Context,
    private val onText: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onEnter: () -> Unit,
    private val onHide: () -> Unit,
    /** The microphone key; left out when null. */
    private val onVoice: (() -> Unit)? = null,
) : LinearLayout(context) {

    private var layout = Layout.ENGLISH
    private var lettersLayout = Layout.ENGLISH
    private var shift = false

    init {
        orientation = VERTICAL
        setBackgroundColor(BACKGROUND)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        // Keys read left to right even when the car's language reads the other way.
        layoutDirection = LAYOUT_DIRECTION_LTR
        build()
    }

    private fun build() {
        removeAllViews()
        for (row in rows(layout, shift)) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
            for (key in row) line.addView(key(key, KEY, 1f) { onText(key); if (shift) { shift = false; build() } })
            addView(line, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)))
        }
        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        if (layout == Layout.ENGLISH) bottom.addView(key("⇧", if (shift) ACCENT else SPECIAL, 1.3f) { shift = !shift; build() })
        bottom.addView(key(if (layout == Layout.SYMBOLS) "ABC" else "123", SPECIAL, 1.3f) {
            layout = if (layout == Layout.SYMBOLS) lettersLayout else Layout.SYMBOLS
            shift = false
            build()
        })
        bottom.addView(key(if (lettersLayout == Layout.ENGLISH) "ع" else "EN", SPECIAL, 1.3f) {
            lettersLayout = if (lettersLayout == Layout.ENGLISH) Layout.ARABIC else Layout.ENGLISH
            layout = lettersLayout
            shift = false
            build()
        })
        if (onVoice != null) bottom.addView(key(MIC, SPECIAL, 1.3f, onVoice))
        bottom.addView(key(" ", KEY, 4.4f) { onText(" ") })
        bottom.addView(key("⌫", SPECIAL, 1.5f, onBackspace))
        bottom.addView(key("⏎", ACCENT, 1.8f, onEnter))
        bottom.addView(key("▾", SPECIAL, 1.2f, onHide))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)))
    }

    /** The language tag to listen in: Arabic while the Arabic letters are up, else the phone's own. */
    fun speechLanguage(): String? = if (lettersLayout == Layout.ARABIC) "ar" else null

    private fun key(label: String, color: Int, weight: Float, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 24f
        setTextColor(TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
        isClickable = true
        // Never takes focus, so the page keeps its cursor where it is.
        isFocusable = false
        if (label == MIC) {
            // Drawn, not typed: the microphone character is an emoji on most phones.
            text = ""
            val inset = dp(15)
            background = android.graphics.drawable.LayerDrawable(arrayOf(background, CarArt.Glyph(CarArt.Symbol.MIC, TEXT))).apply {
                setLayerInset(1, 0, inset, 0, inset)
            }
        }
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(3); bottomMargin = dp(3)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    enum class Layout { ENGLISH, ARABIC, SYMBOLS }

    companion object {
        private const val ROW_DP = 58
        private const val MIC = "mic"
        private const val BACKGROUND = 0xFF070B0E.toInt()
        private const val KEY = 0xFF1F2E36.toInt()
        private const val SPECIAL = 0xFF14212A.toInt()
        private const val ACCENT = 0xFF0D9488.toInt()
        private const val TEXT = 0xFFE8F1F2.toInt()

        private val ENGLISH = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val ARABIC = listOf("ضصثقفغعهخحجد", "شسيبلاتنمكط", "ئءؤرىةوزظذ")
        private val SYMBOLS = listOf("1234567890", "@#&_-+()/?", "!\"':;,.*%=")

        /** The character rows of a layout, each key its own string. */
        fun rows(layout: Layout, shift: Boolean): List<List<String>> = when (layout) {
            Layout.ENGLISH -> ENGLISH.map { row -> row.map { if (shift) it.uppercaseChar().toString() else it.toString() } }
            Layout.ARABIC -> ARABIC.map { row -> row.map { it.toString() } }
            Layout.SYMBOLS -> SYMBOLS.map { row -> row.map { it.toString() } }
        }
    }
}
