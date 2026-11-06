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
import androidx.core.content.ContextCompat
import by.charge.overlay.data.ChargerStatus
import by.charge.overlay.data.Geocoder
import by.charge.overlay.data.Networks
import by.charge.overlay.data.OcmAuth
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
import by.charge.overlay.nav.SpeedZoom
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
    // Главный экран приложения: карта OSM, станции, карточка станции, планирование маршрута A → B, навигация.
    // Разделы в файле помечены строками «// ---------- ... ----------». Состояние планирования и навигации хранится в полях класса.
    // Порядок действий пользователя: GPS → загрузка станций (loadStations) → карточка (showCard) → план (buildPlan) → навигация (applyRoute / updateNavigation).

    // Какая точка выбирается на карте долгим тапом: «Откуда» (FROM) или «Куда» (TO).
    private enum class PickTarget { FROM, TO }

    // Элементы интерфейса из activity_main.xml. Заполняются в onCreate через findViewById.
    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var chips: ChipGroup
    private lateinit var card: View
    private lateinit var cardTitle: TextView
    private lateinit var cardDetails: TextView
    private lateinit var btnRouteExternal: Button
    private lateinit var btnToggleStop: Button
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

    // scope — корутины экрана (загрузка, геокодирование, маршруты). Отменяется в onDestroy.
    // SupervisorJob: ошибка одной корутины не отменяет остальные. Dispatchers.Main.immediate: код выполняется в UI-потоке.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    // Отложенная загрузка станций. Несколько событий карты (прокрутка, зум) сливаются в одну загрузку через RELOAD_DELAY_MS.
    private val reloadRunnable = Runnable { loadStations() }
    private var loadJob: Job? = null
    // Флаг пересборки фишек сетей. Без него слушатель выбора сработает на каждое добавление фишки.
    private var chipsRebuilding = false

    // GPS: tracker создаётся после разрешения; myLocation — последняя точка; meMarker — синяя точка «я» на карте.
    private var tracker: LocationTracker? = null
    private var meMarker: Marker? = null
    private var myLocation: Location? = null
    private var firstFix = true

    // Параметры последней загрузки станций: центр, время, был ли успех и тексты ошибок.
    // По ним решаем, нужна ли новая загрузка (loadStations).
    private var lastLoadCenter: LatLon? = null
    private var lastLoadMs = 0L
    private var lastLoadOk = true
    private var loadErrors: List<String> = emptyList()

    // Станции для текущей области карты. visibleCount — сколько показано после фильтра по сети.
    private var stations: List<Station> = emptyList()
    private var visibleCount = 0
    private var selectedBrand: String? = null
    // Маркеры станций и маркеры остановок маршрута держим отдельно, чтобы очищать каждую группу независимо.
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
    // planJob — текущий расчёт маршрута (новый расчёт отменяет старый).
    // planRoute, planStops и planDestination — результат расчёта для кнопки «Начать навигацию».
    private var planJob: Job? = null
    private var planRoute: Route? = null
    private var planStops: List<RoutePlanner.Stop> = emptyList()
    private var planDestination: LatLon? = null

    // Встроенная навигация
    private var navigating = false
    private var navDestination: LatLon? = null
    // Текущий маршрут навигации (нужен, чтобы вставить станцию в путь по её положению) и пройденное расстояние
    private var activeRoute: Route? = null
    private var traveledM = 0.0
    private var navStops: List<RoutePlanner.Stop> = emptyList()
    // Станции рядом с маршрутом (для полосы) и их положение вдоль текущего маршрута
    private var navNearby: List<Station> = emptyList()
    private var navCorridor: List<RoutePlanner.Stop> = emptyList()
    // planNearby — станции вокруг маршрута, загруженные при расчёте. Из них строится полоса при старте навигации.
    private var planNearby: List<Station> = emptyList()
    // Остановки, о которых уже сказали голосом (по id станции), чтобы не повторять объявление.
    private val announcedStops = HashSet<String>()
    // Расчёт позиции на маршруте. Создаётся в applyRoute, обнуляется в stopNavigation.
    private var routeProgress: RouteProgress? = null

    // Поворот карты по движению: последняя точка, от которой считаем направление, и текущий угол карты
    private var headingFix: LatLon? = null
    private var mapRotationDeg = 0f
    // Масштаб карты по скорости во время навигации: сглаживает скорость и даёт целевой зум.
    private val speedZoom = SpeedZoom()
    // Линия маршрута на карте и задача перестроения маршрута (routeJob).
    // lastRerouteMs — антиспам перестроения. spokenStepIndex — номер манёвра, который уже объявлен голосом.
    private var routeLine: Polyline? = null
    private var routeJob: Job? = null
    private var lastRerouteMs = 0L
    private var spokenStepIndex = -1
    // Синтез речи для голосовых подсказок. Язык ru-RU задаётся после инициализации движка.
    private var tts: TextToSpeech? = null

    // Результат входа в OCM: если ключ сохранён, перезагружаем станции
    private val ocmLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            lastLoadCenter = null
            scheduleLoad()
        }
    }

    // Запрос разрешения на геолокацию. Результат обрабатывается в onPermissionResult.
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
        // Конфигурация osmdroid нужна до создания MapView (он читается из layout). userAgentValue обязателен по правилам OSM.
        // Кэш тайлов лежит в cacheDir: система может его очистить, это безопасно.
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
        btnToggleStop = findViewById(R.id.btn_toggle_stop)
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
        // Репозиторий станций. Ключ OCM читается лямбдой при каждой загрузке, поэтому новый ключ работает без перезапуска.
        repo = StationRepository(Networks.load(this)) { prefs.getString(OcmAuth.PREF_KEY, null) }

        // TTS инициализируется асинхронно. Если движок не запустился, речи не будет, а навигация работает.
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("ru", "RU"))
        }

        // Тайлы стандартного OSM (MAPNIK). Встроенные кнопки зума скрыты: масштаб жестами.
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        // Стартовый вид: центр Минска, пока нет GPS. Когда появится первая точка, карта перейдёт к ней (onLocation).
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(MINSK_LAT, MINSK_LON))

        // Тап по пустому месту закрывает карточку; долгий тап ставит точку «Откуда»/«Куда» или точку назначения
        // Слушатель событий карты: одиночный тап по пустому месту закрывает карточку, долгий тап — выбор точки (onMapLongPress).
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

        // Выбор сети: выбранная фишка задаёт selectedBrand (null — все сети), потом фильтр применяется заново.
        chips.setOnCheckedStateChangeListener { _, ids ->
            if (!chipsRebuilding) {
                selectedBrand = ids.firstOrNull()?.let { id -> chips.findViewById<Chip>(id)?.tag as String? }
                applyFilter()
            }
        }

        // Кнопка «моё местоположение»: центрирует карту на GPS-позиции, если она уже есть.
        fabMe.setOnClickListener {
            val loc = myLocation
            if (loc == null) {
                toast(R.string.waiting_gps)
            } else {
                map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            }
        }
        // Кнопка «Маршрут» в карточке: встроенная навигация до точки карточки (станции или точки на карте).
        findViewById<Button>(R.id.btn_nav).setOnClickListener {
            cardDestination?.let { beginNavigation(it) }
        }
        // Кнопка «Яндекс Навигатор»: открывает внешний маршрут до станции. Для точки на карте она скрыта.
        btnRouteExternal.setOnClickListener {
            val target = cardStation ?: return@setOnClickListener
            if (!NavigatorLauncher.openRoute(this, target.lat, target.lon)) {
                toast(R.string.navigator_missing)
            }
        }
        // Кнопка «Добавить в маршрут» / «Убрать из маршрута» в карточке станции
        btnToggleStop.setOnClickListener { cardStation?.let { editStops(it) } }
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
        // Адрес «Откуда»: поиск через Nominatim, пользователь выбирает место из списка.
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
        // «Построить маршрут»: маршрут A → B, станции вдоль него, остановки и перестроение через них (buildPlan).
        findViewById<Button>(R.id.btn_build).setOnClickListener { buildPlan() }
        // «Начать навигацию» по уже построенному плану. Новый запрос маршрута не делаем.
        btnPlanStart.setOnClickListener { startPlannedNavigation() }
        findViewById<Button>(R.id.btn_plan_clear).setOnClickListener { clearPlan() }
        // Меню «Ключ OCM». Открывается из нижней кнопки и из кнопки «Ключ OCM» в верхней панели.
        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btn_settings_top).setOnClickListener { showSettings() }
        updatePlanLabels()

        statusText.text = getString(R.string.loading)
        if (Permissions.hasLocation(this)) {
            startTracking()
        } else {
            // Разрешения нет — запрашиваем. Станции грузятся и без GPS, по центру карты.
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        scheduleLoad()
    }

    override fun onResume() {
    // osmdroid: MapView нужно уведомлять о жизненном цикле, иначе тайлы могут не обновляться.
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(reloadRunnable)
        // При закрытии экрана отменяем всё, что может обратиться к UI: отложенную загрузку, GPS, маршруты, речь.
        tracker?.stop()
        tracker = null
        routeJob?.cancel()
        planJob?.cancel()
        scope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        // Освобождаем ресурсы osmdroid.
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
        // Повторный вызов не создаёт второй трекер GPS.
        if (tracker != null) return
        tracker = LocationTracker(this) { onLocation(it) }.also { it.start() }
    }

    private fun onLocation(loc: Location) {
        myLocation = loc
        val p = GeoPoint(loc.latitude, loc.longitude)
        // Синяя точка «я»: создаётся один раз и дальше только двигается.
        var marker = meMarker
        if (marker == null) {
            marker = Marker(map)
            marker.icon = dot(Color.rgb(64, 160, 255), Color.WHITE)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            map.overlays.add(marker)
            meMarker = marker
        }
        // Во время навигации позицию задаёт updateNavigation: машина стоит на линии маршрута
        if (!navigating) marker.position = p
        // При первом фиксе центрируем карту на себе и загружаем станции вокруг.
        if (firstFix) {
            firstFix = false
            map.controller.setCenter(p)
            scheduleLoad()
        }
        // Во время навигации каждое обновление GPS: центр карты на машине, поворот по курсу, пересчёт позиции на маршруте.
        if (navigating) {
            // Во время навигации карта следует за машиной и поворачивается по движению
            map.controller.setCenter(p)
            updateHeading(loc)
            updateCameraZoom(loc)
            updateNavigation(LatLon(loc.latitude, loc.longitude))
        }
        map.invalidate()
    }

    // ---------- Станции ----------

    // Откладывает загрузку на RELOAD_DELAY_MS. Если загрузка уже ждёт, таймер перезапускается.
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

        // Запоминаем центр и время до окончания запроса: повторный вызов не запустит тот же запрос.
        lastLoadCenter = center
        lastLoadMs = now
        // Предыдущая загрузка (если ещё идёт) отменяется: нужен только результат для последнего центра карты.
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
        // Запоминаем выбранную сеть, чтобы после перезагрузки фильтр сохранился, если такая сеть ещё есть.
        val wanted = selectedBrand
        chipsRebuilding = true
        chips.removeAllViews()
        // Фишка «Все» всегда первая. Остальные сети сортируются по числу станций.
        val all = chip(getString(R.string.chip_all, stations.size), null)
        chips.addView(all)
        stations.groupBy { it.brandId }.values
            .sortedByDescending { it.size }
            .forEach { group ->
                val first = group.first()
                chips.addView(chip("${first.brandName} (${group.size})", first.brandId))
            }
        // Если выбранной сети уже нет, выбираем «Все».
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
        // Перерисовка маркеров станций с учётом фильтра по сети. Красная обводка — станция не работает.
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
        // Во время навигации и планирования строку статуса занимают эти режимы, счётчик станций туда не пишем.
        if (navigating || planPanel.visibility == View.VISIBLE) return
        val base = getString(R.string.status_count, visibleCount)
        statusText.text = if (loadErrors.isEmpty()) {
            base
        } else {
            "$base · ${getString(R.string.load_error, loadErrors.joinToString("; "))}"
        }
    }

    // ---------- Карточка ----------

    // Карточка станции: название, сеть, расстояние от меня, статус, макс. мощность, разъёмы. Карта центрируется на станции.
    private fun showCard(s: Station) {
        cardStation = s
        cardDestination = LatLon(s.lat, s.lon)
        cardTitle.text = s.title
        val lines = ArrayList<String>()
        // Заголовок: бренд и оператор (оператор показываем, только если он отличается от бренда).
        val header = listOfNotNull(s.brandName, s.operator?.takeIf { it != s.brandName }).joinToString(" · ")
        lines.add(header)
        // Расстояние от моей позиции. Без GPS эта строка не выводится.
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
        updateToggleButton(s)
    }

    // Карточка точки на карте (долгий тап): только координаты и кнопка «Маршрут». Кнопка Навигатора скрыта: у точки нет станции.
    private fun showCustomPoint(p: LatLon) {
        cardStation = null
        cardDestination = p
        cardTitle.text = getString(R.string.custom_point)
        cardDetails.text = coords(p)
        btnRouteExternal.visibility = View.GONE
        updateToggleButton(null)
        card.visibility = View.VISIBLE
    }

    private fun hideCard() {
        cardStation = null
        cardDestination = null
        card.visibility = View.GONE
    }

    // ---------- Планирование маршрута ----------

    // Открываем панель планирования: карточку закрываем, кнопку «моё местоположение» прячем, чтобы не мешала.
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

    // Режим выбора точки на карте: панель скрывается, следующий долгий тап поставит точку (onMapLongPress).
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

    // Подписи «Откуда» и «Куда». По умолчанию: «моё местоположение» и «не задано».
    private fun updatePlanLabels() {
        val fromText = planFromLabel ?: getString(R.string.plan_from_me_label)
        planFrom.text = getString(R.string.plan_from_label, fromText)
        val toText = planToLabel ?: getString(R.string.plan_not_set)
        planTo.text = getString(R.string.plan_to_label, toText)
    }

    // Диалог адреса: ввод → Nominatim в IO-потоке → список мест → callback с выбранным местом.
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
                    // Ошибка сети показываем тостом; пустой результат — отдельным сообщением.
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

    // Меню «Ключ OCM»: войти по логину и паролю, ввести ключ вручную или удалить сохранённый ключ.
    // Кнопка удаления видна только если ключ есть.
    private fun showSettings() {
        val hasKey = !prefs.getString(OcmAuth.PREF_KEY, null).isNullOrBlank()
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setMessage(if (hasKey) R.string.settings_key_set else R.string.settings_key_missing)
            .setPositiveButton(R.string.settings_login) { _, _ -> showLoginDialog() }
            .setNeutralButton(R.string.settings_manual) { _, _ -> showManualKeyDialog() }
        if (hasKey) {
            builder.setNegativeButton(R.string.settings_remove) { _, _ ->
                prefs.edit().remove(OcmAuth.PREF_KEY).apply()
                lastLoadCenter = null
                scheduleLoad()
                toast(R.string.settings_removed)
            }
        }
        builder.show()
    }

    /** E-mail и пароль вводятся здесь и сразу передаются в окно входа OCM. Приложение их не сохраняет. */
    private fun showLoginDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val emailInput = EditText(this).apply {
            hint = getString(R.string.ocm_email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passInput = EditText(this).apply {
            hint = getString(R.string.ocm_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(emailInput)
        container.addView(passInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.ocm_login_title)
            .setMessage(R.string.ocm_login_hint)
            .setView(container)
            .setPositiveButton(R.string.ocm_login_go) { _, _ ->
                // Адрес обрезаем пробелы; пароль не трогаем, пробелы в нём могут быть значимыми.
                val email = emailInput.text.toString().trim()
                val password = passInput.text.toString()
                if (email.isEmpty() || password.isEmpty()) {
                    toast(R.string.ocm_login_empty)
                    return@setPositiveButton
                }
                // Логин и пароль передаём в OcmLoginActivity через Intent. Там пароль очищается после отправки формы.
                // Результат придёт в ocmLauncher.
                ocmLauncher.launch(OcmLoginActivity.intent(this, email, password))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showManualKeyDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.settings_ocm_key)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString(OcmAuth.PREF_KEY, "") ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setMessage(R.string.settings_ocm_hint)
            .setView(input)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val key = input.text.toString().trim()
                // Пустая строка удаляет ключ (сохраняем null). После этого станции перезагружаются без OCM.
                prefs.edit().putString(OcmAuth.PREF_KEY, key.ifEmpty { null }).apply()
                // Сбрасываем кэш, чтобы станции перезагрузились с учётом ключа
                lastLoadCenter = null
                scheduleLoad()
                toast(R.string.settings_saved)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Диапазон в км из поля: некорректный ввод → DEFAULT_RANGE_KM; значения ограничиваются MIN_RANGE_KM..MAX_RANGE_KM.
    private fun rangeKm(): Int =
        rangeInput.text.toString().toIntOrNull()?.coerceIn(MIN_RANGE_KM, MAX_RANGE_KM) ?: DEFAULT_RANGE_KM

    private fun buildPlan() {
        // Нужны точка «Куда» и точка «Откуда». «Откуда» по умолчанию — текущая GPS-позиция.
        val to = planToPoint ?: run {
            toast(R.string.plan_need_to)
            return
        }
        val from = planFromPoint ?: myLocation?.let { LatLon(it.latitude, it.longitude) } ?: run {
            toast(R.string.plan_need_from)
            return
        }
        // Значение нормализуем и показываем в поле, чтобы было видно, что применилось.
        val rangeKm = rangeKm()
        rangeInput.setText(rangeKm.toString())

        planJob?.cancel()
        clearPlanPreview()
        btnPlanStart.visibility = View.GONE
        planResult.text = getString(R.string.plan_route_loading)
        planJob = scope.launch {
            try {
                // Шаг 1: маршрут A → B без остановок. Он нужен, чтобы найти станции вдоль пути.
                val base = Routing.fetch(listOf(from, to))

                planResult.text = getString(R.string.plan_stations_loading)
                // Шаг 2: станции в прямоугольнике вокруг маршрута с запасом STATION_PAD_KM.
                val loaded = repo.load(Box.of(base.geometry).padded(STATION_PAD_KM))

                // Шаг 3: выбор остановок по запасу хода. Если станции не хватает, текст предупреждения показываем в результате,
                // а маршрут строим без остановок.
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
                // Шаг 4: если остановки есть, строим маршрут через них, чтобы линия реально проходила через станции.
                val route = if (picked.isEmpty()) {
                    base
                } else {
                    val points = listOf(from) +
                        picked.map { LatLon(it.station.lat, it.station.lon) } + to
                    Routing.fetch(points)
                }
                // Шаг 5: положение остановок вдоль финального маршрута (он мог измениться после шага 4).
                val stops = RoutePlanner.attach(RouteGeometry(route.geometry), picked.map { it.station })

                // Сохраняем результат для кнопки «Начать навигацию» и рисуем его на карте.
                planRoute = route
                planStops = stops
                planNearby = loaded.stations
                planDestination = to
                drawRoute(route.geometry)
                drawStops(stops)
                // Масштаб карты подгоняем под весь маршрут.
                val bounds = Box.of(route.geometry)
                map.zoomToBoundingBox(
                    BoundingBox(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon),
                    true, 80,
                )
                showPlanResult(route, stops, stopWarning, loaded.errors, rangeKm)
                // Маршрут готов: кнопка «Начать навигацию» появляется только после успешного расчёта.
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
        // Текст результата: сводка, затем список остановок (расстояние от старта, статус, разъёмы).
        // Ошибки загрузки станций показываем здесь, но маршрут всё равно есть.
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
        // Предупреждение о недостающих зарядках — последним, чтобы его было видно.
        stopWarning?.let { lines.add(getString(R.string.plan_warning, it)) }
        if (stationErrors.isNotEmpty()) {
            lines.add(getString(R.string.plan_station_errors, stationErrors.joinToString("; ")))
        }
        planResult.text = lines.joinToString("\n")
    }

    private fun startPlannedNavigation() {
        val route = planRoute ?: return
        val to = planDestination ?: return
        // Переходим к навигации: панель планирования закрываем, маршрут уже построен.
        hidePlanPanel()
        // Запускаем навигацию по готовому плану без нового запроса к OSRM.
        applyRoute(route, to, planStops, planNearby)
    }

    private fun clearPlan() {
        planJob?.cancel()
        // Сброс плана: маршрут, точки, станции и кнопка «Начать». Если навигация уже идёт, она не затрагивается.
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

    // Убирает линию и остановки плана с карты. Во время навигации линию не трогаем: её рисует навигация.
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
            // Жёлтые точки — остановки маршрута. Тап открывает карточку станции.
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

    // Навигация от текущей позиции до точки карточки, без остановок (кнопка «Маршрут» в карточке).
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
        // При перестроении статус не меняем, чтобы текст не мигал.
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
                // Остановки, которые планировались, переводим в положение вдоль нового маршрута.
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
        // Новый маршрут: сбрасываем прогресс, голосовые метки и полосу станций.
        routeProgress = RouteProgress(route)
        activeRoute = route
        navDestination = to
        navStops = stops
        navNearby = nearby
        // Полоса: станции в коридоре 3 км вдоль нового маршрута. Они включают и не выбранные как остановки.
        navCorridor = RoutePlanner.corridor(RouteGeometry(route.geometry), nearby)
        spokenStepIndex = -1
        drawRoute(route.geometry)
        drawStops(stops)
        if (!navigating) {
            // Первый запуск навигации: показываем баннер и не даём экрану гаснуть.
            navigating = true
            setCarMode(true)
            speedZoom.reset()
            navBanner.visibility = View.VISIBLE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        myLocation?.let { updateNavigation(LatLon(it.latitude, it.longitude)) }
        map.invalidate()
    }

    private fun updateNavigation(me: LatLon) {
        // Главная функция навигации: вызывается при каждом обновлении GPS.
        val progress = routeProgress ?: return
        val snap = progress.update(me)
        // Машина на линии маршрута. Если сошли с маршрута, показываем реальную позицию, а не привязанную
        val carAt = if (snap.offRouteM > RouteProgress.OFF_ROUTE_M) me else snap.snapped
        meMarker?.position = GeoPoint(carAt.lat, carAt.lon)
        traveledM = snap.traveledM
        val now = SystemClock.elapsedRealtime()
        // Полоса станций обновляется при каждом обновлении позиции.
        updateStopsStrip(snap.traveledM, snap.remainingM)

        // Прибытие: одно голосовое объявление (метка ARRIVED), дальше обработка не нужна.
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
        // Сошли с маршрута: пересчитываем не чаще REROUTE_MIN_INTERVAL_MS и не параллельно с предыдущим пересчётом.
        // Пересчёт идёт от текущей точки через ещё не пройденные остановки.
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
        // Каждая остановка по пути объявляется голосом один раз, когда до неё осталось меньше STOP_ANNOUNCE_M (метка в announcedStops).
        navStops.forEach { st ->
            val ahead = st.alongM - snap.traveledM
            if (ahead in 0.0..STOP_ANNOUNCE_M && announcedStops.add(st.station.id)) {
                speak(getString(R.string.stop_ahead, st.station.title))
            }
        }

        // Текущий манёвр для баннера и голоса. Если манёвров больше нет, показываем «прибыли».
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
        // Голос: манёвр объявляется один раз, когда до него осталось меньше ANNOUNCE_M.
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
        // Выход из навигации: сбрасываем всё, что относилось к маршруту, и возвращаем карту «север вверх».
        navigating = false
        setCarMode(false)
        speedZoom.reset()
        navDestination = null
        navStops = emptyList()
        navNearby = emptyList()
        navCorridor = emptyList()
        announcedStops.clear()
        routeProgress = null
        activeRoute = null
        traveledM = 0.0
        headingFix = null
        mapRotationDeg = 0f
        // Карта снова смотрит на север.
        map.setMapOrientation(0f)
        stopsList.removeAllViews()
        stopsStrip.visibility = View.GONE
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null
        map.overlays.removeAll(stopMarkers)
        stopMarkers.clear()
        navBanner.visibility = View.GONE
        // Экран снова может гаснуть.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tts?.stop()
        map.invalidate()
        updateStatusText()
    }

    private fun drawRoute(points: List<LatLon>) {
        routeLine?.let { map.overlays.remove(it) }
        // Линия маршрута: синяя, толщина 6 dp.
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
        // moved — расстояние от опорной точки, по которой последний раз считали курс. Если прошли мало, курс по точкам не считаем: GPS шумит.
        val moved = prev?.let { Geo.distanceM(it.lat, it.lon, here.lat, here.lon) } ?: Double.MAX_VALUE
        // Курс от GPS надёжен только на скорости (MIN_SPEED_FOR_BEARING_MS). На стоянке курс прыгает.
        val gpsBearing = if (loc.hasBearing() && loc.speed >= MIN_SPEED_FOR_BEARING_MS) loc.bearing.toDouble() else null
        // Если курса от GPS нет, считаем направление по двум опорным точкам, если прошли достаточно.
        val bearing = gpsBearing
            ?: if (prev != null && moved >= MIN_MOVE_FOR_BEARING_M) {
                Geo.bearingDeg(prev.lat, prev.lon, here.lat, here.lon)
            } else {
                null
            }
        // Новая опорная точка — когда прошли MIN_MOVE_FOR_BEARING_M.
        if (moved >= MIN_MOVE_FOR_BEARING_M) headingFix = here
        if (bearing == null) return

        // Карта повёрнута на -курс: направление движения оказывается сверху
        val target = Heading.mapRotation(bearing)
        // Порог ROTATE_THRESHOLD_DEG: карта не дёргается при мелких изменениях курса.
        if (Heading.angleDelta(target, mapRotationDeg) >= ROTATE_THRESHOLD_DEG) {
            mapRotationDeg = target
            map.setMapOrientation(target)
        }
    }

    /**
     * Масштаб по скорости: медленно — ближе, на трассе — дальше. Зум меняем плавной анимацией
     * и только при заметной разнице (SpeedZoom.MIN_CHANGE), чтобы карта не дёргалась.
     */
    private fun updateCameraZoom(loc: Location) {
        val target = speedZoom.target(if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
        if (SpeedZoom.shouldChange(map.zoomLevelDouble, target)) {
            map.controller.zoomTo(target, SpeedZoom.ANIM_MS)
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
        // Впереди — станции полосы, которые ещё не проехали. Станции ближе STRIP_PASSED_TOLERANCE_M считаем пройденными.
        val ahead = navCorridor.filter { it.alongM > traveledM + STRIP_PASSED_TOLERANCE_M }
        // Если впереди станций нет, пишем явный текст, а не оставляем полосу пустой.
        if (ahead.isEmpty()) {
            addStripText(getString(R.string.strip_none))
        }
        // ★ — остановки, выбранные планировщиком. Остальные станции рядом с маршрутом помечаются ⚡.
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
            // Последняя строка полосы: финиш с оставшимся расстоянием. Карточки у финиша нет.
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
        // Строка полосы: расстояние до станции, название, статус. Тап открывает карточку станции.
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

    // Голос: QUEUE_FLUSH — новая фраза прерывает старую. Важнее свежести подсказки, чем очередь.
    // ---------- Остановки в маршруте ----------

    /** Остановки текущего режима: во время навигации — навигационные, иначе — планируемые. */
    private fun currentStops(): List<RoutePlanner.Stop> = if (navigating) navStops else planStops

    /**
     * Кнопка в карточке: «Добавить в маршрут», если станции нет в маршруте, иначе «Убрать из маршрута».
     * Скрыта, если маршрута нет или карточка точки на карте (null).
     */
    private fun updateToggleButton(s: Station?) {
        val hasRoute = if (navigating) activeRoute != null else planRoute != null
        if (s == null || !hasRoute) {
            btnToggleStop.visibility = View.GONE
            return
        }
        val inRoute = currentStops().any { it.station.id == s.id }
        btnToggleStop.text = getString(if (inRoute) R.string.stop_remove else R.string.stop_add)
        btnToggleStop.visibility = View.VISIBLE
    }

    /**
     * Добавляет станцию в маршрут или убирает её. Маршрут строится заново через все остановки впереди
     * в порядке пути. Станцию можно добавить, только если она работает или статус неизвестен,
     * и только если она рядом с маршрутом и ещё не пройдена.
     */
    private fun editStops(station: Station) {
        val route = (if (navigating) activeRoute else planRoute) ?: run {
            toast(R.string.stop_need_route)
            return
        }
        val dest = (if (navigating) navDestination else planDestination) ?: return
        val geo = RouteGeometry(route.geometry)
        // Позиция, от которой считаем «впереди»: во время навигации — текущая, при планировании — старт
        val pos = if (navigating) traveledM else 0.0
        val current = currentStops()
        val isRemoval = current.any { it.station.id == station.id }

        // Список остановок после изменения, отсортированный по порядку пути
        val updated: List<RoutePlanner.Stop> = if (isRemoval) {
            current.filter { it.station.id != station.id }
        } else {
            if (station.status == ChargerStatus.BROKEN || station.status == ChargerStatus.DISUSED) {
                toast(R.string.stop_bad_status)
                return
            }
            val placed = RoutePlanner.attach(geo, listOf(station)).first()
            if (placed.offM > RoutePlanner.MAX_DETOUR_M) {
                toast(R.string.stop_too_far)
                return
            }
            if (placed.alongM <= pos) {
                toast(R.string.stop_behind)
                return
            }
            (current + placed).sortedBy { it.alongM }
        }
        val ahead = updated.filter { it.alongM > pos }

        // Проверка запаса хода: самый длинный перегон без зарядки (от старта, между остановками и до финиша)
        val usableM = rangeKm() * 1000.0 - RESERVE_M
        val gapM = RoutePlanner.longestGapM(listOf(pos) + ahead.map { it.alongM } + geo.totalM)
        val warning = if (gapM > usableM) getString(R.string.stop_gap_warning, Instructions.distance(gapM)) else null

        // Точка старта: во время навигации — текущая позиция, при планировании — «Откуда»
        val me = myLocation?.let { LatLon(it.latitude, it.longitude) }
        val start = if (navigating) me else (planFromPoint ?: me)
        if (start == null) {
            toast(if (navigating) R.string.nav_need_location else R.string.plan_need_from)
            return
        }

        routeJob?.cancel()
        routeJob = scope.launch {
            try {
                val points = listOf(start) + ahead.map { LatLon(it.station.lat, it.station.lon) } + dest
                val newRoute = Routing.fetch(points)
                val stops = RoutePlanner.attach(RouteGeometry(newRoute.geometry), ahead.map { it.station })
                if (navigating) {
                    applyRoute(newRoute, dest, stops, navNearby)
                    warning?.let { toast(getString(R.string.plan_warning, it)) }
                } else {
                    planRoute = newRoute
                    planStops = stops
                    drawRoute(newRoute.geometry)
                    drawStops(stops)
                    showPlanResult(newRoute, stops, warning, emptyList(), rangeKm())
                }
                updateToggleButton(cardStation)
                toast(if (isRemoval) R.string.stop_removed else R.string.stop_added)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.stop_edit_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /**
     * Значок «я» на карте: во время навигации — машина, иначе — синяя точка.
     * Маркер обычный (не flat), поэтому на экране значок всегда стоит прямо, а карта повёрнута по движению.
     */
    private fun setCarMode(on: Boolean) {
        val m = meMarker ?: return
        m.icon = if (on) {
            ContextCompat.getDrawable(this, R.drawable.ic_car_marker)
        } else {
            dot(Color.rgb(64, 160, 255), Color.WHITE)
        }
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        // При выходе возвращаем точку на последнюю известную позицию GPS, пока не пришёл новый фикс
        if (!on) myLocation?.let { m.position = GeoPoint(it.latitude, it.longitude) }
        map.invalidate()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    // ---------- Утилиты ----------

    // Круглый маркер заданного цвета с обводкой (ring). Цвет маркера — цвет бренда.
    private fun dot(color: Int, ring: Int): GradientDrawable = GradientDrawable().apply {
        setShape(GradientDrawable.OVAL)
        setColor(color)
        setStroke(dp(2), ring)
        setSize(dp(18), dp(18))
    }

    // Цвет бренда из networks.json. Некорректный HEX — серый.
    private fun colorOf(hex: String): Int =
        try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            Color.GRAY
        }

    // Координаты для показа: 5 знаков после запятой, это около 1 метра.
    private fun coords(p: LatLon): String = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)

    // Перевод dp в пиксели по плотности экрана.
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // Короткие уведомления: версия для строкового ресурса и версия для готового текста.
    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        // Центр Минска — стартовая точка, пока нет GPS
        // Центр Минска — стартовая точка карты, пока нет GPS.
        private const val MINSK_LAT = 53.9045
        private const val MINSK_LON = 27.5615
        // Радиус загрузки станций вокруг центра карты, км. Решение пользователя (README и этот код): 15 км.
        // Коммит 5a9e02d ставил 5 км: это расхождение пользователю ещё не подтверждено (см. AGENTS.md).
        private const val SEARCH_RADIUS_KM = 15.0
        // Пороги перезагрузки станций: RELOAD_DELAY_MS — задержка после движения карты; RELOAD_MOVE_M — на сколько сдвинуть центр;
        // RELOAD_AGE_MS — через сколько данные считаются старыми; RETRY_AFTER_ERROR_MS — повтор после ошибки. См. loadStations.
        private const val RELOAD_DELAY_MS = 700L
        private const val RELOAD_MOVE_M = 1500.0
        private const val RELOAD_AGE_MS = 10 * 60 * 1000L
        private const val RETRY_AFTER_ERROR_MS = 30 * 1000L
        // Пороги навигации: REROUTE_MIN_INTERVAL_MS — пауза между перестроениями; ANNOUNCE_M — когда объявлять манёвр;
        // STOP_ANNOUNCE_M — когда объявлять зарядку по пути.
        private const val REROUTE_MIN_INTERVAL_MS = 15 * 1000L
        private const val ANNOUNCE_M = 300.0
        // Пороги поворота карты (см. updateHeading): ROTATE_THRESHOLD_DEG — минимальное изменение угла,
        // MIN_SPEED_FOR_BEARING_MS — минимальная скорость для курса от GPS, MIN_MOVE_FOR_BEARING_M — минимальный путь для курса по точкам.
        private const val ROTATE_THRESHOLD_DEG = 3f
        private const val MIN_SPEED_FOR_BEARING_MS = 1.5f
        private const val MIN_MOVE_FOR_BEARING_M = 8.0
        private const val STRIP_PASSED_TOLERANCE_M = 50.0
        private const val STOP_ANNOUNCE_M = 2000.0
        // Метка «прибыли» для spokenStepIndex. Int.MAX_VALUE, чтобы не совпасть с номером реального манёвра.
        private const val ARRIVED = Int.MAX_VALUE

        // Планирование
        // Планирование: STATION_PAD_KM — запас вокруг маршрута при поиске станций; RESERVE_M — резерв батареи в конце пути (50 км).
        private const val STATION_PAD_KM = 5.0
        private const val RESERVE_M = 50_000.0
        // Запас хода по умолчанию 300 км. Допустимые значения в поле: 100..1000 км.
        private const val DEFAULT_RANGE_KM = 300
        private const val MIN_RANGE_KM = 100
        private const val MAX_RANGE_KM = 1000
    }
}
