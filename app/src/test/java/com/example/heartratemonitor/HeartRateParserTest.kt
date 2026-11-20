package com.example.heartratemonitor

import com.example.heartratemonitor.bluetooth.HeartRateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateParserTest {
    @Test fun parsesUnsignedEightBitBpm() {
        assertEquals(200, HeartRateParser.parse(byteArrayOf(0, 200.toByte())))
    }

    @Test fun parsesLittleEndianSixteenBitBpm() {
        assertEquals(300, HeartRateParser.parse(byteArrayOf(1, 44, 1)))
    }

    @Test fun ignoresEnergyAndRrFields() {
        assertEquals(72, HeartRateParser.parse(byteArrayOf(24, 72, 1, 2, 3, 4)))
    }

    @Test fun rejectsMissingOrTruncatedFields() {
        listOf(
            byteArrayOf(),
            byteArrayOf(0),
            byteArrayOf(1, 44),
            byteArrayOf(8, 72, 1),
            byteArrayOf(16, 72),
            byteArrayOf(16, 72, 1, 2, 3),
            byteArrayOf(0, 0),
        ).forEach { assertNull(HeartRateParser.parse(it)) }
    }
}
