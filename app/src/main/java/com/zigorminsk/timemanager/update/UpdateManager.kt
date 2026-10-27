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
 * Приложение смотрит релизы GitHub, скачивает APK (подписанный тем же ключом,
 * что и установленная версия) и передаёт его системному установщику.
 */
object UpdateManager {

    private const val TAG = "TM/Update"
    private const val WORK_CHECK = "tm-update-check"
    private const val API = "https://api.github.com/repos"

    data class ReleaseInfo(
        val version: String,
        val versionCode: Int,
        val notes: String,
        val apkUrl: String,
        val sha256: String?,
        val pageUrl: String,
    )

    fun repo(): String = BuildConfig.UPDATE_REPO.ifBlank { "zigorminsk-debug/time-manager" }

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

    // ------------------------------------------------------------- сеть

    suspend fun fetchLatest(channel: String = AppGraph.settings.value.updateChannel): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            val url = URL("$API/${repo()}/releases?per_page=20")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TimeManager-Android/${BuildConfig.VERSION_NAME}")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "GitHub API ответ ${conn.responseCode}")
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val releases = JSONArray(body)
                for (i in 0 until releases.length()) {
                    val obj = releases.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft")) continue
                    if (channel == "stable" && obj.optBoolean("prerelease")) continue
                    val tag = obj.optString("tag_name").removePrefix("v").removePrefix("V")
                    val notes = obj.optString("body")
                    val asset = obj.optJSONArray("assets")?.let { assets ->
                        (0 until assets.length())
                            .mapNotNull { assets.optJSONObject(it) }
                            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                    } ?: continue
                    val code = Regex("(?i)versioncode\\D{0,4}(\\d+)").find(notes)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val sha = Regex("(?i)sha256\\D{0,4}([a-f0-9]{64})").find(notes)?.groupValues?.get(1)
                    return@withContext ReleaseInfo(
                        version = tag.ifBlank { "0" },
                        versionCode = code,
                        notes = notes,
                        apkUrl = asset.optString("browser_download_url"),
                        sha256 = sha,
                        pageUrl = obj.optString("html_url"),
                    )
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "не удалось получить релизы", e)
                null
            } finally {
                conn.disconnect()
            }
        }

    suspend fun checkForUpdate(context: Context, silent: Boolean): ReleaseInfo? = withContext(Dispatchers.IO) {
        val settings = AppGraph.settings.value
        val release = fetchLatest(settings.updateChannel)
        AppGraph.settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        if (release == null) return@withContext null
        val newer = isNewer(release.versionCode, release.version, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        if (!newer) return@withContext null
        if (settings.autoUpdate) {
            enqueueDownload(context)
        } else {
            Notifications.showUpdateAvailable(context, release.version, release.notes.take(400))
        }
        release
    }

    fun isNewer(remoteCode: Int, remoteName: String, localCode: Int, localName: String): Boolean {
        if (remoteCode > 0 && localCode > 0) return remoteCode > localCode
        return compareVersions(remoteName, localName) > 0
    }

    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

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
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "скачивание: код ${conn.responseCode}")
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
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${repo()}/releases"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

class UpdateCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val silent = inputData.getBoolean("silent", true)
        return try {
            UpdateManager.checkForUpdate(applicationContext, silent)
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
            val release = UpdateManager.fetchLatest() ?: return Result.failure()
            if (!UpdateManager.isNewer(release.versionCode, release.version, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) {
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
