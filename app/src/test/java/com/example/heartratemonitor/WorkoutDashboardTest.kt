package com.example.heartratemonitor

import com.example.heartratemonitor.workout.TrainingZone
import com.example.heartratemonitor.workout.WorkoutPhase
import com.example.heartratemonitor.workout.WorkoutTimer
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutDashboardTest {
    @Test fun usesUnroundedZoneBoundaries() {
        val readings = listOf(59, 60, 69, 70, 79, 80, 89, 90, 110)
        val expected = listOf(1, 2, 2, 3, 3, 4, 4, 5, 5)
        assertEquals(expected, readings.map { TrainingZone.forReading(it, 100).number })
        assertEquals(1, TrainingZone.forReading(110, 185).number)
        assertEquals(2, TrainingZone.forReading(111, 185).number)
        assertEquals(2, TrainingZone.forReading(129, 185).number)
        assertEquals(3, TrainingZone.forReading(130, 185).number)
        assertEquals(85.0, TrainingZone.percent(153, 180), 0.001)
    }

    @Test fun pauseAndFinishExcludeTimeSpentPaused() {
        var now = 5_000L
        val timer = WorkoutTimer { now }
        timer.start()
        now += 12_000
        timer.pause()
        now += 10_000
        assertEquals(12_000L, timer.elapsedMs())
        timer.resume()
        now += 3_000
        timer.finish()
        now += 10_000
        assertEquals(15_000L, timer.elapsedMs())
        assertEquals(WorkoutPhase.FINISHED, timer.phase)
        timer.start()
        assertEquals(0L, timer.elapsedMs())
    }

    @Test fun finishingWhilePausedKeepsElapsedTime() {
        var now = 0L
        val timer = WorkoutTimer { now }
        timer.start()
        now = 850
        timer.pause()
        now = 10_000
        timer.finish()
        assertEquals(850L, timer.elapsedMs())
    }
}
