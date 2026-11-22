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
            setBackgroundColor(CarWidgets.BACKGROUND)
            setPadding(dp(40), dp(28), dp(40), dp(28))
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

        // What can be opened from here, most used first. Tiles for things that aren't set are left out.
        val tiles = buildList {
            add(tile(context.getString(R.string.car_open_aa), CarWidgets.BRAND, onOpenAa))
            add(tile("YouTube", CarWidgets.SURFACE) { onOpenWeb(CarWebPresentation.YOUTUBE) })
            add(tile("Claude", CarWidgets.SURFACE) { onOpenWeb(CarWebPresentation.CLAUDE) })
            CarWebPresentation.normalizeUrl(AppSettings.carWebPage(context))?.let { page ->
                add(tile(context.getString(R.string.car_web), CarWidgets.SURFACE) { onOpenWeb(page) })
            }
            add(tile(context.getString(R.string.car_apps), CarWidgets.SURFACE, onOpenApps))
            add(tile(context.getString(R.string.car_phone_screen), CarWidgets.SURFACE, onOpenMirror))
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
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.7f))
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

    private fun tile(text: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 22f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(24).toFloat() }
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

    private companion object {
        const val TILES_PER_ROW = 3
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
