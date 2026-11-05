package by.charge.overlay.nav

import by.charge.overlay.geo.Geo
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Линия маршрута с накопленной длиной.
 * project() находит ближайшую точку линии к произвольной точке и её положение вдоль маршрута.
 */
class RouteGeometry(val points: List<LatLon>) {
// cum[i] — накопленная длина от старта до точки i (м). По ней находится положение проекции вдоль маршрута.

    data class Projection(
        /** Расстояние вдоль маршрута от старта до проекции точки, м. */
        val alongM: Double,
        /** Расстояние от точки до линии маршрута, м. */
        val offM: Double,
    )

    private val cum = DoubleArray(points.size)

    /** Длина маршрута по линии, м. */
    val totalM: Double

    init {
        require(points.size >= 2) { "маршрут должен содержать минимум две точки" }
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + Geo.distanceM(
                points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon,
            )
        }
        totalM = cum[points.size - 1]
    }

    /** Проекция на ближайший отрезок (плоское приближение в окрестности точки — для расстояний до 100 км достаточно). */
    fun project(p: LatLon): Projection {
        // Плоское приближение: долгота сжимается на cos(широты), поэтому kx и ky переводят градусы в метры.
        // Для расстояний до 100 км ошибка пренебрежимо мала.
        val kx = METERS_PER_DEG_LAT * cos(Math.toRadians(p.lat))
        val ky = METERS_PER_DEG_LAT
        var best = Projection(0.0, Double.MAX_VALUE)
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.lon - p.lon) * kx
            val ay = (a.lat - p.lat) * ky
            val dx = (b.lon - a.lon) * kx
            val dy = (b.lat - a.lat) * ky
            val len2 = dx * dx + dy * dy
            // t — доля отрезка от a до b, где лежит ближайшая к p точка (зажимаем в 0..1, чтобы остаться на отрезке).
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val px = ax + tc * dx
            val py = ay + tc * dy
            val off = sqrt(px * px + py * py)
            // Оставляем отрезок с наименьшим отклонением от точки p.
            if (off < best.offM) {
                best = Projection(cum[i] + tc * (cum[i + 1] - cum[i]), off)
            }
        }
        return best
    }

    private companion object {
        const val METERS_PER_DEG_LAT = 110_574.0
    }
}
