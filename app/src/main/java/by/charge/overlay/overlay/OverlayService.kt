package by.charge.overlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import by.charge.overlay.MainActivity
import by.charge.overlay.R
import by.charge.overlay.data.Networks
import by.charge.overlay.data.Station
import by.charge.overlay.data.StationRepository
import by.charge.overlay.geo.Geo
import by.charge.overlay.location.LocationTracker
import by.charge.overlay.nav.NavigatorLauncher
import by.charge.overlay.util.Permissions
import by.charge.overlay.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Служба переднего плана: держит системное окно-слой поверх всех приложений
 * (в том числе поверх Яндекс Навигатора), следит за геопозицией и подгружает станции.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: StationRepository
    private var tracker: LocationTracker? = null
    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var hud: HudView? = null
    private var titleText: TextView? = null

    private var location: Location? = null
    private var stations: List<Station> = emptyList()
    private var nearest: Station? = null
    private var statusMessage: String? = null
    private var fetchJob: Job? = null
    private var lastFetchLat = Double.NaN
    private var lastFetchLon = Double.NaN
    private var lastFetchMs = 0L
    private var lastAttemptMs = -RETRY_MS * 10

    override fun onCreate() {
        super.onCreate()
        repo = StationRepository(Networks.load(this))
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Сразу переходим в foreground: Android требует этого в течение 5 секунд после запуска
        startInForeground()

        if (!Permissions.hasLocation(this) || !Permissions.canDrawOverlay(this)) {
            Toast.makeText(this, R.string.need_permissions, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (rootView == null) addOverlay()
        if (rootView == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (tracker == null) {
            tracker = LocationTracker(this) { onLocation(it) }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tracker?.stop()
        tracker = null
        fetchJob?.cancel()
        removeOverlay()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun onLocation(loc: Location) {
        location = loc
        recomputeNearest()
        render()
        maybeRefresh(loc)
    }

    /** Перезагрузка станций: при сдвиге на 2 км, раз в 10 минут и не чаще раза в минуту при ошибках. */
    private fun maybeRefresh(loc: Location) {
        if (fetchJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        val moved = if (lastFetchLat.isNaN()) Double.MAX_VALUE else Geo.distanceM(lastFetchLat, lastFetchLon, loc.latitude, loc.longitude)
        val needRefresh = moved >= MOVE_REFRESH_M || now - lastFetchMs > REFRESH_MS
        if (!needRefresh || now - lastAttemptMs < RETRY_MS) return
        lastAttemptMs = now

        val lat = loc.latitude
        val lon = loc.longitude
        val radiusKm = Prefs.radiusKm(this).toDouble()
        fetchJob = scope.launch {
            statusMessage = getString(R.string.loading)
            render()
            val result = repo.load(lat, lon, radiusKm)
            stations = result.stations
            if (result.errors.isEmpty()) {
                statusMessage = null
                lastFetchLat = lat
                lastFetchLon = lon
                lastFetchMs = SystemClock.elapsedRealtime()
            } else {
                statusMessage = getString(R.string.load_error, result.errors.joinToString("; "))
            }
            recomputeNearest()
            render()
        }
    }

    private fun recomputeNearest() {
        val loc = location
        nearest = if (loc == null) {
            null
        } else {
            stations.minByOrNull { Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lon) }
        }
    }

    private fun render() {
        val loc = location
        val n = nearest
        hud?.update(loc, stations, n?.id)
        val text = if (loc == null) {
            getString(R.string.waiting_gps)
        } else if (n == null) {
            statusMessage ?: getString(R.string.no_stations)
        } else {
            buildString {
                append(n.brandName)
                append(" · ")
                append(formatDistance(Geo.distanceM(loc.latitude, loc.longitude, n.lat, n.lon)))
                n.maxKw?.let { append(" · ${it.toInt()} кВт") }
            }
        }
        titleText?.text = text
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${m.toInt()} м" else String.format(Locale.US, "%.1f км", m / 1000.0)

    private fun openRoute() {
        val target = nearest
        if (target == null) {
            Toast.makeText(this, R.string.no_stations, Toast.LENGTH_SHORT).show()
            return
        }
        if (!NavigatorLauncher.openRoute(this, target.lat, target.lon)) {
            Toast.makeText(this, R.string.navigator_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun addOverlay() {
        val wm = windowManager ?: return

        val titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val routeView = pill(getString(R.string.route), Color.rgb(0, 122, 255)).apply {
            setOnClickListener { openRoute() }
        }
        val closeView = pill("✕", Color.argb(170, 90, 90, 90)).apply {
            setOnClickListener { stopSelf() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
            background = rounded(Color.argb(210, 12, 16, 24), dp(12))
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                routeView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(6) },
            )
            addView(
                closeView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(4) },
            )
        }

        val hudView = HudView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                hudView,
                LinearLayout.LayoutParams(dp(150), dp(150)).apply {
                    topMargin = dp(6)
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
        }

        // NOT_FOCUSABLE: клавиатура и фокус остаются у навигатора. Касания вне окна
        // (размер окна WRAP_CONTENT) проходят в навигатор.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(8)
            title = "ChargeHUD"
        }

        try {
            wm.addView(root, params)
            rootView = root
            hud = hudView
            titleText = titleView
            render()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.need_permissions, Toast.LENGTH_LONG).show()
        }
    }

    private fun removeOverlay() {
        val view = rootView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            // Окно уже удалено системой
        }
        rootView = null
        hud = null
        titleText = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun pill(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = rounded(color, dp(10))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "by.charge.overlay.action.STOP"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIF_ID = 1001
        private const val MOVE_REFRESH_M = 2000.0
        private const val REFRESH_MS = 10 * 60 * 1000L
        private const val RETRY_MS = 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        @Suppress("DEPRECATION")
        private fun overlayType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
    }
}
