package com.example.heartratemonitor.bluetooth

object HeartRateParser {
    fun parse(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        val flags = packet[0].toInt()
        val wide = flags and 1 != 0
        val bpmEnd = if (wide) 3 else 2
        val optionalEnd = bpmEnd + if (flags and 8 != 0) 2 else 0
        if (packet.size < optionalEnd) return null
        val rrBytes = packet.size - optionalEnd
        if (flags and 16 != 0 && (rrBytes < 2 || rrBytes % 2 != 0)) return null

        val low = packet[1].toInt() and 0xff
        val bpm = if (wide) low or ((packet[2].toInt() and 0xff) shl 8) else low
        return bpm.takeIf { it > 0 }
    }
}
