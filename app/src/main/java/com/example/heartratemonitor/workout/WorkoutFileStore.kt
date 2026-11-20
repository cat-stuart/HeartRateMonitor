package com.example.heartratemonitor.workout

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal class WorkoutFileStore(private val folder: File) {
    fun load(): List<WorkoutRecord> {
        ensureFolder()
        val files = folder.listFiles() ?: throw IOException("Cannot list workouts")
        // Opening through AtomicFile also recovers an older backup left by an interrupted write.
        val ids = files.mapNotNull { file ->
            val match = FILE_NAME.matchEntire(file.name) ?: return@mapNotNull null
            match.groupValues[1]
        }.distinct()
        return ids.map { id ->
            val json = JSONObject(String(file(id).readFully(), Charsets.UTF_8))
            check(json.getInt("version") == 1)
            val samples = json.getJSONArray("samples")
            WorkoutRecord(
                id = id,
                startedAtEpochMs = json.getLong("startedAt"),
                maxHr = json.getInt("maxHr"),
                activeMs = json.getLong("activeMs"),
                finishedAtEpochMs = if (json.isNull("finishedAt")) null else json.getLong("finishedAt"),
                samples = List(samples.length()) { index ->
                    val sample = samples.getJSONArray(index)
                    WorkoutSample(sample.getLong(0), sample.getInt(1), sample.getBoolean(2))
                },
            )
        }
    }

    fun save(record: WorkoutRecord) {
        ensureFolder()
        val samples = JSONArray()
        record.samples.forEach {
            samples.put(JSONArray().put(it.elapsedMs).put(it.bpm).put(it.startsSegment))
        }
        val data = JSONObject()
            .put("version", 1)
            .put("startedAt", record.startedAtEpochMs)
            .put("maxHr", record.maxHr)
            .put("activeMs", record.activeMs)
            .put("finishedAt", record.finishedAtEpochMs ?: JSONObject.NULL)
            .put("samples", samples)
            .toString().toByteArray(Charsets.UTF_8)
        val atomic = file(record.id)
        val output = atomic.startWrite()
        try {
            output.write(data)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        if (!atomic.baseFile.isFile || File(folder, "${record.id}.json.new").exists()) {
            throw IOException("Cannot replace workout file")
        }
    }

    fun delete(id: String) {
        val atomic = file(id)
        atomic.delete()
        if (atomic.baseFile.exists()) throw IOException("Cannot delete workout")
    }

    private fun ensureFolder() {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot open workout folder")
    }

    private fun file(id: String) = AtomicFile(File(folder, "$id.json"))

    private companion object {
        val FILE_NAME = Regex("([a-f0-9-]+)\\.json(?:\\.bak)?")
    }
}
