package com.modelviewer3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the deterministic ring-sizing core: given a measured ring and a
 * target US size, it computes the exact target diameter, preserves band and
 * height, and clamps to safe limits — with no AI or network involved.
 */
class RingSizeEngineTest {

    private fun state(id: Float, band: Float = 2f, h: Float = 5f) =
        RingSizeEngine.RingState(id, band, h)

    private fun limits(idMin: Float = 12f, idMax: Float = 24f) =
        RingSizeEngine.Limits(idMin = idMin, idMax = idMax)

    @Test
    fun `plan targets the exact US diameter and preserves profile`() {
        val current = state(id = 16.5f, band = 2.1f, h = 5.3f)  // ~US 6
        val result = RingSizeEngine.planForUsSize(current, 7f, limits())
        assertTrue(result is RingSizeEngine.Result.Ok)
        val plan = (result as RingSizeEngine.Result.Ok).plan
        assertEquals(RingMath.usSizeToDiam(7f), plan.targetInnerDiameterMM, 0.01f)
        // Band + height unchanged (only the size changes).
        assertEquals(2.1f, plan.bandWidthMM, 1e-3f)
        assertEquals(5.3f, plan.heightMM, 1e-3f)
    }

    @Test
    fun `increasing size reports increased direction`() {
        val current = state(id = 15.7f)  // US 5
        val plan = (RingSizeEngine.planForUsSize(current, 9f, limits())
            as RingSizeEngine.Result.Ok).plan
        assertTrue(plan.deltaMM > 0f)
        assertTrue(plan.direction.contains("Increased"))
    }

    @Test
    fun `target outside the ring range is rejected`() {
        val current = state(id = 17.3f)
        val result = RingSizeEngine.planForDiameter(current, 40f, limits(idMax = 24f))
        assertTrue(result is RingSizeEngine.Result.Error)
    }

    @Test
    fun `band and height are clamped to manufacturable limits`() {
        val current = state(id = 17.3f, band = 9f, h = 40f)  // absurd profile
        val plan = (RingSizeEngine.planForUsSize(current, 7f, limits())
            as RingSizeEngine.Result.Ok).plan
        assertTrue(plan.bandWidthMM <= 6f)
        assertTrue(plan.heightMM <= 20f)
    }

    @Test
    fun `verify passes when achieved matches plan and fails when it drifts`() {
        val current = state(id = 15.7f)
        val plan = (RingSizeEngine.planForUsSize(current, 7f, limits())
            as RingSizeEngine.Result.Ok).plan
        val good = state(id = plan.targetInnerDiameterMM)
        assertNull(RingSizeEngine.verify(plan, good))
        val bad = state(id = plan.targetInnerDiameterMM + 1.0f)
        assertNotNull(RingSizeEngine.verify(plan, bad))
    }

    @Test
    fun `free-text diameter plan lands on the requested mm`() {
        val current = state(id = 16.5f)
        val plan = (RingSizeEngine.planForDiameter(current, 18.0f, limits())
            as RingSizeEngine.Result.Ok).plan
        assertEquals(18.0f, plan.targetInnerDiameterMM, 0.01f)
    }

    private fun quality(conf: Float, ovality: Float, pts: Int = 300) =
        RingSizeEngine.Quality(
            roundnessMM = 0.02f, minBoreDiaMM = 17f, maxBoreDiaMM = 17.3f,
            ovalityPct = ovality, confidence = conf, pointCount = pts)

    @Test
    fun `quality tiers reflect confidence and ovality`() {
        assertEquals(RingSizeEngine.Quality.Tier.EXCELLENT,
            quality(0.95f, 0.5f).tier)
        assertEquals(RingSizeEngine.Quality.Tier.GOOD,
            quality(0.7f, 3f).tier)
        assertEquals(RingSizeEngine.Quality.Tier.FAIR,
            quality(0.5f, 6f).tier)
        assertEquals(RingSizeEngine.Quality.Tier.POOR,
            quality(0.2f, 20f).tier)
    }

    @Test
    fun `isRound flags out-of-round bores`() {
        assertTrue(quality(0.9f, 1f).isRound)
        assertTrue(!quality(0.9f, 9f).isRound)
    }
}
