package by.charge.overlay.util

import android.content.Context

object Prefs {
    const val MIN_RADIUS_KM = 3
    const val MAX_RADIUS_KM = 30
    private const val FILE = "charge_prefs"
    private const val KEY_RADIUS = "radius_km"
    private const val DEFAULT_RADIUS_KM = 10

    fun radiusKm(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_RADIUS, DEFAULT_RADIUS_KM)
            .coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM)

    fun setRadiusKm(context: Context, km: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RADIUS, km.coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM))
            .apply()
    }
}
