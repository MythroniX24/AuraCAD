package com.modelviewer3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingAiTest {

    @Test
    fun `parseSuggestion extracts values from valid JSON`() {
        val raw = """{"recommendedInnerDiaMM":17.3,"recommendedBandWidthMM":2.2,"recommendedHeightMM":2.5,"usSize":"US 7","note":"Comfort fit"}"""
        val s = RingAi.parseSuggestion(raw)
        assertEquals(17.3f, s.innerDiaMM!!, 1e-3f)
        assertEquals(2.2f, s.bandWidthMM!!, 1e-3f)
        assertEquals(2.5f, s.heightMM!!, 1e-3f)
        assertEquals("US 7", s.usSize)
        assertEquals("Comfort fit", s.note)
    }

    @Test
    fun `parseSuggestion tolerates extra prose around the JSON`() {
        val raw = """Here you go: {"recommendedInnerDiaMM":16.5,"recommendedBandWidthMM":1.8} hope that helps!"""
        val s = RingAi.parseSuggestion(raw)
        assertEquals(16.5f, s.innerDiaMM!!, 1e-3f)
        assertEquals(1.8f, s.bandWidthMM!!, 1e-3f)
        assertNull(s.heightMM)
    }

    @Test
    fun `parseSuggestion returns empty suggestion for garbage`() {
        val s = RingAi.parseSuggestion("not json at all")
        assertNull(s.innerDiaMM)
        assertNull(s.bandWidthMM)
        assertTrue(s.note.isEmpty())
    }

    @Test
    fun `validate falls back to originals for wildly out-of-range values`() {
        // 999 / 0.0001 / -5 are physically impossible → reject and keep originals
        val s = RingAi.Suggestion(
            innerDiaMM = 999f, bandWidthMM = 0.0001f, heightMM = -5f,
            usSize = null, note = ""
        )
        val v = RingAi.validate(s,
            origInnerDiaMM = 16.5f, origBandMM = 2f, origHeightMM = 3f,
            idMin = 8f, idMax = 33f, bwMin = 0.2f, bwMax = 7f,
            hMin = 0.9f, hMax = 9f)
        assertEquals(16.5f, v.innerDiaMM!!, 1e-3f)
        assertEquals(2f, v.bandWidthMM!!, 1e-3f)
        assertEquals(3f, v.heightMM!!, 1e-3f)
    }

    @Test
    fun `validate clamps borderline values into tool ranges`() {
        val s = RingAi.Suggestion(
            innerDiaMM = 40f, bandWidthMM = 8f, heightMM = 12f,
            usSize = null, note = ""
        )
        val v = RingAi.validate(s,
            origInnerDiaMM = 16.5f, origBandMM = 2f, origHeightMM = 3f,
            idMin = 8f, idMax = 33f, bwMin = 0.2f, bwMax = 7f,
            hMin = 0.9f, hMax = 9f)
        assertEquals(33f, v.innerDiaMM!!, 1e-3f)   // clamped to idMax
        assertEquals(7f, v.bandWidthMM!!, 1e-3f)   // clamped to bwMax
        assertEquals(9f, v.heightMM!!, 1e-3f)      // clamped to hMax
    }

    @Test
    fun `validate falls back to originals for missing values`() {
        val s = RingAi.Suggestion(null, null, null, null, "")
        val v = RingAi.validate(s,
            origInnerDiaMM = 16.5f, origBandMM = 2f, origHeightMM = 3f,
            idMin = 8f, idMax = 33f, bwMin = 0.2f, bwMax = 7f,
            hMin = 0.9f, hMax = 9f)
        assertEquals(16.5f, v.innerDiaMM!!, 1e-3f)
        assertEquals(2f, v.bandWidthMM!!, 1e-3f)
        assertEquals(3f, v.heightMM!!, 1e-3f)
    }

    @Test
    fun `fallback uses parsed diameter and original band and height`() {
        val f = RingAi.fallback(parsedInnerDiaMM = 17.3f,
            origInnerDiaMM = 16.5f, origBandMM = 2f, origHeightMM = 3f, noKey = true)
        assertEquals(17.3f, f.innerDiaMM!!, 1e-3f)
        assertEquals(2f, f.bandWidthMM!!, 1e-3f)
        assertEquals(3f, f.heightMM!!, 1e-3f)
        assertEquals("US 7", f.usSize)
        assertTrue(f.note.contains("key"))
    }

    @Test
    fun `finalInnerDia trusts math when AI drifts far`() {
        // US 7 = 17.3mm; AI says 25mm (way off) → trust math 17.3
        assertEquals(17.3f, RingAi.finalInnerDia(aiDia = 25f, parsedDia = 17.3f)!!, 1e-3f)
        // Small comfort tweak (17.6 vs 17.3) stays
        assertEquals(17.6f, RingAi.finalInnerDia(aiDia = 17.6f, parsedDia = 17.3f)!!, 1e-3f)
        // No parsed size → trust AI
        assertEquals(20f, RingAi.finalInnerDia(aiDia = 20f, parsedDia = null)!!, 1e-3f)
        // No AI → trust math
        assertEquals(16.5f, RingAi.finalInnerDia(aiDia = null, parsedDia = 16.5f)!!, 1e-3f)
    }

    @Test
    fun `buildFitPrompt embeds the current measurements and target`() {
        val p = RingAi.buildFitPrompt(16.5f, 20.5f, 2f, 3f, "ring.stl", "US 6.5")
        assertTrue(p.contains("16.50"))
        assertTrue(p.contains("20.50"))
        assertTrue(p.contains("US 6.5"))
        assertTrue(p.contains("ring.stl"))
    }

    @Test
    fun `AiJson extracts numbers with decimals and negatives`() {
        assertEquals(17.3f, RingAi.AiJson.number("{\"recommendedInnerDiaMM\":17.3}", "recommendedInnerDiaMM")!!, 1e-3f)
        assertNull(RingAi.AiJson.number("{\"foo\":\"bar\"}", "recommendedInnerDiaMM"))
        assertNull(RingAi.AiJson.number("{}", "missing"))
    }

    @Test
    fun `AiJson extracts strings and unescapes quotes`() {
        assertEquals("US 7", RingAi.AiJson.string("{\"usSize\":\"US 7\"}", "usSize"))
        assertEquals("say \"hi\"", RingAi.AiJson.string("{\"note\":\"say \\\"hi\\\"\"}", "note"))
        assertNull(RingAi.AiJson.string("{\"a\":1}", "note"))
    }
}
