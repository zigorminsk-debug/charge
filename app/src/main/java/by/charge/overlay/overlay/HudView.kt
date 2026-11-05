package by.charge.overlay.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.location.Location
import android.view.View
import by.charge.overlay.data.Station
import by.charge.overlay.geo.Geo
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Радар, нарисованный вручную на Canvas (без карт и API-ключей).
 * Кольца 2.5 / 5 / 10 км; станции дальше 10 км показываются стрелками на краю.
 * Если есть курс (скорость > 1.5 м/с), радар повёрнут по курсу, иначе север сверху.
 */
class HudView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private var location: Location? = null
    private var stations: List<Station> = emptyList()
    private var nearestId: String? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(150, 8, 12, 20)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(120, 80, 220, 140)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 200, 235, 215)
        textSize = dp(9f)
    }
    private val centerLabelPaint = Paint(labelPaint).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.WHITE
    }
    private val mePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(64, 160, 255)
    }
    private val path = Path()

    fun update(loc: Location?, list: List<Station>, nearest: String?) {
        location = loc
        stations = list
        nearestId = nearest
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - dp(3f)
        canvas.drawCircle(cx, cy, radius, bgPaint)

        val loc = location
        if (loc == null) {
            canvas.drawText("GPS…", cx, cy, centerLabelPaint)
            return
        }

        val moving = loc.hasBearing() && loc.speed > 1.5f
        val rotation = if (moving) loc.bearing.toDouble() else 0.0

        for ((i, frac) in RING_FRACTIONS.withIndex()) {
            val rr = radius * frac
            canvas.drawCircle(cx, cy, rr, ringPaint)
            canvas.drawText(RING_LABELS[i], cx + dp(3f), cy + rr - dp(2f), labelPaint)
        }

        val north = polar(cx, cy, radius - dp(9f), -rotation)
        canvas.drawText("N", north.x, north.y + dp(4f), centerLabelPaint)

        val maxM = MAX_KM * 1000.0
        for (s in stations) {
            val dist = Geo.distanceM(loc.latitude, loc.longitude, s.lat, s.lon)
            val angle = Geo.bearingDeg(loc.latitude, loc.longitude, s.lat, s.lon) - rotation
            fillPaint.color = colorOf(s.brandColor)
            if (dist <= maxM) {
                val p = polar(cx, cy, (dist / maxM).toFloat() * radius, angle)
                val r = dp(if (s.id == nearestId) 7f else 5f)
                canvas.drawCircle(p.x, p.y, r, fillPaint)
                canvas.drawCircle(p.x, p.y, r, outlinePaint)
            } else {
                // Станция за пределом радара: стрелка на краю в её направлении
                val tip = polar(cx, cy, radius - dp(1f), angle)
                val b1 = polar(cx, cy, radius - dp(12f), angle - 7.0)
                val b2 = polar(cx, cy, radius - dp(12f), angle + 7.0)
                path.reset()
                path.moveTo(tip.x, tip.y)
                path.lineTo(b1.x, b1.y)
                path.lineTo(b2.x, b2.y)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
        }

        // Моя позиция — в центре
        canvas.drawCircle(cx, cy, dp(4f), mePaint)
        canvas.drawCircle(cx, cy, dp(4f), outlinePaint)
    }

    /** Точка на экране: angleDeg отсчитывается от верха по часовой стрелке. */
    private fun polar(cx: Float, cy: Float, r: Float, angleDeg: Double): PointF {
        val a = Math.toRadians(angleDeg)
        return PointF(cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat())
    }

    private fun colorOf(hex: String): Int =
        try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            Color.GRAY
        }

    companion object {
        private const val MAX_KM = 10.0
        private val RING_FRACTIONS = floatArrayOf(0.25f, 0.5f, 1f)
        private val RING_LABELS = arrayOf("2.5", "5", "10 км")
    }
}
