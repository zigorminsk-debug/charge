package by.charge.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourcesTest {

    private val networks = Networks.parse(File("src/main/assets/networks.json").readText())

    @Test
    fun parseKwHandlesUnitsAndDecimalComma() {
        assertEquals(50.0, parseKw("50 kW")!!, 0.001)
        assertEquals(22.0, parseKw("22kW")!!, 0.001)
        assertEquals(120.5, parseKw("120,5 кВт")!!, 0.001)
        assertNull(parseKw(""))
        assertNull(parseKw(null))
    }

    @Test
    fun overpassParsesNodesAndWaysAndSkipsBrokenElements() {
        val json = """
            {"elements":[
              {"type":"node","id":1,"lat":53.90,"lon":27.56,"tags":{
                 "amenity":"charging_station","operator":"Malanka",
                 "socket:type2_combo":"1","socket:type2_combo:output":"50 kW",
                 "socket:chademo":"2","socket:chademo:output":"60 kW"}},
              {"type":"way","id":2,"center":{"lat":53.95,"lon":27.60},"tags":{
                 "amenity":"charging_station","brand":"BatteryFly","maxpower":"22 kW"}},
              {"type":"node","id":3,"tags":{"amenity":"charging_station"}}
            ]}
        """.trimIndent()

        val list = OverpassSource.parse(json, networks)
        assertEquals(2, list.size)

        val malanka = list.first { it.id == "osm-node-1" }
        assertEquals("malanka", malanka.brandId)
        assertEquals(60.0, malanka.maxKw!!, 0.001)
        assertEquals(2, malanka.connectors.size)
        // socket:chademo=2 -> "chademo ×2"
        assertTrue(malanka.connectors.any { it.startsWith("chademo") })

        val bf = list.first { it.id == "osm-way-2" }
        assertEquals(53.95, bf.lat, 1e-9)
        assertEquals("batteryfly", bf.brandId)
        assertEquals(22.0, bf.maxKw!!, 0.001)
    }

    @Test
    fun openChargeMapParsesConnectionsAndNullPower() {
        val json = """
            [{"ID":123,
              "AddressInfo":{"Title":"Malanka, ТЦ","Latitude":53.91,"Longitude":27.5},
              "OperatorInfo":{"Title":"Malanka"},
              "Connections":[
                 {"PowerKW":120,"Quantity":2,"ConnectionType":{"Title":"CCS (Type 2)"}},
                 {"PowerKW":null,"ConnectionType":{"Title":"Type 2"}}]}]
        """.trimIndent()

        val list = OpenChargeMapSource.parse(json, networks)
        assertEquals(1, list.size)
        val s = list[0]
        assertEquals("ocm-123", s.id)
        assertEquals("malanka", s.brandId)
        assertEquals(120.0, s.maxKw!!, 0.001)
        assertEquals(2, s.connectors.size)
    }
}
