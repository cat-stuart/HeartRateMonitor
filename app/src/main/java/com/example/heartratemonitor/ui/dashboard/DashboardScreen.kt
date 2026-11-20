package com.example.heartratemonitor.ui.dashboard

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartratemonitor.R
import com.example.heartratemonitor.SensorController
import com.example.heartratemonitor.bluetooth.ConnectionState
import com.example.heartratemonitor.bluetooth.READING_FRESHNESS_MS
import com.example.heartratemonitor.bluetooth.SensorState
import com.example.heartratemonitor.workout.TrainingZone
import com.example.heartratemonitor.workout.WorkoutPhase
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

internal const val CHART_WINDOW_MS = 300_000L

data class ChartPoint(val atMs: Long, val bpm: Int, val startsSegment: Boolean)

@Composable
fun DashboardScreen(
    model: SensorController,
    state: SensorState,
    message: String?,
    simulated: Boolean,
    scan: () -> Unit,
    reconnect: () -> Unit,
    connect: (String) -> Unit,
    onTrackingRequested: () -> Unit,
    openNotificationSettings: () -> Unit,
    toggleSimulator: () -> Unit,
) {
    val workouts = model.workouts
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMonitor by rememberSaveable { mutableStateOf(false) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var guidedSetup by rememberSaveable { mutableStateOf(false) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var now by rememberSaveable { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val bpm = state.bpm?.takeIf {
        state.readingAtMs?.let { now - it <= READING_FRESHNESS_MS } == true
    }
    val maxHr = workouts.maxHr ?: model.configuredMaxHr ?: 185
    val zone = bpm?.let { TrainingZone.forReading(it, maxHr) }
    val phase = workouts.phase
    val elapsedMs = workouts.elapsedMs

    workouts.summary?.let { record ->
        WorkoutSummaryScreen(record, workouts::closeSummary)
        return
    }
    if (showHistory) {
        WorkoutHistoryScreen(workouts, onBack = { showHistory = false })
        return
    }

    val dashboardView = LocalView.current
    val keepScreenOn = phase == WorkoutPhase.ACTIVE && model.keepScreenAwake
    DisposableEffect(dashboardView, keepScreenOn) {
        dashboardView.keepScreenOn = keepScreenOn
        onDispose { dashboardView.keepScreenOn = false }
    }

    Scaffold { insets ->
        BoxWithConstraints(Modifier.fillMaxWidth().padding(insets)) {
            val wide = maxWidth >= 680.dp && maxWidth > maxHeight
            val inlineHeader = maxWidth >= 740.dp
            val heartHeight = (maxHeight - if (inlineHeader) 128.dp else 176.dp).coerceAtLeast(280.dp)
            val heartNumberSize = if (wide) {
                minOf(maxWidth.value * 0.22f, heartHeight.value * 0.51f).coerceIn(112f, 290f)
            } else {
                (maxWidth.value * 0.39f).coerceIn(94f, 190f)
            }
            val railWidth = (maxWidth * 0.24f).coerceIn(218.dp, 300.dp)
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(
                    horizontal = if (wide) 24.dp else 18.dp,
                    vertical = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val navigation: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showMonitor = true }) { Text("Monitor") }
                        TextButton(onClick = { showHistory = true }) { Text("History") }
                        TextButton(onClick = { showSettings = true }) { Text("Settings") }
                    }
                }
                if (inlineHeader) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        DashboardLogo()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { navigation() }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        DashboardLogo()
                        navigation()
                    }
                }
                val heart: @Composable (Modifier) -> Unit = { modifier ->
                    HeartPanel(
                        bpm, zone, maxHr, message ?: state.status, wide,
                        heartNumberSize, modifier,
                    )
                }
                val workout: @Composable () -> Unit = {
                    WorkoutPanel(
                        phase, elapsedMs, workouts.ready && !workouts.busy, workouts.restored, workouts.pendingSave,
                        start = {
                            workouts.start(model.configuredMaxHr ?: 185)
                            onTrackingRequested()
                        },
                        pause = workouts::pause,
                        resume = {
                            workouts.resume()
                            onTrackingRequested()
                        },
                        retry = workouts::finish,
                    ) {
                        confirmFinish = true
                    }
                }
                val chart: @Composable () -> Unit = { TrendPanel(model.chartPoints, now, wide) }
                val notices: @Composable () -> Unit = {
                    workouts.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (phase == WorkoutPhase.ACTIVE) {
                        model.trackingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (model.configuredMaxHr == null) {
                        TextButton(onClick = { showSettings = true }) { Text("Set your max HR · 185 BPM provisional") }
                    }
                    if (!simulated && state.savedMonitorName == null) {
                        OutlinedButton(onClick = { showMonitor = true }) { Text("Set up heart-rate monitor") }
                    }
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        heart(Modifier.weight(1f).heightIn(min = heartHeight))
                        Column(Modifier.width(railWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            workout()
                            chart()
                            notices()
                        }
                    }
                } else {
                    heart(Modifier)
                    workout()
                    chart()
                    notices()
                }
            }
        }
    }

    if (!model.setupIntroduced && state.savedMonitorName == null && !simulated && !showMonitor && !showSettings) {
        AlertDialog(
            onDismissRequest = model::acknowledgeSetup,
            title = { Text("Welcome to Zach’s Zones") },
            text = { Text("Connect your Polar H10 to see live heart rate and set your maximum heart rate for training zones.") },
            confirmButton = {
                Button(onClick = {
                    model.acknowledgeSetup()
                    guidedSetup = true
                    showMonitor = true
                }) { Text("Set up monitor") }
            },
            dismissButton = { TextButton(onClick = model::acknowledgeSetup) { Text("Later") } },
        )
    }

    if (showMonitor) {
        AlertDialog(
            onDismissRequest = { showMonitor = false },
            title = { Text("Heart-rate monitor") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(message ?: state.status)
                    state.savedMonitorName?.let { Text(it) }
                    if (!simulated) {
                        Text("Wear your Polar H10 and moisten the electrodes before scanning.")
                        if (state.savedMonitorName != null) {
                            OutlinedButton(onClick = reconnect) { Text("Reconnect") }
                            TextButton(onClick = { confirmForget = true }) { Text("Forget sensor") }
                        }
                        OutlinedButton(
                            onClick = scan,
                            enabled = state.connection != ConnectionState.SCANNING || !state.busy,
                        ) { Text(if (state.savedMonitorName == null) "Scan for monitors" else "Scan to change sensor") }
                        state.devices.forEach { device ->
                            OutlinedButton(onClick = { connect(device.id) }) {
                                Text(device.rssi?.let { "${device.name} · $it dBm" } ?: "${device.name} · Paired")
                            }
                        }
                    }
                    if (com.example.heartratemonitor.BuildConfig.DEBUG) {
                        OutlinedButton(onClick = toggleSimulator) {
                            Text(if (simulated) "Use Bluetooth" else "Use simulator")
                        }
                        if (simulated) {
                            OutlinedButton(onClick = {
                                model.simulateDropout()
                                showMonitor = false
                            }) { Text("Simulate 8-second signal loss") }
                        }
                    }
                }
            },
            confirmButton = {
                if (guidedSetup && state.savedMonitorName != null && model.configuredMaxHr == null) {
                    Button(onClick = {
                        guidedSetup = false
                        showMonitor = false
                        showSettings = true
                    }) { Text("Set max HR") }
                } else {
                    TextButton(onClick = {
                        guidedSetup = false
                        showMonitor = false
                    }) { Text("Done") }
                }
            },
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this sensor?") },
            text = { Text("You can scan again to choose a heart-rate monitor.") },
            confirmButton = {
                Button(onClick = {
                    model.forgetMonitor()
                    confirmForget = false
                }) { Text("Forget sensor") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }

    if (showSettings) {
        DashboardSettingsDialog(
            configured = model.configuredMaxHr,
            bluetoothAllowed = model.monitorPermissionsGranted,
            lastReadingEpochMs = model.lastMonitorReadingEpochMs,
            openHistory = {
                showSettings = false
                showHistory = true
            },
            keepScreenAwake = model.keepScreenAwake,
            onKeepScreenAwakeChanged = model::updateKeepScreenAwake,
            openNotificationSettings = openNotificationSettings,
            close = { showSettings = false },
        ) {
            model.setMaxHeartRate(it)
            showSettings = false
        }
    }
    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish workout?") },
            text = { Text("End the timer and save this workout.") },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    workouts.finish()
                    confirmFinish = false
                }) { Text("Finish workout") }
            },
        )
    }
}

