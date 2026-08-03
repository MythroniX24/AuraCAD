package com.modelviewer3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingMathTest {

    private val eps = 1e-2f

    @Test
    fun `US size 7 corresponds to ~17_3 mm inner diameter`() {
        // US 7 → inner circumference ≈ 54.35mm → diameter ≈ 17.3mm
        val dia = RingMath.usSizeToDiam(7f)
        assertEquals(17.3f, dia, 0.3f)
        // And round-trip back to US 7
        assertEquals(7f, RingMath.diamToUSSize(dia), 0.15f)
    }

    @Test
    fun `diamToUSSize round trips through usSizeToDiam`() {
        for (us in listOf(3f, 5f, 6.5f, 8f, 10f, 12f)) {
            val dia = RingMath.usSizeToDiam(us)
            val back = RingMath.diamToUSSize(dia)
            assertEquals(us, back, 0.2f)
        }
    }

    @Test
    fun `small diameters map to size zero not negative`() {
        assertTrue(RingMath.diamToUSSize(2f) >= 0f)
        assertTrue(RingMath.diamToUSSize(0f) == 0f)
    }

    @Test
    fun `outer diameter is inner plus twice the band`() {
        assertEquals(20f, RingMath.outerDia(16f, 2f), eps)
        assertEquals(17.5f, RingMath.outerDia(16f, 0.75f), eps)
    }

    @Test
    fun `band from inner and outer diameter`() {
        assertEquals(2f, RingMath.bandFromDias(16f, 20f), eps)
        assertEquals(0.75f, RingMath.bandFromDias(16f, 17.5f), eps)
    }

    @Test
    fun `newOuterRadius keeps wall thickness when only inner diameter changes`() {
        // orig ring: innerR=8, outerR=10 (band 2). New inner dia 20 (innerR 10).
        // Wall must stay 2 → new outerR 12.
        val outer = RingMath.newOuterRadius(
            origInnerR = 8f, origOuterR = 10f,
            newInnerDiaMM = 20f, newBandMM = 2f,
            unitPerMM = 1f
        )
        assertEquals(12f, outer, eps)
    }

    @Test
    fun `newOuterRadius scales wall when band changes and inner fixed`() {
        // orig innerR=8, outerR=10 (band 2). Double band to 4 with same inner.
        val outer = RingMath.newOuterRadius(
            origInnerR = 8f, origOuterR = 10f,
            newInnerDiaMM = 16f, newBandMM = 4f,
            unitPerMM = 1f
        )
        assertEquals(14f, outer, eps)
    }

    @Test
    fun `newOuterRadius respects unit conversion scale`() {
        // If 1 norm unit = 0.5mm, inner dia 16mm → innerR 16 norm units.
        val outer = RingMath.newOuterRadius(
            origInnerR = 16f, origOuterR = 20f,
            newInnerDiaMM = 16f, newBandMM = 4f,
            unitPerMM = 2f
        )
        // newInnerN = 8 * 2 = 16; bandScale = (4*2)/4 = 2 → outer = 16 + 4*2 = 24
        assertEquals(24f, outer, eps)
    }

    @Test
    fun `usSizeLabel formats whole and half sizes`() {
        assertEquals("US 7", RingMath.usSizeLabel(RingMath.usSizeToDiam(7f)))
        assertTrue(RingMath.usSizeLabel(0f) == "—")
    }
}
