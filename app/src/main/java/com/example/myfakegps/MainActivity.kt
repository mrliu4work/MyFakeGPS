package com.example.myfakegps

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editLat = findViewById<EditText>(R.id.editLatitude)
        val editLng = findViewById<EditText>(R.id.editLongitude)
        val btnSet = findViewById<Button>(R.id.btnSetLocation)

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providerName = LocationManager.GPS_PROVIDER

        btnSet.setOnClickListener {
            val latStr = editLat.text.toString()
            val lngStr = editLng.text.toString()

            if (latStr.isEmpty() || lngStr.isEmpty()) {
                Toast.makeText(this, "請輸入經緯度", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val lat = latStr.toDouble()
            val lng = lngStr.toDouble()

            try {
                // 若先前已建立過 Test Provider，先清理避免重複註冊衝突
                try {
                    locationManager.removeTestProvider(providerName)
                } catch (e: Exception) {
                    // 忽略尚未註冊時引發的例外
                }

                // 1. 向系統註冊 Test Provider
                // 倒數第二個參數 (powerRequirement) 改為 1 (Criteria.POWER_LOW)
                // 最後一個參數 (accuracy) 改為 1 (Criteria.ACCURACY_FINE)
                locationManager.addTestProvider(
                    providerName,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(providerName, true)

                // 2. 建立假座標物件
                val mockLocation = Location(providerName).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 0.0
                    time = System.currentTimeMillis()
                    accuracy = 5f
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }

                // 3. 寫入假座標
                locationManager.setTestProviderLocation(providerName, mockLocation)

                Toast.makeText(this, "位置已成功修改為: $lat, $lng", Toast.LENGTH_SHORT).show()

            } catch (e: SecurityException) {
                Toast.makeText(this, "失敗！請先到開發人員選項設定「模擬位置應用程式」", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            } catch (e: Exception) {
                Toast.makeText(this, "發生錯誤: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
    }
}
