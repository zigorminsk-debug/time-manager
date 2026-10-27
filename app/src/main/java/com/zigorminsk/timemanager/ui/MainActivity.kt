package com.zigorminsk.timemanager.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.zigorminsk.timemanager.AppGraph
import com.zigorminsk.timemanager.model.Place
import com.zigorminsk.timemanager.model.Settings
import com.zigorminsk.timemanager.model.Task
import com.zigorminsk.timemanager.notify.Notifications
import com.zigorminsk.timemanager.notify.ReminderEngine
import com.zigorminsk.timemanager.place.PlaceMonitor
import com.zigorminsk.timemanager.update.UpdateManager
import com.zigorminsk.timemanager.util.CrashReporter
import com.zigorminsk.timemanager.util.RuTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)
        handleIntent(intent)
        setContent { AppRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            AppGraph.init(applicationContext)
            ReminderEngine.checkDueNow(this)
            PlaceMonitor.syncWatching(this)
            UpdateManager.schedulePeriodic(this)
            UpdateManager.maybeAutoCheck(this)
        }.onFailure { CrashReporter.log(this, "onResume", it) }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifications.EXTRA_TASK_ID)?.let { openTask.value = it }
        intent.getStringExtra("open_tab")?.let { openTab.value = it }
    }

    companion object {
        val openTask = MutableStateFlow<String?>(null)
        val openTab = MutableStateFlow<String?>(null)
    }
}

