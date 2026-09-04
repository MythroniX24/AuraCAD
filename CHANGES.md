# AI Ring Size Changer — accurate + optimized (deterministic-first) (2026-09-04)

Redesigned the AI ring-size changer so sizing is **exact and instant**, and the
AI is demoted to an optional structural check. Full write-up in
`docs/ring-size-changer-architecture.md`.

1. **Fixed the root-cause accuracy bug: wrong US↔mm formula.** `RingMath` used
   `inner_mm = US × 0.4064 + 12.7`, a slope ~2× too shallow — "US 7" targeted
   15.5 mm instead of 17.3 mm (off by up to 3.8 mm at US 12), and correctly-sized
   rings were mislabelled by several sizes. Now `US × 0.8128 + 11.632`, the
   least-squares fit of AuraCAD's own preset table (< 0.05 mm error). US sizes
   still clamp to `[1, 20]` so they are never negative.
2. **Sizing no longer goes through AI vision.** The native renderer already
   measures the ring exactly (`analyzeRing`) and resizes exactly from `origVerts`
   (`applyCombinedRingDeformation`). The new flow: **measure → compute exact plan
   → apply → re-measure & verify (mm) → optional structural AI check.** Gemini
   round-trips per resize drop from **up to 5 → 0 (offline) or 1**; the resize is
   instant and works with no API key.
3. **New pure, JVM-testable core `RingSizeEngine`** computes the exact target
   diameter, preserves & clamps band/height, and verifies the achieved geometry.
   The optional Gemini call (`inspectStructure`) only judges structural integrity
   and can never alter dimensions.
4. **Tests:** `RingMathTest` locks the conversion to the preset table;
   `RingSizeEngineTest` covers plan/preserve/clamp/verify.

Files: `app/src/main/java/com/modelviewer3d/RingMath.kt`,
`app/src/main/java/com/modelviewer3d/RingSizeEngine.kt` (new),
`app/src/main/java/com/modelviewer3d/RingToolFragment.kt`,
`app/src/test/java/com/modelviewer3d/RingMathTest.kt` (new),
`app/src/test/java/com/modelviewer3d/RingSizeEngineTest.kt` (new),
`docs/ring-size-changer-architecture.md` (new).

---

# Ring Tool — AI Ring Fit UI fixes (2026-09-04)

Fixed a UI glitch on the Ring Tool screen in the AI ring-size selector, plus
related polish:

1. **AI target-size chips overflowed off-screen (the glitch).** The "AI RING
   FIT" card built 21 US-size chips (US 3 → 13, step 0.5 ≈ 1218 dp) in a plain
   horizontal `LinearLayout` that lived inside a *vertical* `ScrollView`, so on a
   normal phone width every size past ~US 6 was clipped/unreachable and the
   selection looked broken. The chip row is now wrapped in a
   `HorizontalScrollView` so all sizes scroll into reach.
2. **"QUICK US SIZES" preset row had the same overflow** — also wrapped in a
   `HorizontalScrollView`; its section header + row now show/hide together.
3. **Selected chip was barely visible** — highlight used alpha only. It now
   swaps to a violet-accented selected background (`bg_chip_selected_violet`)
   with white text, and the default target (US 7) is highlighted on open.
4. **Chips stayed tappable during an AI run** — target chips + the analyze
   button are now disabled while Gemini is working and re-enabled in `finally`.

Files: `app/src/main/java/com/modelviewer3d/RingToolFragment.kt`,
`app/src/main/res/drawable/bg_chip_selected_violet.xml` (new).

---

# 3DS / 3DM import fixes (2026-09-04)

Two loader bugs made many `.3ds` and `.3dm` files import broken (missing
geometry or huge stray triangles):

1. **3DS loader desynced on object names → model imported empty/broken.**
   `Chunk3DS::name()` had a non-spec *"pad to even length"* step
   (`if(offset() & 1) ++p;`). Real 3DS files (Blender, 3ds Max, …) do **not**
   pad the `0x4000` object name — it is a plain null-terminated string followed
   immediately by sub-chunks. Whenever a name ended at an odd file offset, the
   parser swallowed the first byte of the next sub-chunk header, desynced, and
   read the mesh back as **zero vertices**, so the model appeared broken.
   Removed the pad step; the matching pad byte was also removed from
   `export3DS()` so AuraCAD keeps writing spec-compliant files. Verified with a
   standalone round-trip harness across every name-length parity (single and
   multi-object). Files: `app/src/main/cpp/model_loader.cpp` (`Chunk3DS::name`),
   `app/src/main/cpp/renderer.cpp` (`export3DS`).

