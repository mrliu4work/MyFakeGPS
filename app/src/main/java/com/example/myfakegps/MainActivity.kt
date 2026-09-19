package com.example.myfakegps

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import java.util.UUID
import kotlin.math.cos

data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val name: String = ""
)

data class SavedRoute(
    val id: String,
    val name: String,
    val type: String, // "ROAD" or "DIRECT"
    val points: List<RoutePoint>
)

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var editSearch: EditText
    private lateinit var editDistance: EditText
    private lateinit var editSpeed: EditText
    private lateinit var seekSpeed: SeekBar
    private lateinit var textStatus: TextView

    // 雙頁籤控制項
    private lateinit var btnTabSingle: Button
    private lateinit var btnTabPatrol: Button
    private lateinit var layoutTabSingle: LinearLayout
    private lateinit var layoutTabPatrol: LinearLayout
    private var isPatrolTabActive = false

    // Tab 1: 單點與漫步控制項
    private lateinit var btnToggleMock: Button
    private lateinit var btnToggleWander: Button
    private lateinit var btnClearMapTap: Button
    private lateinit var btnRecenter: Button
    private lateinit var btnSettings: Button

    private lateinit var btnSpeed15: Button
    private lateinit var btnSpeed30: Button
    private lateinit var btnSpeed45: Button

    // Tab 2: 巡航控制項
    private lateinit var spinnerRoutes: Spinner
    private lateinit var btnSaveRoute: Button
    private lateinit var btnDeleteRoute: Button
    private lateinit var rgPatrolType: RadioGroup
    private lateinit var rbRoad: RadioButton
    private lateinit var rbDirect: RadioButton
    private lateinit var btnPickOnMap: Button
    private lateinit var btnClearWaypoints: Button
    private lateinit var textPatrolPoints: TextView
    private lateinit var btnTogglePatrol: Button

    private val currentPatrolPoints = mutableListOf<RoutePoint>()
    private val savedRoutes = mutableListOf<SavedRoute>()
    private val patrolMarkers = mutableListOf<Marker>()
    private var patrolPolylineOverlay: Polyline? = null
    private var isPickingWaypoints = true
    private var isPatrolRunning = false

    private var currentLat: Double = 25.0339
    private var currentLng: Double = 121.5640
    private var targetLat: Double? = null
    private var targetLng: Double? = null
    private var targetName: String = ""

    private var isMockingOn = false
    private var isWanderingOn = false

    private var currentMarker: Marker? = null
    private var targetMarker: Marker? = null

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MockLocationService.ACTION_LOCATION_UPDATED -> {
                    val lat = intent.getDoubleExtra(MockLocationService.EXTRA_LAT, currentLat)
                    val lng = intent.getDoubleExtra(MockLocationService.EXTRA_LNG, currentLng)
                    val mode = intent.getStringExtra(MockLocationService.EXTRA_MODE) ?: MockLocationService.MODE_FIXED
                    currentLat = lat
                    currentLng = lng
                    isMockingOn = true
                    isWanderingOn = (mode == MockLocationService.MODE_RANDOM || mode == MockLocationService.MODE_NAV)
                    isPatrolRunning = (mode == MockLocationService.MODE_PATROL)
                    updateUIState(mode)
                    updateMapAndStatus("模擬中 ($mode)", shouldCenter = false)
                }
                MockLocationService.ACTION_NAV_FINISHED -> {
                    Toast.makeText(this@MainActivity, "🏁 抵達目的地，導航結束！", Toast.LENGTH_LONG).show()
                    isWanderingOn = false
                    updateUIState(MockLocationService.MODE_FIXED)
                    clearTargetMarker()
                }
                MockLocationService.ACTION_PATROL_ERROR -> {
                    val errMsg = intent.getStringExtra(MockLocationService.EXTRA_ERROR_MSG) ?: "巡航規劃失敗"
                    Toast.makeText(this@MainActivity, "❌ $errMsg", Toast.LENGTH_LONG).show()
                    isPatrolRunning = false
                    isMockingOn = false
                    updateUIState(MockLocationService.MODE_FIXED)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = "GPSDebuggerApp/1.0 (Android; com.example.myfakegps)"
        setContentView(R.layout.activity_main)

        // 綁定共用元件
        editSearch = findViewById(R.id.editSearch)
        editDistance = findViewById(R.id.editDistance)
        editSpeed = findViewById(R.id.editSpeed)
        seekSpeed = findViewById(R.id.seekSpeed)
        textStatus = findViewById(R.id.textStatus)

        btnTabSingle = findViewById(R.id.btnTabSingle)
        btnTabPatrol = findViewById(R.id.btnTabPatrol)
        layoutTabSingle = findViewById(R.id.layoutTabSingle)
        layoutTabPatrol = findViewById(R.id.layoutTabPatrol)

        // Tab 1 元件
        btnToggleMock = findViewById(R.id.btnToggleMock)
        btnToggleWander = findViewById(R.id.btnToggleWander)
        btnClearMapTap = findViewById(R.id.btnClearMapTap)
        btnRecenter = findViewById(R.id.btnRecenter)
        btnSettings = findViewById(R.id.btnSettings)

        btnSpeed15 = findViewById(R.id.btnSpeed15)
        btnSpeed30 = findViewById(R.id.btnSpeed30)
        btnSpeed45 = findViewById(R.id.btnSpeed45)

        val btnSet = findViewById<Button>(R.id.btnSetLocation)
        val btnNavSearch = findViewById<Button>(R.id.btnNavToSearch)
        val btnPaste = findViewById<Button>(R.id.btnPaste)

        val btnNorth = findViewById<Button>(R.id.btnNorth)
        val btnSouth = findViewById<Button>(R.id.btnSouth)
        val btnEast = findViewById<Button>(R.id.btnEast)
        val btnWest = findViewById<Button>(R.id.btnWest)

        // Tab 2 巡航元件
        spinnerRoutes = findViewById(R.id.spinnerRoutes)
        btnSaveRoute = findViewById(R.id.btnSaveRoute)
        btnDeleteRoute = findViewById(R.id.btnDeleteRoute)
        rgPatrolType = findViewById(R.id.rgPatrolType)
        rbRoad = findViewById(R.id.rbRoad)
        rbDirect = findViewById(R.id.rbDirect)
        btnPickOnMap = findViewById(R.id.btnPickOnMap)
        btnClearWaypoints = findViewById(R.id.btnClearWaypoints)
        textPatrolPoints = findViewById(R.id.textPatrolPoints)
        btnTogglePatrol = findViewById(R.id.btnTogglePatrol)

        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)

        setupMapEventsOverlay()
        setupSpeedControls()
        setupTabs()
        setupPatrolControls()

        editSearch.setOnClickListener { editSearch.selectAll() }

        btnSettings.setOnClickListener {
            showSettingsMenuDialog()
        }

        btnRecenter.setOnClickListener {
            mapView.controller.animateTo(GeoPoint(currentLat, currentLng))
            Toast.makeText(this, "🎯 已對準當前位置", Toast.LENGTH_SHORT).show()
        }

        btnClearMapTap.setOnClickListener {
            clearTargetMarker()
            Toast.makeText(this, "已清除地圖目標點", Toast.LENGTH_SHORT).show()
        }

        btnPaste.setOnClickListener {
            val text = getClipboardText()
            if (!text.isNullOrEmpty()) {
                editSearch.setText(text)
                editSearch.selectAll()
                Toast.makeText(this, "已貼上剪貼簿內容", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪貼簿為空", Toast.LENGTH_SHORT).show()
            }
        }

        // 10 個快捷地點設定
        setupFavorite(findViewById(R.id.btnFav1), "fav_1", "home", "2G25+6H 雙和里 新北市永和區")
        setupFavorite(findViewById(R.id.btnFav2), "fav_2", "禾豐特區", "WGM6+G6R 華城里 新北市新店區")
        setupFavorite(findViewById(R.id.btnFav3), "fav_3", "寶高", "G27V+RCM 布達佩斯 匈牙利")
        setupFavorite(findViewById(R.id.btnFav4), "fav_4", "明信片", "2CHG+WGF 福營里 新北市新莊區")
        setupFavorite(findViewById(R.id.btnFav5), "fav_5", "匈牙利", "G27V+RCM 布達佩斯 匈牙利")
        setupFavorite(findViewById(R.id.btnFav6), "fav_6", "東京迪士尼", "35.632012, 139.880880")
        setupFavorite(findViewById(R.id.btnFav7), "fav_7", "台北 101", "台北 101")
        setupFavorite(findViewById(R.id.btnFav8), "fav_8", "(空)", "")
        setupFavorite(findViewById(R.id.btnFav9), "fav_9", "(空)", "")
        setupFavorite(findViewById(R.id.btnFav10), "fav_10", "(空)", "")

        // 速度快捷鍵切換 (15 / 30 / 45 km/h)
        btnSpeed15.setOnClickListener { setSpeedValue(15) }
        btnSpeed30.setOnClickListener { setSpeedValue(30) }
        btnSpeed45.setOnClickListener { setSpeedValue(45) }

        btnToggleMock.setOnClickListener {
            if (isMockingOn) {
                MockLocationService.stop(this)
                isMockingOn = false
                isWanderingOn = false
                isPatrolRunning = false
                updateUIState(MockLocationService.MODE_FIXED)
                textStatus.text = "狀態：已關閉模擬定位"
                Toast.makeText(this, "已關閉模擬定位", Toast.LENGTH_SHORT).show()
            } else {
                MockLocationService.startFixed(this, currentLat, currentLng, "自訂點")
                isMockingOn = true
                updateUIState(MockLocationService.MODE_FIXED)
                Toast.makeText(this, "▶ 已開啟定點模擬定位", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleWander.setOnClickListener {
            if (isWanderingOn) {
                MockLocationService.startFixed(this, currentLat, currentLng, "漫步停止點")
                isWanderingOn = false
                updateUIState(MockLocationService.MODE_FIXED)
                Toast.makeText(this, "已停止漫步，定點鎖定在當前位置", Toast.LENGTH_SHORT).show()
            } else {
                val speed = getSpeed()
                MockLocationService.startRandom(this, currentLat, currentLng, speed)
                isMockingOn = true
                isWanderingOn = true
                updateUIState(MockLocationService.MODE_RANDOM)
                Toast.makeText(this, "🎲 已開始沿路隨機漫步！", Toast.LENGTH_SHORT).show()
            }
        }

        btnSet.setOnClickListener {
            val inputText = editSearch.text.toString().trim()
            performLocationSearch(inputText, isNav = false)
        }

        btnNavSearch.setOnClickListener {
            val inputText = editSearch.text.toString().trim()
            performLocationSearch(inputText, isNav = true)
        }

        btnNorth.setOnClickListener { moveLocation(0.0, getDistanceStep()) }
        btnSouth.setOnClickListener { moveLocation(0.0, -getDistanceStep()) }
        btnEast.setOnClickListener { moveLocation(getDistanceStep(), 0.0) }
        btnWest.setOnClickListener { moveLocation(-getDistanceStep(), 0.0) }
    }

    private fun setupTabs() {
        btnTabSingle.setOnClickListener {
            isPatrolTabActive = false
            layoutTabSingle.visibility = View.VISIBLE
            layoutTabPatrol.visibility = View.GONE
            btnTabSingle.setBackgroundColor(Color.parseColor("#1976D2"))
            btnTabSingle.setTextColor(Color.WHITE)
            btnTabPatrol.setBackgroundColor(Color.parseColor("#E0E0E0"))
            btnTabPatrol.setTextColor(Color.parseColor("#333333"))
        }

        btnTabPatrol.setOnClickListener {
            isPatrolTabActive = true
            layoutTabSingle.visibility = View.GONE
            layoutTabPatrol.visibility = View.VISIBLE
            btnTabPatrol.setBackgroundColor(Color.parseColor("#1976D2"))
            btnTabPatrol.setTextColor(Color.WHITE)
            btnTabSingle.setBackgroundColor(Color.parseColor("#E0E0E0"))
            btnTabSingle.setTextColor(Color.parseColor("#333333"))
            refreshPatrolMapOverlays()
        }
    }

    private fun setupPatrolControls() {
        loadSavedRoutesFromStorage()
        updateRoutesSpinner()

        btnPickOnMap.setOnClickListener {
            isPickingWaypoints = !isPickingWaypoints
            updatePickButtonState()
        }

        btnClearWaypoints.setOnClickListener {
            currentPatrolPoints.clear()
            refreshPatrolMapOverlays()
            updatePatrolStatusText()
            Toast.makeText(this, "已清空巡航點位", Toast.LENGTH_SHORT).show()
        }

        btnSaveRoute.setOnClickListener {
            if (currentPatrolPoints.size < 2) {
                Toast.makeText(this, "請至少在地圖上選取 2 個點再儲存！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showSaveRouteDialog()
        }

        btnDeleteRoute.setOnClickListener {
            val selectedPos = spinnerRoutes.selectedItemPosition
            if (selectedPos >= 0 && selectedPos < savedRoutes.size) {
                val routeToDelete = savedRoutes[selectedPos]
                AlertDialog.Builder(this)
                    .setTitle("🗑️ 刪除路線")
                    .setMessage("確定要刪除路線「${routeToDelete.name}」嗎？")
                    .setPositiveButton("刪除") { _, _ ->
                        savedRoutes.removeAt(selectedPos)
                        saveRoutesToStorage()
                        updateRoutesSpinner()
                        currentPatrolPoints.clear()
                        refreshPatrolMapOverlays()
                        updatePatrolStatusText()
                        Toast.makeText(this, "已刪除路線", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                Toast.makeText(this, "目前選取的不是已儲存路線", Toast.LENGTH_SHORT).show()
            }
        }

        btnTogglePatrol.setOnClickListener {
            if (isPatrolRunning) {
                MockLocationService.stop(this)
                isPatrolRunning = false
                isMockingOn = false
                updateUIState(MockLocationService.MODE_FIXED)
                Toast.makeText(this, "⏹ 已停止循環巡航", Toast.LENGTH_SHORT).show()
            } else {
                if (currentPatrolPoints.size < 2) {
                    Toast.makeText(this, "巡航至少需要 2 個點位！請點擊地圖加入點位。", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val type = if (rbRoad.isChecked) MockLocationService.PATROL_TYPE_ROAD else MockLocationService.PATROL_TYPE_DIRECT
                val speed = getSpeed()
                val lats = currentPatrolPoints.map { it.lat }.toDoubleArray()
                val lngs = currentPatrolPoints.map { it.lng }.toDoubleArray()

                val selectedPos = spinnerRoutes.selectedItemPosition
                val routeName = if (selectedPos in 0 until savedRoutes.size) savedRoutes[selectedPos].name else "動態巡航"

                MockLocationService.startPatrol(this, lats, lngs, type, speed, routeName)
                isPatrolRunning = true
                isMockingOn = true
                updateUIState(MockLocationService.MODE_PATROL)
                Toast.makeText(this, "▶ 開始循環巡航 (${if (type == MockLocationService.PATROL_TYPE_ROAD) "道路" else "直線"})！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePickButtonState() {
        if (isPickingWaypoints) {
            btnPickOnMap.text = "📍 選點 (開)"
            btnPickOnMap.setBackgroundColor(Color.parseColor("#4CAF50"))
        } else {
            btnPickOnMap.text = "📍 選點 (關)"
            btnPickOnMap.setBackgroundColor(Color.parseColor("#757575"))
        }
    }

    private fun updatePatrolStatusText() {
        val count = currentPatrolPoints.size
        textPatrolPoints.text = "已選點位: $count 個 ${if (count >= 2) "(閉環循環中)" else "(至少需 2 點)"}"
    }

    private fun refreshPatrolMapOverlays() {
        for (m in patrolMarkers) {
            mapView.overlays.remove(m)
        }
        patrolMarkers.clear()

        patrolPolylineOverlay?.let {
            mapView.overlays.remove(it)
            patrolPolylineOverlay = null
        }

        for ((index, pt) in currentPatrolPoints.withIndex()) {
            val marker = Marker(mapView).apply {
                position = GeoPoint(pt.lat, pt.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = if (pt.name.isNotEmpty()) pt.name else "巡航點 #${index + 1}"
            }
            patrolMarkers.add(marker)
            mapView.overlays.add(marker)
        }

        if (currentPatrolPoints.size >= 2) {
            val loopPoints = currentPatrolPoints.map { GeoPoint(it.lat, it.lng) }.toMutableList()
            loopPoints.add(GeoPoint(currentPatrolPoints[0].lat, currentPatrolPoints[0].lng)) // 閉環連線

            val polyline = Polyline(mapView).apply {
                setPoints(loopPoints)
                outlinePaint.color = if (rbRoad.isChecked) Color.parseColor("#1565C0") else Color.parseColor("#E65100")
                outlinePaint.strokeWidth = 7f
            }
            patrolPolylineOverlay = polyline
            mapView.overlays.add(0, polyline)
        }

        mapView.invalidate()
    }

    private fun loadSavedRoutesFromStorage() {
        savedRoutes.clear()
        val prefs = getSharedPreferences("GPSDebuggerRoutes", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("routes_list", null)

        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("id", UUID.randomUUID().toString())
                    val name = obj.getString("name")
                    val type = obj.optString("type", MockLocationService.PATROL_TYPE_ROAD)
                    val ptsArray = obj.getJSONArray("points")
                    val pts = mutableListOf<RoutePoint>()
                    for (j in 0 until ptsArray.length()) {
                        val pObj = ptsArray.getJSONObject(j)
                        pts.add(RoutePoint(pObj.getDouble("lat"), pObj.getDouble("lng"), pObj.optString("name", "")))
                    }
                    savedRoutes.add(SavedRoute(id, name, type, pts))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 若無任何存檔，提供一條預設示範路線
        if (savedRoutes.isEmpty()) {
            savedRoutes.add(
                SavedRoute(
                    id = "sample_route_1",
                    name = "永和雙和里巡迴",
                    type = MockLocationService.PATROL_TYPE_ROAD,
                    points = listOf(
                        RoutePoint(25.0081, 121.5123, "雙和里1"),
                        RoutePoint(25.0125, 121.5160, "雙和里2"),
                        RoutePoint(25.0070, 121.5200, "雙和里3")
                    )
                )
            )
            saveRoutesToStorage()
        }
    }

    private fun saveRoutesToStorage() {
        val array = JSONArray()
        for (r in savedRoutes) {
            val obj = JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("type", r.type)
                val ptsArray = JSONArray()
                for (p in r.points) {
                    val pObj = JSONObject().apply {
                        put("lat", p.lat)
                        put("lng", p.lng)
                        put("name", p.name)
                    }
                    ptsArray.put(pObj)
                }
                put("points", ptsArray)
            }
            array.put(obj)
        }
        getSharedPreferences("GPSDebuggerRoutes", Context.MODE_PRIVATE)
            .edit()
            .putString("routes_list", array.toString())
            .apply()
    }

    private fun updateRoutesSpinner() {
        val items = savedRoutes.map { it.name }.toMutableList()
        items.add("➕ [新建自訂路線]")

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinnerRoutes.adapter = adapter

        spinnerRoutes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in 0 until savedRoutes.size) {
                    val selected = savedRoutes[position]
                    currentPatrolPoints.clear()
                    currentPatrolPoints.addAll(selected.points)
                    if (selected.type == MockLocationService.PATROL_TYPE_DIRECT) {
                        rbDirect.isChecked = true
                    } else {
                        rbRoad.isChecked = true
                    }
                    refreshPatrolMapOverlays()
                    updatePatrolStatusText()
                    if (currentPatrolPoints.isNotEmpty()) {
                        mapView.controller.animateTo(GeoPoint(currentPatrolPoints[0].lat, currentPatrolPoints[0].lng))
                    }
                } else {
                    currentPatrolPoints.clear()
                    refreshPatrolMapOverlays()
                    updatePatrolStatusText()
                    isPickingWaypoints = true
                    updatePickButtonState()
                    Toast.makeText(this@MainActivity, "請點擊地圖加入巡航點位", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showSaveRouteDialog() {
        val input = EditText(this).apply {
            hint = "請輸入路線名稱 (例如: 公司周邊巡航)"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("💾 儲存巡航路線")
            .setView(layout)
            .setPositiveButton("儲存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val type = if (rbRoad.isChecked) MockLocationService.PATROL_TYPE_ROAD else MockLocationService.PATROL_TYPE_DIRECT
                    val newRoute = SavedRoute(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        type = type,
                        points = currentPatrolPoints.toList()
                    )
                    savedRoutes.add(newRoute)
                    saveRoutesToStorage()
                    updateRoutesSpinner()
                    spinnerRoutes.setSelection(savedRoutes.size - 1)
                    Toast.makeText(this, "已成功儲存路線: $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "路線名稱不能為空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setSpeedValue(spd: Int) {
        editSpeed.setText(spd.toString())
        seekSpeed.progress = spd.coerceIn(1, 50)
        if (isMockingOn) {
            MockLocationService.updateSpeed(this, spd.toDouble())
        }
    }

    private fun showSettingsMenuDialog() {
        val options = arrayOf(
            "🛠️ 選取模擬位置設定 (開發者選項)",
            "🔋 應用程式電池用量",
            "📋 複製當前經緯度 (%.6f, %.6f)".format(currentLat, currentLng)
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ GPS Debugger 設定選單")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(this, "無法開啟開發者選項: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        } catch (e: Exception) {
                            Toast.makeText(this, "無法開啟應用程式資訊: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> {
                        val coordsStr = String.format(Locale.US, "%.6f, %.6f", currentLat, currentLng)
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Coordinates", coordsStr)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "📋 已複製經緯度: $coordsStr", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun clearTargetMarker() {
        if (targetMarker != null) {
            mapView.overlays.remove(targetMarker)
            targetMarker = null
        }
        targetLat = null
        targetLng = null
        targetName = ""
        btnClearMapTap.isEnabled = false
        mapView.invalidate()
    }

    private fun updateUIState(mode: String) {
        if (!isMockingOn) {
            btnToggleMock.text = "▶ 開啟模擬定位"
            btnToggleMock.setBackgroundColor(Color.parseColor("#2E7D32"))
            btnToggleWander.text = "🎲 開始隨機漫步"
            btnToggleWander.setBackgroundColor(Color.parseColor("#1976D2"))
            btnTogglePatrol.text = "▶ 開始循環巡航"
            btnTogglePatrol.setBackgroundColor(Color.parseColor("#2E7D32"))
        } else {
            if (mode == MockLocationService.MODE_PATROL) {
                btnTogglePatrol.text = "⏹ 停止循環巡航"
                btnTogglePatrol.setBackgroundColor(Color.parseColor("#D32F2F"))
                btnToggleMock.text = "▶ 開啟模擬定位"
                btnToggleMock.setBackgroundColor(Color.parseColor("#2E7D32"))
                btnToggleWander.text = "🎲 開始隨機漫步"
                btnToggleWander.setBackgroundColor(Color.parseColor("#1976D2"))
            } else {
                btnTogglePatrol.text = "▶ 開始循環巡航"
                btnTogglePatrol.setBackgroundColor(Color.parseColor("#2E7D32"))
                btnToggleMock.text = "⏹ 關閉模擬定位"
                btnToggleMock.setBackgroundColor(Color.parseColor("#D32F2F"))

                if (isWanderingOn || mode == MockLocationService.MODE_RANDOM || mode == MockLocationService.MODE_NAV) {
                    btnToggleWander.text = "⏸ 停止漫步/導航"
                    btnToggleWander.setBackgroundColor(Color.parseColor("#F57C00"))
                } else {
                    btnToggleWander.text = "🎲 開始隨機漫步"
                    btnToggleWander.setBackgroundColor(Color.parseColor("#1976D2"))
                }
            }
        }
    }

    private fun setupMapEventsOverlay() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let {
                    if (isPatrolTabActive) {
                        // 巡航 Tab：地圖選點
                        if (isPickingWaypoints) {
                            val newIdx = currentPatrolPoints.size + 1
                            currentPatrolPoints.add(RoutePoint(it.latitude, it.longitude, "點 #$newIdx"))
                            refreshPatrolMapOverlays()
                            updatePatrolStatusText()
                            Toast.makeText(this@MainActivity, "已新增巡航點 #$newIdx", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // 單點 / 導航 Tab：標記目標點
                        targetLat = it.latitude
                        targetLng = it.longitude
                        targetName = "地圖標記點"

                        editSearch.setText("%.5f, %.5f".format(it.latitude, it.longitude))
                        btnClearMapTap.isEnabled = true

                        if (targetMarker == null) {
                            targetMarker = Marker(mapView)
                            targetMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                            val defaultIcon = ContextCompat.getDrawable(this@MainActivity, org.osmdroid.library.R.drawable.marker_default)?.mutate()
                            if (defaultIcon != null) {
                                val tintedIcon = DrawableCompat.wrap(defaultIcon)
                                DrawableCompat.setTint(tintedIcon, Color.parseColor("#1565C0"))
                                targetMarker?.icon = tintedIcon
                            }

                            mapView.overlays.add(targetMarker)
                        }
                        targetMarker?.position = it
                        targetMarker?.title = "目標導航點"
                        mapView.invalidate()
                    }
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }

        val overlay = MapEventsOverlay(receiver)
        mapView.overlays.add(0, overlay)
    }

    private fun setupSpeedControls() {
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val spd = if (progress < 1) 1 else progress
                    editSpeed.setText(spd.toString())
                    if (isMockingOn) {
                        MockLocationService.updateSpeed(this@MainActivity, spd.toDouble())
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        editSpeed.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val spd = s.toString().toIntOrNull() ?: 15
                if (seekSpeed.progress != spd) {
                    seekSpeed.progress = spd.coerceIn(1, 50)
                }
                if (isMockingOn) {
                    MockLocationService.updateSpeed(this@MainActivity, spd.toDouble())
                }
            }
        })
    }

    private fun getSpeed(): Double {
        return editSpeed.text.toString().toDoubleOrNull() ?: 15.0
    }

    private fun getClipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            return item?.text?.toString()?.trim()
        }
        return null
    }

    private fun performLocationSearch(inputText: String, isNav: Boolean) {
        if (inputText.isEmpty()) {
            Toast.makeText(this, "請輸入搜尋內容", Toast.LENGTH_SHORT).show()
            return
        }

        textStatus.text = "搜尋定位中..."

        Thread {
            var lat: Double? = null
            var lng: Double? = null
            var resolvedName = inputText

            val coords = inputText.split(Regex("[,\\s]+"))
            if (coords.size == 2 && coords[0].toDoubleOrNull() != null && coords[1].toDoubleOrNull() != null) {
                lat = coords[0].toDouble()
                lng = coords[1].toDouble()
            } else {
                try {
                    val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                    val addresses = geocoder.getFromLocationName(inputText, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        lat = address.latitude
                        lng = address.longitude
                        resolvedName = address.getAddressLine(0) ?: inputText
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            runOnUiThread {
                if (lat != null && lng != null) {
                    val speed = getSpeed()
                    if (isNav) {
                        MockLocationService.startNav(this, currentLat, currentLng, lat!!, lng!!, speed, resolvedName)
                        isMockingOn = true
                        isWanderingOn = true
                        isPatrolRunning = false
                        updateUIState(MockLocationService.MODE_NAV)
                        Toast.makeText(this@MainActivity, "🧭 開始導航至: $resolvedName", Toast.LENGTH_SHORT).show()
                    } else {
                        currentLat = lat!!
                        currentLng = lng!!
                        MockLocationService.startFixed(this, currentLat, currentLng, resolvedName)
                        isMockingOn = true
                        isWanderingOn = false
                        isPatrolRunning = false
                        updateUIState(MockLocationService.MODE_FIXED)
                        updateMapAndStatus(resolvedName, shouldCenter = true)
                        Toast.makeText(this@MainActivity, "📍 已傳送至: $resolvedName", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    textStatus.text = "無法解析該地點"
                    Toast.makeText(this@MainActivity, "找不到地點，請確認輸入內容", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setupFavorite(button: Button, slotKey: String, defaultName: String, defaultQuery: String) {
        val prefs = getSharedPreferences("GPSDebuggerFavs_v2", Context.MODE_PRIVATE)
        var savedName = prefs.getString("${slotKey}_name", defaultName) ?: defaultName
        var savedQuery = prefs.getString("${slotKey}_query", defaultQuery) ?: defaultQuery

        val updateButtonLabel = {
            if (savedName.isEmpty() || savedName == "(空)") {
                button.text = "★ (空)"
            } else {
                val displayLabel = if (savedName.length > 8) savedName.take(7) + "…" else savedName
                button.text = "★ $displayLabel"
            }
        }

        updateButtonLabel()

        button.setOnClickListener {
            if (savedQuery.isNotEmpty()) {
                editSearch.setText(savedQuery)
                editSearch.selectAll()
                Toast.makeText(this, "已帶入最愛: $savedName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "尚未設定地點，長按或點擊設定", Toast.LENGTH_SHORT).show()
                showEditFavoriteDialog(slotKey, button, savedName, savedQuery) { newName, newQuery ->
                    savedName = newName
                    savedQuery = newQuery
                    updateButtonLabel()
                }
            }
        }

        button.setOnLongClickListener {
            showEditFavoriteDialog(slotKey, button, savedName, savedQuery) { newName, newQuery ->
                savedName = newName
                savedQuery = newQuery
                updateButtonLabel()
            }
            true
        }
    }

    private fun showEditFavoriteDialog(
        slotKey: String,
        button: Button,
        currentName: String,
        currentQuery: String,
        onSaved: (String, String) -> Unit
    ) {
        val prefs = getSharedPreferences("GPSDebuggerFavs_v2", Context.MODE_PRIVATE)
        val clipText = getClipboardText() ?: ""
        val initialQuery = if (currentQuery.isNotEmpty()) currentQuery else clipText
        val initialName = if (currentName == "(空)") "" else currentName

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val editName = EditText(this).apply {
            hint = "請輸入顯示名稱 (例如: 公司)"
            setText(initialName)
        }

        val editQuery = EditText(this).apply {
            hint = "請輸入地點 / Plus Code / 經緯度"
            setText(initialQuery)
        }

        layout.addView(editName)
        layout.addView(editQuery)

        AlertDialog.Builder(this)
            .setTitle("✏️ 編輯我的最愛")
            .setView(layout)
            .setPositiveButton("儲存") { _, _ ->
                val newName = editName.text.toString().trim()
                val newQuery = editQuery.text.toString().trim()

                if (newName.isNotEmpty() && newQuery.isNotEmpty()) {
                    prefs.edit()
                        .putString("${slotKey}_name", newName)
                        .putString("${slotKey}_query", newQuery)
                        .apply()
                    onSaved(newName, newQuery)
                    Toast.makeText(this, "已儲存最愛: $newName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "名稱與地點不能為空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getDistanceStep(): Double {
        return editDistance.text.toString().toDoubleOrNull() ?: 1.0
    }

    private fun moveLocation(deltaXMeters: Double, deltaYMeters: Double) {
        val earthRadius = 6378137.0
        val deltaLat = (deltaYMeters / earthRadius) * (180.0 / Math.PI)
        val deltaLng = (deltaXMeters / (earthRadius * cos(Math.toRadians(currentLat)))) * (180.0 / Math.PI)

        currentLat += deltaLat
        currentLng += deltaLng

        MockLocationService.startFixed(this, currentLat, currentLng, "微調移動")
        isMockingOn = true
        isWanderingOn = false
        isPatrolRunning = false
        updateUIState(MockLocationService.MODE_FIXED)
        updateMapAndStatus("微調移動", shouldCenter = false)
    }

    private fun updateMapAndStatus(statusText: String, shouldCenter: Boolean = false) {
        val stateLabel = when {
            !isMockingOn -> "已關閉模擬"
            isPatrolRunning -> "循環巡航中"
            isWanderingOn -> "漫步/導航中"
            else -> "定點模擬中"
        }
        textStatus.text = "狀態：[$stateLabel] $statusText\n座標: %.6f, %.6f | 速度: %s km/h".format(currentLat, currentLng, editSpeed.text)

        val geoPoint = GeoPoint(currentLat, currentLng)

        if (shouldCenter) {
            mapView.controller.setCenter(geoPoint)
        }

        if (currentMarker == null) {
            currentMarker = Marker(mapView)
            currentMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(currentMarker)
        }
        currentMarker?.position = geoPoint
        currentMarker?.title = "目前位置"
        mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        val filter = IntentFilter().apply {
            addAction(MockLocationService.ACTION_LOCATION_UPDATED)
            addAction(MockLocationService.ACTION_NAV_FINISHED)
            addAction(MockLocationService.ACTION_PATROL_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(locationReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        try {
            unregisterReceiver(locationReceiver)
        } catch (e: Exception) {
            // 忽略
        }
    }
}
