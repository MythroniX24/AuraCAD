#pragma once
#include <GLES3/gl3.h>
#include <vector>
#include <string>
#include <cstdint>
#include <atomic>
#include "math_utils.h"
#include "model_loader.h"
#include "mesh_separator.h"

struct TransformState {
    float rotX=0,rotY=0,rotZ=0;
    float posX=0,posY=0,posZ=0;
    float scaX=1,scaY=1,scaZ=1;
};

struct MeshTransformState {
    float rotX=0,rotY=0,rotZ=0;
    float posX=0,posY=0,posZ=0;
    float scaX=1,scaY=1,scaZ=1;
};

struct MeshObject {
    std::string name;
    std::vector<Vertex>       vertices;
    std::vector<unsigned int> indices;

    GLuint vao=0,vbo=0,ibo=0;
    bool   gpuReady=false;

    float rotX=0,rotY=0,rotZ=0;
    float posX=0,posY=0,posZ=0;
    float scaX=1,scaY=1,scaZ=1;

    float colorR=0.72f,colorG=0.72f,colorB=0.92f;
    bool  visible=true;
    bool  selected=false;

    // Per-mesh local-space bounding box, computed in uploadMeshObject()
    // and kept up-to-date by recomputeBounds() after every deformation.
    float bboxMin[3] = { 0.f, 0.f, 0.f };
    float bboxMax[3] = { 0.f, 0.f, 0.f };
};

// One undoable action: global + per-mesh transforms, plus (optionally) a
// vertex snapshot of a single mesh (deformation tools) and/or a full copy of
// a deleted mesh so deletion can be undone.
// NOTE: declared AFTER MeshObject — UndoEntry stores one by value.
struct UndoEntry {
    TransformState global;
    std::vector<MeshTransformState> meshes;
    int vertMeshIdx = -1;        // mesh whose vertices were snapshotted
    std::vector<Vertex> verts;   // before-state of that mesh
    bool hasDeletedMesh = false; // deleteMesh undo payload
    bool meshAbsent     = false; // true = state already has the mesh removed (redo of delete)
    int  deletedMeshIdx = -1;
    MeshObject deleted;          // full copy for re-insertion
};


// ── Ring deformation state ────────────────────────────────────────────────────

// ── Mesh Statistics ───────────────────────────────────────────────────────────
struct MeshStats {
    float surfaceAreaMM2 = 0.f;
    float volumeMM3      = 0.f;
    float bboxW = 0.f, bboxH = 0.f, bboxD = 0.f;
    int   vertCount  = 0;
    int   triCount   = 0;
    int   edgeCount  = 0;
    bool  isWatertight = false;
};

struct RingState {
    Vec3  center      = {0,0,0};   // centroid (normalized space)
    Vec3  axis        = {0,1,0};   // ring hole axis (unit vector)

    // Set once at analyzeRing() — never modified afterwards
    float origInnerR  = 0.0f;
    float origOuterR  = 0.0f;

    // Aliases matching analysis result (same as orig after analyze, before deform)
    float innerR      = 0.0f;   // legacy compat
    float outerR      = 0.0f;   // legacy compat
    float heightAx    = 0.0f;   // original axial extent (normalized units)

    // Live tracking of current deformation state (normalized units)
    float currentInnerR = 0.0f;
    float currentOuterR = 0.0f;
    float currentHeight = 0.0f;  // current axial extent (normalized units)

    bool  valid     = false;
    int   meshIdx   = -1;

    // Original vertex snapshot — deformation ALWAYS starts from here
    std::vector<Vertex> origVerts;
};

class Renderer {
public:
    Renderer(); ~Renderer();

    bool init(int width, int height);
    void draw();
    void resize(int width, int height);

    // TWO-STEP LOAD
    bool parseModel(const std::string& path);  // IO thread — parse only
    bool uploadParsed();                        // GL thread — GPU upload as single mesh (instant)
    bool loadModel(const std::string& path);   // Legacy

    // Separation — called from JNI bridge (see jni_bridge.cpp)
    void getRawData (std::vector<Vertex>& verts, std::vector<uint32_t>& idx) const;
    void takeRawData(std::vector<Vertex>& verts, std::vector<uint32_t>& idx);
    bool loadSeparatedComponents(std::vector<MeshComponent>& comps);
    bool isSeparated() const { return m_isSeparated; }
    /** Skip islands with fewer than [n] faces when uploading separated meshes. */
    void setSeparationMinFaces(int n) { m_sepMinFaces = (n < 1) ? 1 : n; }

