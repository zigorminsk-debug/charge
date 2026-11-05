package by.charge.overlay.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Источник 1: OpenStreetMap через Overpass API.
 * Координаты берутся только из OSM, ничего не выдумывается.
 */
object OverpassSource {
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val SOURCE = "osm"

    fun query(lat: Double, lon: Double, radiusM: Int): String =
        "[out:json][timeout:25];(" +
            "node[\"amenity\"=\"charging_station\"](around:$radiusM,$lat,$lon);" +
            "way[\"amenity\"=\"charging_station\"](around:$radiusM,$lat,$lon);" +
            ");out center tags;"

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
            val connectors = ArrayList<String>()
            val keys = tags.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!key.startsWith("socket:")) continue
                val value = tags.optString(key)
                if (key.endsWith(":output")) {
                    val kw = parseKw(value)
                    if (kw != null && (maxKw == null || kw > maxKw)) maxKw = kw
                } else if (key.count { it == ':' } == 1) {
                    // "socket:type2_combo=2" -> количество разъёмов этого типа
                    val count = value.toIntOrNull() ?: 1
                    val type = key.removePrefix("socket:")
                    connectors.add(if (count > 1) "$type ×$count" else type)
                }
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
                    connectors = connectors,
                    source = SOURCE,
                )
            )
        }
        return out
    }
}

/**
 * Источник 2: Open Charge Map (API v3, без ключа для небольшого объёма запросов).
 * compact=false, чтобы получить AddressInfo, OperatorInfo и Connections.
 */
object OpenChargeMapSource {
    const val ENDPOINT = "https://api.openchargemap.io/v3/poi/"
    private const val SOURCE = "ocm"

    fun url(lat: Double, lon: Double, radiusKm: Double): HttpUrl =
        ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("output", "json")
            .addQueryParameter("latitude", lat.toString())
            .addQueryParameter("longitude", lon.toString())
            .addQueryParameter("distance", radiusKm.toString())
            .addQueryParameter("distanceunit", "KM")
            .addQueryParameter("maxresults", "300")
            .addQueryParameter("compact", "false")
            .addQueryParameter("verbose", "false")
            .build()

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
            val connectors = ArrayList<String>()
            val conns = p.optJSONArray("Connections")
            if (conns != null) {
                for (j in 0 until conns.length()) {
                    val c = conns.optJSONObject(j) ?: continue
                    val kw = c.optDouble("PowerKW", Double.NaN).takeUnless { it.isNaN() }
                    if (kw != null && (maxKw == null || kw > maxKw)) maxKw = kw
                    val type = c.optJSONObject("ConnectionType")?.optString("Title")?.trim()?.ifEmpty { null } ?: "?"
                    val qty = c.optInt("Quantity", 1).coerceAtLeast(1)
                    val kwPart = if (kw != null) " ${kw.toInt()} кВт" else ""
                    val qtyPart = if (qty > 1) " ×$qty" else ""
                    connectors.add("$type$kwPart$qtyPart")
                }
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
                    connectors = connectors,
                    source = SOURCE,
                )
            )
        }
        return out
    }
}
