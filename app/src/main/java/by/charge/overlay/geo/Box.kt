package by.charge.overlay.geo

import by.charge.overlay.nav.LatLon
import kotlin.math.cos

/** Прямоугольная область в градусах (для запросов Overpass по bbox). */
data class Box(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {

    val centerLat: Double get() = (minLat + maxLat) / 2
    val centerLon: Double get() = (minLon + maxLon) / 2

    /** Радиус описанной окружности (половина диагонали), км. Нужен для запросов по радиусу. */
    fun halfDiagonalKm(): Double =
        Geo.distanceM(minLat, minLon, maxLat, maxLon) / 2000.0

    /** Расширяет область на padKm со всех сторон. */
    fun padded(padKm: Double): Box {
        val dLat = padKm / KM_PER_DEG_LAT
        val dLon = padKm / (KM_PER_DEG_LAT * cos(Math.toRadians(centerLat)).coerceAtLeast(0.01))
        return Box(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    companion object {
        private const val KM_PER_DEG_LAT = 111.32

        /** Квадрат со стороной 2*radiusKm вокруг точки. */
        fun around(lat: Double, lon: Double, radiusKm: Double): Box {
            val dLat = radiusKm / KM_PER_DEG_LAT
            val dLon = radiusKm / (KM_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
            return Box(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        }

        fun of(points: List<LatLon>): Box = Box(
            minLat = points.minOf { it.lat },
            minLon = points.minOf { it.lon },
            maxLat = points.maxOf { it.lat },
            maxLon = points.maxOf { it.lon },
        )
    }
}