    // EGL context loss recovery — called from GL thread on Renderer.onSurfaceCreated
    // when contextInitialized==true (the new context invalidated all GL handles).
    void onContextLost();      // zero out GL handle ids, mark gpuReady=false
    void rebuildContext();     // re-create shaders + re-upload all CPU vertex buffers

    // Camera
    void touchRotate(float dx, float dy);
    void touchZoom(float factor);
    void touchPan(float dx, float dy);
    void resetCamera();

    // ── Transform gizmo ───────────────────────────────────────────────────────
    // 0 = off, 1 = move, 2 = rotate, 3 = scale.  When on, a 3D axis manipulator
    // is drawn at the model centre and one-finger drags edit the GLOBAL model
    // transform instead of the camera.  Call on the GL thread.
    void setGizmoMode(int mode);
    int  getGizmoMode() const { return m_gizmoMode; }
    /** start=true pushes ONE undo snapshot (call once per gesture); then stream
     *  dx/dy deltas with start=false.  Applies move / rotate / scale on the
     *  global transform according to m_gizmoMode. */
    void gizmoDrag(float dx, float dy, bool start);

    // Global transform
    void setRotation(float x,float y,float z);
    void setTranslation(float x,float y,float z);
    void setScaleMM(float w,float h,float d);
    void mirrorX(); void mirrorY(); void mirrorZ();
    void resetTransform();
    void resetAllTransforms();   // global + per-mesh, single undo snapshot

    // Visual
    void setColor(float r,float g,float b);
    void setAmbient(float a); void setDiffuse(float d);
    void setWireframe(bool on); void setShowBoundingBox(bool on);

    // Mesh
    int  getMeshCount() const { return (int)m_meshes.size(); }
    void getMeshName(int idx, char* buf, int bufLen) const;
    void selectMesh(int idx);
    int  getSelectedMesh() const { return m_selectedMesh; }
    void deleteMesh(int idx);
    void setMeshVisible(int idx, bool v);
    bool getMeshVisible(int idx) const;
    void setMeshColor(int idx, float r, float g, float b);
    void setMeshScaleMM(int idx, float w, float h, float d);
    void getMeshSizeMM(int idx, float& w, float& h, float& d) const;
    int  getMeshVertexCount(int idx) const;

    // ── Per-mesh independent transforms (Phase 2) ─────────────────────────────
    void setMeshRotation   (int idx, float rx, float ry, float rz);
    void setMeshTranslation(int idx, float px, float py, float pz);
    /** out9 = [rx,ry,rz, px,py,pz, sx,sy,sz] — empty/identity if idx invalid */
    void getMeshTransform  (int idx, float out9[9]) const;
    void resetMeshTransform(int idx);

    /** Ray-pick a mesh from screen coords. Returns mesh idx or -1. */
    int  pickMesh(float sx, float sy, float sw, float sh);

    // ── Mesh processing (MeshLab/OpenSCAD inspired) ───────────────────────────
    bool decimateMesh(int meshIdx, float targetPercent);   // Garland-Heckbert QEM
    void getMeshStats(int meshIdx, MeshStats& out) const;  // area, vol, bbox
    int  weldVertices(int meshIdx, float epsilonMM);       // merge close verts
    int  removeZeroAreaFaces(int meshIdx);                 // remove degenerate tris

    // ── Brush tools ───────────────────────────────────────────────────────────
    // Both operate on the GL thread; they rebuild normals + update the VBO.
    /** Laplacian smooth brush. Blends each vertex within [radius] mm of
     *  world-space point (cx,cy,cz) towards its ring-1 neighbour average
     *  by [strength] (0–1). */
    bool applySmooth(int meshIdx, float cx, float cy, float cz,
                     float radius, float strength);
    /** Sculpt (inflate/deflate) brush. Displaces vertices within [radius] mm
     *  of (cx,cy,cz) along their averaged normal by [amount] mm. */
    bool applySculpt(int meshIdx, float cx, float cy, float cz,
                     float radius, float amount);

    // Ring deformation tools (all GL-thread)
    bool  analyzeRing(int meshIdx);
    bool  getRingParams(float out[6]) const;   // [innerRadMM, outerRadMM, bwMM, innerDiaMM, outerDiaMM, heightMM]
    void  setRingBandWidth(float newWidthMM);
    void  setRingInnerDiameter(float newDiamMM);
    void  setRingHeight(float newHeightMM);
    void  resetRingDeformation();
    bool  isRingAnalyzed() const { return m_ring.valid; }

