package com.example.heartratemonitor.workout

data class WorkoutSample(val elapsedMs: Long, val bpm: Int, val startsSegment: Boolean)

data class WorkoutRecord(
    val id: String,
    val startedAtEpochMs: Long,
    val maxHr: Int,
    val activeMs: Long = 0,
    val finishedAtEpochMs: Long? = null,
    val samples: List<WorkoutSample> = emptyList(),
)

data class WorkoutSummary(
    val zoneMs: List<Long>,
    val noDataMs: Long,
    val averageBpm: Double?,
    val peakBpm: Int?,
)

fun WorkoutRecord.summarize(freshnessMs: Long): WorkoutSummary {
    val zones = LongArray(TrainingZone.entries.size)
    var weightedBpmMs = 0.0
    for (index in 1 until samples.size) {
        val previous = samples[index - 1]
        val current = samples[index]
        val intervalMs = current.elapsedMs - previous.elapsedMs
        if (current.startsSegment || intervalMs !in 1..freshnessMs) continue
        val zone = TrainingZone.forReading(previous.bpm, maxHr)
        zones[zone.ordinal] += intervalMs
        weightedBpmMs += previous.bpm.toDouble() * intervalMs
    }
    val coveredMs = zones.sum()
    return WorkoutSummary(
        zoneMs = zones.toList(),
        noDataMs = activeMs - coveredMs,
        averageBpm = if (coveredMs > 0) weightedBpmMs / coveredMs else null,
        peakBpm = samples.maxOfOrNull { it.bpm },
    )
}
