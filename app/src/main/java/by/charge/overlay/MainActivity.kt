package by.charge.overlay

import android.Manifest
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import by.charge.overlay.data.Networks
import by.charge.overlay.data.Station
import by.charge.overlay.data.StationRepository
import by.charge.overlay.geo.Geo
import by.charge.overlay.location.LocationTracker
import by.charge.overlay.nav.NavigatorLauncher
import by.charge.overlay.util.Permissions
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Locale

/**
 * Карта OpenStreetMap (osmdroid) со станциями. Станции грузятся для текущего центра карты,
 * фильтруются по сети, по нажатию открывается карточка с кнопкой маршрута в Яндекс Навигаторе.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var chips: ChipGroup
    private lateinit var card: View
    private lateinit var cardTitle: TextView
    private lateinit var cardDetails: TextView
    private lateinit var fabMe: FloatingActionButton
    private lateinit var repo: StationRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { loadStations() }
    private var loadJob: Job? = null
    private var chipsRebuilding = false

    private var tracker: LocationTracker? = null
    private var meMarker: Marker? = null
    private var myLocation: Location? = null
    private var firstFix = true

    private var lastLoadCenter: GeoPoint? = null
    private var lastLoadMs = 0L
    private var loadErrors: List<String> = emptyList()

    private var stations: List<Station> = emptyList()
    private var selectedBrand: String? = null
    private var selectedStation: Station? = null
    private val stationMarkers = mutableListOf<Marker>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onPermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Конфигурация osmdroid нужна до того, как MapView будет создан из layout
        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "ChargeHUD/1.0 (Android)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        statusText = findViewById(R.id.status)
        chips = findViewById(R.id.chips)
        card = findViewById(R.id.card)
        cardTitle = findViewById(R.id.card_title)
        cardDetails = findViewById(R.id.card_details)
        fabMe = findViewById(R.id.fab_me)
        repo = StationRepository(Networks.load(this))

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(MINSK_LAT, MINSK_LON))

        // Тап по пустому месту карты закрывает карточку
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: IGeoPoint?): Boolean {
                hideCard()
                return true
            }

            override fun longPressHelper(p: IGeoPoint?): Boolean = false
        }))
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleLoad()
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleLoad()
                return true
            }
        })

        chips.setOnCheckedStateChangeListener { _, ids ->
            if (!chipsRebuilding) {
                selectedBrand = ids.firstOrNull()?.let { id -> chips.findViewById<Chip>(id)?.tag as String? }
                applyFilter()
            }
        }

        fabMe.setOnClickListener {
            val loc = myLocation
            if (loc == null) {
                toast(R.string.waiting_gps)
            } else {
                map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            }
        }
        findViewById<Button>(R.id.btn_route).setOnClickListener {
            val target = selectedStation ?: return@setOnClickListener
            if (!NavigatorLauncher.openRoute(this, target.lat, target.lon)) {
                toast(R.string.navigator_missing)
            }
        }
        findViewById<Button>(R.id.btn_close).setOnClickListener { hideCard() }

        statusText.text = getString(R.string.loading)
        if (Permissions.hasLocation(this)) {
            startTracking()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        scheduleLoad()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(reloadRunnable)
        tracker?.stop()
        tracker = null
        scope.cancel()
        map.onDetach()
        super.onDestroy()
    }

    private fun onPermissionResult() {
        if (Permissions.hasLocation(this)) {
            startTracking()
        } else {
            statusText.text = getString(R.string.location_denied)
        }
    }

    private fun startTracking() {
        if (tracker != null) return
        tracker = LocationTracker(this) { onLocation(it) }.also { it.start() }
    }

    private fun onLocation(loc: Location) {
        myLocation = loc
        val p = GeoPoint(loc.latitude, loc.longitude)
        var marker = meMarker
        if (marker == null) {
            marker = Marker(map)
            marker.icon = dot(Color.rgb(64, 160, 255))
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            map.overlays.add(marker)
            meMarker = marker
        }
        marker.position = p
        if (firstFix) {
            firstFix = false
            map.controller.setCenter(p)
            scheduleLoad()
        }
        map.invalidate()
    }

    private fun scheduleLoad() {
        handler.removeCallbacks(reloadRunnable)
        handler.postDelayed(reloadRunnable, RELOAD_DELAY_MS)
    }

    private fun loadStations() {
        val c = map.mapCenter
        val center = GeoPoint(c.latitude, c.longitude)
        val last = lastLoadCenter
        val now = SystemClock.elapsedRealtime()
        // Не перезагружаем, если центр почти не сдвинулся и данные свежие
        if (last != null &&
            Geo.distanceM(last.latitude, last.longitude, center.latitude, center.longitude) < RELOAD_MOVE_M &&
            now - lastLoadMs < RELOAD_AGE_MS
        ) {
            return
        }
        lastLoadCenter = center
        lastLoadMs = now
        loadJob?.cancel()
        statusText.text = getString(R.string.loading)
        loadJob = scope.launch {
            val result = repo.load(center.latitude, center.longitude, SEARCH_RADIUS_KM)
            stations = result.stations
            loadErrors = result.errors
            // При ошибке разрешаем повтор при следующем движении карты
            if (result.errors.isNotEmpty()) lastLoadCenter = null
            rebuildChips()
            applyFilter()
        }
    }

    private fun rebuildChips() {
        val wanted = selectedBrand
        chipsRebuilding = true
        chips.removeAllViews()
        val all = chip(getString(R.string.chip_all, stations.size), null)
        chips.addView(all)
        stations.groupBy { it.brandId }.values
            .sortedByDescending { it.size }
            .forEach { group ->
                val first = group.first()
                chips.addView(chip("${first.brandName} (${group.size})", first.brandId))
            }
        val match = (0 until chips.childCount)
            .map { chips.getChildAt(it) as Chip }
            .firstOrNull { it.tag == wanted } ?: all
        chips.check(match.id)
        selectedBrand = match.tag as String?
        chipsRebuilding = false
    }

    private fun chip(label: String, brandId: String?): Chip = Chip(this).apply {
        text = label
        isCheckable = true
        isClickable = true
        tag = brandId
        id = View.generateViewId()
    }

    private fun applyFilter() {
        map.overlays.removeAll(stationMarkers)
        stationMarkers.clear()
        val visible = stations.filter { selectedBrand == null || it.brandId == selectedBrand }
        for (s in visible) {
            val m = Marker(map)
            m.position = GeoPoint(s.lat, s.lon)
            m.icon = dot(colorOf(s.brandColor))
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.setOnMarkerClickListener { _, _ ->
                showCard(s)
                true
            }
            stationMarkers.add(m)
        }
        map.overlays.addAll(stationMarkers)
        map.invalidate()

        val base = getString(R.string.status_count, visible.size)
        statusText.text = if (loadErrors.isEmpty()) {
            base
        } else {
            "$base · ${getString(R.string.load_error, loadErrors.joinToString("; "))}"
        }
    }

    private fun showCard(s: Station) {
        selectedStation = s
        cardTitle.text = s.title
        val details = buildList {
            add(s.brandName)
            myLocation?.let { loc ->
                add(formatDistance(Geo.distanceM(loc.latitude, loc.longitude, s.lat, s.lon)))
            }
            s.maxKw?.let { add("${it.toInt()} кВт") }
            if (s.connectors.isNotEmpty()) add(s.connectors.joinToString(", "))
            s.operator?.takeIf { it != s.brandName }?.let { add(it) }
        }
        cardDetails.text = details.joinToString(" · ")
        card.visibility = View.VISIBLE
        map.controller.animateTo(GeoPoint(s.lat, s.lon))
    }

    private fun hideCard() {
        selectedStation = null
        card.visibility = View.GONE
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${m.toInt()} м" else String.format(Locale.US, "%.1f км", m / 1000.0)

    private fun dot(color: Int): GradientDrawable = GradientDrawable().apply {
        setShape(GradientDrawable.OVAL)
        setColor(color)
        setStroke(dp(2), Color.WHITE)
        setSize(dp(18), dp(18))
    }

    private fun colorOf(hex: String): Int =
        try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            Color.GRAY
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        // Центр Минска — стартовая точка, пока нет GPS
        private const val MINSK_LAT = 53.9045
        private const val MINSK_LON = 27.5615
        private const val SEARCH_RADIUS_KM = 15.0
        private const val RELOAD_DELAY_MS = 700L
        private const val RELOAD_MOVE_M = 1500.0
        private const val RELOAD_AGE_MS = 10 * 60 * 1000L
    }
}
