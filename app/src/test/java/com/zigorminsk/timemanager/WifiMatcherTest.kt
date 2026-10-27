package com.zigorminsk.timemanager

import com.zigorminsk.timemanager.util.WifiMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сопоставление Wi-Fi сетей: по SSID и по MAC-адресу точки доступа. */
class WifiMatcherTest {

    @Test
    fun `ssid сравнивается без учёта регистра и кавычек`() {
        assertTrue(WifiMatcher.matches("Home_5G", listOf("home_5g")))
        assertTrue(WifiMatcher.matches("\"Home_5G\"", listOf("HOME_5G")))
        assertTrue(WifiMatcher.matches(" Дом ", listOf("дом")))
    }

    @Test
    fun `пустые и служебные значения не совпадают`() {
        assertFalse(WifiMatcher.matches("", listOf("Home")))
        assertFalse(WifiMatcher.matches("Home", listOf("", "  ")))
        assertFalse(WifiMatcher.matches("<unknown ssid>", listOf("<unknown ssid>")))
    }

    @Test
    fun `соседняя сеть с похожим именем не считается своей`() {
        // раньше сравнение по префиксу давало ложное срабатывание
        assertFalse(WifiMatcher.matches("Home", listOf("Home_Neighbor")))
        assertFalse(WifiMatcher.matches("Home", listOf("MyHome")))
    }

    @Test
    fun `mac адрес сравнивается в любом формате`() {
        assertTrue(WifiMatcher.matches("AA:BB:CC:DD:EE:FF", listOf("aabbccddeeff")))
        assertTrue(WifiMatcher.matches("aa-bb-cc-dd-ee-ff", listOf("AA:BB:CC:DD:EE:FF")))
        assertTrue(WifiMatcher.matches("aabbccddeeff", listOf("aa:bb:cc:dd:ee:ff")))
        assertFalse(WifiMatcher.matches("AA:BB:CC:DD:EE:FF", listOf("AA:BB:CC:DD:EE:00")))
    }

    @Test
    fun `определение что это сеть или mac`() {
        assertTrue(WifiMatcher.isMac("AA:BB:CC:DD:EE:FF"))
        assertTrue(WifiMatcher.isMac("aabbccddeeff"))
        assertFalse(WifiMatcher.isMac("Home_5G"))
        assertFalse(WifiMatcher.isMac(""))
    }

    @Test
    fun `совпадение хотя бы одной сети места`() {
        val nearby = setOf("Home_5G", "aa:bb:cc:dd:ee:ff", "CafeGuest")
        assertTrue(WifiMatcher.matchesAny(listOf("Работа", "home_5g"), nearby))
        assertTrue(WifiMatcher.matchesAny(listOf("AABBCCDDEEFF"), nearby))
        assertFalse(WifiMatcher.matchesAny(listOf("Работа", "Дача"), nearby))
    }
}
