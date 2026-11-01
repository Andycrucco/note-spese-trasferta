package it.scontrini.trasferta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Foto dello scontrino con i numeri letti evidenziati:
 * rosso = importo scelto, giallo = mancia, bianco = altri importi. Tocca un numero per usarlo.
 * Due dita per ingrandire, un dito per spostare quando è ingrandita.
 */
class AmountPicker(ctx: Context) : View(ctx) {
    class Box(val rect: Rect, val value: Double)

    var bitmap: Bitmap? = null
        set(v) { field = v; zoom = 1f; panX = 0f; panY = 0f; invalidate() }
    var boxes: List<Box> = emptyList()
        set(v) { field = v; invalidate() }
    var amount: Double? = null
        set(v) { field = v; invalidate() }
    var tip: Double? = null
        set(v) { field = v; invalidate() }
    var cash: Double? = null
        set(v) { field = v; invalidate() }
    var onPick: ((Double) -> Unit)? = null
    var onEmptyTap: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val m = Matrix()
    private val inv = Matrix()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f * d; isFakeBoldText = true }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: ScaleGestureDetector): Boolean {
            zoom = (zoom * g.scaleFactor).coerceIn(1f, 5f); invalidate(); return true
        }
    })

    private fun layoutMatrix() {
        val b = bitmap ?: return
        val s = min(width / b.width.toFloat(), height / b.height.toFloat()) * zoom
        val dx = (width - b.width * s) / 2f + panX
        val dy = (height - b.height * s) / 2f + panY
        m.reset(); m.postScale(s, s); m.postTranslate(dx, dy); m.invert(inv)
    }

    private fun near(a: Double?, v: Double) = a != null && abs(a - v) < 0.005

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(24, 24, 24))
        val b = bitmap ?: return
        layoutMatrix()
        c.drawBitmap(b, m, bmpPaint)
        var amountDrawn = false
        for (bx in boxes) {
            val r = RectF(bx.rect); m.mapRect(r); r.inset(-4f * d, -3f * d)
            val (col, tag) = when {
                near(amount, bx.value) && !amountDrawn -> { amountDrawn = true; Color.rgb(225, 10, 30) to "IMPORTO" }
                near(tip, bx.value) -> Color.rgb(255, 176, 32) to "MANCIA"
                near(cash, bx.value) -> Color.rgb(255, 176, 32) to "CONTANTI"
                else -> Color.WHITE to null
            }
            fill.color = Color.argb(if (tag != null) 70 else 28, Color.red(col), Color.green(col), Color.blue(col))
            stroke.color = col; stroke.strokeWidth = (if (tag != null) 3f else 1.5f) * d
            c.drawRoundRect(r, 4f * d, 4f * d, fill)
            c.drawRoundRect(r, 4f * d, 4f * d, stroke)
            if (tag != null) {
                val w = label.measureText(tag) + 8 * d
                val top = max(0f, r.top - 16 * d)
                fill.color = col
                c.drawRoundRect(RectF(r.left, top, r.left + w, top + 15 * d), 3 * d, 3 * d, fill)
                label.color = if (col == Color.rgb(255, 176, 32)) Color.BLACK else Color.WHITE
                c.drawText(tag, r.left + 4 * d, top + 11.5f * d, label)
            }
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; moved = false
                parent?.requestDisallowInterceptTouchEvent(zoom > 1f)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { moved = true; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) + abs(e.y - downY) > 12 * d) moved = true
                if (zoom > 1f && e.pointerCount == 1 && !scaler.isInProgress) { panX += e.x - lastX; panY += e.y - lastY; invalidate() }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved) tap(e.x, e.y)
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (bitmap == null) { onEmptyTap?.invoke(); return }
        layoutMatrix()
        val p = floatArrayOf(x, y); inv.mapPoints(p)
        val slack = 14 * d / max(0.01f, m.mapRadius(1f))
        val hit = boxes.filter { b ->
            RectF(b.rect).apply { inset(-slack, -slack) }.contains(p[0], p[1])
        }.minByOrNull { b -> abs(b.rect.exactCenterX() - p[0]) + abs(b.rect.exactCenterY() - p[1]) }
        if (hit != null) { performClick(); onPick?.invoke(hit.value) } else onEmptyTap?.invoke()
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        private val AMT = Regex("""(?<![\d.,])(\d{1,3}(?:[.,]\d{3})+[.,]\d{2}|\d+[.,]\d{2})(?!\d)""")

        /** Riquadri dei numeri con due decimali letti dall'OCR (parole, o righe se la parola non basta). */
        fun boxesFrom(t: Text): List<Box> {
            val out = mutableListOf<Box>()
            for (block in t.textBlocks) for (line in block.lines) {
                var found = false
                for (el in line.elements) {
                    val r = el.boundingBox ?: continue
                    AMT.find(el.text)?.let { mt -> Fmt.num(mt.value)?.takeIf { it > 0 && it < 1e7 }?.let { out.add(Box(r, Fmt.round2(it))); found = true } }
                }
                if (!found) {
                    val r = line.boundingBox ?: continue
                    AMT.findAll(line.text).toList().lastOrNull()?.let { mt -> Fmt.num(mt.value)?.takeIf { it > 0 && it < 1e7 }?.let { out.add(Box(r, Fmt.round2(it))) } }
                }
            }
            return out
        }
    }
}
