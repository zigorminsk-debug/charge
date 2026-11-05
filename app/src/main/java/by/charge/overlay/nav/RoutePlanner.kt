package by.charge.overlay.nav

import by.charge.overlay.data.ChargerStatus
import by.charge.overlay.data.Station

/**
 * Выбор зарядок по дороге.
 *
 * Предположения: в начале пути батарея полная; запас хода — расстояние на полной батарее;
 * последние reserveM метров не используются (страховка). Отклонение от маршрута до станции
 * ограничено MAX_DETOUR_M. Станции со статусом «не работает» и «выведена из эксплуатации» не берутся.
 */
object RoutePlanner {

    // Максимальное отклонение станции от линии маршрута (3 км). Дальше станция не считается «по пути».
    const val MAX_DETOUR_M = 3000.0

    // Станция на маршруте: alongM — расстояние вдоль маршрута от старта, offM — отклонение от линии (м).
    data class Stop(val station: Station, val alongM: Double, val offM: Double)

    // Бросается, если на каком-то участке в пределах запаса хода нет станции.
    // MainActivity показывает текст как предупреждение и строит маршрут без остановок.
    class NoStationException(message: String) : Exception(message)

    /**
     * Жадный выбор: от текущей точки едем максимально далеко по станциям,
     * до которых хватает запаса. Пока финиш в пределах запаса, остановки не нужны.
     */
    fun pickStops(geo: RouteGeometry, stations: List<Station>, rangeM: Double, reserveM: Double): List<Stop> {
        // Полезный запас хода: диапазон минус резерв (в MainActivity RESERVE_M = 50 км).
        val usable = rangeM - reserveM
        require(usable > 0) { "запас хода должен быть больше страхового остатка" }
        val total = geo.totalM

        // Кандидаты в остановки: без BROKEN и DISUSED, в коридоре маршрута и строго внутри пути (не старт и не финиш).
        val candidates = stations
            .filter { it.status != ChargerStatus.BROKEN && it.status != ChargerStatus.DISUSED }
            .map { s ->
                val pr = geo.project(LatLon(s.lat, s.lon))
                Stop(s, pr.alongM, pr.offM)
            }
            .filter { it.offM <= MAX_DETOUR_M && it.alongM > 0 && it.alongM < total }
            .sortedBy { it.alongM }

        // Жадный алгоритм: из текущей позиции берём самую дальнюю станцию, до которой хватает запаса; повторяем.
        // Если финиш уже в пределах запаса, остановки не нужны.
        val stops = ArrayList<Stop>()
        var pos = 0.0
        // Пока финиш дальше полного запаса, нужна ещё одна остановка.
        while (total - pos > usable) {
            val best = candidates
                .filter { it.alongM > pos && it.alongM <= pos + usable }
                .maxByOrNull { it.alongM }
                ?: throw NoStationException(
                    "После ${(pos / 1000).toInt()} км нет зарядки в пределах запаса хода ${(usable / 1000).toInt()} км"
                )
            stops.add(best)
            pos = best.alongM
        }
        return stops
    }

    /**
     * Все станции в коридоре маршрута (отклонение до MAX_DETOUR_M), по порядку вдоль пути.
     * В отличие от pickStops, не выбирает остановки: нужен для полосы «что рядом с маршрутом».
     * Станции «выведена из эксплуатации» не показываются; «не работает» показываются.
     */
    fun corridor(geo: RouteGeometry, stations: List<Station>): List<Stop> =
        // Полоса показывает все станции в коридоре, кроме выведенных из эксплуатации. BROKEN остаются видимыми.
        attach(geo, stations.filter { it.status != ChargerStatus.DISUSED })
            .filter { it.offM <= MAX_DETOUR_M && it.alongM > 0 && it.alongM < geo.totalM }

    // Положение станций вдоль уже построенного маршрута. Нужно после перестроения, когда линия изменилась.
    /** Положение станций вдоль уже построенного маршрута (после добавления остановок OSRM). */
    fun attach(geo: RouteGeometry, stations: List<Station>): List<Stop> =
        stations
            .map { s ->
                val pr = geo.project(LatLon(s.lat, s.lon))
                Stop(s, pr.alongM, pr.offM)
            }
            .sortedBy { it.alongM }
}
