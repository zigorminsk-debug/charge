package by.charge.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NetworksTest {

    // Gradle запускает unit-тесты из каталога модуля app/, поэтому путь относительный
    private val networks = Networks.parse(File("src/main/assets/networks.json").readText())

    @Test
    fun malankaMatchesLatinAndCyrillic() {
        assertEquals("malanka", networks.match("Malanka", null).id)
        assertEquals("malanka", networks.match(null, "Малинка").id)
    }

    @Test
    fun batteryFlyIsCaseInsensitive() {
        assertEquals("batteryfly", networks.match("BATTERYFLY").id)
    }

    @Test
    fun unknownOperatorFallsBackToOther() {
        val brand = networks.match("Random Co")
        assertEquals("other", brand.id)
        assertEquals("Random Co", networks.labelFor(brand, "Random Co"))
    }

    @Test
    fun everyBrandHasHexColor() {
        val json = File("src/main/assets/networks.json").readText()
        val regex = Regex("\"color\": \"(#[0-9A-Fa-f]{6})\"")
        val count = regex.findAll(json).count()
        assertEquals(8, count)
    }
}
