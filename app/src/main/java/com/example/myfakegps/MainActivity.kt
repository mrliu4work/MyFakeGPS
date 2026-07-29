package com.example.myfakegps

import android.content.Context
import android.location.Criteria
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editSearch = findViewById<EditText>(R.id.editSearch)
        val btnSet = findViewById<Button>(R.id.btnSetLocation)
        val textStatus = findViewById<TextView>(R.id.textStatus)

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providerName = LocationManager.GPS_PROVIDER

        btnSet.setOnClickListener {
            val inputText = editSearch.text.toString().trim()

            if (inputText.isEmpty()) {
                Toast.makeText(this, "請輸入搜尋內容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            textStatus.text = "搜尋定位中..."

            // 使用背景執行緒進行 Geocoder 解析，避免阻塞主執行緒
            Thread {
                var lat: Double? = null
                var lng: Double? = null
                var resolvedName = inputText

                // 1. 判斷是否為直接輸入經緯度格式，例如 "25.0339, 121.5640" 或 "25.0339 121.5640"
                val coords = inputText.split(Regex("[,\\s]+"))
                if (coords.size == 2 && coords[0].toDoubleOrNull() != null && coords[1].toDoubleOrNull() != null) {
                    lat = coords[0].toDouble()
                    lng = coords[1].toDouble()
                } else {
                    // 2. 使用 Android 內建 Geocoder 解析 Plus Code 或 地名 (例如 "CCM9+QMH Santorini")
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

                // 回到 UI 執行緒更新畫面與設定位置
                runOnUiThread {
                    if (lat != null && lng != null) {
                        val success = applyMockLocation(locationManager, providerName, lat!!, lng!!)
                        if (success) {
                            textStatus.text = "修改成功！\n目標: $resolvedName\n經度: $lng, 緯度: $lat"
                            Toast.makeText(this@MainActivity, "定位已更新！", Toast.LENGTH_SHORT).show()
                        } else {
                            textStatus.text = "失敗：請確認已開啟開發者選項中的模擬位置設定"
                        }
                    } else {
                        textStatus.text = "無法解析該地點或 Plus Code，請重新檢查輸入"
                        Toast.makeText(this@MainActivity, "找不到地點，請確認網路連線或字串", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun applyMockLocation(
        locationManager: LocationManager,
        providerName: String,
        lat: Double,
        lng: Double
    ): Boolean {
        return try {
            try {
                locationManager.removeTestProvider(providerName)
            } catch (e: Exception) {
                // 忽略
            }

            locationManager.addTestProvider(
                providerName,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(providerName, true)

            val mockLocation = Location(providerName).apply {
                latitude = lat
                longitude = lng
                altitude = 0.0
                time = System.currentTimeMillis()
                accuracy = 5f
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }

            locationManager.setTestProviderLocation(providerName, mockLocation)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