    // Export
    bool exportOBJ(const std::string& path) const;
    bool exportSTL(const std::string& path) const;
    bool exportPLY(const std::string& path) const;
    /** Rhino 3DM (openNURBS) — writes every visible mesh as an ON_Mesh in
     *  document units = millimetres, v6 format (Rhino 6/7/8 + others). */
    bool export3DM(const std::string& path) const;

    /** Merge [indices] meshes into a single mesh (originals removed). */
    bool combineMeshes(const std::vector<int>& indices);

    // Ruler
    bool pickPoint(float sx,float sy,float sw,float sh,float out[3]);
    void setRulerPoints(bool h1,float* p1,bool h2,float* p2);
    void clearRuler();

    // Size info
    // getModelSizeMM  → ORIGINAL size in mm (stored at load, never changes)
    // getCurrentSizeMM → CURRENT size in mm (world-space bbox incl. transforms)
    void getModelSizeMM(float& w,float& h,float& d) const;
    void getCurrentSizeMM(float& w,float& h,float& d) const;

    // Unit conversion helpers — THE single source of truth for mm↔norm.
    // mmPerUnit(): mm per 1 normalized unit = unitToMM / normalizeScale
    // unitPerMM(): normalized units per 1 mm = normalizeScale / unitToMM
    float mmPerUnit() const {
        return (m_normalizeScale > 1e-9f) ? (m_unitToMM / m_normalizeScale) : m_unitToMM;
    }
    float unitPerMM() const {
        return (m_unitToMM > 1e-9f) ? (m_normalizeScale / m_unitToMM) : m_normalizeScale;
    }

    // Undo/redo, screenshot, fps
    void pushUndoState();
    /** Global + per-mesh transforms AND a vertex snapshot of mesh [idx]
     *  (for brush, ring, decimate, weld, cleanup). */
    void pushMeshUndo(int idx);
    /** Full copy of mesh [idx] so a delete can be undone. */
    void pushDeleteUndo(int idx);
    void undo(); void redo();
    bool canUndo() const { return !m_undoStack.empty(); }
    bool canRedo() const { return !m_redoStack.empty(); }
    std::vector<uint8_t> takeScreenshot();
    float getFPS() const { return m_fps; }
    TransformState getTransform() const;

private:
    GLuint m_mainProg=0, m_wireProg=0;
    GLuint m_bbVao=0, m_bbVbo=0, m_bbIbo=0;
    GLuint m_rulerVao=0, m_rulerVbo=0;
    GLsizei m_bbIndexCount=0;

    // Transform gizmo GL state + geometry
    int    m_gizmoMode = 0;
    GLuint m_gizVao=0, m_gizVbo=0;
    GLsizei m_gizVertCount=0;         // total verts in the gizmo buffer
    int    m_gizAxisOffset[3] = {0,0,0};
    int    m_gizAxisCount[3]  = {0,0,0};
    void   buildGizmoGeometry();      // rebuilds vertex buffer for m_gizmoMode
    void   drawGizmo(const Mat4& proj, const Mat4& view);

    // Cached uniform locations — avoids glGetUniformLocation every frame
    struct UniformLocs {
        GLint mvp=-1, model=-1, norm=-1, color=-1, lightDir=-1;
        GLint ambient=-1, diffuse=-1, selected=-1, camPos=-1;
        GLint wireMvp=-1, wireColor=-1, wirePointSize=-1;
    } m_uloc;
    void cacheUniformLocs();

    std::vector<MeshObject> m_meshes;
    int  m_selectedMesh = -1;
    bool m_hasModel     = false;
    bool m_isSeparated  = false;
    int  m_sepMinFaces  = 1;

    int m_width=1, m_height=1;

    float m_camYaw=0.4f, m_camPitch=0.3f, m_camDist=3.5f;
    float m_panX=0, m_panY=0;

    float m_rotX=0,m_rotY=0,m_rotZ=0;
    float m_posX=0,m_posY=0,m_posZ=0;
    float m_scaX=1,m_scaY=1,m_scaZ=1;

    // Pending data between parse and upload
    ModelData*              m_pendingData  = nullptr;

