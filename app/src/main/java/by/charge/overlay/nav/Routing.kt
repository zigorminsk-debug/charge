package by.charge.overlay.nav

import by.charge.overlay.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

data class LatLon(val lat: Double, val lon: Double)

/** Манёвр маршрута: где он происходит, что делать и на какой улице. */
data class Step(
    val at: LatLon,
    val type: String,
    val modifier: String?,
    val name: String,
    val distanceM: Double,
    val durationS: Double,
)

data class Route(
    val geometry: List<LatLon>,
    val steps: List<Step>,
    val distanceM: Double,
    val durationS: Double,
)

/** Маршрут по OSRM (формат ответа /route/v1, geometries=geojson, steps=true). */
object Routing {
    /** Публичные OSRM-серверы: запросы параллельные, побеждает первый успешный ответ. */
    val ENDPOINTS = listOf(
        "https://router.project-osrm.org/route/v1/driving/",
        "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
    )

    private val ROUTE_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Адрес запроса: точки по порядку (старт, остановки, финиш) через «;». */
    fun url(base: String, points: List<LatLon>): String {
        val coords = points.joinToString(";") { "${it.lon},${it.lat}" }
        return "$base$coords?overview=full&geometries=geojson&steps=true&alternatives=false"
    }

    /** Разбор ответа OSRM. Возвращает null, если маршрута нет или он пустой. */
    fun parse(json: String): Route? {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") return null
        val r = root.optJSONArray("routes")?.optJSONObject(0) ?: return null

        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val geometry = (0 until coords.length()).map { i ->
            val c = coords.getJSONArray(i)
            LatLon(lat = c.getDouble(1), lon = c.getDouble(0))
        }
        if (geometry.size < 2) return null

        val steps = ArrayList<Step>()
        val legs = r.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val list = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until list.length()) {
                val s = list.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                steps.add(
                    Step(
                        at = LatLon(lat = loc.getDouble(1), lon = loc.getDouble(0)),
                        type = m.optString("type"),
                        modifier = m.optString("modifier").ifEmpty { null },
                        name = s.optString("name").trim(),
                        distanceM = s.optDouble("distance", 0.0),
                        durationS = s.optDouble("duration", 0.0),
                    )
                )
            }
        }
        return Route(
            geometry = geometry,
            steps = steps,
            distanceM = r.optDouble("distance", 0.0),
            durationS = r.optDouble("duration", 0.0),
        )
    }

    suspend fun fetch(from: LatLon, to: LatLon): Route = fetch(listOf(from, to))

    /** Запрашивает маршрут через все точки у всех серверов параллельно; первый успешный ответ побеждает. */
    suspend fun fetch(points: List<LatLon>): Route {
        require(points.size >= 2) { "нужны минимум две точки" }
        val winner = CompletableDeferred<Route>()
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val pending = AtomicInteger(ENDPOINTS.size)

        for (base in ENDPOINTS) {
            val host = base.substringAfter("://").substringBefore("/")
            ROUTE_SCOPE.launch {
                try {
                    val body = Http.get(url(base, points).toHttpUrl())
                    val route = parse(body) ?: throw IOException("маршрут не найден")
                    winner.complete(route)
                } catch (e: Exception) {
                    errors.add("$host: ${e.message ?: e.javaClass.simpleName}")
                    if (pending.decrementAndGet() == 0) {
                        winner.completeExceptionally(IOException(errors.sorted().joinToString("; ")))
                    }
                }
            }
        }
        return winner.await()
    }
}
