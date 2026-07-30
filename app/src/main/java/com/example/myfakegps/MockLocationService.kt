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
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos

class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var speedKmH: Double = 5.0
    private var mode: String = MODE_FIXED
    private var locationName: String = ""
    private var isMocking = false

    private val polyline = mutableListOf<GeoPoint>()
    private var routeIndex = 0

    private val providers = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isMocking) {
                when (mode) {
                    MODE_FIXED -> {
                        pushLocationToAllProviders(currentLat, currentLng)
                    }
                    MODE_NAV, MODE_RANDOM -> {
                        stepAlongPolyline()
                        pushLocationToAllProviders(currentLat, currentLng)
                        broadcastLocationUpdate()
                    }
                }
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

        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_FIXED
        speedKmH = intent?.getDoubleExtra(EXTRA_SPEED, 5.0) ?: 5.0
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "自訂位置"

        val lat = intent?.getDoubleExtra(EXTRA_LAT, 0.0) ?: 0.0
        val lng = intent?.getDoubleExtra(EXTRA_LNG, 0.0) ?: 0.0

        if (mode == MODE_FIXED) {
            currentLat = lat
            currentLng = lng
            locationName = name
            startForeground(NOTIFICATION_ID, buildNotification("定點傳送: $locationName", currentLat, currentLng))
            startMockingLoop()
        } else if (mode == MODE_NAV) {
            val destLat = intent?.getDoubleExtra(EXTRA_DEST_LAT, 0.0) ?: 0.0
            val destLng = intent?.getDoubleExtra(EXTRA_DEST_LNG, 0.0) ?: 0.0
            locationName = name
            startForeground(NOTIFICATION_ID, buildNotification("導航前往: $locationName", currentLat, currentLng))
            fetchRouteAndStart(destLat, destLng)
        } else if (mode == MODE_RANDOM) {
            locationName = "沿路隨機漫步中"
            startForeground(NOTIFICATION_ID, buildNotification(locationName, currentLat, currentLng))
            fetchNextRandomRouteAndStart()
        }

        return START_STICKY
    }

    private fun startMockingLoop() {
        if (!isMocking) {
            setupTestProviders()
            isMocking = true
            handler.post(updateRunnable)
        }
    }

    private fun stepAlongPolyline() {
        if (polyline.isEmpty() || routeIndex >= polyline.size - 1) {
            if (mode == MODE_RANDOM) {
                fetchNextRandomRouteAndStart()
            } else if (mode == MODE_NAV) {
                mode = MODE_FIXED
                broadcastNavFinished()
            }
            return
        }

        var stepMeters = (speedKmH * 1000.0) / 3600.0

        while (stepMeters > 0 && routeIndex < polyline.size - 1) {
            val p1 = polyline[routeIndex]
            val p2 = polyline[routeIndex + 1]

            val dist = distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude)

            if (stepMeters >= dist) {
                stepMeters -= dist
                currentLat = p2.latitude
                currentLng = p2.longitude
                routeIndex++
            } else {
                val ratio = stepMeters / dist
                currentLat = p1.latitude + ratio * (p2.latitude - p1.latitude)
                currentLng = p1.longitude + ratio * (p2.longitude - p1.longitude)
                stepMeters = 0.0
            }
        }
    }

    private fun fetchRouteAndStart(destLat: Double, destLng: Double) {
        Thread {
            val points = fetchOsrmRoute(currentLat, currentLng, destLat, destLng)
            handler.post {
                if (!points.isNullOrEmpty()) {
                    polyline.clear()
                    polyline.addAll(points)
                    routeIndex = 0
                    startMockingLoop()
                } else {
                    polyline.clear()
                    polyline.add(GeoPoint(currentLat, currentLng))
                    polyline.add(GeoPoint(destLat, destLng))
                    routeIndex = 0
                    startMockingLoop()
                }
            }
        }.start()
    }

    private fun fetchNextRandomRouteAndStart() {
        val randomAngle = Math.random() * 2 * Math.PI
        val randomDist = 300.0 + Math.random() * 300.0
        val earthRadius = 6378137.0

        val deltaLat = (randomDist * Math.cos(randomAngle) / earthRadius) * (180.0 / Math.PI)
        val deltaLng = (randomDist * Math.sin(randomAngle) / (earthRadius * cos(Math.toRadians(currentLat)))) * (180.0 / Math.PI)

        val destLat = currentLat + deltaLat
        val destLng = currentLng + deltaLng

        fetchRouteAndStart(destLat, destLng)
    }

    private fun fetchOsrmRoute(startLat: Double, startLng: Double, endLat: Double, endLng: Double): List<GeoPoint>? {
        return try {
            val urlStr = "https://router.project-osrm.org/route/v1/foot/$startLng,$startLat;$endLng,$endLat?overview=full&geometries=geojson"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "MyFakeGPSApp/1.0 (Android; com.example.myfakegps)")

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(responseText)
                if (json.optString("code") == "Ok") {
                    val routes = json.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val route = routes.getJSONObject(0)
                        val geometry = route.getJSONObject("geometry")
                        val coords = geometry.getJSONArray("coordinates")
                        val pointList = mutableListOf<GeoPoint>()
                        for (i in 0 until coords.length()) {
                            val pair = coords.getJSONArray(i)
                            val lng = pair.getDouble(0)
                            val lat = pair.getDouble(1)
                            pointList.add(GeoPoint(lat, lng))
                        }
                        return pointList
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6378137.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
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

    private fun broadcastLocationUpdate() {
        val intent = Intent(ACTION_LOCATION_UPDATED).apply {
            putExtra(EXTRA_LAT, currentLat)
            putExtra(EXTRA_LNG, currentLng)
            putExtra(EXTRA_MODE, mode)
        }
        sendBroadcast(intent)
    }

    private fun broadcastNavFinished() {
        val intent = Intent(ACTION_NAV_FINISHED)
        sendBroadcast(intent)
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

    private fun buildNotification(titleText: String, lat: Double, lng: Double): android.app.Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyFakeGPS $titleText")
            .setContentText("座標: %.5f, %.5f | 速度: %.1f km/h".format(lat, lng, speedKmH))
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
        const val EXTRA_DEST_LAT = "extra_dest_lat"
        const val EXTRA_DEST_LNG = "extra_dest_lng"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_NAME = "extra_name"

        const val MODE_FIXED = "mode_fixed"
        const val MODE_NAV = "mode_nav"
        const val MODE_RANDOM = "mode_random"

        const val ACTION_STOP = "action_stop"
        const val ACTION_LOCATION_UPDATED = "com.example.myfakegps.LOCATION_UPDATED"
        const val ACTION_NAV_FINISHED = "com.example.myfakegps.NAV_FINISHED"

        fun startFixed(context: Context, lat: Double, lng: Double, name: String) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_FIXED)
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LNG, lng)
                putExtra(EXTRA_NAME, name)
            }
            startServiceIntent(context, intent)
        }

        fun startNav(context: Context, destLat: Double, destLng: Double, speed: Double, name: String) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_NAV)
                putExtra(EXTRA_DEST_LAT, destLat)
                putExtra(EXTRA_DEST_LNG, destLng)
                putExtra(EXTRA_SPEED, speed)
                putExtra(EXTRA_NAME, name)
            }
            startServiceIntent(context, intent)
        }

        fun startRandom(context: Context, speed: Double) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_RANDOM)
                putExtra(EXTRA_SPEED, speed)
            }
            startServiceIntent(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun startServiceIntent(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
