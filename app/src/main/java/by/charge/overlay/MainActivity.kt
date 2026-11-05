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
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import by.charge.overlay.data.ChargerStatus
import by.charge.overlay.data.Geocoder
import by.charge.overlay.data.Networks
import by.charge.overlay.data.Place
import by.charge.overlay.data.Station
import by.charge.overlay.data.StationRepository
import by.charge.overlay.geo.Box
import by.charge.overlay.geo.Geo
import by.charge.overlay.location.LocationTracker
import by.charge.overlay.nav.Heading
import by.charge.overlay.nav.Instructions
import by.charge.overlay.nav.LatLon
import by.charge.overlay.nav.NavigatorLauncher
import by.charge.overlay.nav.Route
import by.charge.overlay.nav.RouteGeometry
import by.charge.overlay.nav.RoutePlanner
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
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale

/**
 * Карта OpenStreetMap со станциями: фильтр по сетям, карточка станции со статусом и разъёмами,
 * планирование маршрута A → B с зарядками по пути (адрес или точка на карте),
 * встроенная навигация: линия, баннер манёвров, голос, перестроение.
 */
class MainActivity : AppCompatActivity() {

    private enum class PickTarget { FROM, TO }

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
    private lateinit var topBar: View
    private lateinit var stopsStrip: View
    private lateinit var stopsList: LinearLayout
    private lateinit var planPanel: View
    private lateinit var planPickHint: TextView
    private lateinit var planFrom: TextView
    private lateinit var planTo: TextView
    private lateinit var rangeInput: EditText
    private lateinit var planResult: TextView
    private lateinit var btnPlanStart: Button
    private lateinit var repo: StationRepository
    private lateinit var prefs: android.content.SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { loadStations() }
    private var loadJob: Job? = null
    private var chipsRebuilding = false

    private var tracker: LocationTracker? = null
    private var meMarker: Marker? = null
    private var myLocation: Location? = null
    private var firstFix = true

    private var lastLoadCenter: LatLon? = null
    private var lastLoadMs = 0L
    private var lastLoadOk = true
    private var loadErrors: List<String> = emptyList()

    private var stations: List<Station> = emptyList()
    private var visibleCount = 0
    private var selectedBrand: String? = null
    private val stationMarkers = mutableListOf<Marker>()
    private val stopMarkers = mutableListOf<Marker>()

    // Карточка: станция (для внешнего Навигатора) или произвольная точка (долгий тап)
    private var cardStation: Station? = null
    private var cardDestination: LatLon? = null

    // Планирование: null у from означает «моё местоположение»
    private var pickTarget: PickTarget? = null
    private var planFromPoint: LatLon? = null
    private var planFromLabel: String? = null
    private var planToPoint: LatLon? = null
    private var planToLabel: String? = null
    private var planJob: Job? = null
    private var planRoute: Route? = null
    private var planStops: List<RoutePlanner.Stop> = emptyList()
    private var planDestination: LatLon? = null

    // Встроенная навигация
    private var navigating = false
    private var navDestination: LatLon? = null
    private var navStops: List<RoutePlanner.Stop> = emptyList()
    // Станции рядом с маршрутом (для полосы) и их положение вдоль текущего маршрута
    private var navNearby: List<Station> = emptyList()
    private var navCorridor: List<RoutePlanner.Stop> = emptyList()
    private var planNearby: List<Station> = emptyList()
    private val announcedStops = HashSet<String>()
    private var routeProgress: RouteProgress? = null

