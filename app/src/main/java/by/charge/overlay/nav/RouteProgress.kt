package by.charge.overlay.nav

import by.charge.overlay.geo.Geo
import kotlin.math.cos

/**
 * Прогресс по маршруту: где я на линии, сколько осталось, какой манёвр следующий,
 * не сошёл ли я с маршрута и не приехал ли.
 * Позиция ищется у ближайшей точки линии, начиная с прошлой позиции (чтобы не прыгать на параллельные улицы).
 */
class RouteProgress(private val route: Route) {
// Состояние навигации по одному маршруту. Создаётся заново для каждого нового маршрута (MainActivity.applyRoute).

    data class Snapshot(
        val offRouteM: Double,
        val remainingM: Double,
        val remainingS: Double,
        val nextStep: Step?,
        val nextStepIndex: Int,
        val distanceToNextM: Double,
        val arrived: Boolean,
        /** Пройденное расстояние вдоль маршрута, м. Нужно, чтобы понять, какие остановки уже пройдены. */
        val traveledM: Double,
        /** Точка на линии маршрута ближе всего к машине: по ней рисуется значок машины. */
        val snapped: LatLon,
    )

    // g — точки линии маршрута. stepPoint[i] — индекс точки в g, где начинается манёвр i.
    private val g: List<LatLon> = route.geometry
    private val cum = DoubleArray(g.size)
    private val stepPoint: IntArray
    private var lastIdx = 0

    init {
        require(g.size >= 2) { "маршрут должен содержать минимум две точки" }
        for (i in 1 until g.size) cum[i] = cum[i - 1] + dist(g[i - 1], g[i])
        stepPoint = IntArray(route.steps.size) { nearest(route.steps[it].at, 0, g.size - 1) }
    }

    fun update(me: LatLon): Snapshot {
        // Ищем ближайшую точку линии в окне впереди прошлой позиции, чтобы не перескакивать на параллельные улицы.
        var idx = nearest(me, lastIdx, minOf(g.size - 1, lastIdx + WINDOW))
        // Если дальше 100 м от линии, ищем по всему маршруту (возможно, перестроились или сильно сбились).
        if (dist(me, g[idx]) > GLOBAL_SEARCH_M) idx = nearest(me, 0, g.size - 1)
        lastIdx = idx

        val remainingM = cum[g.size - 1] - cum[idx]
        val snapped = snapOnRoute(me, idx)
        // Оставшееся время считаем пропорционально оставшейся длине (OSRM не даёт время по отрезкам точнее).
        val remainingS = if (route.distanceM > 0) route.durationS * remainingM / route.distanceM else 0.0
        // Следующий манёвр — первый, чья точка лежит впереди текущей позиции. Если таких нет, next = null.
        val nextIdx = stepPoint.indexOfFirst { it > idx }
        val next = if (nextIdx >= 0) route.steps[nextIdx] else null

        return Snapshot(
            offRouteM = dist(me, g[idx]),
            remainingM = remainingM,
            remainingS = remainingS,
            nextStep = next,
            nextStepIndex = nextIdx,
            distanceToNextM = if (next != null) dist(me, next.at) else 0.0,
            // Прибытие: в радиусе ARRIVE_M от финиша.
            arrived = dist(me, g[g.size - 1]) < ARRIVE_M,
            traveledM = cum[idx],
            snapped = snapped,
        )
    }

    private fun nearest(p: LatLon, from: Int, to: Int): Int {
        var best = from
        var bestD = Double.MAX_VALUE
        for (i in from..to) {
            val d = dist(p, g[i])
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun dist(a: LatLon, b: LatLon): Double = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)

    /**
     * Точка на линии маршрута, ближайшая к p. Ищем на двух отрезках, которые соседствуют с вершиной idx,
     * а не только в самой вершине: иначе машина прыгала бы между вершинами на длинных прямых.
     */
    private fun snapOnRoute(p: LatLon, idx: Int): LatLon {
        var best = g[idx]
        var bestD = Double.MAX_VALUE
        for (s in listOf(idx - 1, idx)) {
            if (s < 0 || s + 1 >= g.size) continue
            val q = projectOnSegment(p, g[s], g[s + 1])
            val d = dist(p, q)
            if (d < bestD) {
                bestD = d
                best = q
            }
        }
        return best
    }

    /** Проекция точки p на отрезок ab (плоское приближение, как в RouteGeometry.project). */
    private fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): LatLon {
        val kx = METERS_PER_DEG_LAT * cos(Math.toRadians(p.lat))
        val ky = METERS_PER_DEG_LAT
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        val dx = (b.lon - a.lon) * kx
        val dy = (b.lat - a.lat) * ky
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        return LatLon(a.lat + tc * (b.lat - a.lat), a.lon + tc * (b.lon - a.lon))
    }

    companion object {
        // ARRIVE_M — радиус прибытия (м). OFF_ROUTE_M — отклонение от линии, после которого MainActivity перестраивает маршрут.
        const val ARRIVE_M = 30.0
        const val OFF_ROUTE_M = 60.0
        // GLOBAL_SEARCH_M — порог для полного поиска по маршруту. WINDOW — сколько точек вперёд смотреть при обычном поиске.
        private const val GLOBAL_SEARCH_M = 100.0
        private const val METERS_PER_DEG_LAT = 110_574.0
        private const val WINDOW = 400
    }
}
