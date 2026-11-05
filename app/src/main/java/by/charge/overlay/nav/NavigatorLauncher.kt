package by.charge.overlay.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/** Открытие Яндекс Навигатора: построение маршрута до станции и простой запуск приложения. */
object NavigatorLauncher {
    /** Пакет Яндекс Навигатора на Android. Если на вашем устройстве другой — поправьте здесь. */
    const val PACKAGE = "ru.yandex.yandexnavi"

    fun routeUri(lat: Double, lon: Double): Uri =
        Uri.parse(String.format(Locale.US, "yandexnavi://build_route_on_map?lat_to=%.6f&lon_to=%.6f", lat, lon))

    /** true — активность нашлась и запущена; false — навигатора нет или схема не обрабатывается. */
    fun openRoute(context: Context, lat: Double, lon: Double): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, routeUri(lat, lon)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    fun openApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
