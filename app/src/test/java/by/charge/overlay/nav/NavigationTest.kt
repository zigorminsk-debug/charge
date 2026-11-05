// Тесты навигации: разбор OSRM, русские подсказки, форматы расстояния и времени, прогресс по маршруту (манёвр, прибытие, сход с маршрута), URL с точками по порядку.
package by.charge.overlay.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    private val sampleJson = """
        {"code":"Ok","routes":[{"distance":1000.0,"duration":90.0,
          "geometry":{"type":"LineString","coordinates":[[27.5,53.9],[27.51,53.91]]},
          "legs":[{"steps":[
             {"distance":500,"duration":40,"name":"Ул",
              "maneuver":{"type":"depart","location":[27.5,53.9]}},
             {"distance":500,"duration":50,"name":"Пр",
              "maneuver":{"type":"turn","modifier":"right","location":[27.51,53.91]}}
          ]}]}]}
    """.trimIndent()

    @Test
    fun parsesGeometryAndSteps() {
        val route = Routing.parse(sampleJson)!!
        assertEquals(2, route.geometry.size)
        assertEquals(53.9, route.geometry[0].lat, 1e-9)
        assertEquals(27.5, route.geometry[0].lon, 1e-9)
        assertEquals(2, route.steps.size)
        assertEquals("right", route.steps[1].modifier)
        assertEquals(1000.0, route.distanceM, 1e-9)
    }

    @Test
    fun noRouteReturnsNull() {
        assertNull(Routing.parse("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test
    fun instructionTextIsRussianAndIncludesStreet() {
        val turn = Step(LatLon(0.0, 0.0), "turn", "right", "Ул", 0.0, 0.0)
        assertEquals("Поверните направо на Ул", Instructions.text(turn))

        val arrive = Step(LatLon(0.0, 0.0), "arrive", null, "", 0.0, 0.0)
        assertEquals("Вы прибыли к цели", Instructions.text(arrive))

        val straight = Step(LatLon(0.0, 0.0), "turn", "straight", "", 0.0, 0.0)
        assertEquals("Продолжайте прямо", Instructions.text(straight))
    }

    @Test
    fun distanceAndDurationFormatting() {
        assertEquals("250 м", Instructions.distance(250.0))
        assertEquals("1.2 км", Instructions.distance(1234.0))
        assertEquals("2 мин", Instructions.duration(90.0))
        assertEquals("1 ч 2 мин", Instructions.duration(3720.0))
    }

    // Маршрут вдоль экватора: точки через 0.001° долготы (~111 м)
    private val route = Route(
        geometry = listOf(
            LatLon(0.0, 0.000),
            LatLon(0.0, 0.001),
            LatLon(0.0, 0.002),
            LatLon(0.0, 0.003),
        ),
        steps = listOf(
            Step(LatLon(0.0, 0.000), "depart", null, "Ул", 0.0, 0.0),
            Step(LatLon(0.0, 0.002), "turn", "right", "Ул", 0.0, 0.0),
            Step(LatLon(0.0, 0.003), "arrive", null, "", 0.0, 0.0),
        ),
        distanceM = 333.0,
        durationS = 60.0,
    )

    @Test
    fun progressFindsNextManeuverAndRemainingDistance() {
        val snap = RouteProgress(route).update(LatLon(0.0, 0.0006))
        assertTrue(snap.offRouteM < RouteProgress.OFF_ROUTE_M)
        assertEquals(222.0, snap.remainingM, 5.0)
        assertEquals(1, snap.nextStepIndex)
        assertEquals("turn", snap.nextStep?.type)
        assertFalse(snap.arrived)
    }

    @Test
    fun progressDetectsArrival() {
        val snap = RouteProgress(route).update(LatLon(0.0, 0.0029))
        assertTrue(snap.arrived)
    }

    @Test
    fun progressDetectsOffRoute() {
        val snap = RouteProgress(route).update(LatLon(0.001, 0.001))
        assertTrue(snap.offRouteM > RouteProgress.OFF_ROUTE_M)
    }

    @Test
    fun routeUrlContainsAllWaypointsInOrder() {
        val url = Routing.url(
            "https://example.com/route/v1/driving/",
            listOf(LatLon(53.9, 27.5), LatLon(53.95, 27.6), LatLon(54.0, 27.7)),
        )
        assertTrue(url.contains("27.5,53.9;27.6,53.95;27.7,54.0"))
        assertTrue(url.contains("steps=true"))
    }
}
