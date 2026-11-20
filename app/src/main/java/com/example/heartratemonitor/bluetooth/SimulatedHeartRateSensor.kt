package com.example.heartratemonitor.bluetooth

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SimulatedHeartRateSensor : HeartRateSensor {
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(SensorState())
    override val state = mutableState.asStateFlow()
    private var nextSample = 0
    private val samples = listOf(84, 87, 92, 99, 107, 114, 120, 115, 108, 97, 89)
    private val tick = object : Runnable {
        override fun run() {
            mutableState.value = SensorState(status = "Simulated reading", bpm = samples[nextSample])
            nextSample = (nextSample + 1) % samples.size
            handler.postDelayed(this, 1_000)
        }
    }

    override fun scan() {
        stop()
        mutableState.value = SensorState(
            status = "Select your simulated monitor",
            devices = listOf(SensorDevice("simulated", "Simulated monitor", -42)),
        )
    }

    override fun connect(id: String) {
        if (id != "simulated") return
        stop()
        tick.run()
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        nextSample = 0
        mutableState.value = SensorState()
    }
}
