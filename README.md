# AuraCAD — 3D Model Viewer & Editor

![Build](https://github.com/MythroniX24/AuraCAD/actions/workflows/android.yml/badge.svg)

A professional Android 3D model viewer & editor built with **OpenGL ES 3.0 (C++17)** and **Kotlin**.
Load, inspect, measure, sculpt, resize and export 3D models — all on your phone, all in real millimetres.

---

## ✨ Features

### 🛠 Modeling Tools
| Tool | What it does |
|------|--------------|
| **Select** | Tap any mesh to pick & highlight it (ray-cast picking). |
| **Move / Rotate** | Per-mesh transforms with axis handles & sliders. |
| **Scale** | Per-mesh uniform + per-axis (W/H/D) scaling **in mm**. |
| **Ruler** | Tap two surface points → live distance in mm. Unit-aware: stays correct after you resize the model. |
| **Ring** | Auto-detects ring-shaped meshes → resize **inner diameter, band width & height (axial)** with US ring-size presets, proportional mode and live preview. |
| **Brush** | Smooth / sculpt brushes with mm-precise radius. |
| **Undo / Redo** | Full transform + edit history — one drag = one undo entry. |

### 🔷 Mesh Separation
- Auto-splits disconnected geometry into named mesh islands (Union-Find, C++).
- Per-mesh visibility toggle, per-mesh color coding, independent **W/H/D resize in mm** (with lock-ratio), and delete.
- Combine islands back into a single mesh with one tap.

### 📤 Unified Export Screen
A dedicated **Export sheet** (overflow menu → **💾 Export…**) covers every format we support:

| Format | Load | Export |
|--------|:----:|:------:|
| OBJ | ✅ | ✅ |
| STL | ✅ | ✅ |
| PLY (ASCII + binary) | ✅ | ✅ |
| GLB | ✅ | ✅ |
| GLTF (JSON) | ✅ | — |
| 3DS (legacy 3D Studio) | ✅ | ✅ |
| **3DM (Rhino)** | ✅ | ✅ |

- Pick a format card → **EXPORT & SAVE** writes to `Downloads/AuraCAD` (MediaStore).
- **SHARE EXPORT** writes the file and opens the system share sheet — WhatsApp, Telegram, Gmail, Drive and more.
- Every exported unit = 1 mm, so files stay correctly sized in every format.
- **Rhino 3DM** import/export runs on the official **openNURBS SDK** (NURBS surfaces, Breps & Extrusions tessellated automatically).

### 🧰 Unit System (mm-first)
- OBJ/STL/PLY/3DS treated as millimetres; **GLB auto-converts metres → mm**. Binary STL is vertex-deduped at load for big-model performance.
- A single conversion pipeline (`unitToMM` / `normalizeScale` → `mmPerUnit`) drives every dimension readout — model size, mesh size, ruler, ring, brush radius and exports — so **nothing drifts after scaling**.

### 🤖 AI Assistant (Gemini)
- Paste your Gemini API key in **AI Settings** (overflow menu → **✨ AI Assistant**).
- AuraCAD calls Gemini to help you size & design — e.g. ring sizing guidance.

### 🎨 UI / UX
- Deep dark theme (`#090910` base, `#00D4FF` cyan accent), rounded bottom sheets, glassmorphism hints, colored slider tracks, live status bar (mesh / vertex / file).
- Compact floating command header, organized bottom tool dock, professional tool screens.
- **Background loading** — press Back while a model loads and parsing continues; a notification fires when done.

## 🧪 Testing
Pure-JVM unit tests — run anywhere, no device or emulator needed:

```bash
./gradlew testDebugUnitTest
```

Covers the ring sizing math (US ring sizes ↔ mm diameters, band geometry) and the unit-conversion pipeline (`UnitMath`, `RingMath`).

## 🤖 CI — GitHub Actions
[`.github/workflows/android.yml`](.github/workflows/android.yml) runs on every push to `main` / PR:

1. JDK 17 + Android SDK (platform 34)
2. NDK `27.0.12077973` + CMake `3.22.1`
3. `testDebugUnitTest` — unit tests must pass
4. `assembleDebug` + `assembleRelease` — APK builds
5. APKs uploaded as **build artifacts**; pushing a `v*` tag also creates a **GitHub Release**

## 🛠 Build Locally
```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
```

**Requirements:** JDK 17 · Android SDK (platform 34) · NDK `27.0.12077973` · CMake `3.22.1`

> 💡 Set `sdk.dir` in a local `local.properties` (git-ignored, machine-specific).
> ⚠️ The openNURBS toolchain makes local C++ builds slow — prefer CI for full verification.

## 📁 Project Layout
```
app/src/main/cpp/                    C++17 renderer, loader, separator, JNI bridge, openNURBS patch
app/src/main/java/com/modelviewer3d/ Kotlin UI, GL surface, tool fragments, math, UISheetKit
app/src/test/java/com/modelviewer3d/ Pure-JVM unit tests
.github/workflows/android.yml        CI pipeline (tests + APK build)
```

## 📚 Docs
- **[AGENTS.md](AGENTS.md)** — conventions for contributors & AI agents working in this repo.
- **[architecture.md](architecture.md)** — layers, thread model, data flow, GL lifecycle.
- **[knowledge.md](knowledge.md)** — deep-dive on the unit system, ring engine, formats, openNURBS and gotchas.
- **[context.md](context.md)** — current state of the project & where to look next.

---

**AuraCAD** — an OpenGL ES 3.0 model viewer/editor for Android, built to make 3D work feel native on a phone.
