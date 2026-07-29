package com.example.myfakegps

import android.content.Context
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
                locationManager.addTestProvider(
                    providerName, false, false, false, false,
                    true, true, true, 0, 5
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

                Toast.makeText(this, "位置已成功修改為: $lat, $lng", Toast.LENGTH_SHORT).show()

            } catch (e: SecurityException) {
                Toast.makeText(this, "失敗！請先到開發人員選項設定「模擬位置應用程式」", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            } catch (e: Exception) {
                Toast.makeText(this, "發生未知錯誤: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
