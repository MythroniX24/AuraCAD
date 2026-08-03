# Knowledge.md — AuraCAD Internal Knowledge Base

Deep-dive notes on how AuraCAD works internally. Read this before touching geometry, units, rendering, or the ring/ruler tools.

---

## 1. Architecture overview

```
Kotlin UI  ──JNI──►  jni_bridge.cpp  ──►  Renderer (C++17, renderer.cpp)  ──►  OpenGL ES 3.0
                        │
                        ├─ model_loader.cpp   (tinyobj: OBJ/STL/GLB parse)
                        ├─ mesh_separator.cpp (Union-Find island split)
                        └─ worker threads      (load / separate / ring, async)
```

- `MainActivity` owns the toolbar, panels and tool state.
- `ModelGLSurfaceView` + `ModelRenderer.kt` run the GL thread.
- Native code parses, renders, and runs geometry algorithms; long ops run on native worker threads and marshal results back to Kotlin via `onMainThread` callbacks.

## 2. The unit system — mm correctness (READ THIS FIRST)

### Coordinate pipeline
1. **Parse**: OBJ/STL → 1 file unit = 1 mm (`unitToMM = 1`). GLB → metres are converted to mm (`unitToMM = 1000`).
2. **Normalize**: the model is normalized to span ≈ 2 units → `normalizeScale = 2 / maxFileExtent`.
3. **World/GPU space** is in normalized units (camera fits the model).
4. **User-facing mm** is recovered with:
   - `mmPerUnit = unitToMM / normalizeScale` → mm per 1 normalized unit
   - `unitPerMM = normalizeScale / unitToMM` → normalized units per 1 mm

### Conversion sites (must stay consistent)
| Site | Conversion |
|------|-----------|
| `getModelSizeMM` (Original size) | `maxExtent / normalizeScale × unitToMM` |
| `getCurrentSizeMM` (live size) | current scaled bounds × `mmPerUnit()` |
| `setMeshScaleMM` / `getMeshSizeMM` | operate on the mesh's current mm size (relative — never absolute) |
| OBJ/STL export | `×(1/normalizeScale)` then `×unitToMM` → 1 exported unit = 1 mm |
| **Ruler** | world distance × `mmPerUnit()` — auto-tracks live model size |
| **Ring** | diameters in mm ⇄ normalized via `unitPerMM()` |
| **Brush radius** | mm → normalized via `unitPerMM()` |

Kotlin mirror: `UnitMath.kt` (`mmPerUnit`, `unitPerMM`, `distance3D`, `distanceMM`, `formatMM`).

## 3. Ruler tool — flow

1. `MainActivity.toggleRulerMode()` → `ModelGLSurfaceView.Mode.RULER`.
2. Single tap → `pickPoint()` (screen→ray→Möller–Trumbore against every mesh) returns a world-space point.
3. `setRulerPoints(h1, p1, h2, p2)` stores both endpoints natively (picked points are "hooked" to the surface).
4. Kotlin computes distance = `UnitMath.distanceMM(..., mmPerUnit)` where `mmPerUnit = nativeGetMMPerUnit()` — so the readout **stays correct after the user resizes the model or a mesh** (the old bug: a hardcoded `maxMM` that ignored scale → stale readings).

## 4. Ring engine (C++)

- `analyzeRing(meshIdx)` — detects the ring axis from the bounding box (largest-variance axis), stores original inner radius, band width and height, and bakes per-vertex cylindrical coordinates.
- Setters — `setRingBandWidth(mm)`, `setRingInnerDiameter(mm)`, `setRingHeightMM(mm)` — all funnel into **`applyCombinedRingDeformation(bw, id, h)`**, the single authoritative deformation entry point (also reached by the async pending path).
- **Radial map**: `r_new = newInnerR + (r_orig − origInnerR) × bandScale`
- **Height (axial)**: scales the coordinate along the detected axis around the ring centre.
- `resetRingDeformation()` restores original vertices.
- Kotlin preview mirror: `RingMath.kt` (US size ⇄ mm diameter, `outerDia`, `bandFromDias`, `newOuterRadius`).

## 5. Mesh separation

`mesh_separator.cpp` runs **Union-Find** over shared vertices to find disconnected islands; each island becomes its own `MeshObject` (name, bounds, mm size, color). After separation each mesh has its own `rot/pos/sca`, composed over the global model transform — per-mesh resize (`setMeshScaleMM`) writes mm sizes relative to the mesh's current extent.

## 6. Sculpt brushes

`BrushToolFragment` → native smooth/sculpt. Radius is passed **in mm** and converted to normalized units via `unitPerMM()`. (Historical bug: the brush code once referenced `glm::vec3` and `.position`/`.normal` — the `Vertex` struct is a plain float struct; **keep it that way**.)

## 7. Build & CI

- Stack: AGP 8.2.0 · Kotlin 1.9.22 · Gradle 8.10.2 (wrapper) · JDK 17 · compileSdk/targetSdk 34 · minSdk 24.
- NDK `27.0.12077973` (set in `app/build.gradle` and the workflow), CMake `3.22.1`, `-std=c++17 -frtti -fexceptions`, STL `c++_shared`, ABIs `arm64-v8a, armeabi-v7a, x86_64`.
- CI (`.github/workflows/android.yml`): `testDebugUnitTest` → `assembleDebug` → `assembleRelease` → artifact upload; `v*` tags also publish a GitHub Release. Release signing falls back to `debug.keystore` (no secrets needed).

## 8. Gotchas / golden rules

- 🚫 **No glm** — no dependency exists; use the plain `Vertex` fields.
- Ruler & ring formulas are **mirrored** between C++ and Kotlin; `RingMathTest` / `UnitMathTest` guard drift.
- Async native work must **never** touch GL objects off the GL thread.
- `local.properties` is machine-specific and git-ignored.
- When adding a JNI export, update all 4 places (renderer method, jni_bridge, NativeLib.kt, call sites).
