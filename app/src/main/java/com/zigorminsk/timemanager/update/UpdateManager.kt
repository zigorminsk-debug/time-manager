package com.zigorminsk.timemanager.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.BuildConfig
import com.zigorminsk.timemanager.notify.Notifications
import com.zigorminsk.timemanager.util.VersionCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Автообновление «поверх» установленной версии.
 *
 * Источники данных (по порядку):
 *  1. GitHub API `/releases` — даёт versionCode, описание, ссылку на APK;
 *  2. резервный способ без API (у него лимит 60 запросов в час на IP) —
 *     редирект `releases/latest/download/...`, из которого берётся тег версии.
 *
 * APK всегда подписан постоянным ключом, поэтому установщик ставит новую версию
 * поверх старой, сохраняя задачи и настройки.
 */
object UpdateManager {

    private const val TAG = "TM/Update"
    private const val WORK_CHECK = "tm-update-check"
    private const val API = "https://api.github.com/repos"
    private const val STABLE_APK = "time-manager.apk"
    private const val SHA_FILE = "sha256.txt"

    /** Постоянные имена файлов в релизе — на них опирается резервная проверка. */
    private const val ASSET_APK = STABLE_APK

    data class ReleaseInfo(
        val version: String,
        val versionCode: Int,
        val notes: String,
        val apkUrl: String,
        val sha256: String?,
        val pageUrl: String,
        val source: String = "api",
    )

    /** Результат проверки — чтобы интерфейс мог объяснить, что именно произошло. */
    sealed interface CheckOutcome {
        data class UpdateAvailable(val release: ReleaseInfo) : CheckOutcome
        data class UpToDate(val current: String, val latest: String?) : CheckOutcome
        data class NoReleases(val current: String) : CheckOutcome
        data class Failed(val reason: String) : CheckOutcome
    }

    internal sealed interface FetchOutcome {
        data class Found(val release: ReleaseInfo) : FetchOutcome
        data object Empty : FetchOutcome
        data class Error(val reason: String) : FetchOutcome
    }

    fun repo(): String = BuildConfig.UPDATE_REPO.ifBlank { "zigorminsk-debug/time-manager" }

    fun releasesPage(): String = "https://github.com/${repo()}/releases"

    // ------------------------------------------------------------- расписание

    fun schedulePeriodic(context: Context, replace: Boolean = false) {
        val hours = AppGraph.settings.value.updateIntervalHours.coerceIn(1, 168).toLong()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_CHECK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Лёгкая проверка «не пора ли» — вызывается из периодического heartbeat. */
    fun maybeAutoCheck(context: Context) {
        val settings = AppGraph.settings.value
        if (!settings.autoUpdate) return
        val intervalMs = settings.updateIntervalHours * 3_600_000L
        if (System.currentTimeMillis() - settings.lastUpdateCheck < intervalMs) return
        enqueueCheck(context, silent = true)
    }

    fun enqueueCheck(context: Context, silent: Boolean) {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setInputData(workDataOf("silent" to silent))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching { WorkManager.getInstance(context).enqueue(request) }
    }

    fun enqueueDownload(context: Context) {
        val request = OneTimeWorkRequestBuilder<DownloadUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching { WorkManager.getInstance(context).enqueue(request) }
    }

    // ------------------------------------------------------------- проверка

    /**
     * Главная функция проверки.
     * @param autoDownload ставить ли загрузку в очередь (для ручной проверки из UI — false,
     *                     чтобы сразу скачать и открыть установщик).
     */
    suspend fun checkForUpdate(
        context: Context,
        silent: Boolean,
        autoDownload: Boolean = true,
    ): CheckOutcome =
        withContext(Dispatchers.IO) {
            val settings = AppGraph.settings.value
            AppGraph.settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }

            when (val fetched = fetchLatest(settings.updateChannel)) {
                is FetchOutcome.Error -> CheckOutcome.Failed(fetched.reason)
                FetchOutcome.Empty -> CheckOutcome.NoReleases(BuildConfig.VERSION_NAME)
                is FetchOutcome.Found -> {
                    val release = fetched.release
                    val newer = isNewer(
                        release.versionCode,
                        release.version,
                        BuildConfig.VERSION_CODE,
                        BuildConfig.VERSION_NAME,
                    )
                    if (!newer) {
                        return@withContext CheckOutcome.UpToDate(
                            current = BuildConfig.VERSION_NAME,
                            latest = release.version,
                        )
                    }
                    if (autoDownload && settings.autoUpdate) {
                        enqueueDownload(context)
                    } else if (!settings.autoUpdate) {
                        Notifications.showUpdateAvailable(context, release.version, release.notes.take(400))
                    }
                    CheckOutcome.UpdateAvailable(release)
                }
            }
        }

    /** Пробует API, затем резервный способ через редирект загрузки. */
    suspend fun fetchLatest(channel: String = AppGraph.settings.value.updateChannel): FetchOutcome =
        withContext(Dispatchers.IO) {
            val viaApi = fetchViaApi(channel)
            if (viaApi is FetchOutcome.Found || viaApi is FetchOutcome.Empty) return@withContext viaApi

            // API недоступен (лимит запросов, нет сети у api.github.com) — берём редирект
            val viaRedirect = fetchViaRedirect()
            if (viaRedirect != null) return@withContext FetchOutcome.Found(viaRedirect)
            viaApi
        }

