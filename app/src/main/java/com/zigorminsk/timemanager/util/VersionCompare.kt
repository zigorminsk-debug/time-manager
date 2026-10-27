package com.zigorminsk.timemanager.util

/**
 * Сравнение версий и разбор служебных данных релиза.
 * Не зависит от Android — покрыто юнит-тестами.
 */
object VersionCompare {

    /**
     * Нужно ли обновление.
     * Если известен versionCode (из описания релиза) — сравниваем его,
     * иначе откатываемся на сравнение строк версий.
     */
    fun isNewer(remoteCode: Int, remoteName: String, localCode: Int, localName: String): Boolean {
        if (remoteCode > 0 && localCode > 0) return remoteCode > localCode
        if (remoteCode > 0) return true
        return compare(remoteName, localName) > 0
    }

    /** Сравнение «1.0.12» и «1.0.9» по числовым частям; суффикс после «-» игнорируется. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .mapNotNull { it.trim().toIntOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** versionCode из описания релиза: «versionCode: 1012». */
    fun parseVersionCode(notes: String): Int =
        Regex("(?i)versioncode\\D{0,6}(\\d+)").find(notes)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** sha256 из описания релиза. */
    fun parseSha256(notes: String): String? =
        Regex("(?i)sha256\\D{0,6}([a-fA-F0-9]{64})").find(notes)?.groupValues?.get(1)?.lowercase()

    /** Тег версии из адреса вида `.../releases/download/v1.0.12/sha256.txt`. */
    fun tagFromDownloadUrl(url: String): String? {
        val raw = Regex("/releases/download/([^/]+)/").find(url)?.groupValues?.get(1) ?: return null
        return raw.removePrefix("v").removePrefix("V").takeIf { it.isNotBlank() }
    }

    /** Ищет в sha256.txt строку для нужного файла и возвращает хэш. */
    fun shaForFile(shaFileContent: String, fileName: String): String? {
        val lines = shaFileContent.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1].trimStart('*').equals(fileName, ignoreCase = true)) {
                return parts[0].lowercase()
            }
        }
        return lines.firstOrNull()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
    }
}
