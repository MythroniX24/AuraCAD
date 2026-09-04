package com.modelviewer3d

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Ring sizing math (mm) — used by the Ring Tool screen.
 *  US size ↔ inner diameter conversions follow the standard
 *  innerDiameterMM = US × 0.8128 + 11.632 (see constants below), clamped to
 *  sane bounds so a US size is never negative.
 */
object RingMath {

    // US ring size ↔ inner diameter (mm).
    //
    //   innerDiameterMM = US × US_SLOPE + US_INTERCEPT
    //
    // The correct standard (ISO 8653-based US sizing, ~2.55 mm circumference per
    // whole size ⇒ ~0.8128 mm diameter per size) has slope 0.8128. These two
    // constants are the least-squares fit of AuraCAD's own US preset table and
    // reproduce it to < 0.05 mm (e.g. US 7 → 17.32 mm, US 12 → 21.39 mm).
    // The previous formula (slope 0.4064, intercept 12.7) made every size ~2×
    // too shallow — US 7 came out 15.5 mm instead of 17.3 mm and real rings were
    // mislabelled by several sizes.
    private const val US_SLOPE = 0.8128f
    private const val US_INTERCEPT = 11.632f

    private fun diamToUS(diaMM: Float): Float = (diaMM - US_INTERCEPT) / US_SLOPE
    private fun usToDiam(us: Float): Float = us * US_SLOPE + US_INTERCEPT

    /** Smallest standard US ring size we surface (below this there is no standard size). */
    const val MIN_US_SIZE = 1f
    /** Largest standard US ring size we surface. */
    const val MAX_US_SIZE = 20f

    /**
     * Convert an inner diameter (mm) into the closest US ring size.
     * US ring sizes are NEVER negative — values below US 1 clamp to US 1
     * so tiny rings never show nonsense like "US -18".
     */
    fun diamToUSSize(diaMM: Float): Float {
        if (diaMM <= 0f) return 0f
        val us = (diamToUS(diaMM) * 2f).roundToInt() / 2f
        return us.coerceIn(MIN_US_SIZE, MAX_US_SIZE)
    }

    /** Convert a US ring size into an inner diameter (mm). */
    fun usSizeToDiam(us: Float): Float = usToDiam(us)

    /**
     * Human label: "US 7", "below US 1" for undersized rings, "—" when invalid.
     * Never returns a negative US size.
     */
    fun usSizeLabel(diaMM: Float): String {
        if (diaMM <= 0f) return "—"
        if (diamToUS(diaMM) < MIN_US_SIZE) return "below US ${MIN_US_SIZE.toInt()}"
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