@Composable
private fun DashboardLogo() {
    Image(
        painter = painterResource(R.drawable.zachs_zones_header),
        contentDescription = stringResource(R.string.app_name),
        modifier = Modifier.width(250.dp).aspectRatio(3f),
    )
}

@Composable
private fun HeartPanel(
    bpm: Int?,
    zone: TrainingZone?,
    maxHr: Int,
    status: String,
    wide: Boolean,
    heartNumberSize: Float,
    modifier: Modifier,
) {
    val zoneBackground = zone?.color
    val darkInk = Color(0xFF101723)
    val readingColor = if (zoneBackground != null) darkInk else MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (zoneBackground != null) darkInk else MaterialTheme.colorScheme.onSurfaceVariant

    Panel(
        modifier = modifier,
        backgroundColor = zoneBackground ?: MaterialTheme.colorScheme.surface,
        verticalArrangement = if (wide) Arrangement.SpaceBetween else Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val indicator = if (zoneBackground != null) darkInk else MaterialTheme.colorScheme.outline
            Box(Modifier.size(8.dp).background(indicator, CircleShape))
            Text(status, color = secondaryColor)
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                bpm?.toString() ?: "—",
                color = readingColor,
                fontSize = heartNumberSize.sp,
                lineHeight = heartNumberSize.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text("BPM", style = MaterialTheme.typography.headlineSmall, color = secondaryColor)
        }

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (zone != null && bpm != null) {
                Text(
                    "Zone ${zone.number} · ${zone.label}",
                    color = readingColor,
                    style = MaterialTheme.typography.headlineMedium,
                )
                val percent = String.format(Locale.getDefault(), "%.1f", TrainingZone.percent(bpm, maxHr))
                Text("$percent% of max HR", color = secondaryColor)
            } else {
                Text("Waiting for heart rate", color = secondaryColor)
            }
            val strip = Modifier.fillMaxWidth()
            Row(
                if (zoneBackground != null) strip.background(darkInk, CircleShape).padding(4.dp) else strip,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TrainingZone.entries.forEach { segment ->
                    Box(
                        Modifier.weight(1f).height(if (wide) 12.dp else 8.dp)
                            .background(segment.color.copy(alpha = if (segment == zone) 1f else 0.58f), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutPanel(
    phase: WorkoutPhase,
    elapsedMs: Long,
    enabled: Boolean,
    restored: Boolean,
    pendingSave: Boolean,
    start: () -> Unit,
    pause: () -> Unit,
    resume: () -> Unit,
    retry: () -> Unit,
    finish: () -> Unit,
) {
    Panel {
        Text(
            when (phase) {
                WorkoutPhase.PAUSED -> "Workout paused"
                WorkoutPhase.FINISHED -> "Workout finished"
                else -> "Workout time"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(formatElapsed(elapsedMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        if (restored) Text("Recovered workout · Resume or finish")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (phase) {
                WorkoutPhase.IDLE, WorkoutPhase.FINISHED -> {
                    if (pendingSave) {
                        Button(onClick = retry, enabled = enabled) { Text("Retry save") }
                    } else {
                        Button(onClick = start, enabled = enabled) { Text("Start workout") }
                    }
                }
                WorkoutPhase.ACTIVE -> Button(onClick = pause) { Text("Pause") }
                WorkoutPhase.PAUSED -> Button(onClick = resume) { Text("Resume") }
            }
            if (phase == WorkoutPhase.ACTIVE || phase == WorkoutPhase.PAUSED) {
                OutlinedButton(onClick = finish) { Text("Finish") }
            }
        }
    }
}

@Composable
private fun TrendPanel(points: List<ChartPoint>, now: Long, wide: Boolean) {
    val visible = points.filter { it.atMs >= now - CHART_WINDOW_MS }
    val trace = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Heart rate", style = MaterialTheme.typography.titleMedium)
            Text("Last 5 min", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(if (wide) 70.dp else 100.dp), contentAlignment = Alignment.Center) {
                Text("Waiting for readings", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val low = minOf(60, visible.minOf { it.bpm } - 5)
            val high = maxOf(180, visible.maxOf { it.bpm } + 5)
            Canvas(Modifier.fillMaxWidth().height(if (wide) 70.dp else 100.dp)) {
                for (fraction in listOf(0.05f, 0.5f, 0.95f)) {
                    drawLine(grid, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
                }
                fun position(point: ChartPoint): Offset = Offset(
                    size.width * (1f - (now - point.atMs).toFloat() / CHART_WINDOW_MS).coerceIn(0f, 1f),
                    size.height * (1f - (point.bpm - low).toFloat() / (high - low)),
                )
                var previous: ChartPoint? = null
                for (point in visible) {
                    val last = previous
                    if (last != null && !point.startsSegment && point.atMs - last.atMs <= READING_FRESHNESS_MS) {
                        drawLine(trace, position(last), position(point), 2.dp.toPx(), StrokeCap.Round)
                    } else {
                        drawCircle(trace, 2.dp.toPx(), position(point))
                    }
                    previous = point
                }
            }
        }
    }
}

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(7.dp),
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().background(backgroundColor, shape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.40f),
                shape = shape,
            ).padding(16.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
private fun DashboardSettingsDialog(
    configured: Int?,
    bluetoothAllowed: Boolean,
    lastReadingEpochMs: Long?,
    openHistory: () -> Unit,
    keepScreenAwake: Boolean,
    onKeepScreenAwakeChanged: (Boolean) -> Unit,
    openNotificationSettings: () -> Unit,
    close: () -> Unit,
    save: (Int) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(configured?.toString() ?: "185") }
    val value = input.toIntOrNull()?.takeIf { it in 1..999 }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Keep screen awake", Modifier.weight(1f))
                    Switch(checked = keepScreenAwake, onCheckedChange = onKeepScreenAwakeChanged)
                }
                TextButton(onClick = openNotificationSettings) { Text("Workout notification settings") }
                Text("Maximum heart rate", style = MaterialTheme.typography.titleMedium)
                if (configured == null) Text("185 BPM is the provisional default. Enter your maximum heart rate.")
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) input = it },
                    label = { Text("Max HR (BPM)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                value?.let { max ->
                    TrainingZone.entries.forEach { zone ->
                        val minimumBpm = (max * zone.minimumPercent + 99) / 100
                        val boundary = if (zone == TrainingZone.GRAY) {
                            "Below ${(max * TrainingZone.BLUE.minimumPercent + 99) / 100} BPM"
                        } else {
                            "Starts at $minimumBpm BPM"
                        }
                        Text("Zone ${zone.number} · ${zone.label} · $boundary")
                    }
                }
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                Text(if (bluetoothAllowed) "Bluetooth permissions granted" else "Bluetooth permissions needed")
                val last = lastReadingEpochMs?.let {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                } ?: "No reading this launch"
                Text("Last monitor reading · $last")
                Text("App version · ${com.example.heartratemonitor.BuildConfig.VERSION_NAME}")
                Text("If your monitor is missing, moisten the strap, wear it, and scan again from Monitor.")
                TextButton(onClick = openHistory) { Text("Workout history") }
            }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        confirmButton = { Button(enabled = value != null, onClick = { value?.let(save) }) { Text("Save") } },
    )
}

internal val TrainingZone.color: Color
    get() = when (this) {
        TrainingZone.GRAY -> Color(0xFFE3E9F3)
        TrainingZone.BLUE -> Color(0xFF50BDFF)
        TrainingZone.GREEN -> Color(0xFF54FA80)
        TrainingZone.ORANGE -> Color(0xFFFFAA38)
        TrainingZone.RED -> Color(0xFFFF6675)
    }

internal fun formatElapsed(ms: Long): String {
    val seconds = ms / 1_000
    return if (seconds >= 3_600) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }
}
