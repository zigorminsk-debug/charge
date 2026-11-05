// Тесты поворота карты: направление движения смотрит вверх, перенос через 0/360, кратчайшая разница углов.
package by.charge.overlay.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingTest {

    @Test
    fun northUpWhenHeadingNorth() {
        assertEquals(0f, Heading.mapRotation(0.0), 0.001f)
    }

    @Test
    fun eastTravelRotatesMapCounterClockwise() {
        // Движение на восток: карта повёрнута на -90° (против часовой), восток смотрит вверх
        assertEquals(270f, Heading.mapRotation(90.0), 0.001f)
    }

    @Test
    fun southTravelIsHalfTurn() {
        assertEquals(180f, Heading.mapRotation(180.0), 0.001f)
    }

    @Test
    fun wrapsNegativeAndLargeValues() {
        assertEquals(90f, Heading.mapRotation(270.0), 0.001f)
        assertEquals(0f, Heading.mapRotation(360.0), 0.001f)
    }

    @Test
    fun angleDeltaTakesShortestWay() {
        assertEquals(20f, Heading.angleDelta(350f, 10f), 0.001f)
        assertEquals(20f, Heading.angleDelta(10f, 350f), 0.001f)
        assertEquals(90f, Heading.angleDelta(0f, 90f), 0.001f)
    }
}