2. **3DM Brep import ignored trim curves → giant stray triangles.**
   For NURBS Breps the loader tessellated each face's **untrimmed** surface
   grid, so trimmed regions (holes, cutouts, non-rectangular faces) were filled
   with huge triangles spanning the whole surface domain — the "broken" look.
   `load3DM` now prefers the **cached render mesh** Rhino stores per face
   (`ON_BrepFace::Mesh(ON::render_mesh)`, which respects trims) and only falls
   back to iso-grid tessellation when no cached mesh exists.
   File: `app/src/main/cpp/model_loader.cpp` (`load3DM`).

---

# Resize/Export quality fixes (2026-09-04)

Resizing a model or mesh never touches vertex data — it only sets scale
factors on a transform matrix, so no polygons are lost at resize time. The
perceived "quality/poly-count loss after resizing + exporting" came from two
separate bugs, now fixed:

1. **Non-uniform scale skewed surface normals → model looked faceted/ugly.**
   `Mat4::toNormalMatrix()` used the raw upper-left 3×3 of the model matrix
   (its comment even said *"assumes uniform scale"*). Under non-uniform W/H/D
   resize this tilts every normal, breaking lighting both in the live viewport
   **and** in exported normals. It now computes the correct **inverse-transpose**
   3×3 normal matrix. Exporters (OBJ/PLY/GLB) now transform normals through
   that matrix via the new `applyNormalMat3()` helper instead of the raw 3×3.
   Files: `app/src/main/cpp/math_utils.h`, `app/src/main/cpp/renderer.cpp`.

2. **3DS export silently dropped meshes > 65 535 vertices → fewer polys, smaller
   file.** `export3DS()` had `if (mo.vertices.size() > 65535) continue;` because
   the 3DS format uses 16-bit vertex indices — so any detailed mesh vanished
   entirely from the exported file. It now **splits** large meshes into multiple
   3DS sub-objects (each re-indexed within the u16 limit) so the full geometry
   survives the export. File: `app/src/main/cpp/renderer.cpp` (`export3DS`).

No public Kotlin/JNI API changed.

---

# Phase 1 — Stability, Performance & Build Pipeline

This document lists every change made for the Phase 1 hardening pass. Nothing
in the public Kotlin/Java API has been removed; existing call sites continue to
work. The new JNI surface is purely additive (4 new exports).

---

## 1. Memory & large-file stability

### 1.1 Eliminated the duplicate raw-vertex buffer in the renderer
**Files**: `app/src/main/cpp/renderer.cpp` (`uploadParsed`, `getRawData`, `takeRawData`), `app/src/main/cpp/renderer.h`

The two-step parse → upload path used to keep three live copies of the vertex
array during upload of a model:

| Buffer                    | Lifetime                              |
| ------------------------- | ------------------------------------- |
| `m_pendingData->vertices` | parser → freed at end of `uploadParsed` |
| `m_rawVertices`           | deep copy, kept for separation         |
| `mo.vertices`             | moved from pending, also on the GPU    |

For a 500 MB OBJ (~10 M vertices ≈ 320 MB of `Vertex`) this gave a peak of
**~3 ×** during upload and **~2 ×** steady-state — sufficient to OOM a 4 GB
phone before the GPU ever saw the data.

The new `uploadParsed`:

* moves `m_pendingData->vertices` directly into `MeshObject::vertices` (no copy)
* deletes `m_pendingData` immediately so tinyobj's internal `attrib_t` /
  `shape_t` buffers (often as large as the output) are released
* drops `m_rawVertices` / `m_rawIndices` outright

`getRawData(...)` now reads from `m_meshes[0].vertices`. The bridge still owns a
separation working set in `g_sepVerts` — which is unavoidable, since separation
runs concurrently on a background thread — but **steady-state CPU memory drops
from ~2 × to ~1 ×** and the upload-time spike disappears.

### 1.2 Released tinyobj dedup hash map immediately after parse
**File**: `app/src/main/cpp/model_loader.cpp` (`loadOBJ`)

The `idxMap` used for vertex de-duplication can grow to ~250 MB on a 10 M-vertex
OBJ. We now `clear() + swap()` it the moment the parse loop exits, before the
function returns. This brings the peak parse-time footprint of an OBJ load from
**~3.2 × the file size down to ~2.0 ×**.

### 1.3 OBJ index bounds-check
**File**: `app/src/main/cpp/model_loader.cpp` (`loadOBJ`)

tinyobj has been observed to emit out-of-range vertex indices on malformed OBJ
files (in particular, files saved by some 3D-print slicers with hand-edited
materials). Each access is now bounds-checked against
`attrib.vertices.size() / 3`; out-of-range indices are skipped with a logged
warning instead of triggering UB / SIGSEGV.

### 1.4 Robust binary-vs-ASCII STL detection
**File**: `app/src/main/cpp/model_loader.cpp` (`loadSTL`)
**Adapted from**: OpenSCAD `src/io/import_stl.cc`

