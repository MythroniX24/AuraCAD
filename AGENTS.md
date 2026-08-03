# AGENTS.md — Working in this repo

Guide for **humans and AI agents** working on AuraCAD. Read `Knowledge.md` for the deep-dive; this file is the quick convention cheat-sheet.

## Quick commands
```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # pure-JVM unit tests (no device needed)
./gradlew assembleDebug testDebugUnitTest
```
- `local.properties` is machine-specific and **git-ignored** — never commit it.
- Heavy builds are done by **GitHub Actions CI**; prefer pushing and watching the workflow over local builds.

## Repo conventions
- **Languages**: Kotlin (UI/glue) + C++17 (rendering/geometry). JNI exports live in `app/src/main/cpp/jni_bridge.cpp`; Kotlin declarations in `NativeLib.kt`.
- **C++ style**: `snake_case` members prefixed `m_`; status-return functions (no exceptions across JNI); `Vertex` is a plain float struct: `px,py,pz,nx,ny,nz,u,v`.
  - 🚫 **Never introduce `glm`** — there is no glm dependency. Use the plain `Vertex` fields (`px/py/pz`, not `.position`).
- **Units**: all public dimensions are **millimetres**. Never hardcode conversions.
  - C++: use `Renderer::mmPerUnit()` / `unitPerMM()`.
  - Kotlin: use `UnitMath` (`mmPerUnit`, `unitPerMM`, `distanceMM`).
  - Single source of truth: `renderer.h` (+ mirrored `UnitMath.kt`).
- **Threading**: rendering is on the GL thread; async native ops (load / separate / ring deformation) marshal results back to the main thread. **Never touch GL objects off the GL thread.**
- **JNI additions require 4 edits** (keep them in sync):
  1. `renderer.h` / `renderer.cpp` — new `Renderer` method
  2. `jni_bridge.cpp` — new `Java_..._nativeXxx` export
  3. `NativeLib.kt` — `external fun nativeXxx(...)`
  4. call site + tests

## Testing
- Any math that can live in Kotlin **must** be pure (no Android imports) and unit-tested in `app/src/test/java/com/modelviewer3d/` with JUnit 4.
- Geometry formulas are mirrored between C++ and Kotlin; the unit tests guard drift (`RingMathTest`, `UnitMathTest`).

## CI
- `.github/workflows/android.yml` is the official artifact build path (tests → debug → release → artifacts, GitHub Release on `v*` tags).
- The NDK version in the workflow **must match** `ndkVersion` in `app/build.gradle` (`27.0.12077973`).
- No private secrets are used — release signing falls back to `debug.keystore`.

## Golden rules
1. **Never break the mm unit pipeline** — ruler/ring/scale correctness depends on it.
2. **No glm.**
3. Keep ruler & ring formulas mirrored between C++ and Kotlin (tests enforce it).
4. When renaming an exported symbol (JNI, Kotlin fun), search & update **all** call sites first.
5. Keep `RingMath`/`UnitMath` dependency-free — they are the unit-test surface.
