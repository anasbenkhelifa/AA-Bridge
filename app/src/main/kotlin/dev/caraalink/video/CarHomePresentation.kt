package dev.caraalink.video

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import dev.caraalink.R
import dev.caraalink.link.CarMotion
import dev.caraalink.settings.AppSettings

/**
 * The car's home screen, shown whenever Android Auto isn't. On the left, a
 * glass panel with the time, the date, the speed and the weather, and the
 * music buttons under it. On the right, everything that can be opened, as a
 * grid of icons the way a phone or CarPlay lays them out: Android Auto, web
 * apps such as YouTube and Claude, the phone's apps, and the phone's screen.
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
            val temperature = Weather.line(context)
            weather.text = temperature
            (weather.parent as View).visibility = if (temperature.isEmpty()) View.GONE else View.VISIBLE
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = CarArt.Backdrop()
            setPadding(dp(28), dp(26), dp(28), dp(26))
            // The clock stays on the left and the apps on the right in every language.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(panel(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(grid(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.15f).apply { marginStart = dp(22) })
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

    // --- the left panel ---

    private fun panel() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(28)
        setPadding(dp(30), dp(24), dp(30), dp(24))

        addView(TextClock(context).apply {
            format12Hour = "EEE d MMM"
            format24Hour = "EEE d MMM"
            textSize = 16f
            isAllCaps = true
            letterSpacing = 0.14f
            setTextColor(CarWidgets.ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 104f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            letterSpacing = -0.02f
        }, wrap().apply { topMargin = dp(2) })

        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        speed = chipValue()
        chips.addView(chip(speed, context.getString(R.string.car_kmh)))
        weather = chipValue()
        chips.addView(chip(weather, null), wrap().apply { marginStart = dp(10) })
        addView(chips, wrap().apply { topMargin = dp(10) })

        addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(media())
    }

    private fun chipValue() = TextView(context).apply {
        textSize = 20f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** A rounded pill with a value and, after it, a quieter unit. */
    private fun chip(value: TextView, unit: String?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(0x1FFFFFFF); cornerRadius = dp(18).toFloat() }
        setPadding(dp(16), dp(7), dp(16), dp(7))
        addView(value)
        if (unit != null) addView(TextView(context).apply {
            text = unit
            textSize = 15f
            setTextColor(CarWidgets.MUTED)
        }, wrap().apply { marginStart = dp(6) })
    }

    /** Previous, play or pause, next: sent as media keys, so they reach whatever is playing on the phone. */
    private fun media() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val buttons = listOf(
            Triple(CarArt.Symbol.PREVIOUS, KeyEvent.KEYCODE_MEDIA_PREVIOUS, false),
            Triple(CarArt.Symbol.PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true),
            Triple(CarArt.Symbol.NEXT, KeyEvent.KEYCODE_MEDIA_NEXT, false),
        )
        for ((symbol, key, primary) in buttons) {
            val size = dp(if (primary) 64 else 54)
            addView(ImageView(context).apply {
                setImageDrawable(CarArt.Glyph(symbol, if (primary) 0xFF04201D.toInt() else Color.WHITE))
                val inset = size / 4
                setPadding(inset, inset, inset, inset)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (primary) CarWidgets.ACCENT else 0x24FFFFFF)
                }
                isClickable = true
                setOnClickListener { mediaKey(key) }
            }, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(14) })
        }
    }

    private fun mediaKey(code: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // --- the grid of things to open ---

    private class Entry(val name: String, val icon: Drawable?, val symbol: CarArt.Symbol?, val open: () -> Unit)

    private fun entries(): List<Entry> {
        val pm = context.packageManager
        fun appIcon(pkg: String?) = pkg?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
        val pages = listOf(
            CarWebPresentation.Companion.Page("YouTube", CarWebPresentation.YOUTUBE),
            CarWebPresentation.Companion.Page("Claude", CarWebPresentation.CLAUDE),
        ) + CarWebPresentation.parsePages(AppSettings.carWebPages(context))
        return buildList {
            add(Entry(context.getString(R.string.car_android_auto), appIcon(GEARHEAD), null, onOpenAa))
            for (page in pages) add(Entry(page.name, appIcon(CarWebPresentation.appFor(page.url)), null) { onOpenWeb(page.url) })
            add(Entry(context.getString(R.string.car_apps), null, CarArt.Symbol.GRID, onOpenApps))
            add(Entry(context.getString(R.string.car_phone_screen), null, CarArt.Symbol.PHONE, onOpenMirror))
        }
    }

    private fun grid(): View {
        val entries = entries()
        val columns = columnsFor(entries.size)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            for (rowEntries in entries.chunked(columns)) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                for (entry in rowEntries) row.addView(cell(entry), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // A short last row keeps its icons in the same columns as the row above.
                repeat(columns - rowEntries.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
    }

    /** One icon with its name under it; the whole cell is the button. */
    private fun cell(entry: Entry) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = dp(ICON_DP)
        val corner = size * 0.24f
        val icon: View = if (entry.icon != null) {
            ImageView(context).apply {
                setImageDrawable(entry.icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        } else {
            FrameLayout(context).apply {
                // Drawn rounded here as well as clipped below: the clip needs hardware drawing, this doesn't.
                // Our own two tiles are a quiet slate, so the colour stays with the apps.
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    if (entry.symbol != null) intArrayOf(0xFF475569.toInt(), 0xFF1E293B.toInt()) else CarArt.blendFor(entry.name),
                ).apply { cornerRadius = corner }
                if (entry.symbol != null) {
                    addView(ImageView(context).apply { setImageDrawable(CarArt.Glyph(entry.symbol, Color.WHITE)) },
                        FrameLayout.LayoutParams(size / 2, size / 2, Gravity.CENTER))
                } else {
                    addView(TextView(context).apply {
                        text = entry.name.take(1).uppercase()
                        textSize = 40f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER
                    })
                }
            }
        }
        // Every icon gets the same rounded-square shape, whatever the app shipped.
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, corner)
        }
        icon.clipToOutline = true
        icon.elevation = dp(6).toFloat()
        addView(icon, LinearLayout.LayoutParams(size, size))
        addView(TextView(context).apply {
            text = entry.name
            textSize = 17f
            setTextColor(0xF2FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        isClickable = true
        setOnClickListener { entry.open() }
    }

    private fun glass(radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x1FFFFFFF, 0x0DFFFFFF),
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), 0x2EFFFFFF)
    }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    companion object {
        private const val ICON_DP = 108
        private const val GEARHEAD = "com.google.android.projection.gearhead"

        /** Four across, as on a phone, until there are more than two rows' worth; then five. */
        fun columnsFor(count: Int): Int = if (count > 8) 5 else 4
    }
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