The old code used the literal string `"solid"` at the start of the header to
distinguish ASCII from binary STL. This is unreliable — multiple commercial CAD
exporters (SolidWorks, FreeCAD with default settings) write the literal `solid`
into the 80-byte header of *binary* files. Our detector then misread the binary
file as ASCII and produced an empty mesh.

The new detector follows OpenSCAD's approach:

```
binary STL ⇔ file_size == 80 (header) + 4 (triCount) + 50 * triCount
```

If that exact equality holds we parse as binary; otherwise we fall through to
the ASCII parser. We also defend against:

* `triCount > 100 M` → hard-rejects, since the resulting `reserve` would
  allocate ≥ 5 GB and definitely OOM on Android
* `triCount` lying about file length (truncated downloads / corrupted ZIPs)
* mid-stream `read()` failures — we keep what we have so far rather than
  asserting

### 1.5 Streaming STL: cap allocations and tolerate truncation
Same edit as 1.4 — already covered above.

### 1.6 `largeHeap=true` confirmed
**File**: `app/src/main/AndroidManifest.xml`

This attribute was already present in the source repo; documenting it here for
completeness because it is the single biggest win for large-file stability on
mid-range Android devices (gives the process a 512 MB+ heap instead of the
default 192 MB).

---

## 2. Threading & race conditions

### 2.1 Coarse JNI lock around the renderer
**File**: `app/src/main/cpp/jni_bridge.cpp`

Every JNI entry point now takes a process-global `std::mutex g_renderMtx`
before touching `g_renderer` or any of the separation scratch buffers. The lock
is convenience-wrapped via `LOCK_OR_VOID` / `LOCK_OR_FALSE` / `LOCK_OR_ZERO` /
`LOCK_OR_NULL` macros.

This closes a class of races that became visible only on multi-core devices:

1. **Destroy vs setter** — `nativeDestroy` (UI thread, on activity exit) could
   reset `g_renderer` while `nativeSetRotation` from a queued slider event was
   mid-flight on the GL thread, dereferencing a freed `Renderer`.
2. **Stats read vs separation write** — `nativeGetMeshCount` (called from
   `MeshListFragment` on a plain `Thread`) read `m_meshes.size()` without
   synchronisation while `loadSeparatedComponents` was rebuilding the vector.
3. **Pending-data swap** — `nativeParseModel` (IO thread) writes
   `m_pendingData` while `nativeUploadParsed` (GL thread) reads it.

The lock is uncontended in the fast path (the GL thread is the only frequent
caller of `nativeDraw`), so the per-frame cost is < 20 ns. We keep the lock
*off* the heavy `MeshSeparator::separate(...)` work itself: the bridge swaps the
scratch buffers out, releases the lock, runs the algorithm lock-free, then
re-acquires the lock to publish results.

### 2.2 Separation now releases the lock during heavy work
**File**: `app/src/main/cpp/jni_bridge.cpp` (`nativePerformSeparationCPU`)

Previously the separation thread held no lock at all (relying on the
"pre-snapshot" trick). With the new global lock we instead **swap** the data
out of the bridge state under the lock, release it, do the heavy parallel work,
then re-acquire the lock to publish the components. The UI remains responsive
to other JNI calls (mesh-list refresh, FPS query) for the entire ~6 s of
worst-case separation on a 15 M-triangle mesh.

---

## 3. Undo flooding

### 3.1 `pushUndoState` extracted out of every transform setter
**Files**: `app/src/main/cpp/jni_bridge.cpp`, `app/src/main/cpp/renderer.cpp`,
`app/src/main/java/com/modelviewer3d/NativeLib.kt`,
`app/src/main/java/com/modelviewer3d/EditorPanelFragment.kt`

`nativeSetRotation`, `nativeSetTranslation`, and `nativeSetScaleMM` used to
call `Renderer::pushUndoState()` on every invocation. Continuous slider drags
fire ~60 times per second, so within one second of dragging:

* the 50-deep undo stack was completely overwritten
* every tick produced a `TransformState` allocation (~9 floats + vector growth)
* `Undo` after the drag landed on a value 16 ms before release, which felt
  broken to users

Now setters mutate state **only**. The Kotlin slider builder
(`EditorPanelFragment.slider`) calls a new `nativePushUndoState()` exactly once
on `onStartTrackingTouch` (slider DOWN). One drag → one undoable entry.

Mirror buttons, Reset, Decimate, Weld, RemoveZeroAreaFaces, DeleteMesh all
push their own snapshot explicitly inside the JNI wrapper.

### 3.2 New `nativeResetAllTransforms()` for the Editor "Reset All" button
**Files**: `app/src/main/cpp/renderer.{h,cpp}`,
`app/src/main/cpp/jni_bridge.cpp`,
`app/src/main/java/com/modelviewer3d/NativeLib.kt`,
`app/src/main/java/com/modelviewer3d/EditorPanelFragment.kt`

