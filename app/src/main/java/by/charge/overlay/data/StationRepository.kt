package by.charge.overlay.data

import by.charge.overlay.geo.Box
import by.charge.overlay.geo.Geo
import by.charge.overlay.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Загружает станции из OpenStreetMap (всегда) и из Open Charge Map (если введён API-ключ).
 * Ошибка одного источника не отменяет результат другого, а причина попадает в errors.
 * Станции со статусом «выведена из эксплуатации» отбрасываются.
 */
class StationRepository(
    private val networks: Networks,
    private val apiKey: () -> String?,
) {

    data class LoadResult(val stations: List<Station>, val errors: List<String>)

    suspend fun load(box: Box): LoadResult = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()

        val osm: List<Station> = try {
            fetchOverpass(box)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add("OSM: ${e.message ?: e.javaClass.simpleName}")
            emptyList()
        }

        val key = apiKey()?.trim().orEmpty()
        val ocm: List<Station> = if (key.isEmpty()) {
            emptyList()
        } else {
            try {
                val url = OpenChargeMapSource.url(box.centerLat, box.centerLon, box.halfDiagonalKm(), key)
                OpenChargeMapSource.parse(Http.get(url), networks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.add("OCM: ${e.message ?: e.javaClass.simpleName}")
                emptyList()
            }
        }

        // Станция из OCM, совпадающая с OSM в пределах 50 м, считается дублем.
        // Статус берём из OCM, если он там есть: это единственный источник статусов.
        val merged = ArrayList<Station>(osm)
        for (s in ocm) {
            val twin = osm.firstOrNull { Geo.distanceM(it.lat, it.lon, s.lat, s.lon) < 50.0 }
            if (twin == null) {
                merged.add(s)
            } else {
                val index = merged.indexOf(twin)
                merged[index] = twin.copy(
                    outlets = if (twin.outlets.isEmpty()) s.outlets else twin.outlets,
                    status = if (s.status != ChargerStatus.UNKNOWN) s.status else twin.status,
                    maxKw = twin.maxKw ?: s.maxKw,
                )
            }
        }
        val visible = merged.filter { it.status != ChargerStatus.DISUSED }
        LoadResult(
            visible.sortedBy { Geo.distanceM(box.centerLat, box.centerLon, it.lat, it.lon) },
            errors,
        )
    }

    /**
     * Опрашивает все зеркала Overpass параллельно и возвращает первый успешный ответ.
     * Если ответили ошибкой все, в исключении перечислены причины по каждому серверу.
     */
    private suspend fun fetchOverpass(box: Box): List<Station> {
        val query = OverpassSource.query(box)
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
        private val OVERPASS_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
