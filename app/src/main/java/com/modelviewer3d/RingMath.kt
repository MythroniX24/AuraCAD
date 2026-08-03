package com.modelviewer3d

import kotlin.math.PI
import kotlin.math.round

/**
 * Pure ring-sizing math — no Android dependencies, fully unit-testable.
 *
 * US ring size convention (most common standard):
 *   size = (innerCircumferenceMM − 36.5) / 2.55
 *
 * All diameter/band/height values are in millimetres.
 */
object RingMath {

    /** US ring size from inner diameter (mm). Rounded to 1 decimal. */
    fun diamToUSSize(diamMM: Float): Float {
        if (diamMM <= 0f) return 0f
        val circ = diamMM * PI.toFloat()
        return ((circ - 36.5f) / 2.55f).coerceAtLeast(0f)
    }

    /** Inner diameter (mm) for a given US ring size. */
    fun usSizeToDiam(size: Float): Float {
        val circ = 36.5f + size * 2.55f
        return (circ / PI.toFloat()).coerceAtLeast(0f)
    }

    /** Outer diameter given inner diameter + band (wall) width. */
    fun outerDia(innerDiaMM: Float, bandMM: Float): Float = innerDiaMM + bandMM * 2f

    /** Band (wall) width given inner + outer diameter. */
    fun bandFromDias(innerDiaMM: Float, outerDiaMM: Float): Float =
        (outerDiaMM - innerDiaMM) / 2f

    /**
     * Combined radial-map radius transform — mirrors the native C++ formula:
     *   r_new = newInnerR + (r_orig − origInnerR) * bandScale
     * Used to preview a ring resize before it is committed natively.
     */
    fun newOuterRadius(
        origInnerR: Float,
        origOuterR: Float,
        newInnerDiaMM: Float,
        newBandMM: Float,
        unitPerMM: Float
    ): Float {
        val newInnerN = (newInnerDiaMM * 0.5f) * unitPerMM
        val origBandN = origOuterR - origInnerR
        val bandScale = if (origBandN > 1e-9f) ((newBandMM * unitPerMM) / origBandN) else 1f
        return newInnerN + (origOuterR - origInnerR) * bandScale
    }

    /** Human-readable label: "US 7" or "US 7.5", fallback "—". */
    fun usSizeLabel(diamMM: Float): String {
        if (diamMM <= 0f) return "—"
        val s = diamToUSSize(diamMM)
        if (s <= 0f) return "—"
        val r = round(s * 10f) / 10f
        val whole = r.toInt()
        return if (r - whole > 0.01f) "US %.1f".format(r) else "US $whole"
    }
}
