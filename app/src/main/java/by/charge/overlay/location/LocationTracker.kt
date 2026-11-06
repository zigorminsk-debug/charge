package by.charge.overlay.location

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Тонкая обёртка над LocationManager (GPS + сеть), без Google Play Services.
 * При подключении внешнего питания частота запроса растёт (см. FixRate), при отключении возвращается к экономному режиму.
 */
class LocationTracker(context: Context, private val onFix: (Location) -> Unit) : LocationListener {

    // Системный LocationManager. Google Play Services не используются, чтобы приложение работало без них.
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    // Контекст приложения: приёмник питания живёт вместе с трекером, а не с конкретным экраном.
    private val appContext = context.applicationContext
    private var running = false
    // Текущее питание: от него зависит частота запроса GPS.
    private var pluggedIn = false
    private var powerReceiverRegistered = false

    // Подключение и отключение внешнего питания: пересчитываем частоту запроса GPS.
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val plugged = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> return
            }
            if (plugged == pluggedIn) return
            pluggedIn = plugged
            if (running) subscribe()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        // Текущее состояние питания берём из последнего «липкого» broadcast батареи.
        pluggedIn = readPluggedNow()
        registerPowerReceiver()
        // Сразу отдаём последнюю известную точку, чтобы HUD не ждал первого фикса.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.getLastKnownLocation(provider)?.let(onFix)
            } catch (e: SecurityException) {
                // Нет разрешения: служба проверит его сама и остановится
            } catch (e: IllegalArgumentException) {
                // Провайдер на устройстве отсутствует
            }
        }
        subscribe()
    }

    fun stop() {
        if (!running) return
        running = false
        unregisterPowerReceiver()
        try {
            lm.removeUpdates(this)
        } catch (e: SecurityException) {
            // Разрешение уже отозвано
        }
    }

    /**
     * Подписка на провайдеры с частотой, которая соответствует текущему питанию.
     * Повторный вызов сначала снимает старую подписку, чтобы не было двух потоков фиксов.
     */
    @SuppressLint("MissingPermission")
    private fun subscribe() {
        try {
            lm.removeUpdates(this)
        } catch (e: SecurityException) {
            // Разрешение отозвано: подписку снимать нечего
        }
        val minTime = FixRate.minTimeMs(pluggedIn)
        val minDistance = FixRate.minDistanceM(pluggedIn)
        // GPS и сеть подписываются отдельно: если один провайдер выключен или отсутствует, работает второй.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                // Подписка только на включённые провайдеры.
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, minTime, minDistance, this, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                // Нет разрешения: служба проверит его сама и остановится
            } catch (e: IllegalArgumentException) {
                // Провайдер на устройстве отсутствует
            }
        }
    }

    private fun readPluggedNow(): Boolean {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun registerPowerReceiver() {
        if (powerReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // Системные broadcast питания: приёмник не экспортируем наружу.
        ContextCompat.registerReceiver(appContext, powerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        powerReceiverRegistered = true
    }

    private fun unregisterPowerReceiver() {
        if (!powerReceiverRegistered) return
        try {
            appContext.unregisterReceiver(powerReceiver)
        } catch (e: IllegalArgumentException) {
            // Уже снят
        }
        powerReceiverRegistered = false
    }

    override fun onLocationChanged(location: Location) {
        onFix(location)
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
