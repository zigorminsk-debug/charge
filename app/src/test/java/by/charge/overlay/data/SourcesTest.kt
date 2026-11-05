// Тесты разбора источников: Overpass (узлы и ways, пропуск элементов без координат), OCM (разъёмы, мощность, статусы), разбор kW, запрос bbox, описание разъёма.
package by.charge.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(2, malanka.outlets.size)
        // socket:chademo=2 и socket:chademo:output=60 kW -> CHAdeMO ×2, 60 кВт
        val chademo = malanka.outlets.first { it.type == "CHAdeMO" }
        assertEquals(2, chademo.count)
        assertEquals(60.0, chademo.kw!!, 0.001)
        assertEquals(ChargerStatus.UNKNOWN, chademo.status)
        assertEquals("CCS2", malanka.outlets.first { it.type == "CCS2" }.type)

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
        assertEquals(2, s.outlets.size)
        val ccs = s.outlets.first { it.type == "CCS (Type 2)" }
        assertEquals(2, ccs.count)
        assertEquals(120.0, ccs.kw!!, 0.001)
        assertEquals(ChargerStatus.UNKNOWN, ccs.status)
    }

    @Test
    fun openChargeMapStatusesComeFromStatusType() {
        val json = """
            [{"ID":1,"AddressInfo":{"Title":"A","Latitude":53.9,"Longitude":27.5},
              "StatusType":{"IsOperational":false,"Title":"Not Operational"},
              "Connections":[{"Quantity":1,"ConnectionType":{"Title":"CCS"},
                "StatusType":{"IsOperational":false,"Title":"Not Operational"}}]},
             {"ID":2,"AddressInfo":{"Title":"B","Latitude":53.9,"Longitude":27.5},
              "StatusType":{"IsOperational":true,"Title":"Operational"},
              "Connections":[{"Quantity":1,"ConnectionType":{"Title":"CHAdeMO"},
                "StatusType":{"IsOperational":true,"Title":"Operational"}},
               {"Quantity":1,"ConnectionType":{"Title":"Type 2"},
                "StatusType":{"Title":"Planned For Future Date"}}]},
             {"ID":3,"AddressInfo":{"Title":"C","Latitude":53.9,"Longitude":27.5},
              "StatusType":{"Title":"Removed"}, "Connections":[]}]
        """.trimIndent()
        val list = OpenChargeMapSource.parse(json, networks)
        assertEquals(3, list.size)
        val broken = list.first { it.id == "ocm-1" }
        assertEquals(ChargerStatus.BROKEN, broken.status)
        assertEquals(ChargerStatus.BROKEN, broken.outlets[0].status)
        val ok = list.first { it.id == "ocm-2" }
        assertEquals(ChargerStatus.OPERATIONAL, ok.status)
        assertEquals(ChargerStatus.UNKNOWN, ok.outlets[1].status)
        assertEquals(ChargerStatus.DISUSED, list.first { it.id == "ocm-3" }.status)
    }

    @Test
    fun overpassQueryUsesBoundingBox() {
        val q = OverpassSource.query(by.charge.overlay.geo.Box(53.0, 27.0, 54.0, 28.0))
        assertTrue(q.contains("(53.0,27.0,54.0,28.0)"))
        assertTrue(q.contains("charging_station"))
    }

    @Test
    fun outletDescribeIncludesPowerCountAndStatus() {
        val o = Outlet("CCS2", 50.0, 2, ChargerStatus.OPERATIONAL)
        assertEquals("CCS2 · 50 кВт · ×2 · работает", o.describe())
        assertEquals("Type 2 · статус неизвестен", Outlet("Type 2", null, 1, ChargerStatus.UNKNOWN).describe())
    }
}
