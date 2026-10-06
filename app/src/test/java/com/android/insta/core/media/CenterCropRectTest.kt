package com.android.insta.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CenterCropRectTest {

    @Test
    fun `wider than the target trims the sides`() {
        assertEquals(CropRect(left = 280, top = 0, width = 1440, height = 1440), centerCropRect(2000, 1440, 1f))
    }

    @Test
    fun `taller than the target trims top and bottom`() {
        assertEquals(CropRect(left = 0, top = 400, width = 1000, height = 1250), centerCropRect(1000, 2050, 0.8f))
    }

    @Test
    fun `already the right shape is untouched`() {
        assertEquals(CropRect(0, 0, 1910, 1000), centerCropRect(1910, 1000, 1.91f))
    }
}
