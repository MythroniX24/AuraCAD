package com.modelviewer3d

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Ring sizing math (mm) — used by the Ring Tool screen.
 *  US size ↔ inner diameter conversions follow the standard
 *  (US) = (inner diameter in mm − 12.7) / 0.4064, clamped to sane bounds.
 */
object RingMath {

    private fun diamToUS(diaMM: Float): Float = (diaMM - 12.7f) / 0.4064f
    private fun usToDiam(us: Float): Float = us * 0.4064f + 12.7f

    /** Convert an inner diameter (mm) into the closest US ring size. */
    fun diamToUSSize(diaMM: Float): Float =
        if (diaMM <= 0f) 0f else (diamToUS(diaMM) * 2).roundToInt() / 2f

    /** Convert a US ring size into an inner diameter (mm). */
    fun usSizeToDiam(us: Float): Float = usToDiam(us)

    /** Human label: "US 7" or "—" for invalid values. */
    fun usSizeLabel(diaMM: Float): String {
        if (diaMM <= 0f) return "—"
        val us = diamToUSSize(diaMM)
        val whole = us.toInt()
        val half = us - whole
        return "US ${if (half > 0f) "$whole.5" else "$whole"}"
    }

    /** Ring circumference from inner diameter. */
    fun circumferenceMM(diaMM: Float): Float = (PI * diaMM).toFloat()

    /** Outer diameter = inner diameter + 2 × band width. */
    fun outerDia(innerDiaMM: Float, bandWidthMM: Float): Float =
        innerDiaMM + bandWidthMM * 2f

    /** Band width from inner and outer diameters. */
    fun bandFromDias(innerDiaMM: Float, outerDiaMM: Float): Float =
        (outerDiaMM - innerDiaMM) / 2f

    /**
     * New outer radius after a band-width change (keeps inner bore fixed).
     * r_new = origInner + (r_orig − origInner) × (newBand / origBand)
     */
    fun newOuterRadius(origInnerR: Float, origOuterR: Float,
                       origBand: Float, newBand: Float): Float {
        if (origBand <= 0f) return origOuterR
        return origInnerR + (origOuterR - origInnerR) * (newBand / origBand)
    }

    /**
     * Parse free-form user text like "US 6", "size 7", "17.5mm", "17.5 mm",
     * "6.5", "20" into an inner diameter in mm.
     */
    fun parseUserSize(raw: String): Float? {
        val t = raw.trim().lowercase()
        if (t.isEmpty()) return null
        val num = Regex("""\d+(\.\d+)?""").find(t)?.value?.toFloatOrNull() ?: return null
        return when {
            t.contains("us") || t.contains("size") || t.contains("s") && !t.contains("mm") -> {
                if (num in 0f..30f) usToDiam(num) else num
            }
            t.contains("mm") -> num
            num in 8f..30f -> num          // bare number in the mm range → diameter
            num in 0f..30f -> usToDiam(num) // otherwise treat as a US size
            else -> null
        }
    }
}