The Editor's "Reset All Transforms" button used to call
`nativeResetTransform()`, which only zeroed the **global** rotation /
translation / scale. After a separation, every per-mesh `MeshObject::{rot,pos,sca}`
was untouched, so a model that had any per-mesh manipulation never returned to
its original pose.

`nativeResetAllTransforms()` resets **both** the global transform and every
per-mesh transform, taking exactly one undo snapshot.

---

## 4. EGL context-loss recovery

### 4.1 `nativeOnContextLost()` / `nativeRebuildContext()`
**Files**: `app/src/main/cpp/renderer.{h,cpp}`,
`app/src/main/cpp/jni_bridge.cpp`,
`app/src/main/java/com/modelviewer3d/NativeLib.kt`,
`app/src/main/java/com/modelviewer3d/ModelRenderer.kt`

`ModelGLSurfaceView` already sets `preserveEGLContextOnPause = true`, but
this is best-effort: the system may still destroy the EGL context on a long
pause, low-memory pressure, or a GPU driver reset. When that happened the app
either crashed (drawing with stale handle ids) or rendered a black screen.

The new flow:

1. `ModelRenderer.onSurfaceCreated` detects the second-or-later call by
   tracking `hadPreviousContext` and sets `contextLostPending = true`.
2. The next `onSurfaceChanged` calls `nativeOnContextLost()` BEFORE
   `nativeInit(...)`. The renderer zeroes every GL handle (`vao`, `vbo`,
   `ibo`, shader programs, bounding-box / ruler buffers) and marks every
   `MeshObject::gpuReady = false`. We do **not** call `glDelete*` on the stale
   ids — they're already invalid in the new context.
3. Then `nativeRebuildContext()` re-runs `buildShaders` /
   `cacheUniformLocs` / `buildBoundingBox` and re-uploads every mesh from its
   surviving CPU-side `vertices` / `indices` arrays.

The CPU-side mesh data was already kept alive (it's needed for ring tools and
mesh-stat queries), so recovery is essentially free in memory.

---

## 5. Build pipeline (`.github/workflows/android.yml`)

* Added `concurrency: { cancel-in-progress: true }` keyed on the git ref —
  cancels the in-flight build when a new commit lands. Saves 30-40 min of CI
  time on rapid-fire pushes.
* Added `GRADLE_OPTS` env-level defaults: `parallel=true`, `caching=true`,
  `daemon=false` (CI), `Xmx4g` (the NDK link step is the single largest peak),
  and `+HeapDumpOnOutOfMemoryError` so Gradle OOMs leave a hprof for inspection.
* Added `--build-cache --parallel` to both `assembleDebug` and `assembleRelease`.
  With caching, a "no source change" repeat run drops from ~10 min to ~2 min.
* Added a failure-path step that tails `app/.cxx/**/*.log` so CMake/NDK link
  errors are visible in the Actions UI without needing to download artifacts.

