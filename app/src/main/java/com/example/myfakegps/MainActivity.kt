package com.example.myfakegps

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
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

    private lateinit var mapView: MapView
    private lateinit var editSearch: EditText
    private lateinit var editDistance: EditText
    private lateinit var textStatus: TextView
    private lateinit var btnSet: Button

    private var isMocking = false
    private var currentLat: Double? = null
    private var currentLng: Double? = null
    private var marker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = "MyFakeGPSApp/1.0 (Android; com.example.myfakegps)"
        setContentView(R.layout.activity_main)

        editSearch = findViewById(R.id.editSearch)
        editDistance = findViewById(R.id.editDistance)
        textStatus = findViewById(R.id.textStatus)
        mapView = findViewById(R.id.mapView)

        btnSet = findViewById(R.id.btnSetLocation)
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

        editSearch.setOnClickListener { editSearch.selectAll() }

        btnPaste.setOnClickListener {
            val text = getClipboardText()
            if (!text.isNullOrEmpty()) {
                editSearch.setText(text)
                editSearch.selectAll()
                performLocationSearch(text)
            } else {
                Toast.makeText(this, "剪貼簿為空或無文字內容", Toast.LENGTH_SHORT).show()
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
            if (isMocking) {
                stopMocking()
            } else {
                val inputText = editSearch.text.toString().trim()
                performLocationSearch(inputText)
            }
        }

        btnNorth.setOnClickListener { moveLocation(0.0, getDistanceStep()) }
        btnSouth.setOnClickListener { moveLocation(0.0, -getDistanceStep()) }
        btnEast.setOnClickListener { moveLocation(getDistanceStep(), 0.0) }
        btnWest.setOnClickListener { moveLocation(-getDistanceStep(), 0.0) }
    }

    private fun getClipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            return item?.text?.toString()?.trim()
        }
        return null
    }

    private fun performLocationSearch(inputText: String) {
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
                    currentLat = lat
                    currentLng = lng
                    startMocking(resolvedName)
                    updateStatusAndMap(resolvedName)
                    Toast.makeText(this@MainActivity, "前景服務已啟動，定位已常駐！", Toast.LENGTH_SHORT).show()
                } else {
                    textStatus.text = "無法解析該地點，請重新檢查"
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
            performLocationSearch(savedVal)
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
        val lat = currentLat ?: return
        val lng = currentLng ?: return

        val earthRadius = 6378137.0
        val deltaLat = (deltaYMeters / earthRadius) * (180.0 / Math.PI)
        val deltaLng = (deltaXMeters / (earthRadius * cos(Math.toRadians(lat)))) * (180.0 / Math.PI)

        currentLat = lat + deltaLat
        currentLng = lng + deltaLng

        if (isMocking) {
            MockLocationService.start(this, currentLat!!, currentLng!!, "微調移動後")
        }
        updateStatusAndMap("微調移動後的位置")
    }

    private fun updateStatusAndMap(locationName: String) {
        val lat = currentLat ?: return
        val lng = currentLng ?: return

        textStatus.text = "前景常駐模擬中...\n位置: $locationName\n緯度: %.6f, 經度: %.6f".format(lat, lng)

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

    private fun startMocking(locationName: String) {
        val lat = currentLat ?: return
        val lng = currentLng ?: return
        isMocking = true
        btnSet.text = "停止模擬定位"
        MockLocationService.start(this, lat, lng, locationName)
    }

    private fun stopMocking() {
        isMocking = false
        btnSet.text = "搜尋並修改定位"
        textStatus.text = "已停止模擬位置"
        MockLocationService.stop(this)
        Toast.makeText(this, "已停止背景模擬服務", Toast.LENGTH_SHORT).show()
    }

    // 自動回復背景服務傳回的最新座標與狀態
    private fun restoreServiceState() {
        val prefs = getSharedPreferences("ServiceState", Context.MODE_PRIVATE)
        val mocking = prefs.getBoolean("is_mocking", false)
        if (mocking) {
            val latStr = prefs.getString("active_lat", null)
            val lngStr = prefs.getString("active_lng", null)
            val name = prefs.getString("active_name", "自訂位置") ?: "自訂位置"

            val lat = latStr?.toDoubleOrNull()
            val lng = lngStr?.toDoubleOrNull()

            if (lat != null && lng != null) {
                currentLat = lat
                currentLng = lng
                isMocking = true
                btnSet.text = "停止模擬定位"
                updateStatusAndMap(name)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        restoreServiceState()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}
