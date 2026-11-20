package com.example.heartratemonitor

import com.example.heartratemonitor.workout.WorkoutRecord
import com.example.heartratemonitor.workout.WorkoutSample
import com.example.heartratemonitor.workout.WorkoutTimer
import com.example.heartratemonitor.workout.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutRecordTest {
    @Test fun weightsIntervalsAndLeavesDisconnectsAndPausesUncovered() {
        val record = WorkoutRecord("test", 0L, 200, activeMs = 18_000, samples = listOf(
            WorkoutSample(1_000, 100, true),
            WorkoutSample(2_000, 140, false),
            WorkoutSample(5_000, 180, false),
            WorkoutSample(12_000, 160, false),
            WorkoutSample(13_000, 120, true),
            WorkoutSample(15_000, 150, false),
        ))
        val result = record.summarize(5_000)
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 0L, 0L), result.zoneMs)
        assertEquals(12_000L, result.noDataMs)
        assertEquals(760.0 / 6, result.averageBpm ?: error("Missing average"), 0.001)
        assertEquals(180, result.peakBpm)
    }

    @Test fun exposesMissingDataWithNoReadings() {
        val result = WorkoutRecord("test", 0L, 185, activeMs = 6_000).summarize(5_000)
        assertEquals(6_000L, result.noDataMs)
        assertNull(result.averageBpm)
        assertNull(result.peakBpm)
    }

    @Test fun restoresElapsedTimeWithoutCountingInterruption() {
        var clock = 100_000L
        val timer = WorkoutTimer { clock }
        timer.restorePaused(12_000)
        clock += 30_000
        assertEquals(12_000L, timer.elapsedMs())
        timer.resume()
        assertNull(timer.readingElapsedMs(clock - 1))
        clock += 2_000
        assertEquals(14_000L, timer.readingElapsedMs(clock))
    }
}
