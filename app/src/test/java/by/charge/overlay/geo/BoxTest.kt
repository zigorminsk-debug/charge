// Тесты прямоугольника: радиус вокруг точки и расширение области с включением точек.
package by.charge.overlay.geo

import by.charge.overlay.nav.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxTest {

    @Test
    fun aroundRadiusIsApproximatelyCorrect() {
        val box = Box.around(53.9, 27.56, 15.0)
        // Половина высоты квадрата ~ 15 км
        val halfHeightKm = Geo.distanceM(box.minLat, 27.56, box.maxLat, 27.56) / 2000.0
        assertEquals(15.0, halfHeightKm, 0.2)
        assertTrue(box.minLon < 27.56 && box.maxLon > 27.56)
    }

    @Test
    fun ofAndPaddedContainPoints() {
        val box = Box.of(listOf(LatLon(53.0, 27.0), LatLon(54.0, 28.0))).padded(5.0)
        assertTrue(box.minLat < 53.0 && box.maxLat > 54.0)
        assertTrue(box.minLon < 27.0 && box.maxLon > 28.0)
    }
}
