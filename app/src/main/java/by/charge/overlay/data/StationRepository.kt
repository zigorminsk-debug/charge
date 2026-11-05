package by.charge.overlay.data

import by.charge.overlay.geo.Geo
import by.charge.overlay.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Загружает станции из OpenStreetMap (основной источник) и, если включён, из Open Charge Map.
 * Ошибка одного источника не отменяет результат другого, а причина попадает в errors.
 */
class StationRepository(private val networks: Networks) {

    data class LoadResult(val stations: List<Station>, val errors: List<String>)

    suspend fun load(lat: Double, lon: Double, radiusKm: Double): LoadResult = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()

        val osm: List<Station> = try {
            fetchOverpass(lat, lon, (radiusKm * 1000).toInt())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add("OSM: ${e.message ?: e.javaClass.simpleName}")
            emptyList()
        }

        val ocm: List<Station> = if (!OPEN_CHARGE_MAP_ENABLED) {
            emptyList()
        } else {
            try {
                OpenChargeMapSource.parse(Http.get(OpenChargeMapSource.url(lat, lon, radiusKm)), networks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.add("OCM: ${e.message ?: e.javaClass.simpleName}")
                emptyList()
            }
        }

        // Станция из OCM, совпадающая с OSM в пределах 50 м, считается дублем
        val merged = ArrayList<Station>(osm)
        for (s in ocm) {
            val duplicate = osm.any { Geo.distanceM(it.lat, it.lon, s.lat, s.lon) < 50.0 }
            if (!duplicate) merged.add(s)
        }
        merged.sortBy { Geo.distanceM(lat, lon, it.lat, it.lon) }

        LoadResult(merged, errors)
    }

    /**
     * Опрашивает все зеркала Overpass параллельно и возвращает первый успешный ответ.
     * Если ответили ошибкой все, в исключении перечислены причины по каждому серверу.
     */
    private suspend fun fetchOverpass(lat: Double, lon: Double, radiusM: Int): List<Station> {
        val query = OverpassSource.query(lat, lon, radiusM)
        val winner = CompletableDeferred<List<Station>>()
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val pending = AtomicInteger(OverpassSource.ENDPOINTS.size)

        for (endpoint in OverpassSource.ENDPOINTS) {
            val host = endpoint.substringAfter("://").substringBefore("/")
            // Отдельный scope: запрос, проигравший гонку, доработает сам и не блокирует результат
            OVERPASS_SCOPE.launch {
                try {
                    winner.complete(OverpassSource.parse(Http.postForm(endpoint, mapOf("data" to query)), networks))
                } catch (e: Exception) {
                    errors.add("$host: ${describe(e)}")
                    if (pending.decrementAndGet() == 0) {
                        winner.completeExceptionally(IOException(errors.sorted().joinToString("; ")))
                    }
                }
            }
        }
        return winner.await()
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "не ответил вовремя"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        /** Open Charge Map отвечает 403 без API-ключа, поэтому по умолчанию выключен. */
        const val OPEN_CHARGE_MAP_ENABLED = false

        private val OVERPASS_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
