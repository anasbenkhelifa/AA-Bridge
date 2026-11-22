package dev.caraalink.video

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import dev.caraalink.R
import dev.caraalink.link.CarMotion
import dev.caraalink.settings.AppSettings

/**
 * The car's home screen, shown whenever Android Auto isn't: the time, the
 * speed, the weather if asked for, music buttons, and the things to open
 * from here -- Android Auto, web apps such as YouTube and Claude, the phone's
 * apps, and the phone's own screen.
 */
class CarHomePresentation(
    context: Context,
    display: Display,
    private val onOpenAa: () -> Unit,
    private val onOpenApps: () -> Unit,
    private val onOpenWeb: (url: String) -> Unit,
    private val onOpenMirror: () -> Unit,
) : Presentation(context, display) {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var speed: TextView
    private lateinit var weather: TextView

    private val tick = object : Runnable {
        override fun run() {
            speed.text = CarWidgets.speedText(CarMotion.speedKmh())
            weather.text = Weather.line(context)
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0E1A20.toInt(), 0xFF070B0E.toInt(), 0xFF0A1512.toInt()))
            setPadding(dp(40), dp(28), dp(32), dp(28))
        }

        val left = column(Gravity.CENTER_VERTICAL or Gravity.START)
        left.addView(clock("h:mm", "HH:mm", 84f, CarWidgets.TEXT, bold = true))
        left.addView(clock("EEEE, d MMMM", "EEEE, d MMMM", 22f, CarWidgets.MUTED))
        val speedRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        speed = label("", 44f, CarWidgets.TEXT, bold = true).also { speedRow.addView(it, gap(0)) }
        speedRow.addView(label(" " + context.getString(R.string.car_kmh), 20f, CarWidgets.MUTED), gap(0))
        weather = label("", 22f, CarWidgets.ACCENT).also { speedRow.addView(it, gap(0).apply { marginStart = dp(24) }) }
        left.addView(speedRow, gap(14))
        left.addView(mediaRow().apply { gravity = Gravity.START }, gap(18))

        // What can be opened from here, most used first.
        val pm = context.packageManager
        fun appIcon(pkg: String?) = pkg?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
        val tiles = buildList {
            add(tile(context.getString(R.string.car_android_auto), appIcon(GEARHEAD), accent = true, onClick = onOpenAa))
            val pages = listOf(CarWebPresentation.Companion.Page("YouTube", CarWebPresentation.YOUTUBE), CarWebPresentation.Companion.Page("Claude", CarWebPresentation.CLAUDE)) +
                CarWebPresentation.parsePages(AppSettings.carWebPages(context))
            for (page in pages) add(tile(page.name, appIcon(CarWebPresentation.appFor(page.url))) { onOpenWeb(page.url) })
            add(tile(context.getString(R.string.car_apps), null, symbol = "▦", onClick = onOpenApps))
            add(tile(context.getString(R.string.car_phone_screen), null, symbol = "📱", onClick = onOpenMirror))
        }
        val right = column(Gravity.CENTER)
        for (rowTiles in tiles.chunked(TILES_PER_ROW)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (t in rowTiles) row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(8); marginEnd = dp(8)
            })
            // A short last row keeps its tiles the same width as the others.
            repeat(TILES_PER_ROW - rowTiles.size) { row.addView(android.view.View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8); marginEnd = dp(8) }) }
            right.addView(row, tileParams())
        }

        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.1f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (AppSettings.carWeather(context)) Weather.refresh(context)
        dev.caraalink.shell.ShellBridge.connect(context)
        tick.run()
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        super.onStop()
    }

    /** Previous, play or pause, next: sent as media keys, so they reach whatever is playing on the phone. */
    private fun mediaRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        for ((symbol, key) in listOf(
            "⏮" to KeyEvent.KEYCODE_MEDIA_PREVIOUS, "⏯" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "⏭" to KeyEvent.KEYCODE_MEDIA_NEXT,
        )) {
            addView(TextView(context).apply {
                text = symbol
                textSize = 30f
                setTextColor(CarWidgets.TEXT)
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(10), dp(22), dp(10))
                background = GradientDrawable().apply { setColor(CarWidgets.SURFACE); cornerRadius = dp(16).toFloat() }
                isClickable = true
                setOnClickListener { mediaKey(key) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6); marginEnd = dp(6)
            })
        }
    }

    private fun mediaKey(code: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** A card with the app's own icon where the phone has it, else a letter or symbol in a coloured disc. */
    private fun tile(
        name: String,
        icon: android.graphics.drawable.Drawable?,
        accent: Boolean = false,
        symbol: String? = null,
        onClick: () -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (accent) intArrayOf(0xFF12A594.toInt(), 0xFF0B6F66.toInt()) else intArrayOf(0xFF1B2A32.toInt(), 0xFF121C22.toInt()),
        ).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), if (accent) 0x5599F6E4 else 0x33FFFFFF)
        }
        if (icon != null) {
            addView(android.widget.ImageView(context).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        } else {
            addView(TextView(context).apply {
                text = symbol ?: name.take(1).uppercase()
                textSize = 30f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(discColor(name)) }
            }, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        }
        addView(TextView(context).apply {
            text = name
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), dp(10), dp(6), 0)
        })
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun tileParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
        topMargin = dp(8); bottomMargin = dp(8)
    }

    private fun column(g: Int) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = g }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun clock(f12: String, f24: String, size: Float, color: Int, bold: Boolean = false) = TextClock(context).apply {
        format12Hour = f12
        format24Hour = f24
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun gap(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    companion object {
        const val TILES_PER_ROW = 4
        private const val ICON_DP = 64
        private const val GEARHEAD = "com.google.android.projection.gearhead"
        private val DISCS = intArrayOf(0xFF2563EB.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), 0xFFEA580C.toInt(), 0xFF059669.toInt(), 0xFF0891B2.toInt())

        /** The same name always gets the same colour. */
        fun discColor(name: String): Int = DISCS[Math.floorMod(name.hashCode(), DISCS.size)]
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
}

/** Colours and small pieces the car screens share. */
object CarWidgets {
    const val BACKGROUND = 0xFF0A0E12.toInt()
    const val SURFACE = 0xFF1A2830.toInt()
    const val ACCENT = 0xFF5EEAD4.toInt()
    const val BRAND = 0xFF0D9488.toInt()
    const val MUTED = 0xFF8AA3A8.toInt()
    const val TEXT = 0xFFE8F1F2.toInt()

    /** The speed as whole km/h, or a dash while GPS has nothing fresh to say. */
    fun speedText(kmh: Int?): String = kmh?.toString() ?: "–"
}
