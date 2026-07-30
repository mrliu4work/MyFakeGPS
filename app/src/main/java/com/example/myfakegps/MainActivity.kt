package com.example.myfakegps

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var btnNavToMapTap: Button

    private var currentLat: Double = 25.0339
    private var currentLng: Double = 121.5640
    private var mapTappedLat: Double? = null
    private var mapTappedLng: Double? = null

    private var currentMarker: Marker? = null
    private var targetMarker: Marker? = null

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MockLocationService.ACTION_LOCATION_UPDATED -> {
                    val lat = intent.getDoubleExtra(MockLocationService.EXTRA_LAT, currentLat)
                    val lng = intent.getDoubleExtra(MockLocationService.EXTRA_LNG, currentLng)
                    val mode = intent.getStringExtra(MockLocationService.EXTRA_MODE)
                    currentLat = lat
                    currentLng = lng
                    updateMapAndStatus("移動中 ($mode)")
                }
                MockLocationService.ACTION_NAV_FINISHED -> {
                    Toast.makeText(this@MainActivity, "🏁 抵達目的地，導航結束！", Toast.LENGTH_LONG).show()
                    if (targetMarker != null) {
                        mapView.overlays.remove(targetMarker)
                        targetMarker = null
                        mapView.invalidate()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = "MyFakeGPSApp/1.0 (Android; com.example.myfakegps)"
        setContentView(R.layout.activity_main)

        editSearch = findViewById(R.id.editSearch)
        editDistance = findViewById(R.id.editDistance)
        editSpeed = findViewById(R.id.editSpeed)
        seekSpeed = findViewById(R.id.seekSpeed)
        textStatus = findViewById(R.id.textStatus)
        textMapSelected = findViewById(R.id.textMapSelected)
        btnNavToMapTap = findViewById(R.id.btnNavToMapTap)
        mapView = findViewById(R.id.mapView)

        val btnSet = findViewById<Button>(R.id.btnSetLocation)
        val btnNavSearch = findViewById<Button>(R.id.btnNavToSearch)
        val btnRandom = findViewById<Button>(R.id.btnRandomWander)
        val btnStop = findViewById<Button>(R.id.btnStopAll)
        val btnPaste = findViewById<Button>(R.id.btnPaste)

        val btnOpenDev = findViewById<Button>(R.id.btnOpenDevSettings)
        val btnOpenAppInfo = findViewById<Button>(R.id.btnOpenAppInfo)

        val btnNorth = findViewById<Button>(R.id.btnNorth)
        val btnSouth = findViewById<Button>(R.id.btnSouth)
        val btnEast = findViewById<Button>(R.id.btnEast)
        val btnWest = findViewById<Button>(R.id.btnWest)

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)

        setupMapEventsOverlay()
        setupSpeedControls()

        editSearch.setOnClickListener { editSearch.selectAll() }

        btnPaste.setOnClickListener {
            val text = getClipboardText()
            if (!text.isNullOrEmpty()) {
                editSearch.setText(text)
                editSearch.selectAll()
                performLocationSearch(text, isNav = false)
            } else {
                Toast.makeText(this, "剪貼簿為空", Toast.LENGTH_SHORT).show()
            }
        }

        setupFavorite(findViewById(R.id.btnFav1), "fav_1", "CCM9+QMH Santorini")
        setupFavorite(findViewById(R.id.btnFav2), "fav_2", "台北 101")
        setupFavorite(findViewById(R.id.btnFav3), "fav_3", "東京塔")
        setupFavorite(findViewById(R.id.btnFav4), "fav_4", "埃菲爾鐵塔")

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

        btnSet.setOnClickListener {
            val inputText = editSearch.text.toString().trim()
            performLocationSearch(inputText, isNav = false)
        }

        btnNavSearch.setOnClickListener {
            val inputText = editSearch.text.toString().trim()
            performLocationSearch(inputText, isNav = true)
        }

        btnRandom.setOnClickListener {
            val speed = getSpeed()
            MockLocationService.startRandom(this, speed)
            Toast.makeText(this, "🎲 沿路隨機漫步模式已啟動！", Toast.LENGTH_SHORT).show()
        }

        btnNavToMapTap.setOnClickListener {
            if (mapTappedLat != null && mapTappedLng != null) {
                val speed = getSpeed()
                MockLocationService.startNav(this, mapTappedLat!!, mapTappedLng!!, speed, "地圖點標記")
                Toast.makeText(this, "🚩 開始導航至地圖點！", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            MockLocationService.stop(this)
            textStatus.text = "已停止定位模擬"
            Toast.makeText(this, "已停止背景服務", Toast.LENGTH_SHORT).show()
        }

        btnNorth.setOnClickListener { moveLocation(0.0, getDistanceStep()) }
        btnSouth.setOnClickListener { moveLocation(0.0, -getDistanceStep()) }
        btnEast.setOnClickListener { moveLocation(getDistanceStep(), 0.0) }
        btnWest.setOnClickListener { moveLocation(-getDistanceStep(), 0.0) }
    }

    private fun setupMapEventsOverlay() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedByStatic(p: GeoPoint?): Boolean {
                p?.let {
                    mapTappedLat = it.latitude
                    mapTappedLng = it.longitude

                    textMapSelected.text = "已選點: %.5f, %.5f".format(it.latitude, it.longitude)
                    btnNavToMapTap.isEnabled = true

                    if (targetMarker == null) {
                        targetMarker = Marker(mapView)
                        targetMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
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
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        editSpeed.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val spd = s.toString().toIntOrNull() ?: 5
                if (seekSpeed.progress != spd) {
                    seekSpeed.progress = spd.coerceIn(1, 50)
                }
            }
        })
    }

    private fun getSpeed(): Double {
        return editSpeed.text.toString().toDoubleOrNull() ?: 5.0
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
                    if (isNav) {
                        val speed = getSpeed()
                        MockLocationService.startNav(this, lat!!, lng!!, speed, resolvedName)
                        Toast.makeText(this@MainActivity, "🧭 開始導航至: $resolvedName", Toast.LENGTH_SHORT).show()
                    } else {
                        currentLat = lat!!
                        currentLng = lng!!
                        MockLocationService.startFixed(this, currentLat, currentLng, resolvedName)
                        updateMapAndStatus(resolvedName)
                        Toast.makeText(this@MainActivity, "📍 已定點傳送！", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    textStatus.text = "無法解析該地點"
                    Toast.makeText(this@MainActivity, "找不到地點，請確認輸入內容", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun setupFavorite(button: Button, slotKey: String, defaultValue: String) {
        val prefs = getSharedPreferences("MyFakeGPSFavs", Context.MODE_PRIVATE)
        var savedVal = prefs.getString(slotKey, defaultValue) ?: defaultValue

        val updateButtonLabel = {
            val displayLabel = if (savedVal.length > 7) savedVal.take(6) + "…" else savedVal
            button.text = "★ $displayLabel"
        }

        updateButtonLabel()

        button.setOnClickListener {
            editSearch.setText(savedVal)
            editSearch.selectAll()
            performLocationSearch(savedVal, isNav = false)
        }

        button.setOnLongClickListener {
            val clipText = getClipboardText()
            if (!clipText.isNullOrEmpty()) {
                savedVal = clipText
                prefs.edit().putString(slotKey, savedVal).apply()
                updateButtonLabel()
                editSearch.setText(savedVal)
                Toast.makeText(this, "已將剪貼簿內容儲存至最愛: $savedVal", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪貼簿為空，無法儲存至最愛", Toast.LENGTH_SHORT).show()
            }
            true
        }
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
        updateMapAndStatus("微調移動")
    }

    private fun updateMapAndStatus(statusText: String) {
        textStatus.text = "模擬中 [$statusText]\n座標: %.5f, %.5f | 速度: %s km/h".format(currentLat, currentLng, editSpeed.text)

        val geoPoint = GeoPoint(currentLat, currentLng)
        mapView.controller.setCenter(geoPoint)

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