    private fun fetchViaApi(channel: String): FetchOutcome {
        val url = URL("$API/${repo()}/releases?per_page=20")
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TimeManager-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val reason = when (code) {
                    403, 429 -> "GitHub ограничил частоту запросов. Попробуйте позже."
                    404 -> "Релизы не найдены (репозиторий или релиз недоступен)."
                    else -> "GitHub ответил кодом $code."
                }
                Log.w(TAG, "API: $code")
                return FetchOutcome.Error(reason)
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val obj = releases.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft")) continue
                if (channel == "stable" && obj.optBoolean("prerelease")) continue
                val release = parseRelease(obj) ?: continue
                return FetchOutcome.Found(release)
            }
            FetchOutcome.Empty
        } catch (e: Exception) {
            Log.w(TAG, "API недоступен", e)
            FetchOutcome.Error("Нет связи с GitHub: ${e.message ?: e::class.java.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseRelease(obj: org.json.JSONObject): ReleaseInfo? {
        val tag = obj.optString("tag_name").removePrefix("v").removePrefix("V")
        val notes = obj.optString("body")
        val assets = obj.optJSONArray("assets") ?: return null
        val assetList = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val apkAsset = assetList.firstOrNull { it.optString("name").equals(ASSET_APK, true) }
            ?: assetList.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return null
        val code = Regex("(?i)versioncode\\D{0,6}(\\d+)").find(notes)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sha = Regex("(?i)sha256\\D{0,6}([a-f0-9]{64})").find(notes)?.groupValues?.get(1)
        return ReleaseInfo(
            version = tag.ifBlank { "0" },
            versionCode = code,
            notes = notes,
            apkUrl = apkAsset.optString("browser_download_url"),
            sha256 = sha,
            pageUrl = obj.optString("html_url"),
            source = "api",
        )
    }

    /**
     * Резервная проверка без API: `releases/latest/download/sha256.txt` отдаёт редирект,
     * в адресе которого есть тег версии, а в теле — контрольная сумма APK.
     */
    private fun fetchViaRedirect(): ReleaseInfo? {
        val shaUrl = "https://github.com/${repo()}/releases/latest/download/$SHA_FILE"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(shaUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TimeManager-Android/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode !in 200..299) return null
            val finalUrl = conn.url.toString()
            val tag = VersionCompare.tagFromDownloadUrl(finalUrl) ?: return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val sha = VersionCompare.shaForFile(text, ASSET_APK)
            ReleaseInfo(
                version = tag,
                versionCode = 0,
                notes = "Версия $tag",
                apkUrl = "https://github.com/${repo()}/releases/latest/download/$ASSET_APK",
                sha256 = sha,
                pageUrl = releasesPage(),
                source = "redirect",
            )
        } catch (e: Exception) {
            Log.w(TAG, "резервная проверка не удалась", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun isNewer(remoteCode: Int, remoteName: String, localCode: Int, localName: String): Boolean =
        VersionCompare.isNewer(remoteCode, remoteName, localCode, localName)

    fun compareVersions(a: String, b: String): Int = VersionCompare.compare(a, b)

    // ------------------------------------------------------------- скачивание

    fun updatesDir(context: Context): File = File(context.cacheDir, "updates").apply { mkdirs() }

    fun latestApk(context: Context): File? = updatesDir(context)
        .listFiles()
        ?.filter { it.isFile && it.extension.equals("apk", true) }
        ?.maxByOrNull { it.lastModified() }

    suspend fun download(context: Context, release: ReleaseInfo): File? = withContext(Dispatchers.IO) {
        val dir = updatesDir(context)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val target = File(dir, "app-${release.version}.apk")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TimeManager-Android/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "скачивание: код $code (${release.apkUrl})")
                return@withContext null
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPercent = -1
                    while (input.read(buffer).also { read = it } > 0) {
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent && percent % 10 == 0) {
                                lastPercent = percent
                                showProgress(context, percent)
                            }
                        }
                    }
                }
            }
            if (total > 0 && target.length() != total) {
                Log.w(TAG, "размер файла не совпал: ${target.length()} != $total")
                target.delete()
                return@withContext null
            }
            val expected = release.sha256
            if (!expected.isNullOrBlank()) {
                val actual = sha256(target)
                if (!actual.equals(expected, ignoreCase = true)) {
                    Log.w(TAG, "sha256 не совпал: $actual != $expected")
                    target.delete()
                    return@withContext null
                }
            }
            Notifications.cancel(context, Notifications.ID_UPDATE_READY)
            target
        } catch (e: Exception) {
            Log.w(TAG, "ошибка скачивания", e)
            runCatching { target.delete() }
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun showProgress(context: Context, percent: Int) {
        runCatching {
            val builder = NotificationCompat.Builder(context, Notifications.CH_UPDATES)
                .setSmallIcon(com.zigorminsk.timemanager.R.drawable.ic_notification)
                .setContentTitle("Загрузка обновления")
                .setContentText("$percent%")
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            NotificationManagerCompat.from(context).notify(Notifications.ID_UPDATE_READY, builder.build())
        }
    }

    // ------------------------------------------------------------- установка

    fun installLatestDownloaded(context: Context) {
        val apk = latestApk(context)
        if (apk == null) {
            enqueueDownload(context)
            return
        }
        install(context, apk)
    }

    fun install(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { openReleasePage(context) }
    }

    fun openReleasePage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(releasesPage()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

class UpdateCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        return try {
            UpdateManager.checkForUpdate(applicationContext, inputData.getBoolean("silent", true))
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class DownloadUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        return try {
            val fetched = UpdateManager.fetchLatest()
            val release = (fetched as? UpdateManager.FetchOutcome.Found)?.release ?: return Result.success()
            if (!UpdateManager.isNewer(
                    release.versionCode,
                    release.version,
                    BuildConfig.VERSION_CODE,
                    BuildConfig.VERSION_NAME,
                )
            ) {
                return Result.success()
            }
            UpdateManager.download(applicationContext, release) ?: return Result.retry()
            Notifications.showUpdateReady(applicationContext, release.version)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
