package by.charge.overlay.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun oneDegreeOfLatitudeIsAbout111km() {
        val d = Geo.distanceM(53.0, 27.5, 54.0, 27.5)
        assertEquals(111_195.0, d, 300.0)
    }

    @Test
    fun distanceToSelfIsZero() {
        assertEquals(0.0, Geo.distanceM(53.9, 27.56, 53.9, 27.56), 1e-6)
    }

    @Test
    fun bearingNorthIsZero() {
        assertEquals(0.0, Geo.bearingDeg(53.9, 27.5, 54.0, 27.5), 0.01)
    }

    @Test
    fun bearingEastOnEquatorIsNinety() {
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.01)
    }

    @Test
    fun bearingSouthWestIsInRange() {
        val b = Geo.bearingDeg(53.9, 27.5, 53.8, 27.4)
        assertEquals(true, b in 180.0..270.0)
    }
}
