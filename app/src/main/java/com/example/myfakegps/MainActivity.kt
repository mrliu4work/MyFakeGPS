package com.example.myfakegps

import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import kotlin.math.cos

class MainActivity : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var mapView: MapView
    private lateinit var editDistance: EditText
    private lateinit var textStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var isMocking = false
    private var currentLat: Double? = null
    private var currentLng: Double? = null
    private var marker: Marker? = null

    private val providers = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isMocking && currentLat != null && currentLng != null) {
                pushLocationToAllProviders(currentLat!!, currentLng!!)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 OSMDroid
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val editSearch = findViewById<EditText>(R.id.editSearch)
        val btnSet = findViewById<Button>(R.id.btnSetLocation)
        val btnOpenDev = findViewById<Button>(R.id.btnOpenDevSettings)
        val btnOpenAppInfo = findViewById<Button>(R.id.btnOpenAppInfo)
        val btnNorth = findViewById<Button>(R.id.btnNorth)
        val btnSouth = findViewById<Button>(R.id.btnSouth)
        val btnEast = findViewById<Button>(R.id.btnEast)
        val btnWest = findViewById<Button>(R.id.btnWest)

        editDistance = findViewById(R.id.editDistance)
        textStatus = findViewById(R.id.textStatus)
        mapView = findViewById(R.id.mapView)

        // 初始化地圖設定
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)

        // 捷徑 1：開啟開發者選項
        btnOpenDev.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "無法開啟開發者選項: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 捷徑 2：開啟應用程式資訊 (可點擊設定電池用量)
        btnOpenAppInfo.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "無法開啟應用程式資訊: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 搜尋定位按鈕
        btnSet.setOnClickListener {
            if (isMocking) {
                stopMocking()
                btnSet.text = "搜尋並修改定位"
                textStatus.text = "已停止模擬位置"
                Toast.makeText(this, "已停止模擬定位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val inputText = editSearch.text.toString().trim()
            if (inputText.isEmpty()) {
                Toast.makeText(this, "請輸入搜尋內容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
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
                        currentLat = lat
                        currentLng = lng
                        startMocking()

                        btnSet.text = "停止模擬定位"
                        updateStatusAndMap(resolvedName)
                        Toast.makeText(this@MainActivity, "定位已更新！", Toast.LENGTH_SHORT).show()
                    } else {
                        textStatus.text = "無法解析該地點，請重新檢查"
                        Toast.makeText(this@MainActivity, "找不到地點，請確認輸入", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        // 東西南北微調移動按鈕
        btnNorth.setOnClickListener { moveLocation(0.0, getDistanceStep()) }
        btnSouth.setOnClickListener { moveLocation(0.0, -getDistanceStep()) }
        btnEast.setOnClickListener { moveLocation(getDistanceStep(), 0.0) }
        btnWest.setOnClickListener { moveLocation(-getDistanceStep(), 0.0) }
    }

    private fun getDistanceStep(): Double {
        return editDistance.text.toString().toDoubleOrNull() ?: 1.0
    }

    // 依據距離 (公尺) 精確計算球面經緯度位移
    private fun moveLocation(deltaXMeters: Double, deltaYMeters: Double) {
        val lat = currentLat ?: return
        val lng = currentLng ?: return

        val earthRadius = 6378137.0 // 地球半徑 (公尺)

        // 緯度位移公式
        val deltaLat = (deltaYMeters / earthRadius) * (180.0 / Math.PI)
        // 經度位移公式 (根據緯度進行 cos 縮放)
        val deltaLng = (deltaXMeters / (earthRadius * cos(Math.toRadians(lat)))) * (180.0 / Math.PI)

        currentLat = lat + deltaLat
        currentLng = lng + deltaLng

        if (isMocking) {
            pushLocationToAllProviders(currentLat!!, currentLng!!)
        }
        updateStatusAndMap("微調移動後的位置")
    }

    private fun updateStatusAndMap(locationName: String) {
        val lat = currentLat ?: return
        val lng = currentLng ?: return

        textStatus.text = "模擬中...\n位置: $locationName\n緯度: %.6f, 經度: %.6f".format(lat, lng)

        // 同步更新 OpenStreetMap 與標記點
        val geoPoint = GeoPoint(lat, lng)
        mapView.controller.setCenter(geoPoint)

        if (marker == null) {
            marker = Marker(mapView)
            marker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(marker)
        }
        marker?.position = geoPoint
        marker?.title = "目前模擬位置"
        mapView.invalidate()
    }

    private fun startMocking() {
        stopMocking()
        setupTestProviders()
        isMocking = true
        handler.post(updateRunnable)
    }

    private fun stopMocking() {
        isMocking = false
        handler.removeCallbacks(updateRunnable)
        removeTestProviders()
    }

    private fun setupTestProviders() {
        for (provider in providers) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                // 忽略
            }
            try {
                locationManager.addTestProvider(
                    provider,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun removeTestProviders() {
        for (provider in providers) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    private fun pushLocationToAllProviders(lat: Double, lng: Double) {
        for (provider in providers) {
            try {
                val mockLocation = Location(provider).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 0.0
                    time = System.currentTimeMillis()
                    accuracy = 5f
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                locationManager.setTestProviderLocation(provider, mockLocation)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMocking()
    }
}
