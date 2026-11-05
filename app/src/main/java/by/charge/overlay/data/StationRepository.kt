package by.charge.overlay.data

import by.charge.overlay.geo.Geo
import by.charge.overlay.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Загружает станции из обоих источников и склеивает их.
 * Если один источник недоступен, второй всё равно отдаётся, а ошибка попадает в errors.
 */
class StationRepository(private val networks: Networks) {

    data class LoadResult(val stations: List<Station>, val errors: List<String>)

    suspend fun load(lat: Double, lon: Double, radiusKm: Double): LoadResult = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()

        val osm: List<Station> = try {
            val body = Http.postForm(
                OverpassSource.ENDPOINT,
                mapOf("data" to OverpassSource.query(lat, lon, (radiusKm * 1000).toInt())),
            )
            OverpassSource.parse(body, networks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add("OSM: ${e.message ?: e.javaClass.simpleName}")
            emptyList()
        }

        val ocm: List<Station> = try {
            val body = Http.get(OpenChargeMapSource.url(lat, lon, radiusKm))
            OpenChargeMapSource.parse(body, networks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add("OCM: ${e.message ?: e.javaClass.simpleName}")
            emptyList()
        }

        // Станция из OCM, которая совпадает с OSM в пределах 50 м, считается дублем
        val merged = ArrayList<Station>(osm)
        for (s in ocm) {
            val duplicate = osm.any { Geo.distanceM(it.lat, it.lon, s.lat, s.lon) < 50.0 }
            if (!duplicate) merged.add(s)
        }
        merged.sortBy { Geo.distanceM(lat, lon, it.lat, it.lon) }

        LoadResult(merged, errors)
    }
}
