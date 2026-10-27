package com.zigorminsk.timemanager.place

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.notify.ReminderEngine
import com.zigorminsk.timemanager.util.WifiMatcher

/**
 * Определение «я на месте» по GPS-радиусу или по известным Wi-Fi сетям.
 *
 * Приоритет источников:
 *  - совпал SSID/BSSID из списка места (работает даже без GPS и в помещении);
 *  - расстояние до координат места меньше радиуса.
 */
object PlaceMonitor {

    private const val TAG = "TM/Place"

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && WifiScanner.hasPermission(context))

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Проверить все «места» и показать баннеры для тех, где мы сейчас находимся. */
    fun checkArrivals(context: Context): List<Place> {
        val repo = AppGraph.repo
        val data = repo.data
        val tasks = data.activeTasks().filter { it.placeId != null && it.dueAt == null }
        if (tasks.isEmpty()) {
            return emptyList()
        }
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "нет разрешения на геолокацию — проверка мест пропущена")
            return emptyList()
        }
        // Если есть места, привязанные к Wi-Fi, просим систему обновить сканирование
        val hasWifiPlace = tasks.any { repo.placeById(it.placeId)?.hasWifi == true }
        val wifi = WifiScanner.observedNames(context, requestNewScan = hasWifiPlace)
        val location = lastLocation(context)
        val arrived = mutableListOf<Place>()

        for (task in tasks) {
            val place = data.placeById(task.placeId) ?: continue
            if (!isAtPlace(place, wifi, location)) continue
            val now = System.currentTimeMillis()
            if (task.snoozedUntil != null && task.snoozedUntil > now) continue
            if (task.notifiedAt != null && now - task.notifiedAt < 10 * 60_000L) continue
            ReminderEngine.fire(context, task.id, ongoing = true)
            arrived += place
        }
        return arrived
    }

    fun isAtPlace(place: Place, wifiNames: Set<String>, location: Location?): Boolean {
        if (place.hasWifi && WifiMatcher.matchesAny(place.wifiNames, wifiNames)) return true
        if (place.hasGeo && location != null) {
            val results = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                place.latitude!!, place.longitude!!,
                results,
            )
            return results[0] <= place.radiusMeters.coerceAtLeast(30)
        }
        return false
    }

    /** Имена сетей «рядом»: подключённая + результаты сканирования (SSID и BSSID). */
    fun currentWifiNames(context: Context): Set<String> = WifiScanner.observedNames(context)

    fun lastLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return runCatching {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            providers.asSequence()
                .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    /** Включить/выключить фоновое слежение в зависимости от настроек и разрешений. */
    fun syncWatching(context: Context, fromBackground: Boolean = false) {
        val enabled = AppGraph.settings.value.placeWatch
        val hasTasks = AppGraph.repo.data.activeTasks().any { it.placeId != null }
        val mayStart = hasLocationPermission(context) &&
            (!fromBackground || hasBackgroundLocation(context))
        if (enabled && hasTasks && mayStart) {
            startWatching(context)
        } else {
            stopWatching(context)
        }
    }

    fun startWatching(context: Context) {
        val intent = Intent(context, PlaceWatchService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { Log.w(TAG, "не удалось запустить слежение", it) }
    }

    fun stopWatching(context: Context) {
        runCatching { context.stopService(Intent(context, PlaceWatchService::class.java)) }
    }

}
