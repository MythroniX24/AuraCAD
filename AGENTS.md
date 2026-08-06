# AGENTS.md — Working in this repository

Guidelines for humans **and** AI agents contributing to AuraCAD.

## Golden rules

1. **Never run heavy builds or full unit-test suites on a dev device.**
   This project is developed on a low-power device — a full `assembleDebug`
   with the C++/openNURBS toolchain can take 30–60+ minutes locally. **CI is
   the compiler**: every push to `main` triggers
   `.github/workflows/android.yml` (unit tests + Debug/Release APK). Use it to
   verify builds instead of building locally.
2. **Only make light, static checks locally** (greps, brace balance,
   `git diff --check`, reading files). Anything CPU-heavy belongs in CI.
3. **Match existing conventions exactly.** The Kotlin UI is built
   programmatically through `UISheetKit`; the native layer is C++17 behind
   `NativeLib` + `jni_bridge.cpp`. Do not introduce new UI paradigms unless
   asked.
4. **Do not reimplement what exists.** Native math, mesh processing, ring
   engine and unit conversion already live in `app/src/main/cpp/`. Reuse via
   the `NativeLib` surface.
5. **Keep the mm-unit invariant.** Every measurement (model size, ruler, ring,
   brush radius, exports) flows through `mmPerUnit`. Never hardcode a scale
   that bypasses it.

## Conventions

### Kotlin (`app/src/main/java/com/modelviewer3d/`)
- Screens are `BottomSheetDialogFragment`s built **programmatically** using
  `UISheetKit` (palette, spacing, cards, buttons). XML layouts exist only for
  the main activity (`activity_main.xml`).
- Native calls happen through the `NativeLib` object. Any call that touches
  GL state **must** be wrapped in `glView.queueEvent { ... }`.
- Heavy work goes on `Dispatchers.IO`; UI updates go through
  `withContext(Dispatchers.Main)`.
- Model loading uses `appScope` (not `lifecycleScope`) so it survives
  back-press → background load + completion notification.
- Toast via the local `toast(...)` helper; live status via `tvStatusFile`.

### C++ (`app/src/main/cpp/`)
- `renderer.cpp` — all GL rendering + mesh data + export writers.
- `model_loader.cpp` — parsers: OBJ (tinyobjloader), GLB/GLTF (tinygltf),
  STL, PLY, 3DS, **3DM (openNURBS)**.
- `mesh_separator.cpp` — connected-component separation (Union-Find).
- `jni_bridge.cpp` — every `nativeXxx` symbol `NativeLib.kt` declares.
- OpenNURBS is fetched by CMake (`FetchContent`) and patched for
  Android < 29 via `PATCH_COMMAND` (`opennurbs_android_font.patch`). Never
  remove that patch; it is required for minSdk 24 builds.

### Undo/redo model
- Sliders/brushes call `nativePushUndoState()` **once on touch-down**, then
  stream values through setters. Setters do **not** push undo themselves —
  that keeps one drag = one undo entry.

## Verification workflow (for agents)
1. Make the change.
2. Local light checks: `git diff --check`, brace balance on edited C++ files,
   grep for dangling references (old function names, deleted symbols).
3. Ask for a code review (static, compile-error-focused — CI is the only real
   compiler).
4. Commit + push. **Then** watch `gh run list` / the Actions tab until green.
5. If CI fails, pull `--log-failed`, fix, push again. Never leave a red run.

## Commit style
- Single focused commit per logical change.
- Message: short imperative summary, mention the subsystem, e.g.
  `Add Rhino 3DM import + export via openNURBS (FetchContent v8.9)`.
