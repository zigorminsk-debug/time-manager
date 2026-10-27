package com.zigorminsk.timemanager

import com.zigorminsk.timemanager.util.VersionCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логика проверки обновлений: сравнение версий и разбор данных релиза. */
class UpdateCheckTest {

    @Test
    fun `versionCode из описания релиза`() {
        val notes = """
            Автоматическая сборка.

            versionCode: 1012
            versionName: 1.0.12
            sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef

            APK подписан постоянным ключом.
        """.trimIndent()
        assertEquals(1012, VersionCompare.parseVersionCode(notes))
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            VersionCompare.parseSha256(notes),
        )
    }

    @Test
    fun `versionCode без описания`() {
        assertEquals(0, VersionCompare.parseVersionCode("просто текст без данных"))
        assertNull(VersionCompare.parseSha256(""))
    }

    @Test
    fun `сравнение версий по числам`() {
        assertTrue(VersionCompare.compare("1.0.12", "1.0.9") > 0)
        assertTrue(VersionCompare.compare("1.1.0", "1.0.99") > 0)
        assertTrue(VersionCompare.compare("2.0.0", "1.99.99") > 0)
        assertEquals(0, VersionCompare.compare("1.0.5", "1.0.5"))
        assertEquals(0, VersionCompare.compare("v1.0.5", "1.0.5"))
        assertEquals(0, VersionCompare.compare("1.0.5-debug", "1.0.5"))
        assertTrue(VersionCompare.compare("1.0.5", "1.0.6") < 0)
    }

    @Test
    fun `обновление нужно когда versionCode больше`() {
        assertTrue(VersionCompare.isNewer(1012, "1.0.12", 1007, "1.0.7"))
        assertFalse(VersionCompare.isNewer(1007, "1.0.7", 1012, "1.0.12"))
        assertFalse(VersionCompare.isNewer(1012, "1.0.12", 1012, "1.0.12"))
    }

    @Test
    fun `если versionCode неизвестен сравниваем названия`() {
        // резервная проверка через редирект не знает versionCode
        assertTrue(VersionCompare.isNewer(0, "1.0.12", 1007, "1.0.7"))
        assertFalse(VersionCompare.isNewer(0, "1.0.7", 1007, "1.0.7"))
        assertFalse(VersionCompare.isNewer(0, "1.0.6", 1007, "1.0.7"))
    }

    @Test
    fun `тег версии из адреса загрузки`() {
        assertEquals(
            "1.0.12",
            VersionCompare.tagFromDownloadUrl(
                "https://github.com/zigorminsk-debug/time-manager/releases/download/v1.0.12/sha256.txt",
            ),
        )
        assertEquals(
            "1.0.12",
            VersionCompare.tagFromDownloadUrl(
                "https://objects.githubusercontent.com/releases/download/1.0.12/time-manager.apk",
            ),
        )
        assertNull(VersionCompare.tagFromDownloadUrl("https://github.com/zigorminsk-debug/time-manager"))
    }

    @Test
    fun `хэш нужного файла из sha256_txt`() {
        val content = """
            4d1f2a0c5b6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708  time-manager-1.0.12.apk
            aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899  time-manager.apk
        """.trimIndent()
        assertEquals(
            "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            VersionCompare.shaForFile(content, "time-manager.apk"),
        )
        assertEquals(
            "4d1f2a0c5b6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708",
            VersionCompare.shaForFile(content, "time-manager-1.0.12.apk"),
        )
        // если имя не найдено — берём первую строку
        assertEquals(
            "4d1f2a0c5b6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708",
            VersionCompare.shaForFile(content, "unknown.apk"),
        )
    }
}
