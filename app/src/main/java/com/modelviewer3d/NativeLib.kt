package com.modelviewer3d

object NativeLib {
    // Core lifecycle
    external fun nativeInit(width: Int, height: Int)
    external fun nativeResize(width: Int, height: Int)
    external fun nativeDraw()
    external fun nativeDestroy()

    // EGL context loss recovery — call from GLSurfaceView.Renderer.onSurfaceCreated
    // when contextInitialized was already true (i.e. the EGL context was lost
    // and re-created).  nativeOnContextLost must be called BEFORE the new
    // nativeInit so stale GL handle ids are scrubbed; nativeRebuildContext is
    // called AFTER nativeInit to re-upload all CPU vertex buffers to the GPU.
    external fun nativeOnContextLost()
    external fun nativeRebuildContext()

    // Model loading (two-step parse → upload)
    external fun nativeParseModel(path: String): Boolean
    external fun nativeUploadParsed(): Boolean
    external fun nativeLoadModel(path: String): Boolean

    // ── Mesh separation ──────────────────────────────────────────────────────
    external fun nativePerformSeparationCPU(): Boolean
    external fun nativePerformSeparationGPU(): Boolean
    external fun nativeIsSeparated(): Boolean
    external fun nativeGetSeparationProgress(): Int
    /** Islands smaller than [n] faces are dropped when separation completes. */
    external fun nativeSetSeparationMinFaces(n: Int)

    // Camera
    external fun nativeTouchRotate(dx: Float, dy: Float)
    external fun nativeTouchZoom(factor: Float)
    external fun nativeTouchPan(dx: Float, dy: Float)
    external fun nativeResetCamera()

    // ── Transform gizmo ──────────────────────────────────────────────────────
    /** 0 = off, 1 = move, 2 = rotate, 3 = scale. Shows the 3D axis manipulator. */
    external fun nativeSetGizmoMode(mode: Int)
    external fun nativeGetGizmoMode(): Int
    /** One-finger drag while a gizmo tool is active. start=true pushes one undo. */
    external fun nativeGizmoDrag(dx: Float, dy: Float, start: Boolean)

    // Global transform
    external fun nativeSetRotation(x: Float, y: Float, z: Float)
    external fun nativeSetTranslation(x: Float, y: Float, z: Float)
    external fun nativeSetScaleMM(w: Float, h: Float, d: Float)
    external fun nativeMirrorX()
    external fun nativeMirrorY()
    external fun nativeMirrorZ()
    external fun nativeResetTransform()
    /** Reset BOTH global transform AND every per-mesh transform. Saves one undo entry. */
    external fun nativeResetAllTransforms()

    // Visual
    external fun nativeSetColor(r: Float, g: Float, b: Float)
    external fun nativeSetAmbient(v: Float)
    external fun nativeSetDiffuse(v: Float)
    external fun nativeSetWireframe(on: Boolean)
    external fun nativeSetBoundingBox(on: Boolean)

    // Undo/redo
    /** Snapshot the current transform onto the undo stack. Call ONCE at slider DOWN —
     *  setRotation/setTranslation/setScaleMM no longer push state internally, so
     *  continuous slider drags produce exactly one undoable entry. */
    external fun nativePushUndoState()
    external fun nativeUndo()
    external fun nativeRedo()
    external fun nativeCanUndo(): Boolean
    external fun nativeCanRedo(): Boolean

    // Stats
    external fun nativeGetFPS(): Float
    external fun nativeGetModelSizeMM(): FloatArray

    // Mesh management
    external fun nativeGetMeshCount(): Int
    external fun nativeGetMeshName(idx: Int): String
    external fun nativeSelectMesh(idx: Int)
    /** Returns the currently-selected mesh idx, or -1 if none. */
    external fun nativeGetSelectedMesh(): Int
    /** Ray-pick the front-most mesh under screen point (sx,sy). Returns idx or -1. */
    external fun nativePickMesh(sx: Float, sy: Float, sw: Float, sh: Float): Int
    external fun nativeDeleteMesh(idx: Int)
    external fun nativeSetMeshVisible(idx: Int, visible: Boolean)
    external fun nativeGetMeshVisible(idx: Int): Boolean
    external fun nativeSetMeshColor(idx: Int, r: Float, g: Float, b: Float)
    external fun nativeSetMeshScaleMM(idx: Int, w: Float, h: Float, d: Float)
    external fun nativeGetMeshSizeMM(idx: Int): FloatArray
    external fun nativeGetMeshVertexCount(idx: Int): Int

