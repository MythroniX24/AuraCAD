# Architecture — AuraCAD

## Modules

```
┌──────────────────────────── Kotlin (UI) ────────────────────────────┐
│ MainActivity        app shell: GLSurfaceView, header, docks, menus   │
│ ModelGLSurfaceView  GL surface + touch routing (rotate/zoom/pan)     │
│ ModelRenderer       GLSurfaceView.Renderer → nativeDraw/nativeInit   │
│ UISheetKit          design system for all bottom sheets              │
│ *Fragment (10+)     tools: Editor, Ring, Brush, Move, Rotate,        │
│                     Separation, MeshList, MeshTools, MeshInfo, Export│
│ NativeLib           JNI facade — every external fun = JNI symbol     │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ JNI (jni_bridge.cpp)
┌──────────────────────────────────▼───────────────────────────────────┐
│                            C++17 core                               │
│  renderer.cpp         GL state, meshes, transforms, exporters, undo  │
│  model_loader.cpp     OBJ/STL/PLY/GLB/GLTF/3DS/3DM parsers           │
│  mesh_separator.cpp   connected-component separation                │
│  math_utils.h         helpers                                        │
│  shader_utils.cpp     GLSL compile/link                              │
│  opennurbs (fetched)  Rhino 3DM SDK (patched for Android <29 fonts)  │
└──────────────────────────────────────────────────────────────────────┘
```

## Thread model

| Thread | Owns | Must never |
|--------|------|------------|
| **GL thread** | All GL calls + mesh data mutations (via `queueEvent`) | Long file I/O |
| **Main (UI)** | Views, sheets, toasts | NativeLib GL calls directly |
| **IO / appScope** | File parsing, MediaStore writes, exports | GL calls |

Crossing rule: **UI → `glView.queueEvent { NativeLib.xxx() }`; results back →
`runOnUiThread`/`withContext(Main)`.**

Model load path: IO thread parses (`nativeParseModel`) → event queued →
`nativeUploadParsed` on GL thread → callback refreshes status bar.

## Data flow — export (new unified pipeline)

```
ExportFragment (sheet)
   │  formatId ("OBJ"…"3DM") + share flag
   ▼
MainActivity.exportModel(format, share)
   │  IO thread: write cache file via nativeExportFor() on GL thread
   ▼
cache file ──▶ MediaStore Downloads/AuraCAD (Android 10+)   ──▶ toast
            └─▶ app storage (Android 9- / fallback) ──▶ FileProvider URI
   │
   └─ share=true ─▶ ACTION_SEND chooser (content URI, GRANT_READ)
```

Every format: OBJ, STL, PLY, GLB, 3DS, 3DM. 1 exported unit = 1 mm.

## GL lifecycle

```
onSurfaceCreated → nativeOnContextLost (if re-created) → nativeInit(w,h)
                 → nativeRebuildContext (re-upload CPU buffers)
onSurfaceChanged → nativeResize(w,h)
onDrawFrame      → nativeDraw()
onPause/Resume   → GLSurfaceView handled
onDestroy        → nativeDestroy()
```

## Key state (native)

- `Renderer` owns: `std::vector<Mesh>` (CPU verts/indices + GPU ids),
  global + per-mesh transforms, camera orbit, ruler points, gizmo mode,
  undo/redo `UndoEntry` stack, mm-scale conversion.
- Meshes survive parse/upload split so background loading works.
