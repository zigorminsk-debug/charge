package by.charge.overlay

import android.Manifest
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
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
import by.charge.overlay.nav.Instructions
import by.charge.overlay.nav.LatLon
import by.charge.overlay.nav.NavigatorLauncher
import by.charge.overlay.nav.Route
import by.charge.overlay.nav.RouteProgress
import by.charge.overlay.nav.Routing
import by.charge.overlay.util.Permissions
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale

/**
 * Карта OpenStreetMap со станциями, фильтр по сетям, карточка станции и встроенная навигация:
 * маршрут по OSRM, линия на карте, баннер со следующим манёвром, голосовые подсказки.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var chips: ChipGroup
    private lateinit var card: View
    private lateinit var cardTitle: TextView
    private lateinit var cardDetails: TextView
    private lateinit var btnRouteExternal: Button
    private lateinit var fabMe: FloatingActionButton
    private lateinit var navBanner: View
    private lateinit var navInstruction: TextView
    private lateinit var navSub: TextView
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
    private var lastLoadOk = true
    private var loadErrors: List<String> = emptyList()

    private var stations: List<Station> = emptyList()
    private var visibleCount = 0
    private var selectedBrand: String? = null
    private val stationMarkers = mutableListOf<Marker>()

    // Карточка: станция (для внешнего Навигатора) или произвольная точка (долгий тап)
    private var cardStation: Station? = null
    private var cardDestination: LatLon? = null

    // Встроенная навигация
    private var navigating = false
    private var navDestination: LatLon? = null
    private var routeProgress: RouteProgress? = null
    private var routeLine: Polyline? = null
    private var routeJob: Job? = null
    private var lastRerouteMs = 0L
    private var spokenStepIndex = -1
    private var tts: TextToSpeech? = null

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
        btnRouteExternal = findViewById(R.id.btn_route)
        fabMe = findViewById(R.id.fab_me)
        navBanner = findViewById(R.id.nav_banner)
        navInstruction = findViewById(R.id.nav_instruction)
        navSub = findViewById(R.id.nav_sub)
        repo = StationRepository(Networks.load(this))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("ru", "RU"))
        }

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(MINSK_LAT, MINSK_LON))

        // Тап по пустому месту закрывает карточку, долгий тап ставит точку назначения
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                hideCard()
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) showCustomPoint(LatLon(p.latitude, p.longitude))
                return true
            }
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
        findViewById<Button>(R.id.btn_nav).setOnClickListener {
            cardDestination?.let { beginNavigation(it) }
        }
        btnRouteExternal.setOnClickListener {
            val target = cardStation ?: return@setOnClickListener
            if (!NavigatorLauncher.openRoute(this, target.lat, target.lon)) {
                toast(R.string.navigator_missing)
            }
        }
        findViewById<Button>(R.id.btn_close).setOnClickListener { hideCard() }
        findViewById<Button>(R.id.btn_nav_stop).setOnClickListener { stopNavigation() }

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
        routeJob?.cancel()
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
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
        if (navigating) {
            // Во время навигации карта следует за машиной
            map.controller.setCenter(p)
            updateNavigation(LatLon(loc.latitude, loc.longitude))
        }
        map.invalidate()
    }

    // ---------- Станции ----------

    private fun scheduleLoad() {
        handler.removeCallbacks(reloadRunnable)
        handler.postDelayed(reloadRunnable, RELOAD_DELAY_MS)
    }

    private fun loadStations() {
        val c = map.mapCenter
        val center = GeoPoint(c.latitude, c.longitude)
        val last = lastLoadCenter
        val now = SystemClock.elapsedRealtime()
        val moved = if (last == null) Double.MAX_VALUE
        else Geo.distanceM(last.latitude, last.longitude, center.latitude, center.longitude)
        // После успеха не грузим повторно, пока центр почти не сдвинулся и данные свежие.
        // После ошибки ждём короче, чтобы не долбить сервер при каждом движении карты.
        val maxAge = if (lastLoadOk) RELOAD_AGE_MS else RETRY_AFTER_ERROR_MS
        if (moved < RELOAD_MOVE_M && now - lastLoadMs < maxAge) return

        lastLoadCenter = center
        lastLoadMs = now
        loadJob?.cancel()
        statusText.text = getString(R.string.loading)
        loadJob = scope.launch {
            val result = repo.load(center.latitude, center.longitude, SEARCH_RADIUS_KM)
            lastLoadOk = result.errors.isEmpty()
            loadErrors = result.errors
            // При ошибке оставляем уже загруженные станции, а не очищаем карту
            if (lastLoadOk || result.stations.isNotEmpty()) stations = result.stations
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
        visibleCount = visible.size
        updateStatusText()
    }

    private fun updateStatusText() {
        if (navigating) return
        val base = getString(R.string.status_count, visibleCount)
        statusText.text = if (loadErrors.isEmpty()) {
            base
        } else {
            "$base · ${getString(R.string.load_error, loadErrors.joinToString("; "))}"
        }
    }

    // ---------- Карточка ----------

    private fun showCard(s: Station) {
        cardStation = s
        cardDestination = LatLon(s.lat, s.lon)
        cardTitle.text = s.title
        val details = buildList {
            add(s.brandName)
            myLocation?.let { loc ->
                add(Instructions.distance(Geo.distanceM(loc.latitude, loc.longitude, s.lat, s.lon)))
            }
            s.maxKw?.let { add("${it.toInt()} кВт") }
            if (s.connectors.isNotEmpty()) add(s.connectors.joinToString(", "))
            s.operator?.takeIf { it != s.brandName }?.let { add(it) }
        }
        cardDetails.text = details.joinToString(" · ")
        btnRouteExternal.visibility = View.VISIBLE
        card.visibility = View.VISIBLE
        map.controller.animateTo(GeoPoint(s.lat, s.lon))
    }

    private fun showCustomPoint(p: LatLon) {
        cardStation = null
        cardDestination = p
        cardTitle.text = getString(R.string.custom_point)
        cardDetails.text = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)
        btnRouteExternal.visibility = View.GONE
        card.visibility = View.VISIBLE
    }

    private fun hideCard() {
        cardStation = null
        cardDestination = null
        card.visibility = View.GONE
    }

    // ---------- Навигация ----------

    private fun beginNavigation(to: LatLon) {
        val loc = myLocation
        if (loc == null) {
            toast(R.string.nav_need_location)
            return
        }
        hideCard()
        startRoute(LatLon(loc.latitude, loc.longitude), to, reroute = false)
    }

    /** Строит маршрут. При перестроении (reroute) старая линия остаётся, пока не придёт новая. */
    private fun startRoute(from: LatLon, to: LatLon, reroute: Boolean) {
        routeJob?.cancel()
        if (!reroute) statusText.text = getString(R.string.nav_building)
        routeJob = scope.launch {
            val route = try {
                Routing.fetch(from, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!reroute) statusText.text = getString(R.string.nav_failed, e.message ?: e.javaClass.simpleName)
                return@launch
            }
            applyRoute(route, to)
        }
    }

    private fun applyRoute(route: Route, to: LatLon) {
        routeProgress = RouteProgress(route)
        navDestination = to
        spokenStepIndex = -1
        drawRoute(route.geometry)
        if (!navigating) {
            navigating = true
            navBanner.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        myLocation?.let { updateNavigation(LatLon(it.latitude, it.longitude)) }
        map.invalidate()
    }

    private fun updateNavigation(me: LatLon) {
        val progress = routeProgress ?: return
        val snap = progress.update(me)
        val now = SystemClock.elapsedRealtime()

        if (snap.arrived) {
            navInstruction.text = getString(R.string.nav_arrived)
            navSub.text = ""
            if (spokenStepIndex != ARRIVED) {
                spokenStepIndex = ARRIVED
                speak(getString(R.string.nav_arrived))
            }
            return
        }

        val dest = navDestination
        if (snap.offRouteM > RouteProgress.OFF_ROUTE_M && dest != null &&
            now - lastRerouteMs > REROUTE_MIN_INTERVAL_MS && routeJob?.isActive != true
        ) {
            lastRerouteMs = now
            navSub.text = getString(R.string.nav_reroute)
            startRoute(me, dest, reroute = true)
            return
        }

        val next = snap.nextStep
        if (next == null) {
            navInstruction.text = getString(R.string.nav_arrived)
            return
        }
        navInstruction.text = Instructions.text(next)
        navSub.text = getString(
            R.string.nav_sub,
            Instructions.distance(snap.distanceToNextM),
            Instructions.distance(snap.remainingM),
            Instructions.duration(snap.remainingS),
        )
        if (snap.distanceToNextM < ANNOUNCE_M && snap.nextStepIndex != spokenStepIndex) {
            spokenStepIndex = snap.nextStepIndex
            speak(
                getString(
                    R.string.nav_speak,
                    Instructions.distance(snap.distanceToNextM),
                    Instructions.text(next),
                )
            )
        }
    }

    private fun stopNavigation() {
        routeJob?.cancel()
        routeJob = null
        navigating = false
        navDestination = null
        routeProgress = null
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
        navBanner.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tts?.stop()
        map.invalidate()
        updateStatusText()
    }

    private fun drawRoute(points: List<LatLon>) {
        routeLine?.let { map.overlays.remove(it) }
        val line = Polyline().apply {
            setPoints(points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = Color.rgb(0, 122, 255)
            outlinePaint.strokeWidth = dp(6).toFloat()
        }
        // Линия ниже маркеров: индекс 0 занят событиями карты
        map.overlays.add(1, line)
        routeLine = line
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    // ---------- Утилиты ----------

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
        private const val RETRY_AFTER_ERROR_MS = 30 * 1000L
        private const val REROUTE_MIN_INTERVAL_MS = 15 * 1000L
        private const val ANNOUNCE_M = 300.0
        private const val ARRIVED = Int.MAX_VALUE
    }
}