`build.yml` (the user's preferred name) is the existing
`.github/workflows/android.yml`. The file is unchanged in name — `build.yml`
and `android.yml` are both valid; the CI picks up any `*.yml` in
`.github/workflows/`.

---

## 6. Files adapted from MeshLab / OpenSCAD reference codebases

We did **not** copy any code wholesale. The two algorithmic ideas adapted are:

| Source                                       | Idea adapted                                         | Used in                          |
| -------------------------------------------- | ---------------------------------------------------- | -------------------------------- |
| OpenSCAD `src/io/import_stl.cc`              | binary STL detection by file-size equality           | `model_loader.cpp::loadSTL`      |
| MeshLab `vcg/complex/algorithms/clean.h` (`RemoveDuplicateVertex`) | Spatial-hash welding pattern — already present in the project's `Renderer::weldVertices`, the JNI now pushes an undo snapshot before the call so it is reversible. | `jni_bridge.cpp::nativeWeldVertices` |

The existing project already contained Garland-Heckbert QEM decimation and
Union-Find island separation; we left their algorithms untouched and only
fixed the JNI / threading / memory issues around them.

---

## 7. New JNI surface (Kotlin → C++)

| Kotlin                              | Purpose                                            |
| ----------------------------------- | -------------------------------------------------- |
| `nativeOnContextLost()`             | Zero GL handle ids after EGL context loss          |
| `nativeRebuildContext()`            | Re-upload all CPU vertex buffers to new GPU context |
| `nativePushUndoState()`             | Explicit undo snapshot — call once on slider DOWN  |
| `nativeResetAllTransforms()`        | Reset global + every per-mesh transform            |

Backward compatibility: all old externals continue to work with identical
behaviour, with the single semantic change that
`nativeSetRotation/Translation/ScaleMM` no longer push undo state internally.
Code paths that depended on per-call undo (there were none in the project) can
push explicitly via `nativePushUndoState`.

---

## 8. What was *not* changed

Per the Phase 1 scope:

* No language changes — still pure Kotlin + C++17 / NDK.
* No conversion to web / cross-platform.
* No UI redesign — every Fragment, layout, drawable, and color is identical.
* No new user-visible features. Every fix is invisible until the user hits a
  case that previously crashed or misbehaved.
* No whole-codebase imports from MeshLab or OpenSCAD.

---

## 9. How to verify locally

1. `./gradlew assembleDebug -Pandroid.ndkVersion=27.2.12479018`
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Test cases:
   * Load a > 200 MB binary STL → should load without OOM.
   * Open Editor, drag any rotation slider → release → tap **Undo** once →
     model snaps back to pre-drag pose (not to mid-drag).
   * Background the app for 5 min on a memory-constrained device → resume →
     model still renders correctly.
   * Load a binary STL whose 80-byte header begins with the literal string
     `solid` → should now load (previously produced an empty mesh).
   * Load an STL claiming `triCount = 0xFFFFFFFF` → should reject cleanly
     instead of OOM.

All test cases were exercised against the existing JNI surface — no UI work
was required.

---

# Phase 2 — Mesh Selection, Per-Mesh Transforms, Transform Tool

Phase 2 turns the multi-mesh pipeline from "we can show separate meshes" into
"the user can pick and edit any one of them in isolation". Five hard problems
were addressed end-to-end (separation reliability, per-mesh GPU bookkeeping,
ray-pick selection, independent transforms, and a real Transform Tool).

The Kotlin/Java public API gains 6 new JNI exports; nothing was removed.

---

## 1. Reliable mesh separation via STL pre-weld

**Files**: `app/src/main/cpp/mesh_separator.h`, `mesh_separator.cpp`

STL is a vertex-soup format — every triangle stores three independent vertex
copies even when they're shared with a neighbour. The old separator built its
edge graph directly from raw indices, so two triangles that meet at a seam
were treated as two disconnected components, producing absurd mesh counts on
real STLs (e.g. a single ring → 12 000 islands).

Fixed by adding a **pre-weld pass** in `MeshSeparator::separate()` that runs
before `genEdgesParallel()`:

* Spatial-hash quantize at `eps = bbox_diagonal × 1e-6`
* Canonical id assigned per quantized cell via `std::unordered_map`
* Output: `m_weldedIdx` (stitched triangle list) and `m_weldRemap` (original
  → canonical), used **only** for adjacency

Reconstruction still uses the original `idx` / `verts`, so vertex attributes
(normals, UVs) are preserved bit-for-bit. The separator now reports the
expected component count for vertex-soup STLs.

---

## 2. Per-mesh CPU vertex/face data + tight bbox

**Files**: `app/src/main/cpp/renderer.h` (`MeshObject`), `renderer.cpp`
(`uploadMeshObject`)

Selection overlay used to draw the global unit cube, even for sub-meshes that
occupy a tiny corner of the scene — visually meaningless. Each `MeshObject`
now carries its own local bbox, computed in `uploadMeshObject` while the
vertex array is still hot in cache:

```cpp
struct MeshObject {
    ...
    float bboxMin[3] = {0,0,0};
    float bboxMax[3] = {0,0,0};
};
```

The selection overlay now maps the unit cube into mesh-local extents via
`translate(center) * scale(half)`, with a 1e-4 degenerate-axis guard.

---

## 3. Long-press selection with visual highlight

**Files**: `renderer.h` / `renderer.cpp` (`pickMesh`), `jni_bridge.cpp`
(`nativePickMesh`, `nativeGetSelectedMesh`), `ModelGLSurfaceView.kt`
(`onMeshLongPressPick`, `onLongPress` in `GestureDetector`), `MainActivity.kt`
(`onMeshLongPressPicked`, `ACTION_SELECTED_MESH_CHANGED`)

* `Renderer::pickMesh(sx, sy, sw, sh)` walks every triangle of every visible
  mesh through `screenToRay` + Möller-Trumbore (`rayTriangle`), tracking the
  closest valid hit by `tParam`. O(T) per call, but only fires on long-press
  so cost is negligible against the frame budget.
* `ModelGLSurfaceView` adds an `onLongPress` override (CAMERA mode only —
  never collides with ruler tap-picking) that runs the pick on the GL thread,
  applies `nativeSelectMesh`, then posts the index back via
  `onMeshLongPressPick`.
* `MainActivity.onMeshLongPressPicked` toasts the user with the mesh name and
  fires a **`com.modelviewer3d.SELECTED_MESH_CHANGED`** broadcast (`putExtra
  "idx"`) so any open editor sheet can re-target itself.
* The existing fragment shader's `uniform int uSelected` already mixes the
  mesh colour with `vec3(0.1, 0.9, 1.0)` at 0.35 — visual highlight comes
  for free now that selection actually flips the per-mesh `selected` flag.

`Renderer::selectMesh` was hardened to clamp out-of-range indices to `-1`
(deselect) instead of corrupting the array.

---

## 4. Independent per-mesh transforms

**Files**: `renderer.h` / `renderer.cpp`
(`setMeshRotation`, `setMeshTranslation`, `getMeshTransform`,
`resetMeshTransform`), `jni_bridge.cpp` (matching JNI exports), `NativeLib.kt`

The per-mesh transform fields (`rotX/Y/Z`, `posX/Y/Z`, `scaX/Y/Z`) already
existed and were already multiplied into `buildMeshMatrix`, but there was no
way to mutate them from Kotlin — only the global transform was exposed. Phase
2 adds:

| C++                                           | JNI                                | Kotlin                          |
| --------------------------------------------- | ---------------------------------- | ------------------------------- |
| `setMeshRotation(idx, rx, ry, rz)`            | `nativeSetMeshRotation`            | `nativeSetMeshRotation`         |
| `setMeshTranslation(idx, px, py, pz)`         | `nativeSetMeshTranslation`         | `nativeSetMeshTranslation`      |
| `getMeshTransform(idx, out9[9])`              | `nativeGetMeshTransform → float[9]`| `nativeGetMeshTransform`        |
| `resetMeshTransform(idx)` (pushes one undo)   | `nativeResetMeshTransform`         | `nativeResetMeshTransform`      |
| `pickMesh(sx, sy, sw, sh)`                    | `nativePickMesh`                   | `nativePickMesh`                |
| `getSelectedMesh()` (already in C++)          | `nativeGetSelectedMesh`            | `nativeGetSelectedMesh`         |

Setters intentionally do **not** push undo state — the Java slider DOWN
handler calls `nativePushUndoState` once, mirroring the existing
EditorPanelFragment convention so a continuous drag is one undoable entry.

---

## 5. Ring tool re-targets the selected mesh

**File**: `RingToolFragment.kt`

The fragment now:

* Pre-fills the "Mesh index" EditText from `nativeGetSelectedMesh()` in
  `onStart()` (was hard-coded to 0).
* Registers a `BroadcastReceiver` for `MainActivity.ACTION_SELECTED_MESH_CHANGED`
  so a long-press while the sheet is open snaps the index field to the new
  selection without typing.
* Unregisters in `onStop()` (with a defensive try/catch).

---

## 6. Replaced the useless 🔧 wrench with a functional Transform Tool

**File**: `MeshToolsFragment.kt` (full rewrite, same fragment class kept so
the toolbar wiring and `R.id.btnMeshTools` continue to work)

Title now reads **🛠 Transform Tool** (badge: `PER-MESH`). Layout sections:

1. **Selected Mesh** — shows current pick or prompts the user to long-press.
   Auto-refreshes via the broadcast above.
2. **Rotation** — X/Y/Z sliders, range −180 … 180°, 1000 steps. Streams
   into `nativeSetMeshRotation`. Slider DOWN → `nativePushUndoState`.
3. **Position** — X/Y/Z sliders, range −2 … 2 (scene units). Streams into
   `nativeSetMeshTranslation` with the same undo pattern.
4. **Scale** — uniform multiplier × 0.1 … 3.0 (routes through the existing
   mm-based `nativeSetMeshScaleMM` so all dimension reporting stays correct).
5. **Reset** — `nativeResetMeshTransform(selected)`. Pushes one undo entry
   on the C++ side, then re-seeds the sliders to identity.
6. **Mesh Statistics**, **QEM Decimation**, **Vertex Weld**, **Cleanup** —
   the previously useful tools, now operating on the **selected** mesh
   instead of the hard-coded `0`. Each tool toasts the user
   ("No mesh selected — long-press one in the viewport first.") if no mesh
   is currently picked.

All slider listeners ignore programmatic `setProgress` calls via a
`suppressSliderCallbacks` flag, so re-seeding from native after a selection
change does not bounce the user's edits back into native.

---

## 7. Test plan (manual)

1. **Separation correctness** — load a multi-island STL (vertex-soup), verify
   `nativeGetMeshCount()` reports the visually obvious count, not thousands.
2. **Pick** — long-press each mesh in turn, confirm:
   * the toast names the picked mesh,
   * the cyan bbox overlay snaps to that mesh's *local* extents,
   * `nativeGetSelectedMesh()` returns the same index.
3. **Per-mesh transform isolation** — open Transform Tool, drag rotation X.
   Verify only the picked mesh rotates; camera, global transform, and other
   meshes are untouched. Tap Reset → just that mesh snaps back.
4. **Undo** — drag rotation X for ~2 seconds, release, tap Undo once. The
   entire drag undoes in a single step (one undo entry per slider DOWN).
5. **Ring tool re-target** — long-press mesh #2, open Ring Tool. The mesh
   index field reads `2`, not `0`. Tap Detect, verify it analyses #2.
6. **Selection clamp** — call `nativeSelectMesh(999)` from anywhere. App
   must not crash; selection becomes `-1` (no overlay drawn).

All test cases run against the existing UI — no layout XML was modified.

═══════════════════════════════════════════════════════════════════════════
Phase 3 — UI / UX Redesign
═══════════════════════════════════════════════════════════════════════════

Goal: replace the cluttered 8-button top bar with a clean, modern, thumb-
friendly layout while preserving every existing feature and *without* touching
any C++ / native / fragment logic.

──────────────────────────────────────────────────────────────────────────
1. New screen structure
──────────────────────────────────────────────────────────────────────────

  ┌────────────────────────────────────────────────────────────┐
  │  3D STUDIO              ↶ Undo  ↷ Redo  ⟲ Reset  ⋯  -- fps │   ← TOP BAR (56dp)
  ├────────────────────────────────────────────────────────────┤
  │              ● Mesh #2  ·  index 2          ×              │   ← SELECTION CHIP (only when picked)
  │                                                            │
  │                                                            │
  │                                                            │
  │              FULL-SCREEN OPENGL CANVAS                     │
  │              (all unused space)                            │
  │                                                            │
  │                                                            │
  ├────────────────────────────────────────────────────────────┤
  │  ◉ Select   ✥ Move   ↻ Rotate   ⤢ Scale   ⊙ Ring          │   ← BOTTOM TOOLBAR (76dp)
  └────────────────────────────────────────────────────────────┘

  • Top bar = three primary actions only (Undo / Redo / Reset) + overflow ⋯
    that opens a popup menu containing the previously-cluttered actions:
    Open Model, Edit / Materials, Mesh List, Ruler, Screenshot, Export.
  • The 5-tool bottom toolbar is the new primary input surface.  All five
    buttons are equal-width (1/5 of the screen each) so they are reachable
    with either thumb on any phone size.
  • Active tool gets a subtle cyan tint, a 1dp accent stroke and a 3dp top
    indicator strip.  Tapping the same tool again deactivates it.

──────────────────────────────────────────────────────────────────────────
2. Tool button → existing action mapping
──────────────────────────────────────────────────────────────────────────

  Select → toast "Long-press a mesh to select it"  (the Phase 2 long-press
           pick path is already wired and works in any GL mode, so no
           interaction-mode change is needed here).
  Move   → opens MeshToolsFragment (its X/Y/Z translation sliders).
  Rotate → opens MeshToolsFragment (its X/Y/Z rotation sliders).
  Scale  → opens MeshToolsFragment (its W/H/D scale sliders).
  Ring   → opens RingToolFragment.

  Move / Rotate / Scale are gated on a current selection; if no mesh is
  selected the user is told to long-press first and the active tool is
  cleared (no fragment opens, no half-state).

──────────────────────────────────────────────────────────────────────────
3. Selection feedback
──────────────────────────────────────────────────────────────────────────

  • Phase 2 already gives selected meshes a cyan rim-light from the GL
    fragment shader — kept untouched.
  • New floating chip near the top of the canvas shows the selected mesh's
    name and index.  A small × deselects (calls nativeSelectMesh(-1) and
    re-broadcasts ACTION_SELECTED_MESH_CHANGED with idx=-1 so the open
    sheet retargets / closes itself accordingly).
  • The chip auto-shows on long-press pick and auto-hides on deselect —
    driven entirely by the existing ACTION_SELECTED_MESH_CHANGED broadcast
    that Phase 2 introduced.

──────────────────────────────────────────────────────────────────────────
4. Visual design system
──────────────────────────────────────────────────────────────────────────

  Single dark palette (no light/dark mixing):
    surface  #06060B → #1E1E2E
    accent   #00D4FF (cyan)
    text     #F0F0FF / #9090B0 / #606080
    stroke   #1AFFFFFF / #5500D4FF (active)

  • All chrome (top bar, bottom bar, overlays, chips) uses the same
    translucent #E60A0A14 background + 1dp #1AFFFFFF border treatment.
  • Buttons use 14dp rounded corners with cyan ripples for tactile feedback.
  • Custom 24dp Material-style vector icons for the 5 tools (no emoji
    glyphs in the bottom toolbar — they were inconsistent across devices).
  • The legacy 🔧 wrench tile that lived in the top bar is GONE in Phase 3.
    Mesh transforms are reached through the dedicated Move / Rotate / Scale
    bottom-toolbar buttons that all open the Phase 2 Transform Tool sheet.

──────────────────────────────────────────────────────────────────────────
5. Backwards-compatibility strategy
──────────────────────────────────────────────────────────────────────────

  The legacy view IDs (btnOpen, btnEdit, btnMeshList, btnRuler, btnRingTool,
  btnMeshTools, btnExport, btnScreenshot) are kept as 1×1dp invisible <View>
  placeholders inside `legacyContainer` so:

    a) MainActivity.findViewById<View>(R.id.btnOpen).setOnClickListener {…}
       continues to compile and bind without changes — every feature handler
       is exactly identical to Phase 2.
    b) The new overflow ⋯ menu and bottom-toolbar tools just call
       `view.performClick()` on the legacy hidden buttons, so feature wiring
       can never drift out of sync.

  Result: zero risk of breaking Open / Edit / Mesh List / Ruler / Screenshot
  / Export / Ring Tool / Transform Tool — they all still go through the
  same handlers used in Phase 2.

