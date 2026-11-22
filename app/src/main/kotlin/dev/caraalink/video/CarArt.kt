package dev.caraalink.video

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * What the car screens are drawn with. Symbols are drawn as shapes rather
 * than typed as characters: on a phone, characters such as the media arrows
 * come out as coloured emoji, differently on every make.
 */
object CarArt {

    /** The home screen's backdrop: near black, lit by a teal glow from one corner and an indigo one from the other. */
    class Backdrop : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            canvas.drawColor(0xFF05080B.toInt())
            glow(canvas, w * 0.12f, h * 0.05f, w * 0.55f, 0x5514B8A6)
            glow(canvas, w * 0.95f, h * 1.05f, w * 0.60f, 0x554F46E5)
            glow(canvas, w * 0.60f, h * -0.2f, w * 0.35f, 0x220EA5E9)
        }

        private fun glow(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
            paint.shader = RadialGradient(x, y, radius, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    enum class Symbol { PREVIOUS, PLAY_PAUSE, NEXT, GRID, PHONE, HOME, BACK }

    /** [symbol] in one colour, drawn to fill the middle of whatever it is given. */
    class Glyph(private val symbol: Symbol, color: Int) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            // Everything is laid out on a 24 x 24 grid, like an icon font.
            val size = minOf(bounds.width(), bounds.height()).toFloat()
            val u = size / 24f
            canvas.save()
            canvas.translate(bounds.left + (bounds.width() - size) / 2f, bounds.top + (bounds.height() - size) / 2f)
            canvas.scale(u, u)
            stroke.strokeWidth = 2f
            when (symbol) {
                Symbol.PREVIOUS -> {
                    canvas.drawRoundRect(RectF(5f, 6f, 7.4f, 18f), 1f, 1f, fill)
                    canvas.drawPath(triangle(19f, 6f, 19f, 18f, 9f, 12f), fill)
                }
                Symbol.NEXT -> {
                    canvas.drawRoundRect(RectF(16.6f, 6f, 19f, 18f), 1f, 1f, fill)
                    canvas.drawPath(triangle(5f, 6f, 5f, 18f, 15f, 12f), fill)
                }
                Symbol.PLAY_PAUSE -> {
                    canvas.drawPath(triangle(4f, 6f, 4f, 18f, 12.5f, 12f), fill)
                    canvas.drawRoundRect(RectF(14.5f, 6f, 16.8f, 18f), 1f, 1f, fill)
                    canvas.drawRoundRect(RectF(18.6f, 6f, 20.9f, 18f), 1f, 1f, fill)
                }
                Symbol.GRID -> for (row in 0..2) for (col in 0..2) {
                    val x = 4.5f + col * 6f
                    val y = 4.5f + row * 6f
                    canvas.drawRoundRect(RectF(x, y, x + 3.6f, y + 3.6f), 1f, 1f, fill)
                }
                Symbol.PHONE -> {
                    canvas.drawRoundRect(RectF(7f, 3f, 17f, 21f), 2.4f, 2.4f, stroke)
                    canvas.drawRoundRect(RectF(10.5f, 17.4f, 13.5f, 18.6f), 0.6f, 0.6f, fill)
                }
                Symbol.HOME -> {
                    val roof = Path().apply { moveTo(3.5f, 11.5f); lineTo(12f, 4f); lineTo(20.5f, 11.5f) }
                    canvas.drawPath(roof, stroke)
                    val walls = Path().apply { moveTo(6f, 10.5f); lineTo(6f, 19.5f); lineTo(18f, 19.5f); lineTo(18f, 10.5f) }
                    canvas.drawPath(walls, stroke)
                }
                Symbol.BACK -> {
                    val arrow = Path().apply { moveTo(20f, 12f); lineTo(5f, 12f); moveTo(11f, 5.5f); lineTo(4.5f, 12f); lineTo(11f, 18.5f) }
                    canvas.drawPath(arrow, stroke)
                }
            }
            canvas.restore()
        }

        private fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Path().apply {
            moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Two-colour blends for icons we have to draw ourselves; the same name always gets the same one. */
    private val BLENDS = arrayOf(
        intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt()),
        intArrayOf(0xFFA78BFA.toInt(), 0xFF6D28D9.toInt()),
        intArrayOf(0xFFFB7185.toInt(), 0xFFBE123C.toInt()),
        intArrayOf(0xFFFBBF24.toInt(), 0xFFEA580C.toInt()),
        intArrayOf(0xFF34D399.toInt(), 0xFF047857.toInt()),
        intArrayOf(0xFF22D3EE.toInt(), 0xFF0E7490.toInt()),
    )

    fun blendFor(name: String): IntArray = BLENDS[Math.floorMod(name.hashCode(), BLENDS.size)]
}
