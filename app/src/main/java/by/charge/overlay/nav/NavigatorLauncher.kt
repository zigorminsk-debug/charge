package by.charge.overlay.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/** Построение маршрута до станции в Яндекс Навигаторе через deep link. */
object NavigatorLauncher {

    fun routeUri(lat: Double, lon: Double): Uri =
        Uri.parse(String.format(Locale.US, "yandexnavi://build_route_on_map?lat_to=%.6f&lon_to=%.6f", lat, lon))

    /** true — навигатор нашёлся и открыт; false — приложения нет или схема не обрабатывается. */
    fun openRoute(context: Context, lat: Double, lon: Double): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, routeUri(lat, lon)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
