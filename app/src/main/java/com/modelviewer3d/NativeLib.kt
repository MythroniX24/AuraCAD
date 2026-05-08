package com.modelviewer3d

object NativeLib {
    external fun nativeInit(width: Int, height: Int)
    external fun nativeResize(width: Int, height: Int)
    external fun nativeDraw()
    external fun nativeDestroy()

    // Model loading
    external fun nativeParseModel(path: String): Boolean
    external fun nativeUploadParsed(): Boolean
    external fun nativeLoadModel(path: String): Boolean

    // Separation
    external fun nativePerformSeparationCPU(): Boolean
    external fun nativePerformSeparationGPU(): Boolean
    external fun nativeIsSeparated(): Boolean
    external fun nativeGetSeparationProgress(): Int

    // Camera
    external fun nativeTouchRotate(dx: Float, dy: Float)
    external fun nativeTouchZoom(factor: Float)
    external fun nativeTouchPan(dx: Float, dy: Float)
    external fun nativeResetCamera()

    // Transforms
    external fun nativeSetRotation(x: Float, y: Float, z: Float)
    external fun nativeSetTranslation(x: Float, y: Float, z: Float)
    external fun nativeSetScaleMM(w: Float, h: Float, d: Float)
    external fun nativeMirrorX()
    external fun nativeMirrorY()
    external fun nativeMirrorZ()
    external fun nativeResetTransform()
    external fun nativeResetAllTransforms()

    // Visual
    external fun nativeSetColor(r: Float, g: Float, b: Float)
    external fun nativeSetAmbient(v: Float)
    external fun nativeSetDiffuse(v: Float)
    external fun nativeSetWireframe(on: Boolean)
    external fun nativeSetBoundingBox(on: Boolean)

    // Undo/redo
    external fun nativeUndo()
    external fun nativeRedo()

    // Stats
    external fun nativeGetFPS(): Float
    external fun nativeGetModelSizeMM(): FloatArray

    // Mesh management
    external fun nativeGetMeshCount(): Int
    external fun nativeGetMeshName(idx: Int): String
    external fun nativeSelectMesh(idx: Int)
    external fun nativeGetSelectedMesh(): Int
    external fun nativeDeleteMesh(idx: Int)
    external fun nativeSetMeshVisible(idx: Int, visible: Boolean)
    external fun nativeGetMeshVisible(idx: Int): Boolean
    external fun nativeSetMeshColor(idx: Int, r: Float, g: Float, b: Float)
    external fun nativeSetMeshScaleMM(idx: Int, w: Float, h: Float, d: Float)
    external fun nativeGetMeshSizeMM(idx: Int): FloatArray
    external fun nativeGetMeshVertexCount(idx: Int): Int

    // Export
    external fun nativeExportOBJ(path: String): Boolean
    external fun nativeExportSTL(path: String): Boolean
    external fun nativeExportPLY(path: String): Boolean

    // Combine + per-mesh scale
    external fun nativeCombineMeshes(indices: IntArray): Boolean
    external fun nativeSetMeshScaleMMDirect(meshIdx: Int, w: Float, h: Float, d: Float)

    // Mesh stats
    /** [surfaceAreaMM2, volumeMM3, bboxW, bboxH, bboxD, verts, tris, edges, watertight] */
    external fun nativeGetMeshStats(meshIdx: Int): FloatArray

    // Ruler
    external fun nativePickPoint(sx: Float, sy: Float, sw: Float, sh: Float): FloatArray?
    external fun nativeSetRulerPoints(hasP1: Boolean, p1: FloatArray?, hasP2: Boolean, p2: FloatArray?)
    external fun nativeClearRuler()

    // Screenshot
    external fun nativeTakeScreenshot(): ByteArray?

    // Ring tool
    external fun nativeAnalyzeRing(meshIdx: Int): Boolean
    external fun nativeGetRingParams(): FloatArray
    external fun nativeSetRingBandWidth(widthMM: Float)
    external fun nativeSetRingInnerDiameter(diamMM: Float)
    external fun nativeResetRingDeformation()
    external fun nativeIsRingAnalyzed(): Boolean

    // ── Brush sculpting ────────────────────────────────────────────────────────
    /** Smooth/polish brush — Laplacian relaxation at world point (wx,wy,wz) */
    external fun nativeApplySmooth(meshIdx: Int, wx: Float, wy: Float, wz: Float,
                                   radius: Float, intensity: Float)
    /** Height/sculpt brush — displace surface along normals. sign=+1 raise, -1 lower */
    external fun nativeApplySculpt(meshIdx: Int, wx: Float, wy: Float, wz: Float,
                                   radius: Float, intensity: Float, sign: Float)

    init { System.loadLibrary("modelviewer") }
}
