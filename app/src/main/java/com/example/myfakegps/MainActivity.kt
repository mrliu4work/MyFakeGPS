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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import kotlin.math.cos

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var editSearch: EditText
    private lateinit var editDistance: EditText
    private lateinit var editSpeed: EditText
    private lateinit var seekSpeed: SeekBar
    private lateinit var textStatus: TextView
    private lateinit var textMapSelected: TextView

    private lateinit var btnToggleMock: Button
    private lateinit var btnToggleWander: Button
    private lateinit var btnNavToMapTap: Button
    private lateinit var btnClearMapTap: Button
    private lateinit var btnCopyCoords: Button
    private lateinit var btnRecenter: Button

    private var currentLat: Double = 25.0339
    private var currentLng: Double = 121.5640
    private var mapTappedLat: Double? = null
    private var mapTappedLng: Double? = null

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
                    updateUIState(mode)
                    updateMapAndStatus("模擬中 ($mode)", shouldCenter = false)
                }
                MockLocationService.ACTION_NAV_FINISHED -> {
                    Toast.makeText(this@MainActivity, "🏁 抵達目的地，導航結束！", Toast.LENGTH_LONG).show()
                    isWanderingOn = false
                    updateUIState(MockLocationService.MODE_FIXED)
                    clearTargetMarker()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = "GPSDebuggerApp/1.0 (Android; com.example.myfakegps)"
        setContentView(R.layout.activity_main)

        editSearch = findViewById(R.id.editSearch)
        editDistance = findViewById(R.id.editDistance)
        editSpeed = findViewById(R.id.editSpeed)
        seekSpeed = findViewById(R.id.seekSpeed)
        textStatus = findViewById(R.id.textStatus)
        textMapSelected = findViewById(R.id.textMapSelected)

        btnToggleMock = findViewById(R.id.btnToggleMock)
        btnToggleWander = findViewById(R.id.btnToggleWander)
        btnNavToMapTap = findViewById(R.id.btnNavToMapTap)
        btnClearMapTap = findViewById(R.id.btnClearMapTap)
        btnCopyCoords = findViewById(R.id.btnCopyCoords)
        btnRecenter = findViewById(R.id.btnRecenter)

        val btnSet = findViewById<Button>(R.id.btnSetLocation)
        val btnNavSearch = findViewById<Button>(R.id.btnNavToSearch)
        val btnPaste = findViewById<Button>(R.id.btnPaste)

        val btnOpenDev = findViewById<Button>(R.id.btnOpenDevSettings)
        val btnOpenAppInfo = findViewById<Button>(R.id.btnOpenAppInfo)

        val btnNorth = findViewById<Button>(R.id.btnNorth)
        val btnSouth = findViewById<Button>(R.id.btnSouth)
        val btnEast = findViewById<Button>(R.id.btnEast)
        val btnWest = findViewById<Button>(R.id.btnWest)

        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)

        setupMapEventsOverlay()
        setupSpeedControls()

        editSearch.setOnClickListener { editSearch.selectAll() }

        // 一鍵複製座標
        btnCopyCoords.setOnClickListener {
            val coordsStr = String.format(Locale.US, "%.6f, %.6f", currentLat, currentLng)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Coordinates", coordsStr)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "📋 已複製經緯度: $coordsStr", Toast.LENGTH_SHORT).show()
        }

        // 移回當前位置
        btnRecenter.setOnClickListener {
            mapView.controller.animateTo(GeoPoint(currentLat, currentLng))
            Toast.makeText(this, "🎯 鏡頭已對準當前位置", Toast.LENGTH_SHORT).show()
        }

        // 清除地圖選點
        btnClearMapTap.setOnClickListener {
            clearTargetMarker()
            Toast.makeText(this, "已清除地圖目標點", Toast.LENGTH_SHORT).show()
        }

        // 僅貼上文字
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

        setupFavorite(findViewById(R.id.btnFav1), "fav_1", "希臘", "CCM9+QMH Santorini")
        setupFavorite(findViewById(R.id.btnFav2), "fav_2", "101", "台北 101")
        setupFavorite(findViewById(R.id.btnFav3), "fav_3", "東京塔", "東京塔")
        setupFavorite(findViewById(R.id.btnFav4), "fav_4", "鐵塔", "埃菲爾鐵塔")

        btnOpenDev.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "無法開啟開發者選項: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenAppInfo.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            } catch (e: Exception) {
                Toast.makeText(this, "無法開啟應用程式資訊: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleMock.setOnClickListener {
            if (isMockingOn) {
                MockLocationService.stop(this)
                isMockingOn = false
                isWanderingOn = false
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

        btnNavToMapTap.setOnClickListener {
            if (mapTappedLat != null && mapTappedLng != null) {
                val speed = getSpeed()
                MockLocationService.startNav(this, currentLat, currentLng, mapTappedLat!!, mapTappedLng!!, speed, "地圖點標記")
                isMockingOn = true
                isWanderingOn = true
                updateUIState(MockLocationService.MODE_NAV)
                Toast.makeText(this, "🚩 開始導航至地圖點！", Toast.LENGTH_SHORT).show()
            }
        }

        btnNorth.setOnClickListener { moveLocation(0.0, getDistanceStep()) }
        btnSouth.setOnClickListener { moveLocation(0.0, -getDistanceStep()) }
        btnEast.setOnClickListener { moveLocation(getDistanceStep(), 0.0) }
        btnWest.setOnClickListener { moveLocation(-getDistanceStep(), 0.0) }
    }

    private fun clearTargetMarker() {
        if (targetMarker != null) {
            mapView.overlays.remove(targetMarker)
            targetMarker = null
        }
        mapTappedLat = null
        mapTappedLng = null
        textMapSelected.text = "地圖點擊選點：點擊地圖設置標記"
        btnNavToMapTap.isEnabled = false
        btnClearMapTap.isEnabled = false
        mapView.invalidate()
    }

    private fun updateUIState(mode: String) {
        if (!isMockingOn) {
            btnToggleMock.text = "▶ 開啟模擬定位"
            btnToggleMock.setBackgroundColor(Color.parseColor("#2E7D32"))
            btnToggleWander.text = "🎲 開始隨機漫步"
            btnToggleWander.setBackgroundColor(Color.parseColor("#1976D2"))
        } else {
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

    private fun setupMapEventsOverlay() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let {
                    mapTappedLat = it.latitude
                    mapTappedLng = it.longitude

                    textMapSelected.text = "已選點: %.5f, %.5f".format(it.latitude, it.longitude)
                    btnNavToMapTap.isEnabled = true
                    btnClearMapTap.isEnabled = true

                    if (targetMarker == null) {
                        targetMarker = Marker(mapView)
                        targetMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                        val defaultIcon = ContextCompat.getDrawable(this@MainActivity, org.osmdroid.library.R.drawable.marker_default)?.mutate()
                        if (defaultIcon != null) {
                            val tintedIcon = DrawableCompat.wrap(defaultIcon)
                            DrawableCompat.setTint(tintedIcon, Color.parseColor("#1565C0")) // 藍色 Marker
                            targetMarker?.icon = tintedIcon
                        }

                        mapView.overlays.add(targetMarker)
                    }
                    targetMarker?.position = it
                    targetMarker?.title = "目標導航點"
                    mapView.invalidate()
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
                val spd = s.toString().toIntOrNull() ?: 10
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
        return editSpeed.text.toString().toDoubleOrNull() ?: 10.0
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
                        updateUIState(MockLocationService.MODE_NAV)
                        Toast.makeText(this@MainActivity, "🧭 開始導航至: $resolvedName", Toast.LENGTH_SHORT).show()
                    } else {
                        currentLat = lat!!
                        currentLng = lng!!
                        MockLocationService.startFixed(this, currentLat, currentLng, resolvedName)
                        isMockingOn = true
                        isWanderingOn = false
                        updateUIState(MockLocationService.MODE_FIXED)
                        updateMapAndStatus(resolvedName, shouldCenter = true)
                        Toast.makeText(this@MainActivity, "📍 已定點傳送！", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    textStatus.text = "無法解析該地點"
                    Toast.makeText(this@MainActivity, "找不到地點，請確認輸入內容", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // 我的最愛點擊僅帶入文字，不直接發動傳送
    private fun setupFavorite(button: Button, slotKey: String, defaultName: String, defaultQuery: String) {
        val prefs = getSharedPreferences("GPSDebuggerFavs", Context.MODE_PRIVATE)
        var savedName = prefs.getString("${slotKey}_name", defaultName) ?: defaultName
        var savedQuery = prefs.getString("${slotKey}_query", defaultQuery) ?: defaultQuery

        val updateButtonLabel = {
            val displayLabel = if (savedName.length > 6) savedName.take(5) + "…" else savedName
            button.text = "★ $displayLabel"
        }

        updateButtonLabel()

        button.setOnClickListener {
            editSearch.setText(savedQuery)
            editSearch.selectAll()
            Toast.makeText(this, "已帶入最愛: $savedName", Toast.LENGTH_SHORT).show()
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
        val prefs = getSharedPreferences("GPSDebuggerFavs", Context.MODE_PRIVATE)
        val clipText = getClipboardText() ?: ""
        val initialQuery = if (currentQuery.isNotEmpty()) currentQuery else clipText

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val editName = EditText(this).apply {
            hint = "請輸入顯示名稱 (例如: 聖托里尼)"
            setText(currentName)
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
        updateUIState(MockLocationService.MODE_FIXED)
        updateMapAndStatus("微調移動", shouldCenter = false)
    }

    private fun updateMapAndStatus(statusText: String, shouldCenter: Boolean = false) {
        val stateLabel = when {
            !isMockingOn -> "已關閉模擬"
            isWanderingOn -> "漫步/導航中"
            else -> "定點模擬中"
        }
        textStatus.text = "狀態：[$stateLabel] $statusText\n座標: %.6f, %.6f | 速度: %s km/h".format(currentLat, currentLng, editSpeed.text)

        val geoPoint = GeoPoint(currentLat, currentLng)

        // 僅在點擊傳送或手動歸位時置中，漫步中不強行抓回鏡頭
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
