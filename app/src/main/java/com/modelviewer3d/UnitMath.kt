package com.modelviewer3d

import kotlin.math.sqrt

/**
 * Pure unit-conversion math — no Android dependencies, fully unit-testable.
 *
 * Coordinate conventions:
 *  - Model files are loaded and normalized so the model spans ≈2 units.
 *  - normalizeScale = 2 / maxSize (in file units).
 *  - unitToMM = 1 for STL/OBJ (already mm), 1000 for GLB (meters → mm).
 *
 * Derived helpers (single source of truth used by native + Kotlin):
 *  - mmPerUnit = unitToMM / normalizeScale   (mm per 1 normalized unit)
 *  - unitPerMM = normalizeScale / unitToMM   (normalized units per 1 mm)
 */
object UnitMath {

    fun mmPerUnit(unitToMM: Float, normalizeScale: Float): Float =
        if (normalizeScale > 1e-9f) unitToMM / normalizeScale else unitToMM

    fun unitPerMM(unitToMM: Float, normalizeScale: Float): Float =
        if (unitToMM > 1e-9f) normalizeScale / unitToMM else normalizeScale

    /** World-space distance between two points (any units). */
    fun distance3D(x1: Float, y1: Float, z1: Float,
                   x2: Float, y2: Float, z2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1; val dz = z2 - z1
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Ruler distance in mm.
     * Points are in world space (post-transform); multiplying by the current
     * mm-per-unit factor yields mm — this automatically tracks live model size.
     */
    fun distanceMM(x1: Float, y1: Float, z1: Float,
                   x2: Float, y2: Float, z2: Float,
                   mmPerUnit: Float): Float =
        distance3D(x1, y1, z1, x2, y2, z2) * mmPerUnit

    /** mm value formatted for display (2 decimals). */
    fun formatMM(v: Float): String = "%.2f".format(v)
}