    // ── Per-mesh independent transforms (Phase 2 Transform Tool) ─────────────
    // Slider DOWN should call nativePushUndoState() once, then stream value
    // changes through these setters — they intentionally do NOT push undo
    // state so a continuous drag is one undoable entry.
    external fun nativeSetMeshRotation(idx: Int, rx: Float, ry: Float, rz: Float)
    external fun nativeSetMeshTranslation(idx: Int, px: Float, py: Float, pz: Float)
    /** Returns [rx, ry, rz, px, py, pz, sx, sy, sz]; identity if idx invalid. */
    external fun nativeGetMeshTransform(idx: Int): FloatArray
    /** Reset per-mesh transform to identity. Pushes one undo entry. */
    external fun nativeResetMeshTransform(idx: Int)

    // Ruler
    external fun nativePickPoint(sx: Float, sy: Float, sw: Float, sh: Float): FloatArray?
    external fun nativeSetRulerPoints(hasP1: Boolean, p1: FloatArray?, hasP2: Boolean, p2: FloatArray?)
    external fun nativeClearRuler()

    // Screenshot
    external fun nativeTakeScreenshot(): ByteArray?

    // ── Ring Deformation Tools ────────────────────────────────────────────────
    external fun nativeAnalyzeRing(meshIdx: Int): Boolean
    external fun nativeGetRingParams(): FloatArray
    external fun nativeSetRingBandWidth(widthMM: Float)
    external fun nativeSetRingInnerDiameter(diamMM: Float)
    external fun nativeSetRingHeight(heightMM: Float)
    external fun nativeResetRingDeformation()
    external fun nativeIsRingAnalyzed(): Boolean

    // ── Mesh Processing (MeshLab/OpenSCAD Inspired) ───────────────────────────
    /** Quadric Error Metric decimation. targetPercent: 0.1 = 10% of faces remain */
    external fun nativeDecimateMesh(meshIdx: Int, targetPercent: Float): Boolean
    /** [surfaceAreaMM2, volumeMM3, bboxW, bboxH, bboxD, verts, tris, edges, watertight(0/1)] */
    external fun nativeGetMeshStats(meshIdx: Int): FloatArray
    /** Merge vertices closer than epsilonMM */
    external fun nativeWeldVertices(meshIdx: Int, epsilonMM: Float): Int
    /** Remove zero-area / degenerate triangles */
    external fun nativeRemoveZeroAreaFaces(meshIdx: Int): Int

    external fun nativeSetRingBandWidthAsync(widthMM: Float)
    external fun nativeSetRingInnerDiameterAsync(diamMM: Float)
    external fun nativeSetRingHeightAsync(heightMM: Float)
    external fun nativeGetNormalizeScale(): Float
    /** Current mm per normalized unit — the single conversion for world→mm distances. */
    external fun nativeGetMMPerUnit(): Float
    external fun nativeCombineMeshes(indices: IntArray): Boolean

    // ── Brush Tools ───────────────────────────────────────────────────────────
    /** Laplacian smooth brush: applies iterative smoothing within [radius] mm
     *  around world-space point (cx,cy,cz) on [meshIdx]. [strength] in 0..1. */
    external fun nativeApplySmooth(meshIdx: Int, cx: Float, cy: Float, cz: Float,
                                   radius: Float, strength: Float): Boolean
    /** Sculpt brush: displaces vertices within [radius] mm around world-space
     *  point (cx,cy,cz) along their averaged normal by [amount] mm (±). */
    external fun nativeApplySculpt(meshIdx: Int, cx: Float, cy: Float, cz: Float,
                                   radius: Float, amount: Float): Boolean

    init { System.loadLibrary("modelviewer") }
}
