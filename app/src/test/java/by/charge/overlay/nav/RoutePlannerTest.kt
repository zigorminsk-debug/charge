package by.charge.overlay.nav

import by.charge.overlay.data.ChargerStatus
import by.charge.overlay.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoutePlannerTest {

    // Прямая вдоль экватора: 1° долготы ≈ 111.3 км, общая длина ≈ 300 км
    private val straight = RouteGeometry(
        listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0), LatLon(0.0, 2.0), LatLon(0.0, 2.7))
    )

    private fun station(id: String, lon: Double, lat: Double = 0.0, status: ChargerStatus = ChargerStatus.OPERATIONAL) =
        Station(
            id = id, lat = lat, lon = lon, title = id, operator = null,
            brandId = "other", brandName = "Другая сеть", brandColor = "#9E9E9E",
            maxKw = 50.0, outlets = emptyList(), status = status, source = "test",
        )

    @Test
    fun projectionGivesAlongAndOffset() {
        val pr = straight.project(LatLon(0.001, 1.0))
        // Точка на 1° долготы: около 111 км вдоль маршрута
        assertEquals(111_200.0, pr.alongM, 500.0)
        assertEquals(111.0, pr.offM, 2.0)
    }

    @Test
    fun noStopsWhenRangeIsEnough() {
        val stops = RoutePlanner.pickStops(straight, listOf(station("a", 1.0)), 400_000.0, 50_000.0)
        assertTrue(stops.isEmpty())
    }

    @Test
    fun greedyPicksFurthestReachableStations() {
        // Запас 150 км - резерв 30 км = 120 км на отрезок. Станции на 111 и 222 км.
        val stations = listOf(station("near", 1.0), station("second", 2.0))
        val stops = RoutePlanner.pickStops(straight, stations, 150_000.0, 30_000.0)
        assertEquals(listOf("near", "second"), stops.map { it.station.id })
        assertTrue(stops[0].alongM < stops[1].alongM)
    }

    @Test
    fun stationFarFromRouteIsIgnored() {
        // Смещение около 11 км от маршрута — выше MAX_DETOUR_M
        val far = station("far", 1.0, lat = 0.1)
        try {
            RoutePlanner.pickStops(straight, listOf(far), 150_000.0, 30_000.0)
            fail("ожидалась ошибка: станция вне коридора")
        } catch (e: RoutePlanner.NoStationException) {
            assertTrue(e.message!!.contains("нет зарядки"))
        }
    }

    @Test
    fun brokenAndDisusedStationsAreNotUsed() {
        val stations = listOf(
            station("broken", 1.0, status = ChargerStatus.BROKEN),
            station("disused", 2.0, status = ChargerStatus.DISUSED),
            station("ok", 1.5),
        )
        // Запас 200 км - резерв 30 км = 170 км: станция «ok» на ~167 км достижима
        val stops = RoutePlanner.pickStops(straight, stations, 200_000.0, 30_000.0)
        assertEquals(listOf("ok"), stops.map { it.station.id })
    }

    @Test
    fun noStationsAtAllThrows() {
        try {
            RoutePlanner.pickStops(straight, emptyList(), 150_000.0, 30_000.0)
            fail("ожидалась ошибка")
        } catch (e: RoutePlanner.NoStationException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun routeProgressReportsTraveledDistance() {
        val route = Route(
            geometry = straight.points,
            steps = listOf(Step(LatLon(0.0, 0.0), "depart", null, "", 0.0, 0.0)),
            distanceM = straight.totalM,
            durationS = 3600.0,
        )
        val snap = RouteProgress(route).update(LatLon(0.0, 1.0))
        assertEquals(111_320.0, snap.traveledM, 500.0)
    }
}
