package com.example.heartratemonitor.workout

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WorkoutSessions internal constructor(
    private val store: WorkoutFileStore,
    private val scope: CoroutineScope,
) {
    private val timer = WorkoutTimer(SystemClock::elapsedRealtime)

    private var current: WorkoutRecord? = null
    private val samples = mutableListOf<WorkoutSample>()
    private var startsSegment = true
    var phase by mutableStateOf(WorkoutPhase.IDLE)
        private set
    var busy by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var history by mutableStateOf(emptyList<WorkoutRecord>())
        private set
    var summary by mutableStateOf<WorkoutRecord?>(null)
        private set
    var restored by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set

    val elapsedMs get() = timer.elapsedMs()
    val maxHr get() = current?.takeIf { phase != WorkoutPhase.FINISHED }?.maxHr
    val pendingSave get() = phase == WorkoutPhase.FINISHED && current != null

    init {
        scope.launch {
            try {
                val records = writes.withLock { withContext(Dispatchers.IO) { store.load() } }
                history = records.filter { it.finishedAtEpochMs != null }.sortedByDescending { it.startedAtEpochMs }
                val unfinished = records.singleOrNull { it.finishedAtEpochMs == null }
                check(records.count { it.finishedAtEpochMs == null } <= 1)
                if (unfinished != null) {
                    current = unfinished
                    samples.addAll(unfinished.samples)
                    timer.restorePaused(unfinished.activeMs)
                    phase = timer.phase
                    restored = true
                }
                ready = true
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Could not load saved workouts. Reopen the app to retry."
            } finally {
                busy = false
            }
            while (true) {
                delay(5_000)
                if (phase == WorkoutPhase.ACTIVE) checkpoint()
            }
        }
    }

    fun start(maxHr: Int) {
        if (!ready || busy || current != null) return
        current = WorkoutRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), maxHr)
        samples.clear()
        startsSegment = true
        timer.start()
        phase = timer.phase
        checkpoint()
    }

    fun pause() {
        if (phase != WorkoutPhase.ACTIVE) return
        timer.pause()
        startsSegment = true
        phase = timer.phase
        checkpoint()
    }

    fun resume() {
        if (phase != WorkoutPhase.PAUSED) return
        timer.resume()
        restored = false
        phase = timer.phase
        checkpoint()
    }

    fun finish() {
        if (busy) return
        val record = current ?: return
        if (phase != WorkoutPhase.FINISHED) {
            timer.finish()
            restored = false
            phase = timer.phase
        }
        val completed = snapshot(record).copy(finishedAtEpochMs = record.finishedAtEpochMs ?: System.currentTimeMillis())
        current = completed
        busy = true
        launchIo(
            "Could not save workout. Tap Retry save.",
            operation = { store.save(completed) },
            complete = { saved ->
                if (saved) {
                    history = (history + completed).sortedByDescending { it.startedAtEpochMs }
                    summary = completed
                    current = null
                    samples.clear()
                }
                busy = false
            },
        )
    }

    fun sensorGap() {
        startsSegment = true
    }

    fun addReading(atMs: Long, bpm: Int) {
        val elapsed = timer.readingElapsedMs(atMs) ?: return
        if (elapsed > timer.elapsedMs() || elapsed <= (samples.lastOrNull()?.elapsedMs ?: -1)) return
        samples.add(WorkoutSample(elapsed, bpm, startsSegment))
        startsSegment = false
    }

    fun checkpoint() {
        if (phase != WorkoutPhase.ACTIVE && phase != WorkoutPhase.PAUSED) return
        val record = current ?: return
        val snapshot = snapshot(record)
        launchIo("Could not save workout progress.", operation = { store.save(snapshot) })
    }

    fun openSummary(record: WorkoutRecord) {
        summary = record
    }

    fun closeSummary() {
        summary = null
    }

    fun delete(record: WorkoutRecord) {
        if (busy) return
        busy = true
        launchIo(
            "Could not delete the workout.",
            operation = { store.delete(record.id) },
            complete = { saved ->
                if (saved) history = history.filterNot { it.id == record.id }
                busy = false
            },
        )
    }

    private fun snapshot(record: WorkoutRecord) = record.copy(activeMs = timer.elapsedMs(), samples = samples.toList())

    private fun launchIo(
        failureMessage: String,
        operation: () -> Unit,
        complete: (Boolean) -> Unit = {},
    ) = scope.launch {
        writes.withLock {
            val succeeded = try {
                withContext(Dispatchers.IO) { operation() }
                error = null
                true
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failureMessage
                false
            }
            complete(succeeded)
        }
    }

    private companion object {
        val writes = Mutex()
    }
}