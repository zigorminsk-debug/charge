package by.charge.overlay.nav

import kotlin.math.abs

/** Расчёт поворота карты по направлению движения. */
object Heading {

    /**
     * Угол поворота карты, при котором направление движения (курс, градусы от севера по часовой)
     * смотрит вверх экрана. Карта поворачивается на -курс.
     */
    fun mapRotation(bearingDeg: Double): Float = (((360.0 - bearingDeg) % 360.0 + 360.0) % 360.0).toFloat()

    /** Наименьшая разница между двумя углами, 0..180. */
    fun angleDelta(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
