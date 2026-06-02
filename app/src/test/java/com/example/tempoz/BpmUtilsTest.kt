package com.example.tempoz

import org.junit.Assert.assertEquals
import org.junit.Test

class BpmUtilsTest {

    // formatBpm: display

    @Test
    fun formatBpm_integerValue_noDecimalPoint() {
        assertEquals("120", formatBpm(120.0))
    }

    @Test
    fun formatBpm_fractionalValue_oneDecimal() {
        assertEquals("98.5", formatBpm(98.5))
    }

    @Test
    fun formatBpm_roundsToOneDecimal_belowHalf() {
        // 120.04 rounds to 120.0 → displayed as "120"
        assertEquals("120", formatBpm(120.04))
    }

    @Test
    fun formatBpm_roundsToOneDecimal_aboveHalf() {
        // 99.96 rounds to 100.0 → displayed as "100"
        assertEquals("100", formatBpm(99.96))
    }

    @Test
    fun formatBpm_alreadyOneDecimal_retained() {
        assertEquals("98.6", formatBpm(98.6))
    }

    // quantizeBpm: rounding + clamping

    @Test
    fun quantizeBpm_roundsToNearestTenth() {
        assertEquals(98.5, quantizeBpm(98.5), 0.0)
        assertEquals(98.5, quantizeBpm(98.54), 0.001)
        assertEquals(98.6, quantizeBpm(98.55), 0.001)
    }

    @Test
    fun quantizeBpm_clampMaxTo240() {
        assertEquals(240.0, quantizeBpm(300.0), 0.0)
        assertEquals(240.0, quantizeBpm(240.1), 0.0)
        assertEquals(240.0, quantizeBpm(240.0), 0.0)
    }

    @Test
    fun quantizeBpm_clampMinTo40() {
        assertEquals(40.0, quantizeBpm(0.0), 0.0)
        assertEquals(40.0, quantizeBpm(39.9), 0.0)
        assertEquals(40.0, quantizeBpm(40.0), 0.0)
    }

    @Test
    fun quantizeBpm_midRange_noClamp() {
        assertEquals(120.0, quantizeBpm(120.0), 0.0)
        assertEquals(130.5, quantizeBpm(130.5), 0.0)
    }
}
