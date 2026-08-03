package com.modelviewer3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitMathTest {

    private val eps = 1e-3f

    @Test
    fun `mmPerUnit for STL equals 1 over normalizeScale`() {
        // STL: unitToMM=1, normalizeScale such that 2 units = 2000mm → scale=0.001
        assertEquals(1000f, UnitMath.mmPerUnit(1f, 0.001f), eps)
        assertEquals(500f, UnitMath.mmPerUnit(1f, 0.002f), eps)
    }

    @Test
    fun `mmPerUnit for GLB multiplies by 1000`() {
        // GLB: unitToMM=1000 (meters→mm)
        assertEquals(500_000f, UnitMath.mmPerUnit(1000f, 0.002f), eps)
    }

    @Test
    fun `unitPerMM is the inverse of mmPerUnit`() {
        for ((u, s) in listOf(1f to 0.001f, 1f to 0.002f, 1000f to 0.002f)) {
            val mpu = UnitMath.mmPerUnit(u, s)
            val upm = UnitMath.unitPerMM(u, s)
            assertEquals(1f, mpu * upm, 1e-2f)
        }
    }

    @Test
    fun `mmPerUnit guards against zero normalizeScale`() {
        assertEquals(1f, UnitMath.mmPerUnit(1f, 0f), eps)
        assertEquals(1000f, UnitMath.mmPerUnit(1000f, 0f), eps)
    }

    @Test
    fun `distance3D computes euclidean distance`() {
        assertEquals(5f, UnitMath.distance3D(0f, 0f, 0f, 3f, 4f, 0f), eps)
        // 3-4-5 in 3D: (0,0,0)→(1,2,2) = 3
        assertEquals(3f, UnitMath.distance3D(0f, 0f, 0f, 1f, 2f, 2f), eps)
    }

    @Test
    fun `distanceMM scales world distance by mmPerUnit`() {
        // World distance 2 units, 500 mm/unit → 1000 mm
        assertEquals(1000f, UnitMath.distanceMM(0f, 0f, 0f, 2f, 0f, 0f, 500f), eps)
    }

    @Test
    fun `distanceMM reflects scaled model (2x model → 2x mm)`() {
        // Model originally 10 units apart; after 2x scale they're 20 apart.
        // mmPerUnit unchanged → measurement doubles, matching live size.
        val mm1 = UnitMath.distanceMM(0f, 0f, 0f, 10f, 0f, 0f, 100f)
        val mm2 = UnitMath.distanceMM(0f, 0f, 0f, 20f, 0f, 0f, 100f)
        assertEquals(1000f, mm1, eps)
        assertEquals(2000f, mm2, eps)
        assertTrue(mm2 > mm1)
    }

    @Test
    fun `formatMM uses two decimals`() {
        assertEquals("12.50", UnitMath.formatMM(12.5f))
        assertEquals("0.00", UnitMath.formatMM(0f))
    }
}
