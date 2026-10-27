package com.zigorminsk.timemanager.place

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.notify.ReminderEngine

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
            PackageManager.PERMISSION_GRANTED

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
        val wifi = currentWifiNames(context)
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
        if (place.hasWifi) {
            val normalized = wifiNames.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
            val matched = place.wifiNames.any { known ->
                val n = normalize(known)
                n.isNotEmpty() && normalized.any { it == n || it.startsWith(n) || n.startsWith(it) }
            }
            if (matched) return true
        }
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

    /** SSID и BSSID подключённой сети + результаты последнего сканирования. */
    fun currentWifiNames(context: Context): Set<String> {
        val names = mutableSetOf<String>()
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return names
        val canRead = hasLocationPermission(context)

        runCatching {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (canRead) {
                cleanSsid(info.ssid)?.let { names += it }
                cleanSsid(info.bssid)?.let { names += it }
            }
        }.onFailure { Log.w(TAG, "connectionInfo", it) }

        if (canRead) {
            runCatching {
                @Suppress("DEPRECATION")
                wm.scanResults?.forEach { result ->
                    cleanSsid(result.SSID)?.let { names += it }
                    cleanSsid(result.BSSID)?.let { names += it }
                }
            }.onFailure { Log.w(TAG, "scanResults", it) }
        }
        return names
    }

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

    private fun cleanSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().trim('"')
        if (cleaned.equals("<unknown ssid>", true) || cleaned == "0x" || cleaned.isEmpty()) return null
        return cleaned
    }

    private fun normalize(value: String): String =
        cleanSsid(value)?.lowercase()?.trim() ?: ""
}
