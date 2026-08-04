package com.modelviewer3d

import kotlin.math.abs

/**
 * Pure-Kotlin AI ring-fitting logic — zero Android dependencies so the whole
 * class is unit-testable on the JVM.
 *
 * The Gemini model does the "looking" (vision screenshot) and free-text
 * understanding; this layer guarantees ACCURACY by:
 *  1. parsing the AI's JSON with [AiJson] (no org.json needed → JVM-safe),
 *  2. validating/clamping every number into the ring tool's ranges,
 *  3. falling back to deterministic RingMath sizing when AI is unavailable,
 *  4. preferring the math-parsed size when the AI drifts too far from it.
 */
object RingAi {

    data class Suggestion(
        val innerDiaMM: Float?,
        val bandWidthMM: Float?,
        val heightMM: Float?,
        val usSize: String?,
        val note: String
    )

    const val SYSTEM_PROMPT: String =
        "You are a professional jeweler and 3D ring-sizing expert. Given a ring 3D model's " +
        "current measurements and the user's requested size, recommend precise millimetre " +
        "values to resize the ring. Rules: inner diameter IS the ring size. " +
        "US size formula: size = (innerCircumferenceMm - 36.5) / 2.55. " +
        "If the user asks for a 'comfort fit' or says the ring is 'tight', add 0.3-0.5 mm to " +
        "the inner diameter; for a 'snug' fit keep it exact. When the requested size differs by " +
        "more than 2 full US sizes, scale band width and height proportionally (+10-15%). " +
        "All numbers are in millimetres. Reply with a single flat JSON object ONLY: " +
        "{\"recommendedInnerDiaMM\": <number>, \"recommendedBandWidthMM\": <number>, " +
        "\"recommendedHeightMM\": <number>, \"usSize\": \"US X\", \"note\": \"1-2 sentence advice\"}. " +
        "Never output any text outside the JSON object."

    fun buildFitPrompt(
        innerDiaMM: Float,
        outerDiaMM: Float,
        bandMM: Float,
        heightMM: Float,
        modelName: String,
        targetText: String
    ): String =
        "Ring model screenshot attached. Current measured ring: " +
        "inner diameter ${"%.2f".format(innerDiaMM)} mm (≈ ${RingMath.usSizeLabel(innerDiaMM)}), " +
        "outer diameter ${"%.2f".format(outerDiaMM)} mm, band width ${"%.2f".format(bandMM)} mm, " +
        "height ${"%.2f".format(heightMM)} mm. Model: $modelName. " +
        "User's requested size: \"$targetText\". " +
        "Look at the ring in the screenshot, confirm it is a ring, then give exact mm values " +
        "to resize this ring to fit the user's request. Respond with JSON only."

    /** Parse the AI's flat JSON reply. Tolerates extra text around the object. */
    fun parseSuggestion(raw: String): Suggestion {
        if (raw.isBlank()) return Suggestion(null, null, null, null, "")
        return Suggestion(
            innerDiaMM = AiJson.number(raw, "recommendedInnerDiaMM"),
            bandWidthMM = AiJson.number(raw, "recommendedBandWidthMM"),
            heightMM = AiJson.number(raw, "recommendedHeightMM"),
            usSize = AiJson.string(raw, "usSize"),
            note = AiJson.string(raw, "note") ?: ""
        )
    }

    /** Clamp every suggested value into the tool's ranges; drop garbage values. */
    fun validate(
        s: Suggestion,
        origInnerDiaMM: Float,
        origBandMM: Float,
        origHeightMM: Float,
        idMin: Float, idMax: Float,
        bwMin: Float, bwMax: Float,
        hMin: Float, hMax: Float
    ): Suggestion {
        val inner = s.innerDiaMM?.takeIf { it in 0.5f..120f } ?: origInnerDiaMM
        val band  = s.bandWidthMM?.takeIf { it in 0.05f..40f } ?: origBandMM
        val h     = s.heightMM?.takeIf { it in 0.1f..120f } ?: origHeightMM
        return s.copy(
            innerDiaMM = inner.coerceIn(idMin, idMax),
            bandWidthMM = band.coerceIn(bwMin, bwMax),
            heightMM = h.coerceIn(hMin, hMax)
        )
    }

    /** Deterministic sizing used when Gemini is missing/unreachable. */
    fun fallback(
        parsedInnerDiaMM: Float?,
        origInnerDiaMM: Float,
        origBandMM: Float,
        origHeightMM: Float,
        noKey: Boolean
    ): Suggestion {
        val inner = parsedInnerDiaMM ?: origInnerDiaMM
        return Suggestion(
            innerDiaMM = inner,
            bandWidthMM = origBandMM,
            heightMM = origHeightMM,
            usSize = if (inner > 0f) RingMath.usSizeLabel(inner) else null,
            note = if (noKey)
                "No Gemini API key — exact math-based sizing applied. Add a key in AI Settings for AI guidance."
            else
                "Gemini unavailable — exact math-based sizing applied."
        )
    }

    /**
     * Accuracy guard: the math-parsed size is authoritative. If the AI drifts
     * more than 1.5 mm from it, trust the math (the AI's comfort-fit tweaks
     * stay within that window).
     */
    fun finalInnerDia(aiDia: Float?, parsedDia: Float?): Float? {
        if (aiDia == null) return parsedDia
        if (parsedDia == null) return aiDia
        return if (abs(aiDia - parsedDia) > 1.5f) parsedDia else aiDia
    }

    /**
     * Minimal flat-JSON field extractor (JVM-safe — no org.json). Scans for
     * "\"key\"" then returns the raw value token (quoted string or number).
     */
    object AiJson {
        fun number(json: String, key: String): Float? {
            val v = rawValue(json, key) ?: return null
            return Regex("-?\\d+\\.?\\d*").find(v)?.value?.toFloatOrNull()
        }

        fun string(json: String, key: String): String? {
            val v = rawValue(json, key) ?: return null
            return if (v.startsWith("\"")) {
                v.removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
            } else v
        }

        private fun rawValue(json: String, key: String): String? {
            val idx = json.indexOf("\"$key\"")
            if (idx < 0) return null
            val colon = json.indexOf(':', idx)
            if (colon < 0) return null
            var i = colon + 1
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length) return null
            if (json[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < json.length) {
                    val c = json[i]
                    if (c == '\\') {
                        if (i + 1 < json.length) { sb.append(json[i + 1]); i += 2 } else break
                        continue
                    }
                    if (c == '"') break
                    sb.append(c); i++
                }
                return "\"" + sb + "\""
            }
            val start = i
            while (i < json.length && (json[i].isDigit() || json[i] == '-' || json[i] == '.')) i++
            return if (i == start) null else json.substring(start, i)
        }
    }
}