private enum class Tab(val title: String, val short: String) {
    TODAY("Сегодня", "Сегодня"),
    ALL("Все задачи", "Все"),
    ARCHIVE("Архив", "Архив"),
    PLACES("Места", "Места"),
    SETTINGS("Настройки", "Настройки"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val data by AppGraph.repo.state.collectAsState()
    val settings by AppGraph.settings.state.collectAsState()
    val speaking by AppGraph.speaker.speaking.collectAsState()
    val voiceMissing by AppGraph.speaker.russianVoiceMissing.collectAsState()
    val now = rememberNow()

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY.name) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }
    var moving by remember { mutableStateOf<Task?>(null) }
    var deleting by remember { mutableStateOf<Task?>(null) }
    var placeDialog by remember { mutableStateOf(false) }
    var placeToEdit by remember { mutableStateOf<Place?>(null) }
    var placeForNewTask by remember { mutableStateOf<Place?>(null) }
    var crashReport by remember { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentTab = Tab.entries.firstOrNull { it.name == tab } ?: Tab.TODAY

    // При первом запуске просим уведомления и геолокацию
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("asked_permissions", false)) {
            prefs.edit().putBoolean("asked_permissions", true).apply()
            val permissions = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    val taskToOpen by MainActivity.openTask.collectAsState()
    LaunchedEffect(taskToOpen) {
        val id = taskToOpen ?: return@LaunchedEffect
        AppGraph.repo.taskById(id)?.let { editing = it; editorOpen = true }
        MainActivity.openTask.value = null
    }
    val tabRequest by MainActivity.openTab.collectAsState()
    LaunchedEffect(tabRequest) {
        if (tabRequest == "places") tab = Tab.PLACES.name
        MainActivity.openTab.value = null
    }
    LaunchedEffect(Unit) {
        if (CrashReporter.hasCrash(context)) {
            crashReport = CrashReporter.report(context)
        }
    }
    LaunchedEffect(voiceMissing) {
        if (voiceMissing) {
            snackbar.showSnackbar("Русский голос для синтеза речи не установлен. Android: Настройки → Синтез речи.")
        }
    }

    val actions = TaskActions(
        edit = { editing = it; editorOpen = true },
        done = { ReminderEngine.complete(context, it.id) },
        postpone = { task, minutes ->
            ReminderEngine.snooze(context, task.id, minutes)
            scope.launch { snackbar.showSnackbar("Отложено на $minutes мин") }
        },
        move = { moving = it },
        archive = { AppGraph.repo.archiveTask(it.id) },
        delete = { deleting = it },
        restore = { AppGraph.repo.restoreTask(it.id) },
    )

    TimeManagerTheme(themeMode = settings.themeMode, fontScale = settings.fontScale) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(currentTab.title, fontWeight = FontWeight.SemiBold)
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (currentTab == Tab.TODAY || currentTab == Tab.ALL) {
                    ExtendedFloatingActionButton(
                        onClick = { editing = null; placeForNewTask = null; editorOpen = true },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("Задача") },
                    )
                }
            },
            bottomBar = {
                BoxWithConstraints {
                    // На узком телефоне пять подписей не помещаются — оставляем только выбранную
                    val compactBar = maxWidth < 380.dp
                    NavigationBar {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = currentTab == item,
                                onClick = { tab = item.name },
                                alwaysShowLabel = false,
                                icon = {
                                    Icon(
                                        when (item) {
                                            Tab.TODAY -> Icons.Default.DateRange
                                            Tab.ALL -> AppIcons.List
                                            Tab.ARCHIVE -> AppIcons.Archive
                                            Tab.PLACES -> Icons.Default.Place
                                            Tab.SETTINGS -> Icons.Default.Settings
                                        },
                                        contentDescription = item.title,
                                    )
                                },
                                label = if (!compactBar || currentTab == item) {
                                    {
                                        Text(
                                            item.short,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            AdaptiveScreen(Modifier.padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                when (currentTab) {
                    Tab.TODAY -> TodayScreen(
                        data = data,
                        now = now,
                        speaking = speaking,
                        actions = actions,
                        onSpeakDay = {
                            val zone = ZoneId.systemDefault()
                            val today = RuTime.today(zone)
                            val current = System.currentTimeMillis()
                            val forToday = data.tasks.filter {
                                it.isActive && it.dueAt != null &&
                                    RuTime.dateOf(it.dueAt, zone) == today
                            }
                            val overdue = data.tasks.filter {
                                it.isActive && it.dueAt != null && it.dueAt < current
                            }
                            val placeTasks = data.tasks.filter { it.isActive && it.placeId != null && it.dueAt == null }
                            val text = RuTime.speakDay(
                                (overdue + forToday).distinctBy { it.id }.sortedBy { it.dueAt },
                                placeTasks,
                            )
                            AppGraph.speaker.speak(text, settings.speechRate)
                        },
                        onStopSpeaking = { AppGraph.speaker.stop() },
                        header = {
                            if (!notificationsEnabled(context)) {
                                PermissionCard(
                                    text = "Уведомления выключены — напоминания не придут.",
                                    button = "Разрешить",
                                    onClick = { openNotificationSettings(context) },
                                )
                            } else if (!AlarmSchedulerHelper.canExact(context)) {
                                PermissionCard(
                                    text = "Нет разрешения на точные будильники — напоминания могут опаздывать.",
                                    button = "Исправить",
                                    onClick = { openExactAlarmSettings(context) },
                                )
                            }
                        },
                    )

                    Tab.ALL -> AllTasksScreen(data = data, now = now, actions = actions)

                    Tab.ARCHIVE -> ArchiveScreen(data = data, actions = actions)

                    Tab.PLACES -> PlacesScreen(
                        data = data,
                        onEditPlace = { placeToEdit = it; placeDialog = true },
                        onAddPlace = { placeToEdit = null; placeDialog = true },
                        onAddTaskForPlace = { place -> editing = null; placeForNewTask = place; editorOpen = true },
                    )

                    Tab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onSettings = { transform -> AppGraph.settings.update(transform) },
                        onCheckUpdate = {
                            scope.launch {
                                snackbar.showSnackbar("Проверяю обновления…")
                                val outcome = runCatching {
                                    UpdateManager.checkForUpdate(context, silent = false, autoDownload = false)
                                }.getOrElse { throwable ->
                                    UpdateManager.CheckOutcome.Failed(
                                        throwable.message ?: throwable::class.java.simpleName,
                                    )
                                }
                                when (outcome) {
                                    is UpdateManager.CheckOutcome.UpdateAvailable -> {
                                        if (!AppGraph.settings.value.autoUpdate) {
                                            snackbar.showSnackbar(
                                                "Доступно обновление ${outcome.release.version} — скачайте вручную",
                                                actionLabel = "Скачать",
                                            )
                                        } else {
                                            snackbar.showSnackbar("Скачиваю ${outcome.release.version}…")
                                            val file = UpdateManager.download(context, outcome.release)
                                            if (file != null) {
                                                snackbar.showSnackbar("Загружено — открываю установщик")
                                                UpdateManager.install(context, file)
                                            } else {
                                                val action = snackbar.showSnackbar(
                                                    "Не удалось скачать обновление",
                                                    actionLabel = "Страница релизов",
                                                )
                                                if (action == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                                    UpdateManager.openReleasePage(context)
                                                }
                                            }
                                        }
                                    }
                                    is UpdateManager.CheckOutcome.UpToDate -> {
                                        val latest = outcome.latest
                                        snackbar.showSnackbar(
                                            "Установлена последняя версия ${outcome.current}" +
                                                if (latest != null && latest != outcome.current) " (на GitHub: $latest)" else "",
                                        )
                                    }
                                    is UpdateManager.CheckOutcome.NoReleases ->
                                        snackbar.showSnackbar(
                                            "На GitHub пока нет опубликованных версий с APK",
                                            actionLabel = "Релизы",
                                        ).let { result ->
                                            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                                UpdateManager.openReleasePage(context)
                                            }
                                        }
                                    is UpdateManager.CheckOutcome.Failed -> snackbar.showSnackbar(
                                        "Не удалось проверить обновления: ${outcome.reason}",
                                        actionLabel = "Релизы",
                                    ).let { result ->
                                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                            UpdateManager.openReleasePage(context)
                                        }
                                    }
                                }
                            }
                        },
                        onSpeakTest = {
                            AppGraph.speaker.speak(
                                "Привет! Я напомню о задачах. Сейчас " + RuTime.formatTime(System.currentTimeMillis()) + ".",
                                settings.speechRate,
                            )
                        },
                        onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                    )
                }
                }
            }
        }

        if (editorOpen) {
            TaskEditorSheet(
                task = editing,
                initialPlace = placeForNewTask,
                places = data.places,
                allTasks = data.tasks,
                onDismiss = { editorOpen = false; editing = null; placeForNewTask = null },
                onSave = { task ->
                    runCatching {
                        val exists = AppGraph.repo.taskById(task.id) != null
                        if (exists) AppGraph.repo.updateTask(task) else AppGraph.repo.addTask(task)
                    }.onFailure {
                        CrashReporter.log(context, "Сохранение задачи", it)
                        scope.launch { snackbar.showSnackbar("Не удалось сохранить: ${it.message ?: "ошибка"}") }
                    }
                    editorOpen = false
                    editing = null
                    placeForNewTask = null
                    val message = when {
                        task.dueAt != null -> "Напомню ${RuTime.formatDue(task.dueAt, task.hasTime)}"
                        task.placeId != null -> "Напомню по прибытии: ${task.placeName}"
                        else -> "Задача сохранена"
                    }
                    scope.launch { snackbar.showSnackbar(message) }
                },
                onAddPlace = { placeToEdit = null; placeDialog = true },
                onMessage = { scope.launch { snackbar.showSnackbar(it) } },
            )
        }

        if (placeDialog) {
            PlaceEditorDialog(
                place = placeToEdit,
                onDismiss = { placeDialog = false },
                onMessage = { scope.launch { snackbar.showSnackbar(it) } },
                onSave = { place ->
                    if (AppGraph.repo.placeById(place.id) != null) {
                        AppGraph.repo.updatePlace(place)
                    } else {
                        AppGraph.repo.addPlace(place)
                    }
                    placeDialog = false
                    scope.launch { snackbar.showSnackbar("Место «${place.name}» сохранено") }
                },
            )
        }

        moving?.let { task ->
            MoveTaskDialog(
                task = task,
                onDismiss = { moving = null },
                onMoved = { millis, hasTime ->
                    AppGraph.repo.moveTask(task.id, millis, hasTime)
                    moving = null
                    scope.launch {
                        snackbar.showSnackbar("Перенесено на ${RuTime.formatDue(millis, hasTime)}")
                    }
                },
            )
        }

        crashReport?.let { report ->
            CrashReportDialog(
                report = report,
                onDismiss = {
                    CrashReporter.clearCrash(context)
                    crashReport = null
                },
            )
        }

        deleting?.let { task ->
            ConfirmDialog(
                title = "Удалить задачу?",
                text = "«${task.title}» будет удалена навсегда.",
                confirmText = "Удалить",
                destructive = true,
                onConfirm = {
                    AppGraph.repo.deleteTask(task.id)
                    Notifications.cancelTask(context, task.id)
                },
                onDismiss = { deleting = null },
            )
        }
    }
}

@Composable
private fun PermissionCard(text: String, button: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

fun openNotificationSettings(context: Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun openExactAlarmSettings(context: Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object AlarmSchedulerHelper {
    fun canExact(context: Context): Boolean = com.zigorminsk.timemanager.notify.AlarmScheduler.canScheduleExact(context)
}
