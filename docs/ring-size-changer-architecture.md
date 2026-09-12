# AI Ring Size Changer — Architecture (deep-think redesign)

_Last updated: 2026-09-04 · module: `RingToolFragment` + `RingSizeEngine` + `RingMath` + native renderer_

## 1. Problem statement

The "AI Ring Fit" feature let a user pick a target US ring size and have the app
resize a ring mesh to match. The original design routed the **sizing decision
itself** through a Gemini vision model: it rendered calibrated top/side
screenshots, asked Gemini to *visually measure* the ring in pixels, convert to
mm, and return target dimensions, then re-rendered and re-asked up to four more
times to "verify and correct."

Two classes of defect fell out of that design.

### 1.1 Accuracy — a wrong conversion constant (root cause)

`RingMath` converted US size ↔ inner diameter with the wrong slope:

```
inner_mm = US × 0.4064 + 12.7      ← WRONG (slope ~2× too shallow)
```

The correct standard (ISO 8653-style US sizing, ~2.55 mm circumference per whole
size) — and AuraCAD's own `usPresets` table — is:

```
inner_mm = US × 0.8128 + 11.632    ← CORRECT (least-squares fit of the app's table, < 0.05 mm error)
```

Measured against the app's own preset table, the old formula was off by:

| US | app preset | old formula | error |
|----|-----------|-------------|-------|
| 6  | 16.5 mm   | 15.14 mm    | −1.36 mm |
| 7  | 17.3 mm   | 15.54 mm    | **−1.76 mm** |
| 10 | 19.8 mm   | 16.76 mm    | −3.04 mm |
| 12 | 21.4 mm   | 17.58 mm    | **−3.82 mm** |

So "resize to US 7" actually produced a ~US 5 ring, and a correctly-sized 17.3 mm
ring *displayed* as "US 11.5". No amount of AI quality could fix this — the
target fed to the model was already wrong.

### 1.2 Efficiency & robustness — AI on the accuracy hot path

Even with a correct formula, using vision to *measure* was the wrong tool:

- The native renderer **already measures** the ring's inner diameter, band and
  height exactly from geometry (`analyzeRing` → bore-refined PCA axis, 3rd/97th
  percentile radii). Gemini re-measuring the same thing in pixels is strictly
  less accurate and can only add error.
- The native deformer **already resizes exactly** (`applyCombinedRingDeformation`
  deforms from the pristine `origVerts` every time, so there is zero cumulative
  error and the achieved inner radius is `(idMM/2)·unitsPerMM` by construction).
- The flow made **up to 5 Gemini round-trips** per resize (fit + retry + verify +
  correct + re-verify), each rendering 2 PNGs with a 45 s timeout — slow, costly,
  and prone to JSON/parse/timeout failures.
- With **no API key the whole feature hard-failed**, even though sizing needs no
  AI whatsoever.

## 2. Design principle

> **Sizing is deterministic geometry. Compute it exactly; never guess it.**
> The AI's only legitimate job is *qualitative* — "does the resized mesh still
> look like a clean ring?" — where it can advise but never corrupt the numbers.

## 3. Pipeline

```
┌───────────┐   ┌──────────────┐   ┌───────────────┐   ┌──────────────┐   ┌──────────────────┐
│ 1 MEASURE │──▶│ 2 COMPUTE    │──▶│ 3 APPLY       │──▶│ 4 VERIFY     │──▶│ 5 INSPECT (opt.) │
│ native    │   │ RingSize-    │   │ native deform │   │ re-measure   │   │ 1 Gemini call    │
│ analyzeRing   │ Engine        │   │ (exact)       │   │ mm, compare  │   │ structure only   │
└───────────┘   └──────────────┘   └───────────────┘   └──────────────┘   └──────────────────┘
   exact mm        exact target        exact geom          instant/exact       advisory, never
                   (formula)                                (no vision)         edits dimensions
```

| Step | Owner | Cost | Can it make sizing wrong? |
|------|-------|------|---------------------------|
| 1 Measure | `NativeLib.nativeAnalyzeRing` / `nativeGetRingParams` | µs | No — ground truth |
| 2 Compute | `RingSizeEngine.planForUsSize` (pure Kotlin) | µs | No — closed-form + clamps |
| 3 Apply | native `applyCombinedRingDeformation` | ms | No — deforms from `origVerts` |
| 4 Verify | re-read `getRingParams`, compare mm | ms | No — exact numeric check |
| 5 Inspect | **optional** single Gemini call | ~1 network call | **No — advisory only** |

Gemini round-trips per resize: **up to 5 → 0** (offline) **or 1** (structural
check when a key is configured). The resize itself is now instant and exact
regardless.

## 4. Components

### `RingMath` (pure)
US ↔ mm conversion with the corrected constants; clamps US to `[1, 20]` so a size
is never negative. Single source of truth for the formula.

### `RingSizeEngine` (pure, JVM-testable — new)
The deterministic core, with no Android/GL/network dependencies:

- `RingState(innerDiameterMM, bandWidthMM, heightMM)` — measured geometry.
- `Limits(idMin, idMax, band/height min/max)` — per-ring safe bounds.
- `planForUsSize(current, targetUs, limits)` / `planForDiameter(...)` →
  `ResizePlan(targetInnerDiameterMM, bandWidthMM, heightMM, …)`. Only the inner
  diameter changes; band and height are preserved (and clamped) so the ring keeps
  its profile. Out-of-range targets return a typed `Result.Error`.
- `verify(plan, achieved, toleranceMM = 0.15)` → `null` when the re-measured mm
  matches the plan, else a human message.

### `RingToolFragment.runAiRingFit` (orchestration)
Runs steps 1–5 on a coroutine: GL-thread measurement via `glAwait{}`, deterministic
plan, `applyRingValues` (single undo snapshot + async native setters), re-measure &
`verify`, then the optional `inspectStructure` Gemini call. All UI feedback flows
through `setAiStatus`; chips/button are disabled for the (now very short) duration.

### Native renderer (unchanged)
`analyzeRing` (measure) and `applyCombinedRingDeformation` (deform from
`origVerts`) already provide exact measurement and exact, non-accumulating resize.

## 5. Guarantees

- **Accurate:** target mm is closed-form and correct to < 0.05 mm vs the app's
  preset table; geometry is deformed exactly and numerically verified.
- **Fast:** resize is instant (µs of math + one GL deform); no vision on the hot path.
- **Offline-capable:** sizing needs no API key; the AI step is skipped silently.
- **Safe:** the AI can never alter dimensions — worst case it adds an advisory note.
- **Tested:** `RingMathTest` locks the formula to the preset table; `RingSizeEngineTest`
  covers plan/preserve/clamp/verify on the pure JVM.

## 6. Future options

- Surface the structural advisory as a dismissible chip rather than status text.
- Add a free-text target ("US 6.5" / "17.3 mm") backed by `RingMath.parseUserSize`
  + `RingSizeEngine.planForDiameter`.
- Batch-resize multiple selected ring meshes with the same deterministic plan.
