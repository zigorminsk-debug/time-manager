package com.zigorminsk.timemanager.place

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.zigorminsk.timemanager.util.WifiMatcher

/** Одна обнаруженная сеть Wi-Fi. */
data class WifiNetwork(
    val ssid: String,
    val bssid: String?,
    /** Уровень сигнала: 0…4. */
    val level: Int,
    val secured: Boolean,
    /** Сеть сохранена на устройстве (если систему удалось об этом спросить). */
    val saved: Boolean,
    /** Телефон прямо сейчас подключён к этой сети. */
    val connected: Boolean,
) {
    /** Значение, которое сохраняется в настройках места. */
    val key: String get() = ssid.ifBlank { bssid ?: "" }
}

/**
 * Чтение списка Wi-Fi сетей: видимые сейчас (сканирование) + сохранённые на устройстве.
 *
 * Начиная с Android 10 система отдаёт список сохранённых сетей только ограниченно,
 * поэтому «сохранена» — подсказка: либо сеть в списке конфигураций, либо к ней
 * уже подключён телефон.
 */
object WifiScanner {

    private const val TAG = "TM/Wifi"
    private const val SCAN_THROTTLE_MS = 90_000L
    private var lastScanRequest = 0L

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val nearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fine || nearby
    }

    fun manager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun isWifiEnabled(context: Context): Boolean =
        manager(context)?.isWifiEnabled == true

    /** Просит систему обновить результаты сканирования (не чаще раза в 90 секунд). */
    fun requestScan(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val now = System.currentTimeMillis()
        if (now - lastScanRequest < SCAN_THROTTLE_MS) return false
        lastScanRequest = now
        val wm = manager(context) ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            wm.startScan()
        }.getOrDefault(false)
    }

    /** Список сохранённых на устройстве сетей (насколько это разрешает система). */
    fun savedSsids(context: Context): Set<String> {
        val wm = manager(context) ?: return emptySet()
        if (!hasPermission(context)) return emptySet()
        return runCatching {
            @Suppress("DEPRECATION")
            val configured = wm.configuredNetworks ?: return emptySet()
            configured.mapNotNull { WifiMatcher.normalize(it.SSID).ifBlank { null } }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Подключение прямо сейчас: SSID и BSSID. */
    fun connected(context: Context): Pair<String?, String?> {
        val wm = manager(context) ?: return null to null
        if (!hasPermission(context)) return null to null
        return runCatching {
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val ssid = WifiMatcher.normalize(info.ssid).ifBlank { null }
            val bssid = WifiMatcher.normalize(info.bssid).ifBlank { null }
            ssid to bssid
        }.getOrDefault(null to null)
    }

    /**
     * Все сети «на виду»: подключённая + результаты сканирования.
     * Отсортированы по полезности: подключённая, сохранённые, затем по уровню сигнала.
     */
    fun scan(context: Context, requestNewScan: Boolean = true): List<WifiNetwork> {
        if (!hasPermission(context)) return emptyList()
        if (requestNewScan) requestScan(context)

        val wm = manager(context) ?: return emptyList()
        val saved = savedSsids(context)
        val (connectedSsid, connectedBssid) = connected(context)
        val byKey = linkedMapOf<String, WifiNetwork>()

        runCatching {
            @Suppress("DEPRECATION")
            val results = wm.scanResults
            results.forEach { result ->
                val ssid = WifiMatcher.normalize(result.SSID)
                val bssid = WifiMatcher.normalize(result.BSSID).ifBlank { null }
                val key = ssid.ifBlank { bssid ?: return@forEach }
                if (key.isEmpty()) return@forEach
                val level = WifiManager.calculateSignalLevel(result.level, 5)
                val isConnected = connectedSsid != null && ssid == connectedSsid
                val isSaved = (ssid.isNotEmpty() && saved.contains(ssid)) ||
                    (bssid != null && saved.contains(bssid)) ||
                    isConnected
                val existing = byKey[key]
                if (existing == null || level > existing.level) {
                    byKey[key] = WifiNetwork(
                        ssid = result.SSID.trim('"'),
                        bssid = result.BSSID,
                        level = level,
                        secured = result.capabilities?.contains("WPA") == true ||
                            result.capabilities?.contains("WEP") == true,
                        saved = isSaved || existing?.saved == true,
                        connected = isConnected || existing?.connected == true,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "scanResults", it) }

        // Подключённую сеть добавляем даже если её нет в результатах сканирования
        if (connectedSsid != null && byKey.keys.none { it == connectedSsid }) {
            byKey[connectedSsid] = WifiNetwork(
                ssid = connectedSsid,
                bssid = connectedBssid,
                level = 4,
                secured = true,
                saved = true,
                connected = true,
            )
        }

        return byKey.values.sortedWith(
            compareByDescending<WifiNetwork> { it.connected }
                .thenByDescending { it.saved }
                .thenByDescending { it.level }
                .thenBy { it.ssid.lowercase() },
        )
    }

    /** Имена всех сетей «рядом» — для проверки прибытия. */
    fun observedNames(context: Context, requestNewScan: Boolean = false): Set<String> {
        val names = mutableSetOf<String>()
        val (connectedSsid, connectedBssid) = connected(context)
        connectedSsid?.let { names += it }
        connectedBssid?.let { names += it }
        scan(context, requestNewScan).forEach {
            if (it.ssid.isNotBlank()) names += it.ssid
            it.bssid?.let { mac -> names += mac }
        }
        return names
    }
}
