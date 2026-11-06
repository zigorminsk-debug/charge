package by.charge.overlay.nav

import kotlin.math.abs

/**
 * Масштаб карты во время навигации по скорости.
 * На малой скорости (город, светофор) карта приближается, на трассе отдаляется, чтобы впереди было больше дороги.
 * Скорость сглаживается, поэтому масштаб меняется плавно, а не рывками на каждом обновлении GPS.
 */
class SpeedZoom {

    // Сглаженная скорость, м/с. Пока не было ни одного замера, сглаживания нет.
    private var smoothedMs = 0.0
    private var started = false

    /** Сбрасывает сглаживание: вызывается при старте и выходе из навигации. */
    fun reset() {
        smoothedMs = 0.0
        started = false
    }

    /**
     * Целевой масштаб по скорости GPS (м/с). Отрицательные и нечисловые значения считаем нулём.
     * Возвращает значение от ZOOM_SLOW (медленно) до ZOOM_FAST (быстро).
     */
    fun target(rawSpeedMs: Double): Double {
        val v = if (rawSpeedMs.isFinite() && rawSpeedMs > 0.0) rawSpeedMs else 0.0
        smoothedMs = if (started) smoothedMs + (v - smoothedMs) * SMOOTHING else v
        started = true
        val t = ((smoothedMs - SLOW_SPEED_MS) / (FAST_SPEED_MS - SLOW_SPEED_MS)).coerceIn(0.0, 1.0)
        return ZOOM_SLOW + (ZOOM_FAST - ZOOM_SLOW) * t
    }

    companion object {
        /** Масштаб на малой скорости: ближе, видно улицы и повороты. */
        const val ZOOM_SLOW = 17.0
        /** Масштаб на трассе: дальше, видно дорогу впереди. */
        const val ZOOM_FAST = 15.0
        /** Скорость, ниже которой масштаб максимальный (18 км/ч). */
        const val SLOW_SPEED_MS = 5.0
        /** Скорость, выше которой масштаб минимальный (90 км/ч). */
        const val FAST_SPEED_MS = 25.0
        /** Доля нового замера в сглаженной скорости (0..1). Меньше — плавнее, но медленнее реакция. */
        const val SMOOTHING = 0.3
        /** Меньшее изменение масштаба не применяем, чтобы карта не «дышала» на каждом обновлении GPS. */
        const val MIN_CHANGE = 0.2
        /** Длительность анимации зума, мс. */
        const val ANIM_MS = 900L

        /** Нужно ли менять масштаб с current на target. */
        fun shouldChange(current: Double, target: Double): Boolean = abs(target - current) >= MIN_CHANGE
    }
}
