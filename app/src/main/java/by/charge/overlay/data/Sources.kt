package by.charge.overlay.data

import by.charge.overlay.geo.Box
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Источник 1: OpenStreetMap через Overpass API.
 * Координаты и разъёмы берутся только из OSM, ничего не выдумывается.
 * Статусы в OSM редко заполнены, поэтому чаще всего они UNKNOWN.
 */
object OverpassSource {
    /** Публичные зеркала Overpass. Запросы идут параллельно, побеждает первый успешный ответ. */
    val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://overpass.openstreetmap.ru/api/interpreter",
    )

    private const val SOURCE = "osm"

    /** Человекочитаемые имена типов разъёмов из тегов socket:*. */
    private val SOCKET_NAMES = mapOf(
        "type1" to "Type 1",
        "type1_combo" to "CCS1",
        "type2" to "Type 2",
        "type2_combo" to "CCS2",
        "type2_socket" to "Type 2 розетка",
        "type2_cable" to "Type 2 кабель",
        "chademo" to "CHAdeMO",
        "tesla_supercharger" to "Tesla",
        "tesla_destination" to "Tesla (дест.)",
        "schuko" to "Schuko",
    )

    fun query(box: Box): String {
        val bbox = "(${box.minLat},${box.minLon},${box.maxLat},${box.maxLon})"
        return "[out:json][timeout:25];(" +
            "node[\"amenity\"=\"charging_station\"]$bbox;" +
            "way[\"amenity\"=\"charging_station\"]$bbox;" +
            ");out center tags;"
    }

    fun parse(json: String, networks: Networks): List<Station> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Station>(elements.length())
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            val center = e.optJSONObject("center")
            val lat = e.optDouble("lat", center?.optDouble("lat", Double.NaN) ?: Double.NaN)
            val lon = e.optDouble("lon", center?.optDouble("lon", Double.NaN) ?: Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val tags = e.optJSONObject("tags") ?: JSONObject()
            val operator = tags.optString("operator").trim().ifEmpty { null }
            val brand = networks.match(
                tags.optString("brand"),
                tags.optString("network"),
                operator,
                tags.optString("name"),
            )

            var maxKw: Double? = parseKw(tags.optString("maxpower"))
                ?: parseKw(tags.optString("charging_station:output"))

            // socket:<тип>=<кол-во> и socket:<тип>:output=<кВт>
            val counts = LinkedHashMap<String, Int>()
            val powers = HashMap<String, Double>()
            val keys = tags.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!key.startsWith("socket:")) continue
                val rest = key.removePrefix("socket:")
                val value = tags.optString(key)
                if (rest.endsWith(":output")) {
                    val type = rest.removeSuffix(":output")
                    val kw = parseKw(value) ?: continue
                    powers[type] = kw
                    if (maxKw == null || kw > maxKw) maxKw = kw
                } else if (!rest.contains(':')) {
                    counts[rest] = value.toIntOrNull() ?: 1
                }
            }
            val outlets = (counts.keys + powers.keys).distinct().map { type ->
                Outlet(
                    type = SOCKET_NAMES[type] ?: type,
                    kw = powers[type],
                    count = counts[type] ?: 1,
                    status = ChargerStatus.UNKNOWN,
                )
            }

            val status = when (tags.optString("operational_status")) {
                "operational" -> ChargerStatus.OPERATIONAL
                "broken", "out_of_order" -> ChargerStatus.BROKEN
                else -> if (tags.optString("disused") == "yes") ChargerStatus.DISUSED else ChargerStatus.UNKNOWN
            }

            val label = networks.labelFor(brand, operator)
            out.add(
                Station(
                    id = "osm-${e.optString("type")}-${e.optLong("id")}",
                    lat = lat,
                    lon = lon,
                    title = tags.optString("name").trim().ifEmpty { label },
                    operator = operator,
                    brandId = brand.id,
                    brandName = label,
                    brandColor = brand.color,
                    maxKw = maxKw,
                    outlets = outlets,
                    status = status,
                    source = SOURCE,
                )
            )
        }
        return out
    }
}

