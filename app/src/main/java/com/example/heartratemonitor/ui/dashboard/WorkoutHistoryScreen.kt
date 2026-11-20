package com.example.heartratemonitor.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.example.heartratemonitor.bluetooth.READING_FRESHNESS_MS
import com.example.heartratemonitor.workout.TrainingZone
import com.example.heartratemonitor.workout.WorkoutRecord
import com.example.heartratemonitor.workout.WorkoutSessions
import com.example.heartratemonitor.workout.summarize
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun WorkoutHistoryScreen(workouts: WorkoutSessions, onBack: () -> Unit) {
    var pendingDeletion by remember { mutableStateOf<WorkoutRecord?>(null) }
    BackHandler(onBack = onBack)
    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(insets).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TextButton(onClick = onBack) { Text("Dashboard") }
                Text("Workout history", style = MaterialTheme.typography.headlineMedium)
            }
            workouts.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (workouts.history.isEmpty() && workouts.ready) item { Text("No saved workouts yet") }
            items(workouts.history, key = { it.id }) { record ->
                val summary = remember(record) { record.summarize(READING_FRESHNESS_MS) }
                Panel {
                    Text(formatDate(record), style = MaterialTheme.typography.titleMedium)
                    Text("Workout · ${formatElapsed(record.activeMs)} · Avg HR ${formatBpm(summary.averageBpm)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { workouts.openSummary(record) }) { Text("View workout") }
                        TextButton(onClick = { pendingDeletion = record }, enabled = !workouts.busy) { Text("Delete") }
                    }
                }
            }
        }
    }
    pendingDeletion?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete workout?") },
            text = { Text("This removes the workout and all its readings from this tablet.") },
            confirmButton = {
                Button(onClick = { workouts.delete(record); pendingDeletion = null }) { Text("Delete workout") }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun WorkoutSummaryScreen(record: WorkoutRecord, onBack: () -> Unit) {
    val summary = remember(record) { record.summarize(READING_FRESHNESS_MS) }
    val covered = summary.zoneMs.sum()
    BackHandler(onBack = onBack)
    Scaffold { insets ->
        Column(
            Modifier.fillMaxWidth().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Workout summary", style = MaterialTheme.typography.headlineMedium)
            Text(formatDate(record))
            Panel {
                Text("Active time · ${formatElapsed(record.activeMs)}", style = MaterialTheme.typography.titleLarge)
                Text("Average HR · ${formatBpm(summary.averageBpm)}")
                Text("Peak HR · ${formatBpm(summary.peakBpm?.toDouble())}")
                Text("Workout max HR setting · ${record.maxHr} BPM")
            }
            Panel {
                Text("Time in zones", style = MaterialTheme.typography.titleLarge)
                TrainingZone.entries.forEach { zone ->
                    val duration = summary.zoneMs[zone.ordinal]
                    val percent = if (covered > 0) {
                        String.format(Locale.getDefault(), "%.1f%%", duration * 100.0 / covered)
                    } else "—"
                    Text("${zone.label}  ${formatElapsed(duration)}  ·  $percent", color = zone.color)
                }
                Text("Tracked · ${formatElapsed(covered)}")
                Text("No Data · ${formatElapsed(summary.noDataMs)}")
            }
            Panel {
                Text("Workout heart rate", style = MaterialTheme.typography.titleLarge)
                if (record.samples.isEmpty()) Text("No heart-rate readings") else SummaryChart(record)
            }
            Button(onClick = onBack) { Text("Done") }
        }
    }
}

@Composable
private fun SummaryChart(record: WorkoutRecord) {
    val trace = MaterialTheme.colorScheme.primary
    val samples = record.samples
    val low = minOf(60, samples.minOf { it.bpm } - 5)
    val high = maxOf(180, samples.maxOf { it.bpm } + 5)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        fun offset(timeMs: Long, bpm: Int) = Offset(
            size.width * timeMs.toFloat() / record.activeMs.coerceAtLeast(1),
            size.height * (1f - (bpm - low).toFloat() / (high - low)),
        )
        samples.forEachIndexed { index, sample ->
            val current = offset(sample.elapsedMs, sample.bpm)
            val previous = samples.getOrNull(index - 1)
            if (previous != null && !sample.startsSegment &&
                sample.elapsedMs - previous.elapsedMs <= READING_FRESHNESS_MS
            ) {
                drawLine(trace, offset(previous.elapsedMs, previous.bpm), current, 2.dp.toPx())
            } else {
                drawCircle(trace, 2.dp.toPx(), current)
            }
        }
    }
}

private fun formatBpm(value: Double?) =
    value?.let { String.format(Locale.getDefault(), "%.0f BPM", it) } ?: "—"

private fun formatDate(record: WorkoutRecord): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.startedAtEpochMs))
