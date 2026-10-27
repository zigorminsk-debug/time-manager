package com.zigorminsk.timemanager.place

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.notify.Notifications

/**
 * Фоновое слежение за местами: слушает GPS и раз в минуту проверяет Wi-Fi.
 * Живёт, пока включена настройка «Следить за местами в фоне» и есть задачи по месту.
 */
class PlaceWatchService : Service(), LocationListener {

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            checkNow()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val places = AppGraph.repo.data.places.map { it.name }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.ID_SERVICE,
                    Notifications.serviceNotification(this, places),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(Notifications.ID_SERVICE, Notifications.serviceNotification(this, places))
            }
        } catch (e: Exception) {
            Log.w(TAG, "не удалось перейти в foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        startLocationUpdates()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (!PlaceMonitor.hasLocationPermission(this)) return
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 60_000L, 25f, this, Looper.getMainLooper())
                }
            }
        }.onFailure { Log.w(TAG, "requestLocationUpdates", it) }
    }

    private fun checkNow() {
        if (!AppGraph.settings.value.placeWatch) {
            stopSelf()
            return
        }
        runCatching { PlaceMonitor.checkArrivals(this) }
        val names = AppGraph.repo.data.places.map { it.name }
        Notifications.showPlaceService(this, names)
    }

    override fun onLocationChanged(location: Location) {
        checkNow()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        runCatching {
            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
            lm?.removeUpdates(this)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TM/PlaceService"
        const val ACTION_STOP = "com.zigorminsk.timemanager.action.STOP_PLACE_WATCH"
    }
}
