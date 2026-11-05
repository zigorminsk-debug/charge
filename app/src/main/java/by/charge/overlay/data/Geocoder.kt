package by.charge.overlay.data

import by.charge.overlay.net.Http
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray

/** Место из поиска по адресу. */
data class Place(val name: String, val lat: Double, val lon: Double)

/**
 * Поиск адреса через Nominatim (OpenStreetMap), без API-ключа.
 * Ограничен Беларусью. Правила Nominatim: не чаще 1 запроса в секунду и с понятным User-Agent.
 */
object Geocoder {
    const val ENDPOINT = "https://nominatim.openstreetmap.org/search"

    // Запрос к Nominatim: только Беларусь (countrycodes=by), ответ на русском, не больше 5 мест.
    fun url(query: String): HttpUrl =
        ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "5")
            .addQueryParameter("countrycodes", "by")
            .addQueryParameter("accept-language", "ru")
            .build()

    // Разбор ответа. Места без корректных lat/lon пропускаются. Пустое название заменяется координатами.
    fun parse(json: String): List<Place> {
        val arr = JSONArray(json)
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            val name = o.optString("display_name").trim().ifEmpty { "$lat, $lon" }
            out.add(Place(name, lat, lon))
        }
        return out
    }

    // Синхронный запрос: вызывать только из IO-потока (MainActivity.askAddress делает это в withContext).
    fun search(query: String): List<Place> = parse(Http.get(url(query.trim())))
}
