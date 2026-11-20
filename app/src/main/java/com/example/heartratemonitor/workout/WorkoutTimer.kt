package com.example.heartratemonitor.workout

enum class TrainingZone(val number: Int, val label: String, val minimumPercent: Int) {
    GRAY(1, "Gray", 0),
    BLUE(2, "Blue", 60),
    GREEN(3, "Green", 70),
    ORANGE(4, "Orange", 80),
    RED(5, "Red", 90);

    companion object {
        fun forReading(bpm: Int, maxHeartRate: Int): TrainingZone =
            entries.last { bpm.toLong() * 100 >= maxHeartRate.toLong() * it.minimumPercent }

        fun percent(bpm: Int, maxHeartRate: Int): Double = bpm * 100.0 / maxHeartRate
    }
}

enum class WorkoutPhase { IDLE, ACTIVE, PAUSED, FINISHED }

class WorkoutTimer(private val nowMs: () -> Long) {
    var phase = WorkoutPhase.IDLE
        private set
    private var accumulatedMs = 0L
    private var startedAtMs = 0L

    fun start() {
        check(phase == WorkoutPhase.IDLE || phase == WorkoutPhase.FINISHED)
        accumulatedMs = 0
        startedAtMs = nowMs()
        phase = WorkoutPhase.ACTIVE
    }

    fun restorePaused(durationMs: Long) {
        check(phase == WorkoutPhase.IDLE)
        accumulatedMs = durationMs
        phase = WorkoutPhase.PAUSED
    }

    fun readingElapsedMs(readingAtMs: Long): Long? {
        if (phase != WorkoutPhase.ACTIVE || readingAtMs < startedAtMs) return null
        return accumulatedMs + readingAtMs - startedAtMs
    }

    fun pause() {
        check(phase == WorkoutPhase.ACTIVE)
        accumulatedMs = elapsedMs()
        phase = WorkoutPhase.PAUSED
    }

    fun resume() {
        check(phase == WorkoutPhase.PAUSED)
        startedAtMs = nowMs()
        phase = WorkoutPhase.ACTIVE
    }

    fun finish() {
        check(phase == WorkoutPhase.ACTIVE || phase == WorkoutPhase.PAUSED)
        accumulatedMs = elapsedMs()
        phase = WorkoutPhase.FINISHED
    }

    fun elapsedMs(): Long =
        accumulatedMs + if (phase == WorkoutPhase.ACTIVE) nowMs() - startedAtMs else 0
}
