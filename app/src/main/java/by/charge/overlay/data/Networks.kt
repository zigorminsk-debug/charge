package by.charge.overlay.data

import android.content.Context
import org.json.JSONObject

data class Brand(
    val id: String,
    val name: String,
    val color: String,
    val aliases: List<String>,
)

/** Справочник брендов из assets/networks.json: приводит сырые названия операторов к бренду. */
class Networks(private val brands: List<Brand>) {

    val fallback = Brand(id = "other", name = "Другая сеть", color = "#9E9E9E", aliases = emptyList())

    fun match(vararg texts: String?): Brand {
        val hay = texts.filterNotNull().joinToString(" | ").lowercase()
        if (hay.isBlank()) return fallback
        return brands.firstOrNull { brand -> brand.aliases.any { hay.contains(it.lowercase()) } }
            ?: fallback
    }

    /** Название для показа: известный бренд, либо сырое имя оператора для неизвестных сетей. */
    fun labelFor(brand: Brand, operator: String?): String =
        if (brand.id == fallback.id && !operator.isNullOrBlank()) operator else brand.name

    companion object {
        fun parse(json: String): Networks {
            val arr = JSONObject(json).getJSONArray("brands")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val aliases = o.getJSONArray("aliases")
                Brand(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    color = o.getString("color"),
                    aliases = (0 until aliases.length()).map { aliases.getString(it) },
                )
            }
            return Networks(list)
        }

        fun load(context: Context): Networks =
            context.assets.open("networks.json").bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
    }
}