    // Raw vertex/index data kept alive for separation (safe to read from IO thread)
    std::vector<Vertex>       m_rawVertices;
    std::vector<unsigned int> m_rawIndices;

    float m_origWmm=1,m_origHmm=1,m_origDmm=1;
    float m_unitToMM=1.0f;      // 1 for STL/OBJ, 1000 for GLB (meters→mm)
    float m_normalizeScale=1.0f;

    float m_colorR=0.72f,m_colorG=0.72f,m_colorB=0.92f;
    float m_ambient=0.3f, m_diffuse=0.8f;
    bool  m_wireframe=false, m_showBBox=false;

    float   m_fps=0; int m_frameCount=0; int64_t m_fpsTimerNs=0;

    bool  m_rulerHasP1=false, m_rulerHasP2=false;
    float m_rulerP1[3]={}, m_rulerP2[3]={};

    static constexpr int MAX_UNDO=30;
    std::vector<UndoEntry> m_undoStack, m_redoStack;

    UndoEntry captureState() const;
    void restoreState(const UndoEntry& e);

    void buildShaders();
    void buildBoundingBox();
    void uploadMeshObject(MeshObject& mo);
    void separateIntoMeshes(const ModelData& md);
    void separateMeshesCPU(const ModelData& md, std::vector<MeshObject>& out);
    Mat4 buildGlobalMatrix() const;
    Mat4 buildMeshMatrix(const MeshObject& mo) const;
    void updateFPS();
    Vec3 cameraEye() const;

    struct Ray { Vec3 origin, dir; };
    Ray  screenToRay(float sx,float sy,float sw,float sh) const;
    bool rayTriangle(const Ray& r,const Vec3& v0,const Vec3& v1,const Vec3& v2,float& t) const;
    void regenerateNormals(MeshObject& mo);
    void updateMeshVBO(MeshObject& mo);

    // ── Ring deformation helpers ──────────────────────────────────────────────
    // recomputeBounds: rebuild bboxMin/bboxMax from current mo.vertices.
    // Must be called after any operation that modifies vertex positions
    // (ring deformation, decimation, weld, cleanup).
    void recomputeBounds(MeshObject& mo);

    // applyCombinedRingDeformation: THE authoritative deformation entry point.
    // Always operates on m_ring.origVerts and applies bwMM, idMM AND hMM
    // simultaneously in a single radial+axial map pass, so no parameter can
    // silently override the others.  Updates currentInnerR/currentOuterR/
    // currentHeight, regenerates normals, uploads VBO, rebuilds bbox.
    void applyCombinedRingDeformation(float bwMM, float idMM, float hMM);

    // Production-grade mesh separator (reusable, preallocates buffers)
    MeshSeparator m_separator;

    // ── Ring deformation state ────────────────────────────────────────────────
    RingState m_ring;

    // Full desired deformation state (mm units, GL thread only).
    // Initialized in analyzeRing() to original values.
    // Updated by setRingBandWidth / setRingInnerDiameter / setRingHeight /
    // applyPendingRingDeformation.  Always applied together via
    // applyCombinedRingDeformation so no parameter can shadow the others.
    float m_desiredBWmm = 0.f;
    float m_desiredIDmm = 0.f;
    float m_desiredHmm  = 0.f;

    // Pending async values posted from the UI thread.
    // Atomic so the UI thread can write without holding g_renderMtx while the
    // GL thread may simultaneously be inside draw().  applyPendingRingDeformation
    // exchanges them to -1 after consuming.
    std::atomic<float> m_pendingBW{-1.f};
    std::atomic<float> m_pendingID{-1.f};
    std::atomic<float> m_pendingH{-1.f};
    std::atomic<bool>  m_ringDirty{false};
    void applyPendingRingDeformation();

public:
    float getNormalizeScale() const { return m_normalizeScale; }

    // Called from UI thread (without g_renderMtx held) — atomic writes only.
    void setPendingBandWidth(float v) {
        m_pendingBW.store(v, std::memory_order_relaxed);
        m_ringDirty.store(true, std::memory_order_release);
    }
    void setPendingInnerDiameter(float v) {
        m_pendingID.store(v, std::memory_order_relaxed);
        m_ringDirty.store(true, std::memory_order_release);
    }
    void setPendingHeight(float v) {
        m_pendingH.store(v, std::memory_order_relaxed);
        m_ringDirty.store(true, std::memory_order_release);
    }
};
