package by.charge.overlay.location

/**
 * Частота запроса GPS. От батареи — экономим: не чаще раза в 3 с и не чаще чем на 10 м.
 * От внешнего питания — максимально часто: без ограничения по времени и расстоянию,
 * тогда карта и поворот в навигации обновляются с той частотой, которую отдаёт GPS (обычно около 1 Гц).
 */
object FixRate {
    /** Минимальный интервал между фиксами от батареи, мс. */
    const val BATTERY_MIN_TIME_MS = 3_000L
    /** Минимальное расстояние между фиксами от батареи, м. */
    const val BATTERY_MIN_DISTANCE_M = 10f
    /** Интервал при внешнем питании: 0 — отдавать каждый фикс, который даёт провайдер. */
    const val POWERED_MIN_TIME_MS = 0L
    /** Расстояние при внешнем питании: 0 — без порога по метрам. */
    const val POWERED_MIN_DISTANCE_M = 0f

    /** Минимальный интервал для запроса GPS при текущем питании, мс. */
    fun minTimeMs(pluggedIn: Boolean): Long = if (pluggedIn) POWERED_MIN_TIME_MS else BATTERY_MIN_TIME_MS

    /** Минимальное расстояние для запроса GPS при текущем питании, м. */
    fun minDistanceM(pluggedIn: Boolean): Float = if (pluggedIn) POWERED_MIN_DISTANCE_M else BATTERY_MIN_DISTANCE_M
}
