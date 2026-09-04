package com.modelviewer3d

import kotlin.math.abs

/**
 * RingSizeEngine — the deterministic core of the AI Ring Size Changer.
 *
 * ## Why this exists
 * Ring sizing is *pure geometry*, not a vision problem. The native layer
 * ([Renderer.analyzeRing]) already measures the current ring's inner diameter,
 * band width and height directly from the mesh — exactly, in millimetres. The
 * target diameter for a US size is a closed-form formula ([RingMath]). So the
 * whole "resize this ring to US N" operation can be computed with zero error
 * and zero network calls.
 *
 * The AI (Gemini) is therefore demoted from *doing the math* (which it did by
 * eyeballing pixels — inaccurate and slow) to an **optional structural advisor**
 * that only inspects whether the resized mesh still looks like a clean ring.
 * The AI can never change the dimensions, so it can never make sizing wrong.
 *
 * This object is intentionally free of Android / GL / network dependencies so
 * it is fully unit-testable on the JVM (see RingSizeEngineTest).
 */
object RingSizeEngine {

    /** Current, measured ring geometry (all mm). Source: native getRingParams. */
    data class RingState(
        val innerDiameterMM: Float,
        val bandWidthMM: Float,
        val heightMM: Float,
    ) {
        val outerDiameterMM: Float get() = innerDiameterMM + bandWidthMM * 2f
        val usSize: Float get() = RingMath.diamToUSSize(innerDiameterMM)
        val usLabel: String get() = RingMath.usSizeLabel(innerDiameterMM)
    }

    /** Hard manufacturability + detected-range limits for a given ring. */
    data class Limits(
        val idMin: Float, val idMax: Float,
        val bandMin: Float = 0.3f, val bandMax: Float = 6f,
        val heightMin: Float = 0.5f, val heightMax: Float = 20f,
    )

    /** The computed resize plan: what to send to the native deformer. */
    data class ResizePlan(
        val targetInnerDiameterMM: Float,
        val bandWidthMM: Float,
        val heightMM: Float,
        val fromUsLabel: String,
        val toUsLabel: String,
        val deltaMM: Float,
    ) {
        val direction: String get() = when {
            deltaMM > 0.01f -> "↑ Increased"
            deltaMM < -0.01f -> "↓ Decreased"
            else -> "≈ Unchanged"
        }
    }

    sealed class Result {
        data class Ok(val plan: ResizePlan) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Compute the exact resize plan to take [current] to a target US size.
     *
     * A ring "size change" only moves the inner diameter — band width and
     * height are preserved so the ring keeps its profile. All values are clamped
     * to [limits] so the plan is always physically valid before it ever touches
     * the mesh.
     */
    fun planForUsSize(current: RingState, targetUs: Float, limits: Limits): Result {
        if (targetUs < RingMath.MIN_US_SIZE || targetUs > RingMath.MAX_US_SIZE) {
            return Result.Error(
                "US $targetUs is outside the supported range " +
                    "(US ${RingMath.MIN_US_SIZE.toInt()}–${RingMath.MAX_US_SIZE.toInt()})."
            )
        }
        val targetMM = RingMath.usSizeToDiam(targetUs)
        return planForDiameter(current, targetMM, limits)
    }

    /**
     * Compute the exact resize plan to take [current] to a target inner
     * diameter in mm (used by the free-text "17.3 mm" / "US 6.5" input).
     */
    fun planForDiameter(current: RingState, targetDiaMM: Float, limits: Limits): Result {
        if (!targetDiaMM.isFinite() || targetDiaMM <= 0f) {
            return Result.Error("Enter a valid target size.")
        }
        if (targetDiaMM < limits.idMin || targetDiaMM > limits.idMax) {
            return Result.Error(
                "Target %.2f mm (%s) is outside this ring's safe range %.1f–%.1f mm."
                    .format(targetDiaMM, RingMath.usSizeLabel(targetDiaMM),
                        limits.idMin, limits.idMax)
            )
        }
        val safeId = targetDiaMM.coerceIn(limits.idMin, limits.idMax)
        val safeBand = current.bandWidthMM.coerceIn(limits.bandMin, limits.bandMax)
        val safeHeight = current.heightMM.coerceIn(limits.heightMin, limits.heightMax)
        return Result.Ok(
            ResizePlan(
                targetInnerDiameterMM = safeId,
                bandWidthMM = safeBand,
                heightMM = safeHeight,
                fromUsLabel = RingMath.usSizeLabel(current.innerDiameterMM),
                toUsLabel = RingMath.usSizeLabel(safeId),
                deltaMM = safeId - current.innerDiameterMM,
            )
        )
    }

    /**
     * After the native deformer runs, verify the achieved geometry matches the
     * plan — instantly and exactly, by comparing re-measured mm (no vision).
     * Returns null when within [toleranceMM]; otherwise a human message.
     */
    fun verify(plan: ResizePlan, achieved: RingState, toleranceMM: Float = 0.15f): String? {
        val err = abs(achieved.innerDiameterMM - plan.targetInnerDiameterMM)
        return if (err <= toleranceMM) null
        else "Applied %.2f mm, target was %.2f mm (off by %.2f mm)."
            .format(achieved.innerDiameterMM, plan.targetInnerDiameterMM, err)
    }
}
