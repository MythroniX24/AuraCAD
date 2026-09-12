package com.modelviewer3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the US ring-size ↔ millimetre conversion to the correct standard.
 *
 * These values are the ground truth from AuraCAD's own US preset table; the
 * previous 0.4064 slope was ~2× too shallow and made every AI resize land at
 * the wrong size (US 7 → 15.5 mm instead of 17.3 mm).
 */
class RingMathTest {

    private val eps = 0.1f  // mm tolerance vs the published preset table

    @Test
    fun `US size to diameter matches the standard preset table`() {
        assertEquals(14.1f, RingMath.usSizeToDiam(3f), eps)
        assertEquals(16.5f, RingMath.usSizeToDiam(6f), eps)
        assertEquals(17.3f, RingMath.usSizeToDiam(7f), eps)
        assertEquals(19.8f, RingMath.usSizeToDiam(10f), eps)
        assertEquals(21.4f, RingMath.usSizeToDiam(12f), eps)
    }

    @Test
    fun `diameter to US size is the inverse of US to diameter`() {
        for (us in listOf(3f, 5f, 6.5f, 7f, 9f, 11f, 12f)) {
            val dia = RingMath.usSizeToDiam(us)
            assertEquals(us, RingMath.diamToUSSize(dia), 0.25f)
        }
    }

    @Test
    fun `real 17_3mm ring reads as US 7 not a mislabelled size`() {
        assertEquals(7f, RingMath.diamToUSSize(17.3f), 0.25f)
        assertTrue(RingMath.usSizeLabel(17.3f).contains("7"))
    }

    @Test
    fun `US sizes are never negative`() {
        // A tiny ring clamps to the minimum standard size, never a negative US.
        assertTrue(RingMath.diamToUSSize(5f) >= RingMath.MIN_US_SIZE)
        assertEquals("—", RingMath.usSizeLabel(0f))
    }

    @Test
    fun `outer diameter and band conversions round-trip`() {
        val inner = 17.3f
        val band = 2.0f
        val outer = RingMath.outerDia(inner, band)
        assertEquals(21.3f, outer, 1e-3f)
        assertEquals(band, RingMath.bandFromDias(inner, outer), 1e-3f)
    }

    @Test
    fun `circumference follows pi times diameter`() {
        assertEquals((Math.PI * 17.3).toFloat(), RingMath.circumferenceMM(17.3f), 1e-2f)
    }

    @Test
    fun `parseUserSize understands mm and US inputs`() {
        assertEquals(17.3f, RingMath.parseUserSize("17.3mm")!!, 0.1f)
        assertEquals(17.3f, RingMath.parseUserSize("US 7")!!, 0.2f)
        assertEquals(17.3f, RingMath.parseUserSize("size 7")!!, 0.2f)
    }
}
