// Тесты частоты GPS: от батареи — экономно, при внешнем питании — максимально часто.
package by.charge.overlay.location

import org.junit.Assert.assertEquals
import org.junit.Test

class FixRateTest {

    @Test
    fun onBatteryIsThrottled() {
        assertEquals(3_000L, FixRate.minTimeMs(pluggedIn = false))
        assertEquals(10f, FixRate.minDistanceM(pluggedIn = false), 0f)
    }

    @Test
    fun onPowerHasNoThrottle() {
        assertEquals(0L, FixRate.minTimeMs(pluggedIn = true))
        assertEquals(0f, FixRate.minDistanceM(pluggedIn = true), 0f)
    }
}
