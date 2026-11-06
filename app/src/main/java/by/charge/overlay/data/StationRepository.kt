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

    // Результат загрузки: станции и тексты ошибок по источникам.
    // Ошибки показываются в статусной строке, но не отменяют станции, которые удалось получить.
    // osmCount/ocmCount — сколько станций пришло из каждого источника, matchedCount — сколько станций OCM слились с OSM.
    data class LoadResult(
        val stations: List<Station>,
        val errors: List<String>,
        val osmCount: Int = 0,
        val ocmCount: Int = 0,
        val matchedCount: Int = 0,
    )

    suspend fun load(box: Box): LoadResult = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()

        // Сначала OSM. Ошибка OSM записывается в errors, загрузка OCM продолжается.
        val osm: List<Station> = try {
            fetchOverpass(box)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add("OSM: ${e.message ?: e.javaClass.simpleName}")
            emptyList()
        }

        // Ключ читается при каждой загрузке (лямбда), поэтому после входа в OCM он применяется сразу.
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

        // Слияние станций OSM и OCM (правила в StationMerge). Статус и разъёмы берутся из OCM, если они там есть.
        val merged = StationMerge.merge(osm, ocm)
        // Станции «выведена из эксплуатации» не показываем ни на карте, ни в маршрутах.
        val visible = merged.filter { it.status != ChargerStatus.DISUSED }
        LoadResult(
            visible.sortedBy { Geo.distanceM(box.centerLat, box.centerLon, it.lat, it.lon) },
            errors,
            osmCount = osm.size,
            ocmCount = ocm.size,
            // Каждая станция OCM либо слилась с OSM, либо добавила одну строку в список.
            matchedCount = ocm.size - (merged.size - osm.size),
        )
    }

    /**
     * Опрашивает все зеркала Overpass параллельно и возвращает первый успешный ответ.
     * Если ответили ошибкой все, в исключении перечислены причины по каждому серверу.
     */
    private suspend fun fetchOverpass(box: Box): List<Station> {
    // Каждое зеркало Overpass запускается в своей корутине (OVERPASS_SCOPE).
    // Первый успешный ответ завершает winner; проигравшие запросы дорабатывают сами и не блокируют результат.
    // Ошибку winner получает только тогда, когда ответили с ошибкой все зеркала.
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

    // Короткое описание ошибки для статусной строки: таймаут — отдельная фраза.
    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "не ответил вовремя"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        // Отдельный scope не отменяется вместе с вызовом load: запросы, проигравшие гонку, спокойно завершаются.
        private val OVERPASS_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