    // Поворот карты по движению: последняя точка, от которой считаем направление, и текущий угол карты
    private var headingFix: LatLon? = null
    private var mapRotationDeg = 0f
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

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
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
        planPanel = findViewById(R.id.plan_panel)
        topBar = findViewById(R.id.top_bar)
        stopsStrip = findViewById(R.id.stops_strip)
        stopsList = findViewById(R.id.stops_list)
        // Полоса начинается сразу под верхней панелью, какой бы высоты она ни была
        topBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val lp = stopsStrip.layoutParams as FrameLayout.LayoutParams
            val want = v.bottom + dp(4)
            if (lp.topMargin != want) {
                lp.topMargin = want
                stopsStrip.layoutParams = lp
            }
        }
        planPickHint = findViewById(R.id.plan_pick_hint)
        planFrom = findViewById(R.id.plan_from)
        planTo = findViewById(R.id.plan_to)
        rangeInput = findViewById(R.id.range_km)
        planResult = findViewById(R.id.plan_result)
        btnPlanStart = findViewById(R.id.btn_plan_start)
        repo = StationRepository(Networks.load(this)) { prefs.getString(KEY_OCM, null) }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("ru", "RU"))
        }

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(MINSK_LAT, MINSK_LON))

        // Тап по пустому месту закрывает карточку; долгий тап ставит точку «Откуда»/«Куда» или точку назначения
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                hideCard()
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) onMapLongPress(LatLon(p.latitude, p.longitude))
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

        // Планирование
        findViewById<Button>(R.id.btn_plan_open).setOnClickListener { showPlanPanel() }
        findViewById<Button>(R.id.btn_plan_close).setOnClickListener { hidePlanPanel() }
        findViewById<Button>(R.id.btn_from_me).setOnClickListener {
            planFromPoint = null
            planFromLabel = null
            updatePlanLabels()
        }
        findViewById<Button>(R.id.btn_from_addr).setOnClickListener {
            askAddress { place ->
                planFromPoint = LatLon(place.lat, place.lon)
                planFromLabel = place.name
                updatePlanLabels()
            }
        }
        findViewById<Button>(R.id.btn_from_map).setOnClickListener { startPick(PickTarget.FROM) }
        findViewById<Button>(R.id.btn_to_addr).setOnClickListener {
            askAddress { place ->
                planToPoint = LatLon(place.lat, place.lon)
                planToLabel = place.name
                updatePlanLabels()
            }
        }
        findViewById<Button>(R.id.btn_to_map).setOnClickListener { startPick(PickTarget.TO) }
        findViewById<Button>(R.id.btn_build).setOnClickListener { buildPlan() }
        btnPlanStart.setOnClickListener { startPlannedNavigation() }
        findViewById<Button>(R.id.btn_plan_clear).setOnClickListener { clearPlan() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettings() }
        updatePlanLabels()

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
        planJob?.cancel()
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
            marker.icon = dot(Color.rgb(64, 160, 255), Color.WHITE)
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
            // Во время навигации карта следует за машиной и поворачивается по движению
            map.controller.setCenter(p)
            updateHeading(loc)
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
        val center = LatLon(c.latitude, c.longitude)
        val last = lastLoadCenter
        val now = SystemClock.elapsedRealtime()
        val moved = if (last == null) Double.MAX_VALUE
        else Geo.distanceM(last.lat, last.lon, center.lat, center.lon)
        // После успеха не грузим повторно, пока центр почти не сдвинулся и данные свежие.
        // После ошибки ждём короче, чтобы не долбить сервер при каждом движении карты.
        val maxAge = if (lastLoadOk) RELOAD_AGE_MS else RETRY_AFTER_ERROR_MS
        if (moved < RELOAD_MOVE_M && now - lastLoadMs < maxAge) return

        lastLoadCenter = center
        lastLoadMs = now
        loadJob?.cancel()
        statusText.text = getString(R.string.loading)
        loadJob = scope.launch {
            val result = repo.load(Box.around(center.lat, center.lon, SEARCH_RADIUS_KM))
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
            // Красная обводка — станция не работает по данным источника
            val ring = if (s.status == ChargerStatus.BROKEN) Color.RED else Color.WHITE
            m.icon = dot(colorOf(s.brandColor), ring)
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
        if (navigating || planPanel.visibility == View.VISIBLE) return
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
        val lines = ArrayList<String>()
        val header = listOfNotNull(s.brandName, s.operator?.takeIf { it != s.brandName }).joinToString(" · ")
        lines.add(header)
        myLocation?.let { loc ->
            lines.add(Instructions.distance(Geo.distanceM(loc.latitude, loc.longitude, s.lat, s.lon)))
        }
        lines.add(getString(R.string.card_status, s.status.label))
        s.maxKw?.let { lines.add(getString(R.string.card_max_kw, it.toInt())) }
        if (s.outlets.isEmpty()) {
            lines.add(getString(R.string.card_no_outlets))
        } else {
            lines.add(getString(R.string.card_outlets))
            s.outlets.forEach { lines.add("• ${it.describe()}") }
        }
        cardDetails.text = lines.joinToString("\n")
        btnRouteExternal.visibility = View.VISIBLE
        card.visibility = View.VISIBLE
        map.controller.animateTo(GeoPoint(s.lat, s.lon))
    }

    private fun showCustomPoint(p: LatLon) {
        cardStation = null
        cardDestination = p
        cardTitle.text = getString(R.string.custom_point)
        cardDetails.text = coords(p)
        btnRouteExternal.visibility = View.GONE
        card.visibility = View.VISIBLE
    }

    private fun hideCard() {
        cardStation = null
        cardDestination = null
        card.visibility = View.GONE
    }

    // ---------- Планирование маршрута ----------

    private fun showPlanPanel() {
        hideCard()
        planPanel.visibility = View.VISIBLE
        fabMe.visibility = View.GONE
        updatePlanLabels()
    }

    private fun hidePlanPanel() {
        pickTarget = null
        planPickHint.visibility = View.GONE
        planPanel.visibility = View.GONE
        fabMe.visibility = View.VISIBLE
        updateStatusText()
    }

    /** Долгий тап на карте: режим выбора точки маршрута, либо карточка точки назначения. */
    private fun onMapLongPress(p: LatLon) {
        val target = pickTarget
        if (target == null) {
            showCustomPoint(p)
            return
        }
        pickTarget = null
        val label = getString(R.string.plan_point_label, coords(p))
        if (target == PickTarget.FROM) {
            planFromPoint = p
            planFromLabel = label
        } else {
            planToPoint = p
            planToLabel = label
        }
        planPickHint.visibility = View.GONE
        planPanel.visibility = View.VISIBLE
        updatePlanLabels()
    }

    private fun startPick(target: PickTarget) {
        pickTarget = target
        planPanel.visibility = View.GONE
        planPickHint.text = getString(
            if (target == PickTarget.FROM) R.string.plan_pick_from else R.string.plan_pick_to
        )
        planPickHint.visibility = View.VISIBLE
        // Кнопка «Отмена» в подсказке: повторный тап по «Маршрут» возвращает панель
        planPickHint.setOnClickListener {
            pickTarget = null
            planPickHint.visibility = View.GONE
            planPanel.visibility = View.VISIBLE
        }
    }

    private fun updatePlanLabels() {
        val fromText = planFromLabel ?: getString(R.string.plan_from_me_label)
        planFrom.text = getString(R.string.plan_from_label, fromText)
        val toText = planToLabel ?: getString(R.string.plan_not_set)
        planTo.text = getString(R.string.plan_to_label, toText)
    }

    private fun askAddress(onPicked: (Place) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.addr_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.addr_title)
            .setView(input)
            .setPositiveButton(R.string.addr_search) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isEmpty()) return@setPositiveButton
                scope.launch {
                    val places = try {
                        withContext(Dispatchers.IO) { Geocoder.search(query) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        toast(e.message ?: e.javaClass.simpleName)
                        return@launch
                    }
                    if (places.isEmpty()) {
                        toast(R.string.addr_not_found)
                        return@launch
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.addr_pick)
                        .setItems(places.map { it.name }.toTypedArray()) { _, which -> onPicked(places[which]) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSettings() {
        val input = EditText(this).apply {
            hint = getString(R.string.settings_ocm_key)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString(KEY_OCM, "") ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setMessage(R.string.settings_ocm_hint)
            .setView(input)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val key = input.text.toString().trim()
                prefs.edit().putString(KEY_OCM, key.ifEmpty { null }).apply()
                // Сбрасываем кэш, чтобы станции перезагрузились с учётом ключа
                lastLoadCenter = null
                scheduleLoad()
                toast(R.string.settings_saved)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rangeKm(): Int =
        rangeInput.text.toString().toIntOrNull()?.coerceIn(MIN_RANGE_KM, MAX_RANGE_KM) ?: DEFAULT_RANGE_KM

    private fun buildPlan() {
        val to = planToPoint ?: run {
            toast(R.string.plan_need_to)
            return
        }
        val from = planFromPoint ?: myLocation?.let { LatLon(it.latitude, it.longitude) } ?: run {
            toast(R.string.plan_need_from)
            return
        }
        val rangeKm = rangeKm()
        rangeInput.setText(rangeKm.toString())

        planJob?.cancel()
        clearPlanPreview()
        btnPlanStart.visibility = View.GONE
        planResult.text = getString(R.string.plan_route_loading)
        planJob = scope.launch {
            try {
                val base = Routing.fetch(listOf(from, to))

                planResult.text = getString(R.string.plan_stations_loading)
                val loaded = repo.load(Box.of(base.geometry).padded(STATION_PAD_KM))

                var stopWarning: String? = null
                val picked = try {
                    RoutePlanner.pickStops(
                        RouteGeometry(base.geometry), loaded.stations,
                        rangeKm * 1000.0, RESERVE_M,
                    )
                } catch (e: RoutePlanner.NoStationException) {
                    stopWarning = e.message
                    emptyList()
                }

                // Перестраиваем маршрут через выбранные зарядки, чтобы линия реально проходила через них
                val route = if (picked.isEmpty()) {
                    base
                } else {
                    val points = listOf(from) +
                        picked.map { LatLon(it.station.lat, it.station.lon) } + to
                    Routing.fetch(points)
                }
                val stops = RoutePlanner.attach(RouteGeometry(route.geometry), picked.map { it.station })

                planRoute = route
                planStops = stops
                planNearby = loaded.stations
                planDestination = to
                drawRoute(route.geometry)
                drawStops(stops)
                val bounds = Box.of(route.geometry)
                map.zoomToBoundingBox(
                    BoundingBox(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon),
                    true, 80,
                )
                showPlanResult(route, stops, stopWarning, loaded.errors, rangeKm)
                btnPlanStart.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                planResult.text = getString(R.string.plan_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun showPlanResult(
        route: Route,
        stops: List<RoutePlanner.Stop>,
        stopWarning: String?,
        stationErrors: List<String>,
        rangeKm: Int,
    ) {
        val lines = ArrayList<String>()
        lines.add(
            getString(
                R.string.plan_summary,
                Instructions.distance(route.distanceM),
                Instructions.duration(route.durationS),
            )
        )
        if (stops.isEmpty()) {
            lines.add(getString(R.string.plan_no_stops))
        } else {
            lines.add(getString(R.string.plan_stops_count, stops.size))
            stops.forEachIndexed { i, st ->
                lines.add(
                    getString(
                        R.string.plan_stop_line,
                        i + 1,
                        st.station.title,
                        Instructions.distance(st.alongM),
                    )
                )
                lines.add("    " + st.station.status.label + " · " +
                    st.station.outlets.joinToString("; ") { it.describe() }
                        .ifEmpty { getString(R.string.plan_no_outlets) })
            }
        }
        stopWarning?.let { lines.add(getString(R.string.plan_warning, it)) }
        if (stationErrors.isNotEmpty()) {
            lines.add(getString(R.string.plan_station_errors, stationErrors.joinToString("; ")))
        }
        planResult.text = lines.joinToString("\n")
    }

    private fun startPlannedNavigation() {
        val route = planRoute ?: return
        val to = planDestination ?: return
        hidePlanPanel()
        applyRoute(route, to, planStops, planNearby)
    }

    private fun clearPlan() {
        planJob?.cancel()
        planJob = null
        planRoute = null
        planStops = emptyList()
        planNearby = emptyList()
        planDestination = null
        planFromPoint = null
        planFromLabel = null
        planToPoint = null
        planToLabel = null
        btnPlanStart.visibility = View.GONE
        planResult.text = ""
        clearPlanPreview()
        updatePlanLabels()
    }

    private fun clearPlanPreview() {
        if (!navigating) {
            routeLine?.let { map.overlays.remove(it) }
            routeLine = null
        }
        map.overlays.removeAll(stopMarkers)
        stopMarkers.clear()
        map.invalidate()
    }

    private fun drawStops(stops: List<RoutePlanner.Stop>) {
        map.overlays.removeAll(stopMarkers)
        stopMarkers.clear()
        for (st in stops) {
            val m = Marker(map)
            m.position = GeoPoint(st.station.lat, st.station.lon)
            m.icon = dot(Color.rgb(255, 193, 7), Color.BLACK)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.title = st.station.title
            m.setOnMarkerClickListener { _, _ ->
                showCard(st.station)
                true
            }
            stopMarkers.add(m)
        }
        map.overlays.addAll(stopMarkers)
        map.invalidate()
    }

    // ---------- Навигация ----------

    private fun beginNavigation(to: LatLon) {
        val loc = myLocation
        if (loc == null) {
            toast(R.string.nav_need_location)
            return
        }
        hideCard()
        startRoute(listOf(LatLon(loc.latitude, loc.longitude), to), to, emptyList(), null, reroute = false)
    }

    /**
     * Строит маршрут и запускает навигацию. Остановки передаются станциями:
     * после перестроения пройденные остановки отбрасываются.
     */
    private fun startRoute(
        points: List<LatLon>,
        to: LatLon,
        plannedStations: List<Station>,
        nearby: List<Station>?,
        reroute: Boolean,
    ) {
        routeJob?.cancel()
        if (!reroute) statusText.text = getString(R.string.nav_building)
        routeJob = scope.launch {
            try {
                val route = Routing.fetch(points)
                // Станции рядом с маршрутом нужны полосе. При перестроении берём уже загруженные
                val near = nearby ?: try {
                    repo.load(Box.of(route.geometry).padded(STATION_PAD_KM)).stations
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                val planned = RoutePlanner.attach(RouteGeometry(route.geometry), plannedStations)
                applyRoute(route, to, planned, near)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!reroute) statusText.text = getString(R.string.nav_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun applyRoute(
        route: Route,
        to: LatLon,
        stops: List<RoutePlanner.Stop>,
        nearby: List<Station>,
    ) {
        routeProgress = RouteProgress(route)
        navDestination = to
        navStops = stops
        navNearby = nearby
        navCorridor = RoutePlanner.corridor(RouteGeometry(route.geometry), nearby)
        spokenStepIndex = -1
        drawRoute(route.geometry)
        drawStops(stops)
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
        updateStopsStrip(snap.traveledM, snap.remainingM)

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
            // Пересчёт: от текущей точки через ещё не пройденные зарядки к цели
            val remaining = navStops.filter { it.alongM > snap.traveledM }
            val points = listOf(me) + remaining.map { LatLon(it.station.lat, it.station.lon) } + dest
            startRoute(points, dest, remaining.map { it.station }, navNearby, reroute = true)
            return
        }

        // Зарядка по пути: одно предупреждение заранее
        navStops.forEach { st ->
            val ahead = st.alongM - snap.traveledM
            if (ahead in 0.0..STOP_ANNOUNCE_M && announcedStops.add(st.station.id)) {
                speak(getString(R.string.stop_ahead, st.station.title))
            }
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
        navStops = emptyList()
        navNearby = emptyList()
        navCorridor = emptyList()
        announcedStops.clear()
        routeProgress = null
        headingFix = null
        mapRotationDeg = 0f
        map.setMapOrientation(0f)
        stopsList.removeAllViews()
        stopsStrip.visibility = View.GONE
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
        map.overlays.removeAll(stopMarkers)
        stopMarkers.clear()
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
        map.invalidate()
    }

    /**
     * Поворот карты: направление движения смотрит вверх экрана.
     * Берём курс от GPS, если он есть и скорость заметная; иначе считаем по двум последним точкам.
     * Поворот меняем только при заметном изменении угла, чтобы карта не дёргалась.
     */
    private fun updateHeading(loc: Location) {
        val here = LatLon(loc.latitude, loc.longitude)
        val prev = headingFix
        val moved = prev?.let { Geo.distanceM(it.lat, it.lon, here.lat, here.lon) } ?: Double.MAX_VALUE
        val gpsBearing = if (loc.hasBearing() && loc.speed >= MIN_SPEED_FOR_BEARING_MS) loc.bearing.toDouble() else null
        val bearing = gpsBearing
            ?: if (prev != null && moved >= MIN_MOVE_FOR_BEARING_M) {
                Geo.bearingDeg(prev.lat, prev.lon, here.lat, here.lon)
            } else {
                null
            }
        if (moved >= MIN_MOVE_FOR_BEARING_M) headingFix = here
        if (bearing == null) return

        // Карта повёрнута на -курс: направление движения оказывается сверху
        val target = Heading.mapRotation(bearing)
        if (Heading.angleDelta(target, mapRotationDeg) >= ROTATE_THRESHOLD_DEG) {
            mapRotationDeg = target
            map.setMapOrientation(target)
        }
    }

    /** Полоса слева: станции рядом с маршрутом впереди и расстояние до каждой, в конце — финиш. */
    private fun updateStopsStrip(traveledM: Double, remainingM: Double) {
        if (!navigating) {
            stopsStrip.visibility = View.GONE
            return
        }
        stopsStrip.visibility = View.VISIBLE
        stopsList.removeAllViews()
        val ahead = navCorridor.filter { it.alongM > traveledM + STRIP_PASSED_TOLERANCE_M }
        if (ahead.isEmpty()) {
            addStripText(getString(R.string.strip_none))
        }
        val plannedIds = navStops.map { it.station.id }.toSet()
        ahead.forEach { st ->
            addStripRow(
                distance = Instructions.distance(st.alongM - traveledM),
                title = st.station.title,
                status = st.station.status.label,
                station = st.station,
                planned = st.station.id in plannedIds,
            )
        }
        addStripRow(
            distance = Instructions.distance(remainingM),
            title = getString(R.string.strip_finish),
            status = null,
            station = null,
            planned = false,
        )
    }

    private fun addStripText(text: String) {
        stopsList.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(6), dp(6), dp(6), dp(6))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) },
        )
    }

    private fun addStripRow(
        distance: String,
        title: String,
        status: String?,
        station: Station?,
        planned: Boolean,
    ) {
        val row = TextView(this).apply {
            text = buildString {
                // ★ — остановка, выбранная планировщиком; ⚡ — просто станция рядом с маршрутом
                append(if (planned) "★ план · " else "⚡ ").append(distance).append('\n').append(title)
                if (status != null) append('\n').append(status)
            }
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(60, 255, 255, 255))
                cornerRadius = dp(8).toFloat()
            }
            station?.let { st -> setOnClickListener { showCard(st) } }
        }
        stopsList.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) },
        )
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    // ---------- Утилиты ----------

    private fun dot(color: Int, ring: Int): GradientDrawable = GradientDrawable().apply {
        setShape(GradientDrawable.OVAL)
        setColor(color)
        setStroke(dp(2), ring)
        setSize(dp(18), dp(18))
    }

    private fun colorOf(hex: String): Int =
        try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            Color.GRAY
        }

    private fun coords(p: LatLon): String = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
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
        private const val ROTATE_THRESHOLD_DEG = 3f
        private const val MIN_SPEED_FOR_BEARING_MS = 1.5f
        private const val MIN_MOVE_FOR_BEARING_M = 8.0
        private const val STRIP_PASSED_TOLERANCE_M = 50.0
        private const val STOP_ANNOUNCE_M = 2000.0
        private const val ARRIVED = Int.MAX_VALUE

        // Планирование
        private const val STATION_PAD_KM = 5.0
        private const val RESERVE_M = 50_000.0
        private const val DEFAULT_RANGE_KM = 300
        private const val MIN_RANGE_KM = 100
        private const val MAX_RANGE_KM = 1000
        private const val KEY_OCM = "ocm_api_key"
    }
}
