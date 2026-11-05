package by.charge.overlay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** Тонкая обёртка над LocationManager (GPS + сеть), без Google Play Services. */
class LocationTracker(context: Context, private val onFix: (Location) -> Unit) : LocationListener {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                // Сразу отдаём последнюю известную точку, чтобы HUD не ждал первого фикса
                lm.getLastKnownLocation(provider)?.let(onFix)
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, this, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                // Нет разрешения: служба проверит его сама и остановится
            } catch (e: IllegalArgumentException) {
                // Провайдер на устройстве отсутствует
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            lm.removeUpdates(this)
        } catch (e: SecurityException) {
            // Разрешение уже отозвано
        }
    }

    override fun onLocationChanged(location: Location) {
        onFix(location)
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    companion object {
        private const val MIN_TIME_MS = 3_000L
        private const val MIN_DISTANCE_M = 10f
    }
}
