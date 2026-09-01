package com.inkey.proto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Transparent overlay that draws the virtual QWERTY grid over the camera preview,
 * plus fingertip markers and a highlight flash for pressed keys. Also owns the
 * image-space -> view-space coordinate mapping (center-crop, same as PreviewView's
 * default FILL_CENTER).
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Key(val label: String, val output: String, val rect: RectF)

    companion object {
        const val BACKSPACE = "\b"
        const val ENTER = "\n"
        const val CALIBRATE = "<cal>"
        private const val FLASH_MS = 150L
    }

    private var keys = listOf<Key>()
    private var flashedKey: Key? = null
    private var flashUntil = 0L

    /** Bounding box of the key grid in view pixels; the calibration target zone. */
    var keysBounds = RectF()
        private set

    /**
     * Touchscreen fallback: physically tapping a key presses it. Guarantees the
     * ⌖ key stays reachable even when air calibration is completely off.
     */
    var onTouchKey: ((Key, Float, Float) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val handler = onTouchKey
            val key = keyAt(event.x, event.y)
            if (handler != null && key != null) {
                handler(key, event.x, event.y)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    var fingertips: List<PointF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Active glide path, drawn as a trail while the user is pinch-swiping. */
    var trail: List<PointF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val keyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val keyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val keyFlash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 76, 175, 80)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 193, 7)
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trailPath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys(w.toFloat(), h.toFloat())
    }

    private fun layoutKeys(w: Float, h: Float) {
        // Standard Android keyboard sizing: ~56dp per row, anchored to the
        // bottom edge. In the full-screen demo the cap keeps it to the lower
        // half; in the IME strip the view is exactly the keyboard.
        val density = resources.displayMetrics.density
        val rowHeight = minOf(56f * density, h / 4f)
        val top = h - rowHeight * 4f
        keysBounds = RectF(0f, top, w, h)
        val unit = w / 10f
        val gap = unit * 0.06f
        val built = mutableListOf<Key>()

        fun addRow(rowIndex: Int, entries: List<Triple<String, String, Float>>) {
            val totalUnits = entries.sumOf { it.third.toDouble() }.toFloat()
            var x = (w - totalUnits * unit) / 2f
            val y = top + rowIndex * rowHeight
            for ((label, output, widthUnits) in entries) {
                val rect = RectF(x + gap, y + gap, x + widthUnits * unit - gap, y + rowHeight - gap)
                built += Key(label, output, rect)
                x += widthUnits * unit
            }
        }

        addRow(0, "QWERTYUIOP".map { Triple(it.toString(), it.lowercaseChar().toString(), 1f) })
        addRow(1, "ASDFGHJKL".map { Triple(it.toString(), it.lowercaseChar().toString(), 1f) })
        addRow(2, "ZXCVBNM".map { Triple(it.toString(), it.lowercaseChar().toString(), 1f) })
        addRow(3, listOf(
            Triple("⌫", BACKSPACE, 2f),
            Triple("space", " ", 5f),
            Triple("⏎", ENTER, 1.5f),
            Triple("⌖", CALIBRATE, 1.5f),
        ))

        keys = built
        labelPaint.textSize = rowHeight * 0.42f
    }

    /** Nearest key to (x, y): inside a key wins, otherwise the closest one within a key-height. */
    fun keyAt(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestDist = Float.MAX_VALUE
        for (key in keys) {
            val dx = max(0f, max(key.rect.left - x, x - key.rect.right))
            val dy = max(0f, max(key.rect.top - y, y - key.rect.bottom))
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = key
            }
        }
        val maxDist = keys.firstOrNull()?.rect?.height() ?: return null
        return if (bestDist <= maxDist * maxDist) best else null
    }

    /** Centers of the a-z keys, for glide-path decoding. */
    fun letterCenters(): Map<Char, PointF> {
        val out = HashMap<Char, PointF>()
        for (key in keys) {
            val c = key.output.singleOrNull() ?: continue
            if (c in 'a'..'z') out[c] = PointF(key.rect.centerX(), key.rect.centerY())
        }
        return out
    }

    /** Width of a letter key, the scale unit for glide decoding. */
    fun keyWidth(): Float =
        keys.firstOrNull { it.output.singleOrNull() in 'a'..'z' }?.rect?.width() ?: 0f

    /**
     * Distribution over letter keys near (x, y) as log-likelihoods, using an
     * anisotropic Gaussian (row height differs from key width). Feeds the
     * word decoder with "which letters might this tap have meant".
     */
    fun letterCandidatesAt(x: Float, y: Float): Map<Char, Float> {
        val out = HashMap<Char, Float>()
        for (key in keys) {
            val c = key.output.singleOrNull() ?: continue
            if (c !in 'a'..'z') continue
            val sx = key.rect.width() * 0.65f
            val sy = key.rect.height() * 0.65f
            val dx = (x - key.rect.centerX()) / sx
            val dy = (y - key.rect.centerY()) / sy
            val mahal = dx * dx + dy * dy
            if (mahal < 9f) out[c] = -mahal / 2f // within 3 sigma
        }
        return out
    }

    fun flash(key: Key) {
        flashedKey = key
        flashUntil = SystemClock.uptimeMillis() + FLASH_MS
        invalidate()
    }

    /**
     * Maps a normalized landmark coordinate from the analysis image to view pixels,
     * assuming the image is center-cropped to fill the view.
     */
    fun mapToView(nx: Float, ny: Float, imageWidth: Int, imageHeight: Int): PointF {
        val scale = max(width / imageWidth.toFloat(), height / imageHeight.toFloat())
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f
        return PointF(nx * imageWidth * scale + offsetX, ny * imageHeight * scale + offsetY)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val flashed = if (now < flashUntil) flashedKey else null

        for (key in keys) {
            val fill = if (key === flashed) keyFlash else keyFill
            canvas.drawRoundRect(key.rect, 10f, 10f, fill)
            canvas.drawRoundRect(key.rect, 10f, 10f, keyStroke)
            canvas.drawText(
                key.label,
                key.rect.centerX(),
                key.rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                labelPaint,
            )
        }

        if (trail.size >= 2) {
            trailPath.reset()
            trailPath.moveTo(trail[0].x, trail[0].y)
            for (i in 1 until trail.size) trailPath.lineTo(trail[i].x, trail[i].y)
            canvas.drawPath(trailPath, trailPaint)
        }

        for (tip in fingertips) {
            canvas.drawCircle(tip.x, tip.y, 14f, tipPaint)
        }

        if (flashed != null) postInvalidateDelayed(FLASH_MS)
    }
}
