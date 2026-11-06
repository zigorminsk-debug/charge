package by.charge.overlay.data

import by.charge.overlay.geo.Geo

/**
 * Слияние станций OpenStreetMap и Open Charge Map в одну точку на карте.
 *
 * Станция OCM считается той же, что станция OSM, если:
 *  - они в пределах CLOSE_M метров (любая сеть), или
 *  - они в пределах NEAR_M метров и у обеих одна известная сеть (бренд).
 *
 * Каждая станция OSM принимает не больше одной станции OCM (ближайшую). Лишние станции OCM остаются отдельными точками,
 * иначе две зарядки на одной площадке сливались бы в одну и вторая пропадала с карты.
 *
 * От OCM берутся: статус станции, разъёмы со статусами и мощность (если в OSM её нет), ID OCM для карточки.
 * От OSM — название, id и координаты. Статусы разъёмов из OSM не используются: в OSM их обычно нет.
 */
object StationMerge {
    /** Расстояние, в пределах которого станции считаются одной при любой сети, м. */
    const val CLOSE_M = 50.0
    /** Расстояние, в пределах которого станции считаются одной при совпадении известной сети, м. */
    const val NEAR_M = 100.0
    /** Бренд «Другая сеть» не считается совпадением: иначе любые две безымянные станции рядом сливались бы. */
    private const val GENERIC_BRAND = "other"

    fun merge(osm: List<Station>, ocm: List<Station>): List<Station> {
        // Индексы в merged совпадают с индексами osm: станция OSM заменяется на слитую, а не удаляется.
        val merged = ArrayList<Station>(osm)
        val used = HashSet<Int>()
        for (s in ocm) {
            // Ближайший свободный двойник среди станций OSM.
            val index = osm.indices
                .filter { it !in used && isTwin(osm[it], s) }
                .minByOrNull { Geo.distanceM(osm[it].lat, osm[it].lon, s.lat, s.lon) }
            if (index == null) {
                merged.add(s)
            } else {
                used.add(index)
                merged[index] = mergeInto(merged[index], s)
            }
        }
        return merged
    }

    /** Совпадают ли станции: близко при любой сети или недалеко при одной известной сети. */
    fun isTwin(osmStation: Station, ocmStation: Station): Boolean {
        val d = Geo.distanceM(osmStation.lat, osmStation.lon, ocmStation.lat, ocmStation.lon)
        if (d < CLOSE_M) return true
        return d < NEAR_M && osmStation.brandId == ocmStation.brandId && osmStation.brandId != GENERIC_BRAND
    }

    private fun mergeInto(osmStation: Station, ocmStation: Station): Station {
        // Разъёмы OCM берём, если в них есть хоть один известный статус: у OSM статусов нет, а OCM их даёт.
        val ocmHasStatus = ocmStation.outlets.any { it.status != ChargerStatus.UNKNOWN }
        val outlets = when {
            ocmStation.outlets.isEmpty() -> osmStation.outlets
            ocmHasStatus -> ocmStation.outlets
            osmStation.outlets.isEmpty() -> ocmStation.outlets
            else -> osmStation.outlets
        }
        return osmStation.copy(
            outlets = outlets,
            status = if (ocmStation.status != ChargerStatus.UNKNOWN) ocmStation.status else osmStation.status,
            maxKw = osmStation.maxKw ?: ocmStation.maxKw,
            ocmId = ocmStation.ocmId,
        )
    }
}
