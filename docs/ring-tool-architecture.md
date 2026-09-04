# Ring Tool — Architecture (accuracy upgrade)

_Last updated: 2026-09-04 · scope: **accuracy-first, US sizing** · modules:
native `Renderer` (analyze/deform) · `RingMath` · `RingSizeEngine` ·
`RingToolFragment` · `RingPreviewView`_

## 1. Goal

Make ring **measurement** as accurate and trustworthy as the (already exact)
resize, and tell the user how much to trust it. The resize math was fixed
previously (deterministic-first sizing + corrected US↔mm formula); this pass
attacks the remaining weak link: **how the ring is measured from the mesh.**

## 2. Where accuracy was lost (before)

`analyzeRing` estimated the inner radius as a **single percentile** of every
vertex's distance from the axis (3rd percentile). That is robust but limited:

- It is a scalar, so it **cannot detect an oval or off-center bore** — a
  squashed ring still reports one "diameter", silently wrong.
- The percentile is **biased by inner chamfers/engraving** near the bore edge.
- There was **no quality signal**: the user could not tell a clean CAD ring from
  a noisy scan, or know the measurement's confidence.

## 3. The upgrade — least-squares bore circle fit

### 3.1 Native (`analyzeRing`, Pass 5 — new)

After the existing robust passes (crude axis → bore-refined axis/centroid →
percentile radii), a new pass fits an actual circle to the inner bore:

1. Build an in-plane orthonormal basis `(u, v)` perpendicular to the ring axis.
2. Collect **bore-shell vertices** — those whose radius sits in a tight band
   around the percentile inner radius (never reaching the outer wall) — and
   project them to 2-D `(u, v)` coordinates.
3. Fit a circle with the **Kåsa algebraic least-squares** method (closed-form,
   no iteration): solves a 2×2 system for the center offset and radius.
4. From the fit, derive:
   - **fitted radius** → the authoritative inner radius (truer than a percentile),
   - **re-centered bore center** → shifts `m_ring.center` in-plane onto the real
     hole center, so subsequent deformation is symmetric about the true bore,
   - **RMS residual** (roundness), **min/max bore radius**, **ovality %**, and a
     **confidence** score `0.4·pointScore + 0.6·fitScore`.

The circle fit is guarded (needs ≥ 32 bore points and a non-singular system);
otherwise it falls back to the percentile radius, so detection never regresses.
Deformation is unchanged and still exact (`applyCombinedRingDeformation` from
`origVerts`), now driven by the better radius + center.

### 3.2 Data contract (`getRingParams`)

Widened from 6 → **12 floats** (old indices unchanged, so nothing breaks):

| idx | value | idx | value |
|-----|-------|-----|-------|
| 0 | inner radius mm | 6 | roundness (RMS residual) mm |
| 1 | outer radius mm | 7 | min bore diameter mm |
| 2 | band width mm | 8 | max bore diameter mm |
| 3 | inner diameter mm | 9 | ovality % |
| 4 | outer diameter mm | 10 | confidence 0..1 |
| 5 | height mm | 11 | bore point count |

### 3.3 Kotlin (`RingSizeEngine.Quality`)

A pure, JVM-testable model wraps indices 6–11 with a coarse **tier**
(EXCELLENT / GOOD / FAIR / POOR) from confidence + ovality, an `isRound` flag,
and a one-line `summary()`. `RingToolFragment.applyParams` builds it when the
native array has ≥ 12 entries (graceful for the legacy 6-float path).

### 3.4 UI (`RingToolFragment` summary)

The detection summary now shows, below the dimensions:
- **inner circumference** (mm), and
- a colour-coded **measurement-quality line** ("🟢 Excellent fit · 92% confidence
  · 0.4% ovality"), plus an explicit **out-of-round warning** with the min/max
  bore diameter when ovality is high — so users know when a single size is only
  approximate.

## 4. Layering

```
┌───────────────────────── native (C++) ─────────────────────────┐
│ analyzeRing:  axis → bore-refined axis → percentile → CIRCLE FIT │  measure
│               → fitted radius + center + quality metrics         │
│ applyCombinedRingDeformation: exact resize from origVerts        │  deform
│ getRingParams: 12 floats (dims + quality)                        │  report
└─────────────────────────────────────────────────────────────────┘
                              │ JNI (NativeLib)
┌───────────────────────── Kotlin ────────────────────────────────┐
│ RingMath        US↔mm (corrected), circumference, labels          │
│ RingSizeEngine  RingState · Limits · ResizePlan · Quality (new)   │  pure/tested
│ RingToolFragment  detect · sliders · presets · AI · quality UI    │
│ RingPreviewView   live top-view                                   │
└──────────────────────────────────────────────────────────────────┘
```

## 5. Guarantees & fallbacks

- **More accurate:** circle-fit radius + true bore center beat a single
  percentile; deformation stays exact.
- **Trustworthy:** ovality/roundness/confidence are surfaced; out-of-round
  meshes are flagged instead of silently mis-sized.
- **Safe:** fit is guarded and falls back to the percentile; the 12-float
  contract is a superset of the old 6-float one; single source of truth for US
  presets (derived from `RingMath`).
- **Tested:** `RingSizeEngineTest` covers the quality tiers + `isRound`;
  `RingMathTest` locks the conversion.

## 6. Future (deferred by scope choice)

Multi-standard sizing (UK/EU-ISO/JP), a side/cross-section preview, and an
ovality overlay in `RingPreviewView` were scoped out this pass (US-only,
accuracy-first) but the `Quality` model + 12-float contract already carry the
data they'd need.
