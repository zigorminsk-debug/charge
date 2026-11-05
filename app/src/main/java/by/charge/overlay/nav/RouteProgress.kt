package by.charge.overlay.nav

import by.charge.overlay.geo.Geo

/**
 * Прогресс по маршруту: где я на линии, сколько осталось, какой манёвр следующий,
 * не сошёл ли я с маршрута и не приехал ли.
 * Позиция ищется у ближайшей точки линии, начиная с прошлой позиции (чтобы не прыгать на параллельные улицы).
 */
class RouteProgress(private val route: Route) {

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
    )

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
        var idx = nearest(me, lastIdx, minOf(g.size - 1, lastIdx + WINDOW))
        if (dist(me, g[idx]) > GLOBAL_SEARCH_M) idx = nearest(me, 0, g.size - 1)
        lastIdx = idx

        val remainingM = cum[g.size - 1] - cum[idx]
        val remainingS = if (route.distanceM > 0) route.durationS * remainingM / route.distanceM else 0.0
        val nextIdx = stepPoint.indexOfFirst { it > idx }
        val next = if (nextIdx >= 0) route.steps[nextIdx] else null

        return Snapshot(
            offRouteM = dist(me, g[idx]),
            remainingM = remainingM,
            remainingS = remainingS,
            nextStep = next,
            nextStepIndex = nextIdx,
            distanceToNextM = if (next != null) dist(me, next.at) else 0.0,
            arrived = dist(me, g[g.size - 1]) < ARRIVE_M,
            traveledM = cum[idx],
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

    companion object {
        const val ARRIVE_M = 30.0
        const val OFF_ROUTE_M = 60.0
        private const val GLOBAL_SEARCH_M = 100.0
        private const val WINDOW = 400
    }
}
