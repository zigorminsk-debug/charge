// Тесты масштаба по скорости: медленно — ближе, быстро — дальше, сглаживание скорости, порог смены масштаба.
package by.charge.overlay.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedZoomTest {

    @Test
    fun stoppedOrSlowIsZoomedIn() {
        val z = SpeedZoom()
        assertEquals(SpeedZoom.ZOOM_SLOW, z.target(0.0), 1e-9)
        z.reset()
        assertEquals(SpeedZoom.ZOOM_SLOW, z.target(SpeedZoom.SLOW_SPEED_MS), 1e-9)
    }

    @Test
    fun highwayIsZoomedOut() {
        val z = SpeedZoom()
        assertEquals(SpeedZoom.ZOOM_FAST, z.target(SpeedZoom.FAST_SPEED_MS), 1e-9)
        z.reset()
        assertEquals(SpeedZoom.ZOOM_FAST, z.target(40.0), 1e-9)
    }

    @Test
    fun zoomDecreasesMonotonicallyWithSpeed() {
        var prev = Double.MAX_VALUE
        for (v in 0..30) {
            val z = SpeedZoom()
            val t = z.target(v.toDouble())
            assertTrue("zoom must not grow as speed grows, v=$v", t <= prev + 1e-9)
            prev = t
        }
    }

    @Test
    fun invalidSpeedIsTreatedAsZero() {
        assertEquals(SpeedZoom.ZOOM_SLOW, SpeedZoom().target(-3.0), 1e-9)
        assertEquals(SpeedZoom.ZOOM_SLOW, SpeedZoom().target(Double.NaN), 1e-9)
    }

    @Test
    fun speedIsSmoothed() {
        // Резкий рывок до 25 м/с не переводит масштаб сразу в минимум: после первого замера сглаживание работает.
        val z = SpeedZoom()
        z.target(0.0)
        val first = z.target(25.0)
        assertTrue("one jump must not reach ZOOM_FAST at once", first > SpeedZoom.ZOOM_FAST + 0.1)
        // Потом при постоянной скорости масштаб постепенно сходится к минимуму.
        var last = first
        repeat(30) { last = z.target(25.0) }
        assertEquals(SpeedZoom.ZOOM_FAST, last, 0.05)
    }

    @Test
    fun smallChangesAreIgnored() {
        assertFalse(SpeedZoom.shouldChange(16.0, 16.1))
        assertTrue(SpeedZoom.shouldChange(16.0, 16.5))
        assertTrue(SpeedZoom.shouldChange(16.5, 16.0))
    }
}