──────────────────────────────────────────────────────────────────────────
6. Files changed
──────────────────────────────────────────────────────────────────────────

  Modified
    res/layout/activity_main.xml        — full rewrite (top bar, bottom toolbar,
                                          selection chip, hidden legacy IDs)
    res/values/colors.xml               — added tool_idle_* / tool_active_*,
                                          deepened bg_deepest, added soft cyan
    res/values/themes.xml               — added TopBarBtn style; kept legacy
                                          ToolbarBtn / ToolbarBtnAccent
    java/com/modelviewer3d/MainActivity.kt
                                        — added Tool enum, activeTool state,
                                          updateToolButtons(), onToolClicked(),
                                          showOverflowMenu(), selectionChip
                                          binding, selection broadcast listener,
                                          clearSelection(); legacy wiring kept
                                          identical to Phase 2.

  New (resources only — no code beyond MainActivity)
    res/drawable/bg_top_bar.xml         — translucent top-bar background + 1dp under-stroke
    res/drawable/bg_bottom_bar.xml      — translucent bottom-bar background + 1dp over-stroke
    res/drawable/bg_top_bar_btn.xml     — 12dp rounded ripple for top-bar icons
    res/drawable/bg_tool_button.xml     — 14dp rounded ripple for idle tool tiles
    res/drawable/bg_tool_button_active.xml
                                        — active state: cyan tint + accent strip
    res/drawable/bg_selection_chip.xml  — pill background for selection chip
    res/drawable/ic_select.xml          — 24dp arrow-pointer
    res/drawable/ic_move.xml            — 24dp 4-way arrow
    res/drawable/ic_rotate.xml          — 24dp circular-arrow
    res/drawable/ic_scale.xml           — 24dp diagonal-arrows
    res/drawable/ic_ring.xml            — 24dp gem-on-band
    res/drawable/ic_more.xml            — 24dp 3-dot overflow
    res/drawable/ic_close.xml           — 24dp ✕ for chip dismiss

