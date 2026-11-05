package by.charge.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocoderTest {

    @Test
    fun parsesNominatimResults() {
        val json = """
            [{"lat":"53.9006","lon":"27.5590","display_name":"проспект Независимости, Минск"},
             {"lat":"bad","lon":"27.5","display_name":"skipped"},
             {"lat":"53.8","lon":"27.4"}]
        """.trimIndent()
        val list = Geocoder.parse(json)
        assertEquals(2, list.size)
        assertEquals("проспект Независимости, Минск", list[0].name)
        assertEquals(53.9006, list[0].lat, 1e-9)
        assertEquals(27.4, list[1].lon, 1e-9)
        assertEquals("53.8, 27.4", list[1].name)
    }

    @Test
    fun urlRestrictsToBelarusAndRussianLanguage() {
        val url = Geocoder.url("Минск").toString()
        assertTrue(url.contains("countrycodes=by"))
        assertTrue(url.contains("accept-language=ru"))
    }
}
