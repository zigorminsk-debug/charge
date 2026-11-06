// Тесты слияния станций OSM и OCM: радиусы совпадения, один двойник на станцию, перенос статусов разъёмов из OCM.
package by.charge.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationMergeTest {

    private val baseLat = 53.9
    private val baseLon = 27.5

    // Смещение по широте на meters метров (1 градус ≈ 111 км).
    private fun north(meters: Double) = baseLat + meters / 111_000.0

    private fun station(
        id: String,
        lat: Double,
        brand: String,
        source: String,
        status: ChargerStatus = ChargerStatus.UNKNOWN,
        outlets: List<Outlet> = emptyList(),
        ocmId: Int? = null,
    ) = Station(
        id = id,
        lat = lat,
        lon = baseLon,
        title = id,
        operator = null,
        brandId = brand,
        brandName = brand,
        brandColor = "#000000",
        maxKw = null,
        outlets = outlets,
        status = status,
        source = source,
        ocmId = ocmId,
    )

    @Test
    fun closeStationMergesRegardlessOfBrand() {
        val osm = listOf(station("osm-1", baseLat, "malanka", "osm"))
        val ocm = listOf(station("ocm-7", north(30.0), "other", "ocm", ocmId = 7))
        val out = StationMerge.merge(osm, ocm)
        assertEquals(1, out.size)
        assertEquals(7, out[0].ocmId)
        assertEquals("osm-1", out[0].id)
    }

    @Test
    fun mediumDistanceMergesOnlyWithSameBrand() {
        val osm = listOf(station("osm-1", baseLat, "malanka", "osm"))
        val same = StationMerge.merge(osm, listOf(station("ocm-1", north(80.0), "malanka", "ocm", ocmId = 1)))
        assertEquals(1, same.size)
        val other = StationMerge.merge(osm, listOf(station("ocm-2", north(80.0), "belorusneft", "ocm", ocmId = 2)))
        assertEquals(2, other.size)
    }

    @Test
    fun genericBrandDoesNotMergeAtMediumDistance() {
        val osm = listOf(station("osm-1", baseLat, "other", "osm"))
        val out = StationMerge.merge(osm, listOf(station("ocm-3", north(80.0), "other", "ocm", ocmId = 3)))
        assertEquals(2, out.size)
    }

    @Test
    fun farStationIsNotMerged() {
        val osm = listOf(station("osm-1", baseLat, "malanka", "osm"))
        val out = StationMerge.merge(osm, listOf(station("ocm-4", north(300.0), "malanka", "ocm", ocmId = 4)))
        assertEquals(2, out.size)
        assertNull(out[0].ocmId)
    }

    @Test
    fun oneOsmStationTakesOnlyOneOcmStation() {
        // Две зарядки OCM на одной площадке: первая сливается с OSM, вторая остаётся отдельной точкой, а не пропадает.
        val osm = listOf(station("osm-1", baseLat, "malanka", "osm"))
        val ocm = listOf(
            station("ocm-10", north(10.0), "malanka", "ocm", ocmId = 10),
            station("ocm-11", north(20.0), "malanka", "ocm", ocmId = 11),
        )
        val out = StationMerge.merge(osm, ocm)
        assertEquals(2, out.size)
        assertEquals(10, out[0].ocmId)
        assertEquals(11, out[1].ocmId)
    }

    @Test
    fun ocmOutletStatusesReplaceOsmOutlets() {
        val osmOutlets = listOf(Outlet("Type 2", null, 1, ChargerStatus.UNKNOWN))
        val ocmOutlets = listOf(Outlet("Type 2 (Mennekes)", 22.0, 2, ChargerStatus.OPERATIONAL))
        val osm = listOf(station("osm-1", baseLat, "malanka", "osm", outlets = osmOutlets))
        val ocm = listOf(station("ocm-5", north(10.0), "malanka", "ocm", status = ChargerStatus.OPERATIONAL, outlets = ocmOutlets, ocmId = 5))
        val out = StationMerge.merge(osm, ocm).single()
        assertEquals(ChargerStatus.OPERATIONAL, out.status)
        assertEquals(ocmOutlets, out.outlets)
    }

    @Test
    fun osmOutletsKeptWhenOcmHasNoStatus() {
        val osmOutlets = listOf(Outlet("Type 2", null, 1, ChargerStatus.UNKNOWN))
        val ocmOutlets = listOf(Outlet("Type 2 (Mennekes)", 22.0, 1, ChargerStatus.UNKNOWN))
        val osm = listOf(station("osm-1", baseLat, "malanka", "osm", outlets = osmOutlets))
        val ocm = listOf(station("ocm-6", north(10.0), "malanka", "ocm", outlets = ocmOutlets, ocmId = 6))
        val out = StationMerge.merge(osm, ocm).single()
        assertEquals(ChargerStatus.UNKNOWN, out.status)
        assertEquals(osmOutlets, out.outlets)
    }
}
