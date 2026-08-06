# Knowledge — AuraCAD deep dive

AuraCAD is a professional Android **3D model viewer & editor**: OpenGL ES 3.0
renderer written in **C++17**, Kotlin UI, everything dimensioned in real
**millimetres**.

## Stack

| Layer | Tech |
|-------|------|
| UI | Kotlin, `AppCompatActivity` + `BottomSheetDialogFragment`s, programmatic layouts via `UISheetKit` |
| Rendering | OpenGL ES 3.0, C++17 (`renderer.cpp`), GLSurfaceView |
| Parsers | tinyobjloader (OBJ), tinygltf (GLB/GLTF), custom STL/PLY/3DS, **openNURBS (3DM)** |
| Build | Gradle 8.10.2, AGP, NDK `27.0.12077973`, CMake `3.22.1`, minSdk 24 / target+compile 34 |
| CI | GitHub Actions — unit tests + Debug/Release APK on every push |

## Architecture in one paragraph

`MainActivity` owns the `ModelGLSurfaceView` and dispatches all UI events.
Every tool/panel is a bottom sheet that talks to native code through
`NativeLib` (a JNI `object` mirroring `jni_bridge.cpp`). Native code owns all
mesh data (CPU vertex buffers) and the GL state. `queueEvent` marshals work
onto the GL thread; heavy file I/O happens on IO threads before upload.

## The mm-unit pipeline (critical invariant)

Everything measurable shares ONE conversion chain:

```
file units ──unitToMM──▶ normalized model space ──mmPerUnit──▶ real mm
```

- OBJ / STL / PLY / 3DS are assumed millimetres; GLB is metres→mm.
- `mmPerUnit` = current model scale in mm per normalized unit. It is updated
  by **any** resize (global or per-mesh) and drives: model size, mesh W/H/D,
  ruler, ring diameters, brush radius, and **all exporters**.
- **Rule:** never add an independent scale path. If a feature measures
  distances, it must read `mmPerUnit`.

## Formats

### Load (all in `model_loader.cpp`)
| Format | Engine | Notes |
|--------|--------|-------|
| OBJ | tinyobjloader | materials optional |
| STL | custom | binary + ASCII; binary is vertex-deduped |
| PLY | custom | ASCII + binary little-endian |
| GLB | tinygltf | metres → mm |
| GLTF (JSON) | tinygltf | — |
| 3DS | custom | legacy chunks |
| 3DM | **openNURBS v8.9** | ON_Mesh copied directly (quads→tris); NURBS surfaces/Breps/Extrusions iso-grid tessellated (≤64×64/surface); document units converted via `ON_3dmUnitsAndTolerances::Scale(mm)` |

### Export (all in `renderer.cpp`, 1 unit = 1 mm)
`exportOBJ`, `exportSTL`, `exportPLY`, `exportGLB` (binary glTF via tinygltf),
`export3DS`, `export3DM` (Rhino v6, mm units, via openNURBS
`AddModelGeometryComponentForExperts`).

Export is exposed in-app through the **Export sheet** (`ExportFragment`):
6 format cards + "EXPORT & SAVE" (→ `Downloads/AuraCAD` via MediaStore) and
"SHARE EXPORT" (→ system share sheet via content URI / FileProvider).

## openNURBS integration notes

- Fetched at configure time via `FetchContent` (Git tag `v8.9.24194.18121`,
  shallow). Its CMake adds `zlib`/`freetype263`/`android_uuid` subdirectories
  and links `opennurbsStatic`.
- **`opennurbs_android_font.patch` is required** — without it
  `opennurbs_freetype.cpp` fails to compile on Android < API 29
  (`AFontMatcher_*` symbols don't exist). Applied via `PATCH_COMMAND`.
- `ON_ModelGeometryComponent::Geometry()` needs a parameter
  (`Geometry(nullptr)`).
- `m_unit_system` is the **class** `ON_UnitSystem`, not the `ON::LengthUnitSystem`
  enum — use `ON_3dmUnitsAndTolerances::Scale(unitSystem)` for conversion.

## Tools (native engines)

- **Separation** — `mesh_separator.cpp`: Union-Find islands → separate meshes.
  `nativeSetSeparationMinFaces` drops dust islands.
- **Gizmos** — `nativeSetGizmoMode` (0 off, 1 move, 2 rotate, 3 scale) +
  `nativeGizmoDrag(dx,dy,start)`.
- **Ring** — `nativeAnalyzeRing` → `nativeGetRingParams` (inner Ø, band width,
  height) → `nativeSetRingInnerDiameter/BandWidth/Height`; US ring-size
  presets; proportional mode; live top-view preview.
- **Brush** — Laplacian smooth + sculpt with mm radius.
- **Mesh ops** — decimate (QEM), weld vertices, remove zero-area faces,
  combine meshes, per-mesh stats.

## Undo/redo model

- `nativePushUndoState()` snapshots mesh data + transforms on the undo stack
  (delete/vertex edits included). Called ONCE on slider/brush **down**.
- Setters (`nativeSetRotation`, `nativeSetScaleMM`, per-mesh setters, brush
  apply, delete…) intentionally do **not** push — continuous drags collapse
  into one entry. Polled buttons enable/disable via `nativeCanUndo/Redo`.

## UI kit

`UISheetKit` is the single design language: palette constants
(`BG_SHEET #0F1520`, cyan `#4DD8FF`, amber, violet, green, red), card system,
buttons, chips, inputs, seek bars. Use it for every new screen so spacing and
colors never drift.

## Gotchas

- GL calls outside the GL thread are crash fuel — always `queueEvent`.
- `MediaScannerConnection.scanFile` inside a coroutine needs
  `this@MainActivity` (Kotlin scope shadowing bug bites easily).
- `armeabi-v7a` is intentionally not built (CI 60-min budget); arm64 + x86_64.
- Keep the openNURBS patch in sync with the pinned tag; verify with
  `git apply --check` against a fresh checkout if the tag ever bumps.