/**
 * Источник 2: Open Charge Map (API v3). Нужен API-ключ: без него сервис отвечает 403.
 * Ключ вводится в приложении (Настройки) и хранится только на устройстве.
 * Даёт статусы станций и разъёмов, поэтому это основной источник статусов.
 */
object OpenChargeMapSource {
    const val ENDPOINT = "https://api.openchargemap.io/v3/poi/"
    private const val SOURCE = "ocm"

    fun url(lat: Double, lon: Double, radiusKm: Double, apiKey: String): HttpUrl =
        ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("output", "json")
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("distance", radiusKm.toString())
            .addQueryParameter("distanceunit", "KM")
            .addQueryParameter("maxresults", "500")
            .addQueryParameter("compact", "false")
            .addQueryParameter("verbose", "false")
            .addQueryParameter("key", apiKey)
            .build()

    /** Статус из объекта StatusType (IsOperational или Title). null — данных нет. */
    internal fun statusOf(obj: JSONObject?): ChargerStatus? {
        if (obj == null) return null
        val title = obj.optString("Title")
        if (title.contains("Removed", ignoreCase = true)) return ChargerStatus.DISUSED
        if (!obj.isNull("IsOperational")) {
            return if (obj.optBoolean("IsOperational")) ChargerStatus.OPERATIONAL else ChargerStatus.BROKEN
        }
        return when {
            title.contains("Not Operational", ignoreCase = true) -> ChargerStatus.BROKEN
            title.contains("Operational", ignoreCase = true) -> ChargerStatus.OPERATIONAL
            else -> null
        }
    }

    fun parse(json: String, networks: Networks): List<Station> {
        val arr = JSONArray(json)
        val out = ArrayList<Station>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val ai = p.optJSONObject("AddressInfo") ?: continue
            val lat = ai.optDouble("Latitude", Double.NaN)
            val lon = ai.optDouble("Longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val operator = p.optJSONObject("OperatorInfo")?.optString("Title")?.trim()?.ifEmpty { null }
            val addressTitle = ai.optString("Title").trim()
            val brand = networks.match(operator, addressTitle)
            val label = networks.labelFor(brand, operator)

            var maxKw: Double? = null
            val outlets = ArrayList<Outlet>()
            val conns = p.optJSONArray("Connections")
            if (conns != null) {
                for (j in 0 until conns.length()) {
                    val c = conns.optJSONObject(j) ?: continue
                    val kw = c.optDouble("PowerKW", Double.NaN).takeUnless { it.isNaN() }
                    if (kw != null && (maxKw == null || kw > maxKw)) maxKw = kw
                    val type = c.optJSONObject("ConnectionType")?.optString("Title")?.trim()?.ifEmpty { null } ?: "?"
                    outlets.add(
                        Outlet(
                            type = type,
                            kw = kw,
                            count = c.optInt("Quantity", 1).coerceAtLeast(1),
                            status = statusOf(c.optJSONObject("StatusType")) ?: ChargerStatus.UNKNOWN,
                        )
                    )
                }
            }

            val status = statusOf(p.optJSONObject("StatusType")) ?: when {
                outlets.isNotEmpty() && outlets.all { it.status == ChargerStatus.OPERATIONAL } -> ChargerStatus.OPERATIONAL
                outlets.isNotEmpty() && outlets.all { it.status == ChargerStatus.BROKEN } -> ChargerStatus.BROKEN
                else -> ChargerStatus.UNKNOWN
            }

            out.add(
                Station(
                    id = "ocm-${p.optInt("ID")}",
                    lat = lat,
                    lon = lon,
                    title = addressTitle.ifEmpty { label },
                    operator = operator,
                    brandId = brand.id,
                    brandName = label,
                    brandColor = brand.color,
                    maxKw = maxKw,
                    outlets = outlets,
                    status = status,
                    source = SOURCE,
                )
            )
        }
        return out
    }
}
