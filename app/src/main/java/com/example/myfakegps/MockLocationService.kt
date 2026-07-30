package com.example.myfakegps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var locationName: String = ""
    private var isMocking = false

    private val providers = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isMocking) {
                pushLocationToAllProviders(currentLat, currentLng)
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopMockingService()
            return START_NOT_STICKY
        }

        val lat = intent?.getDoubleExtra(EXTRA_LAT, 0.0) ?: 0.0
        val lng = intent?.getDoubleExtra(EXTRA_LNG, 0.0) ?: 0.0
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "自訂位置"

        currentLat = lat
        currentLng = lng
        locationName = name

        // 啟動 Foreground Service 常駐通知
        startForeground(NOTIFICATION_ID, buildNotification(locationName, currentLat, currentLng))

        if (!isMocking) {
            setupTestProviders()
            isMocking = true
            handler.post(updateRunnable)
        }

        return START_STICKY
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

    private fun stopMockingService() {
        isMocking = false
        handler.removeCallbacks(updateRunnable)
        for (provider in providers) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                // 忽略
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模擬定位服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "提供背景常駐廣播模擬定位"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(name: String, lat: Double, lng: Double): android.app.Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyFakeGPS 定位模擬中")
            .setContentText("$name (%.5f, %.5f)".format(lat, lng))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMockingService()
    }

    companion object {
        const val CHANNEL_ID = "mock_location_service_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_NAME = "extra_name"
        const val ACTION_STOP = "action_stop"

        fun start(context: Context, lat: Double, lng: Double, name: String) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LNG, lng)
                putExtra(EXTRA_NAME, name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