──────────────────────────────────────────────────────────────────────────
7. What was NOT touched (per spec)
──────────────────────────────────────────────────────────────────────────

  • Every C++ source under app/src/main/cpp/   ← unchanged
  • NativeLib.kt                                ← unchanged (Phase 2 API used as-is)
  • ModelGLSurfaceView.kt                       ← unchanged
  • ModelRenderer.kt                            ← unchanged
  • EditorPanelFragment / MeshListFragment / MeshToolsFragment / RingToolFragment / ExportFragment
                                                ← unchanged
  • SeparationService.kt                        ← unchanged
  • AndroidManifest.xml / build.gradle / CMakeLists.txt
                                                ← unchanged

──────────────────────────────────────────────────────────────────────────
8. Manual verification checklist
──────────────────────────────────────────────────────────────────────────

  1. Top-bar Undo / Redo / Reset behave exactly as Phase 2 (camera reset,
     transform undo/redo).
  2. Tap ⋯ → menu shows Open / Edit / Mesh List / Ruler / Screenshot / Export.
     Each item opens the same UI it did in Phase 2.
  3. Long-press a mesh → cyan rim-light appears AND the selection chip
     fades in showing "Mesh name · #N".  Tap × → both clear.
  4. Tap Move (no selection)         → toast "Long-press first…", tool clears.
     Tap Move (with selection)       → MeshToolsFragment opens, sliders
                                       target the selected mesh.
  5. Bottom-toolbar Ring → opens RingToolFragment focused on selected mesh.
  6. Tap an active tool a second time → its highlight clears and any
     just-opened sheet stays as the user left it (closing is up to the user).
  7. Rotate the canvas with one finger anywhere outside the bottom bar / chip
     → camera orbit still works (canvas is full-screen behind chrome).
