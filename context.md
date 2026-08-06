# Context — current state of AuraCAD

Updated: 2026-08-06

## What AuraCAD is

Android 3D model viewer/editor. OpenGL ES 3.0 (C++17) + Kotlin. Real-mm
dimensions throughout. Repo: `MythroniX24/AuraCAD`.

## Recently shipped

- **Rhino 3DM import** — openNURBS v8.9 via CMake `FetchContent`; ON_Mesh
  direct + NURBS/Brep/Extrusion iso-grid tessellation; document units
  converted to mm. `.3dm` opens from file managers and share intents.
- **3DM export** — Rhino v6 file, mm units, via overflow menu.
- **Unified Export screen** — `ExportFragment`: 6 format cards
  (OBJ/STL/PLY/GLB/3DS/3DM) + **EXPORT & SAVE** + **SHARE EXPORT**.
  Replaces the old single-format overflow item ("💾 Export…" → sheet).
- **FileProvider** restored (`file_paths.xml`) for share on Android 9-.
- **Compact top header** — smaller buttons (36dp), tighter padding, reduced
  margins so it stops dominating the viewport.
- Earlier: separation screen rebuild, ring/brush/undo fixes, background
  model loading with notification, Gemini AI settings, mesh gizmos,
  per-mesh mm transforms, mm unit pipeline.

## Native export bindings (all live)

`NativeLib`: `nativeExportOBJ/STL/PLY/GLB/3DS/3DM(path): Boolean` — JNI
symbols exist in `jni_bridge.cpp`, implementations in `renderer.cpp`.

## CI status

GitHub Actions `.github/workflows/android.yml` on every push to `main`:
unit tests → `assembleDebug` → `assembleRelease` → artifact upload. Tag
`v*` also creates a GitHub Release. **Last green: commit `7a00e06`**
(openNURBS font patch). Heavy verification always happens here, not on the
dev device.

## Where to look next

| Task | Files |
|------|-------|
| Export UX | `ExportFragment.kt`, `MainActivity.kt` (`exportModel`) |
| 3DM loader | `model_loader.cpp` (`load3DM`) |
| 3DM writer | `renderer.cpp` (`export3DM`) |
| openNURBS patch | `opennurbs_android_font.patch`, `CMakeLists.txt` |
| Header layout | `activity_main.xml` (`topBar`), `themes.xml` (`TopBarBtn`) |
| Design system | `UISheetKit.kt` |

## Known notes

- Trimmed NURBS surfaces use untrimmed grid tessellation on import
  (renderable approximation) — fine for preview + export.
- `armeabi-v7a` intentionally not built (CI time budget).
- Always push and confirm the Actions run goes green before declaring done.
