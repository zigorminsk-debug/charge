package by.charge.overlay.data

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
    val connectors: List<String>,
    val source: String,
)

private val KW_REGEX = Regex("""(\d+(?:[.,]\d+)?)""")

/** "50 kW" -> 50.0, "22kW" -> 22.0, "120,5 кВт" -> 120.5, пусто -> null */
fun parseKw(raw: String?): Double? {
    if (raw.isNullOrBlank()) return null
    val match = KW_REGEX.find(raw) ?: return null
    return match.value.replace(',', '.').toDoubleOrNull()
}
