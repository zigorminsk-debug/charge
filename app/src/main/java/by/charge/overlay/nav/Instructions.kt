package by.charge.overlay.nav

import java.util.Locale
import kotlin.math.roundToInt

/** Текст подсказок по-русски из манёвров OSRM. */
object Instructions {
    // Перевод модификаторов OSRM (left, slight right, uturn и т.д.) на русский язык.
    private val MODIFIER_RU = mapOf(
        "left" to "налево",
        "right" to "направо",
        "sharp left" to "резко налево",
        "sharp right" to "резко направо",
        "slight left" to "плавно налево",
        "slight right" to "плавно направо",
        "straight" to "прямо",
        "uturn" to "развернитесь",
    )

    // Текст подсказки: действие по типу манёвра (type) + направление (modifier) + улица.
    // Неизвестные типы и модификаторы дают общие фразы, чтобы подсказка никогда не была пустой.
    fun text(step: Step): String {
        val mod = step.modifier
        val ru = mod?.let { MODIFIER_RU[it] }
        // Тип манёвра из OSRM: depart, arrive, turn, roundabout, fork, merge, ramp, end of road и т.д.
        val base = when (step.type) {
            "depart" -> "Начните движение"
            "arrive" -> "Вы прибыли к цели"
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" ->
                if (ru != null) "На кольце: $ru" else "На кольце"
            "fork" -> if (ru != null) "На развилке держитесь: $ru" else "На развилке"
            "merge" -> if (ru != null) "Перестройтесь: $ru" else "Продолжайте движение"
            "on ramp", "off ramp" -> if (ru != null) "Съезд: $ru" else "Съезд"
            "end of road" -> if (ru != null) "В конце дороги поверните $ru" else "В конце дороги"
            "turn" -> when {
                mod == null -> "Поверните"
                mod == "straight" -> "Продолжайте прямо"
                mod == "uturn" -> "Развернитесь"
                ru != null -> "Поверните $ru"
                else -> "Поверните"
            }
            else -> if (ru != null && mod != "straight") "Держитесь: $ru" else "Продолжайте движение"
        }
        // Название улицы добавляем, если оно известно (для прибытия не нужно).
        return if (step.name.isNotBlank() && step.type != "arrive") "$base на ${step.name}" else base
    }

    /** 250 м, 1.2 км (округление до 10 м внутри километра). */
    fun distance(m: Double): String =
        if (m < 1000) {
            "${((m / 10).roundToInt() * 10).coerceAtLeast(0)} м"
        } else {
            String.format(Locale.US, "%.1f км", m / 1000.0)
        }

    /** 2 мин, 1 ч 5 мин. */
    fun duration(seconds: Double): String {
        val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч ${minutes % 60} мин"
    }
}
