package com.example.tempoz

import com.example.tempoz.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the [AppSettings] schema: all six fields exist with correct documented defaults.
 * Pinned here so later tasks cannot silently drop a field or change a default.
 */
class AppSettingsTest {

    @Test
    fun defaultClickSound_isClick() {
        assertEquals("Click", AppSettings().clickSound)
    }

    @Test
    fun defaultSubdivision_isOne() {
        assertEquals(1, AppSettings().subdivision)
    }

    @Test
    fun defaultGhostVolume_isApprox035() {
        assertEquals(0.35f, AppSettings().ghostVolume, 0.001f)
    }

    @Test
    fun defaultCountInBars_isZero() {
        assertEquals(0, AppSettings().countInBars)
    }

    @Test
    fun defaultTrackVolume_isOne() {
        assertEquals(1.0f, AppSettings().defaultTrackVolume, 0.001f)
    }

    @Test
    fun defaultClickVolume_is08() {
        assertEquals(0.8f, AppSettings().defaultClickVolume, 0.001f)
    }
}
