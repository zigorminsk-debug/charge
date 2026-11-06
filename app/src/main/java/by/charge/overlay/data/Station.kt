// Модель данных зарядной станции.
// Единый формат для двух источников: OpenStreetMap (Sources.kt, OverpassSource) и Open Charge Map (OpenChargeMapSource).
// Всё, что выше по слоям (карта, маршрут, полоса), работает только с этими классами.
package by.charge.overlay.data

/** Статус зарядной станции. Определяется источником; если данных нет — UNKNOWN. */
enum class ChargerStatus(val label: String) {
    OPERATIONAL("работает"),
    BROKEN("не работает"),
    UNKNOWN("статус неизвестен"),
    DISUSED("выведена из эксплуатации"),
}

/** Разъём (тип, мощность, количество, статус). Мощность может быть неизвестна. */
data class Outlet(
    val type: String,
    val kw: Double?,
    val count: Int,
    val status: ChargerStatus,
) {
    // Строка для карточки: «CCS2 · 150 кВт · ×2 · работает». Части без данных пропускаются.
    fun describe(): String {
        val parts = mutableListOf(type)
        if (kw != null) parts.add("${kw.toInt()} кВт")
        if (count > 1) parts.add("×$count")
        parts.add(status.label)
        return parts.joinToString(" · ")
    }
}

/** Зарядная станция в едином формате (одинаковый для OSM и OpenChargeMap). */
data class Station(
    val id: String,
    val lat: Double,
    val lon: Double,
    val title: String,
    val operator: String?,
    val brandId: String,
    val brandName: String,
    val brandColor: String,
    val maxKw: Double?,
    val outlets: List<Outlet>,
    val status: ChargerStatus,
    val source: String,
    /** ID точки в Open Charge Map, если станция есть в OCM (для карточки и проверки на сайте). */
    val ocmId: Int? = null,
)

// Первое число в строке (целое или с десятичной точкой / запятой): «50 kW», «120,5 кВт».
private val KW_REGEX = Regex("""(\d+(?:[.,]\d+)?)""")

/** "50 kW" -> 50.0, "22kW" -> 22.0, "120,5 кВт" -> 120.5, пусто -> null */
fun parseKw(raw: String?): Double? {
    if (raw.isNullOrBlank()) return null
    val match = KW_REGEX.find(raw) ?: return null
    return match.value.replace(',', '.').toDoubleOrNull()
}
