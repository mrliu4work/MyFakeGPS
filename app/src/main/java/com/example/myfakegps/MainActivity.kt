package com.example.myfakegps

import android.content.Context
import android.location.Criteria
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var isMocking = false
    private var currentLat: Double? = null
    private var currentLng: Double? = null

    // 同時鎖定 GPS 與 NETWORK 兩個定位通道
    private val providers = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    // 每 1 秒持續發送最新假座標的迴圈
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isMocking && currentLat != null && currentLng != null) {
                pushLocationToAllProviders(currentLat!!, currentLng!!)
                handler.postDelayed(this, 1000) // 1000 毫秒 (1 秒) 刷一次
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editSearch = findViewById<EditText>(R.id.editSearch)
        val btnSet = findViewById<Button>(R.id.btnSetLocation)
        val textStatus = findViewById<TextView>(R.id.textStatus)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        btnSet.setOnClickListener {
            // 如果正在模擬中，再次點擊按鈕則「停止模擬」
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

                // 判斷是否為經緯度格式
                val coords = inputText.split(Regex("[,\\s]+"))
                if (coords.size == 2 && coords[0].toDoubleOrNull() != null && coords[1].toDoubleOrNull() != null) {
                    lat = coords[0].toDouble()
                    lng = coords[1].toDouble()
                } else {
                    // Geocoder 解析地名或 Plus Code
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
                        textStatus.text = "持續模擬中...\n目標: $resolvedName\n經度: $lng, 緯度: $lat"
                        Toast.makeText(this@MainActivity, "定位鎖定成功，每秒持續廣播中！", Toast.LENGTH_SHORT).show()
                    } else {
                        textStatus.text = "無法解析該地點或 Plus Code，請重新檢查輸入"
                        Toast.makeText(this@MainActivity, "找不到地點，請確認輸入內容", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun startMocking() {
        stopMocking() // 確保先清理舊的 Provider
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

    override fun onDestroy() {
        super.onDestroy()
        stopMocking()
    }
}
