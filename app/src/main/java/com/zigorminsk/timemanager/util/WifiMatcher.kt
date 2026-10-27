package com.zigorminsk.timemanager.util

/**
 * Сопоставление Wi-Fi сетей без зависимостей от Android — покрыто юнит-тестами.
 *
 * Правила:
 *  - SSID сравниваются без учёта регистра и кавычек;
 *  - BSSID (MAC-адрес) сравниваются в нормализованном виде: без разделителей,
 *    в нижнем регистре, чтобы «AA:BB:CC:DD:EE:FF» совпадал с «aabbccddeeff»;
 *  - для SSID допускается только точное совпадение, для MAC — точное после
 *    нормализации. Это защищает от ложных срабатываний вида «Home» ↔ «Home_Neighbor».
 */
object WifiMatcher {

    private val MAC_LIKE = Regex("^[0-9a-f]{12}$")
    private val MAC_PREFIX = Regex("^[0-9a-f]{2}([:-])[0-9a-f]{2}(\\1[0-9a-f]{2}){4}$")

    /** Убирает кавычки и пробелы, приводит к нижнему регистру. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val cleaned = raw.trim().trim('"').trim()
        if (cleaned.equals("<unknown ssid>", ignoreCase = true)) return ""
        return cleaned.lowercase()
    }

    /** MAC-адрес в виде «aabbccddeeff» → true. */
    fun isMac(raw: String): Boolean {
        val n = normalize(raw)
        return n.matches(MAC_LIKE) || n.matches(MAC_PREFIX)
    }

    private fun normalizeMac(raw: String): String =
        normalize(raw).replace(":", "").replace("-", "")

    /** Совпадает ли известная сеть из настроек места с одной из обнаруженных. */
    fun matches(known: String, observed: Collection<String>): Boolean {
        val knownNorm = normalize(known)
        if (knownNorm.isEmpty()) return false
        val knownIsMac = isMac(known)
        return observed.any { candidate ->
            val candNorm = normalize(candidate)
            if (candNorm.isEmpty()) return@any false
            if (knownIsMac || isMac(candidate)) {
                val a = normalizeMac(known)
                val b = normalizeMac(candidate)
                a.isNotEmpty() && a == b
            } else {
                knownNorm == candNorm
            }
        }
    }

    /** true, если телефон находится в зоне хотя бы одной из сетей места. */
    fun matchesAny(knownNetworks: Collection<String>, observed: Collection<String>): Boolean =
        knownNetworks.any { matches(it, observed) }
}
