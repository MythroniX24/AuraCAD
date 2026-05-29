// =============================================================================
// jni_bridge.cpp — Kotlin ↔ C++ bridge for the Renderer
// =============================================================================
// Threading model
//   GL thread       : nativeInit, nativeResize, nativeDraw, nativeDestroy,
//                     nativeUploadParsed, nativePerformSeparationGPU,
//                     all setters, exporters, ring tools, mesh ops.
//   IO  thread      : nativeParseModel, nativePerformSeparationCPU.
//   Any thread      : nativeGetMeshCount, nativeGet*, nativeIsSeparated, etc.
//
// All entry points take g_renderMtx — a coarse lock that serialises every
// touch of g_renderer.  GL calls only happen from the GL thread, so we never
// deadlock; the mutex only protects the unique_ptr lifetime + g_sep* buffers
// against concurrent access (e.g. UI calling nativeGetMeshCount while the IO
// thread is parsing, or destroy racing with a setter on app exit).
// =============================================================================

#include <jni.h>
#include <android/log.h>
#include <memory>
#include <cstring>
#include <atomic>
#include <vector>
#include <thread>
#include <mutex>
#include "renderer.h"
#include "mesh_separator.h"

#define TAG "JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Globals (all guarded by g_renderMtx) ─────────────────────────────────────
static std::unique_ptr<Renderer>  g_renderer;
static std::mutex                 g_renderMtx;

// Separation scratch state — owned by background thread, hands off to GL thread
static std::vector<Vertex>        g_sepVerts;
static std::vector<uint32_t>      g_sepIdx;
static std::vector<MeshComponent> g_sepComponents;
static MeshSeparator              g_separator;        // reusable, owns its buffers

static std::string jstr(JNIEnv* env, jstring js){
    if(!js) return "";
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c ? c : ""); env->ReleaseStringUTFChars(js, c); return s;
}

// Convenience macro: lock + early-return if renderer was destroyed
#define LOCK_RENDERER() std::lock_guard<std::mutex> _lk(g_renderMtx)
#define LOCK_OR_FALSE() std::lock_guard<std::mutex> _lk(g_renderMtx); if(!g_renderer) return JNI_FALSE
#define LOCK_OR_VOID()  std::lock_guard<std::mutex> _lk(g_renderMtx); if(!g_renderer) return
#define LOCK_OR_ZERO()  std::lock_guard<std::mutex> _lk(g_renderMtx); if(!g_renderer) return 0
#define LOCK_OR_NULL()  std::lock_guard<std::mutex> _lk(g_renderMtx); if(!g_renderer) return nullptr

extern "C" {

// ── Lifecycle ────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeInit(JNIEnv*,jclass,jint w,jint h){
    LOCK_RENDERER();
    if(!g_renderer) g_renderer = std::make_unique<Renderer>();
    g_renderer->init((int)w,(int)h);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeResize(JNIEnv*,jclass,jint w,jint h){
    LOCK_OR_VOID();
    g_renderer->resize((int)w,(int)h);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeDraw(JNIEnv*,jclass){
    // Hot path — taken every frame.  std::mutex is uncontended here so the
    // lock is ~10 ns; fine.
    LOCK_OR_VOID();
    g_renderer->draw();
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeDestroy(JNIEnv*,jclass){
    LOCK_RENDERER();
    g_renderer.reset();
    g_sepVerts.clear();      g_sepVerts.shrink_to_fit();
    g_sepIdx.clear();        g_sepIdx.shrink_to_fit();
    g_sepComponents.clear(); g_sepComponents.shrink_to_fit();
}

// EGL context lost (e.g. driver reset, app left "preserveEGLContextOnPause"
// behaviour).  Java side calls this from onSurfaceCreated when it detects a
// re-creation.  Implementation invalidates GL handles only — CPU buffers
// survive and get re-uploaded by rebuildContext on the next frame.
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeOnContextLost(JNIEnv*,jclass){
    LOCK_OR_VOID();
    g_renderer->onContextLost();
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeRebuildContext(JNIEnv*,jclass){
    LOCK_OR_VOID();
    g_renderer->rebuildContext();
}

// ── Two-step load ────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeParseModel(JNIEnv* env,jclass,jstring path){
    LOCK_OR_FALSE();
    return g_renderer->parseModel(jstr(env,path)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeUploadParsed(JNIEnv*,jclass){
    LOCK_OR_FALSE();
    bool ok = g_renderer->uploadParsed();
    if(ok){
        // Pull a working copy for separation.  The renderer keeps the original
        // on the GPU; the bridge owns this scratch buffer until separation is
        // either run or replaced by a new load.
        g_renderer->getRawData(g_sepVerts, g_sepIdx);
        g_sepComponents.clear();
        LOGI("nativeUploadParsed: stored %zu verts, %zu tris for separation",
             g_sepVerts.size(), g_sepIdx.size()/3);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeLoadModel(JNIEnv* env,jclass,jstring path){
    LOCK_OR_FALSE();
    return g_renderer->loadModel(jstr(env,path)) ? JNI_TRUE : JNI_FALSE;
}

// ── Camera ───────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeTouchRotate(JNIEnv*,jclass,jfloat dx,jfloat dy){
    LOCK_OR_VOID(); g_renderer->touchRotate(dx,dy);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeTouchZoom(JNIEnv*,jclass,jfloat f){
    LOCK_OR_VOID(); g_renderer->touchZoom(f);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeTouchPan(JNIEnv*,jclass,jfloat dx,jfloat dy){
    LOCK_OR_VOID(); g_renderer->touchPan(dx,dy);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeResetCamera(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->resetCamera();
}

// ── Transform ────────────────────────────────────────────────────────────────
// IMPORTANT: setRotation/setTranslation/setScaleMM no longer push undo state
// internally.  Continuous slider drags used to flood the undo stack with one
// entry per ~16 ms tick (~60 entries/sec), making Undo useless.
//
// Java side now calls nativePushUndoState() once on slider DOWN, then streams
// the value changes.  Mirror buttons / Reset push their own state explicitly.
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetRotation(JNIEnv*,jclass,jfloat x,jfloat y,jfloat z){
    LOCK_OR_VOID(); g_renderer->setRotation(x,y,z);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetTranslation(JNIEnv*,jclass,jfloat x,jfloat y,jfloat z){
    LOCK_OR_VOID(); g_renderer->setTranslation(x,y,z);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetScaleMM(JNIEnv*,jclass,jfloat w,jfloat h,jfloat d){
    LOCK_OR_VOID(); g_renderer->setScaleMM(w,h,d);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeMirrorX(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->pushUndoState(); g_renderer->mirrorX();
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeMirrorY(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->pushUndoState(); g_renderer->mirrorY();
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeMirrorZ(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->pushUndoState(); g_renderer->mirrorZ();
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeResetTransform(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->pushUndoState(); g_renderer->resetTransform();
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeResetAllTransforms(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->resetAllTransforms();   // saves undo internally
}

// ── Visual ───────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetColor(JNIEnv*,jclass,jfloat r,jfloat g,jfloat b){
    LOCK_OR_VOID(); g_renderer->setColor(r,g,b);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetAmbient(JNIEnv*,jclass,jfloat v){
    LOCK_OR_VOID(); g_renderer->setAmbient(v);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetDiffuse(JNIEnv*,jclass,jfloat v){
    LOCK_OR_VOID(); g_renderer->setDiffuse(v);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetWireframe(JNIEnv*,jclass,jboolean on){
    LOCK_OR_VOID(); g_renderer->setWireframe(on==JNI_TRUE);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetBoundingBox(JNIEnv*,jclass,jboolean on){
    LOCK_OR_VOID(); g_renderer->setShowBoundingBox(on==JNI_TRUE);
}

// ── Undo/Redo ────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativePushUndoState(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->pushUndoState();
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeUndo(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->undo();
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeRedo(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->redo();
}

// ── Stats ────────────────────────────────────────────────────────────────────
JNIEXPORT jfloat JNICALL Java_com_modelviewer3d_NativeLib_nativeGetFPS(JNIEnv*,jclass){
    LOCK_OR_ZERO(); return (jfloat)g_renderer->getFPS();
}
JNIEXPORT jfloatArray JNICALL Java_com_modelviewer3d_NativeLib_nativeGetModelSizeMM(JNIEnv* env,jclass){
    float d[6]={1,1,1,1,1,1};
    {
        LOCK_RENDERER();
        if(g_renderer){
            g_renderer->getModelSizeMM (d[0],d[1],d[2]);
            g_renderer->getCurrentSizeMM(d[3],d[4],d[5]);
        }
    }
    jfloatArray arr=env->NewFloatArray(6); env->SetFloatArrayRegion(arr,0,6,d); return arr;
}

// ── Separation ───────────────────────────────────────────────────────────────
// CPU step: heavy, runs on background Thread.  Reads g_sepVerts/g_sepIdx
// (snapshot taken at upload time), produces g_sepComponents.  Does NOT touch
// g_renderer at all — this is the whole point of the snapshot pattern.
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativePerformSeparationCPU(JNIEnv*,jclass){
    // Snapshot data under the lock, then release before doing the heavy work.
    std::vector<Vertex>   verts;
    std::vector<uint32_t> idx;
    {
        LOCK_RENDERER();
        if(g_sepVerts.empty() || g_sepIdx.empty()){
            LOGE("nativePerformSeparationCPU: no data — load a model first");
            return JNI_FALSE;
        }
        verts.swap(g_sepVerts);   // move out of bridge ownership
        idx.swap(g_sepIdx);
    }
    const uint32_t triCount  = (uint32_t)(idx.size()   / 3);
    const uint32_t vertCount = (uint32_t)(verts.size());
    LOGI("nativePerformSeparationCPU: %u verts, %u tris (lock released)",
         vertCount, triCount);

    std::vector<MeshComponent> comps;
    g_separator.separate(verts.data(),  vertCount,
                         idx.data(),    triCount,
                         comps);

    // Re-acquire lock to publish results
    {
        LOCK_RENDERER();
        g_sepComponents = std::move(comps);
        // Put scratch back in case of cancel/retry
        g_sepVerts = std::move(verts);
        g_sepIdx   = std::move(idx);
        LOGI("nativePerformSeparationCPU done: %d components", (int)g_sepComponents.size());
        return g_sepComponents.empty() ? JNI_FALSE : JNI_TRUE;
    }
}

JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativePerformSeparationGPU(JNIEnv*,jclass){
    LOCK_OR_FALSE();
    if(g_sepComponents.empty()) return JNI_FALSE;
    bool ok = g_renderer->loadSeparatedComponents(g_sepComponents);
    if(ok){
        g_sepVerts.clear();      g_sepVerts.shrink_to_fit();
        g_sepIdx.clear();        g_sepIdx.shrink_to_fit();
        g_sepComponents.clear(); g_sepComponents.shrink_to_fit();
    }
    LOGI("nativePerformSeparationGPU: ok=%d meshes=%d", ok, g_renderer->getMeshCount());
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeIsSeparated(JNIEnv*,jclass){
    LOCK_OR_FALSE();
    return g_renderer->isSeparated() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_modelviewer3d_NativeLib_nativeGetSeparationProgress(JNIEnv*,jclass){
    // Lock-free read — atomic in MeshSeparator
    return (jint)MeshSeparator::g_progress.load();
}

// ── Mesh management ──────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL Java_com_modelviewer3d_NativeLib_nativeGetMeshCount(JNIEnv*,jclass){
    LOCK_OR_ZERO(); return (jint)g_renderer->getMeshCount();
}
JNIEXPORT jstring JNICALL Java_com_modelviewer3d_NativeLib_nativeGetMeshName(JNIEnv* env,jclass,jint idx){
    char buf[64]="?";
    {
        LOCK_RENDERER();
        if(g_renderer) g_renderer->getMeshName((int)idx,buf,64);
    }
    return env->NewStringUTF(buf);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSelectMesh(JNIEnv*,jclass,jint idx){
    LOCK_OR_VOID(); g_renderer->selectMesh((int)idx);
}
JNIEXPORT jint JNICALL Java_com_modelviewer3d_NativeLib_nativeGetSelectedMesh(JNIEnv*,jclass){
    LOCK_RENDERER();
    if(!g_renderer) return -1;
    return (jint)g_renderer->getSelectedMesh();
}
// Ray-pick the front-most mesh under (sx,sy). Returns mesh idx or -1.
// Designed to be called from a long-press handler — caller decides whether
// to also call selectMesh() with the returned id (we don't here so that the
// pick remains side-effect free).
JNIEXPORT jint JNICALL Java_com_modelviewer3d_NativeLib_nativePickMesh(
        JNIEnv*,jclass,jfloat sx,jfloat sy,jfloat sw,jfloat sh){
    LOCK_RENDERER();
    if(!g_renderer) return -1;
    return (jint)g_renderer->pickMesh(sx,sy,sw,sh);
}
// Per-mesh transform setters — mirror the global versions above.  Slider DOWN
// in Java pushes one undo entry; subsequent drags stream values without
// further undo pushes.  No internal pushUndoState here.
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetMeshRotation(
        JNIEnv*,jclass,jint idx,jfloat rx,jfloat ry,jfloat rz){
    LOCK_OR_VOID(); g_renderer->setMeshRotation((int)idx,rx,ry,rz);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetMeshTranslation(
        JNIEnv*,jclass,jint idx,jfloat px,jfloat py,jfloat pz){
    LOCK_OR_VOID(); g_renderer->setMeshTranslation((int)idx,px,py,pz);
}
JNIEXPORT jfloatArray JNICALL Java_com_modelviewer3d_NativeLib_nativeGetMeshTransform(
        JNIEnv* env,jclass,jint idx){
    float d[9] = {0,0,0, 0,0,0, 1,1,1};
    { LOCK_RENDERER(); if(g_renderer) g_renderer->getMeshTransform((int)idx, d); }
    jfloatArray arr = env->NewFloatArray(9);
    env->SetFloatArrayRegion(arr,0,9,d);
    return arr;
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeResetMeshTransform(
        JNIEnv*,jclass,jint idx){
    LOCK_OR_VOID();
    g_renderer->pushUndoState();
    g_renderer->resetMeshTransform((int)idx);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeDeleteMesh(JNIEnv*,jclass,jint idx){
    LOCK_OR_VOID(); g_renderer->pushUndoState(); g_renderer->deleteMesh((int)idx);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetMeshVisible(JNIEnv*,jclass,jint idx,jboolean v){
    LOCK_OR_VOID(); g_renderer->setMeshVisible((int)idx,v==JNI_TRUE);
}
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeGetMeshVisible(JNIEnv*,jclass,jint idx){
    LOCK_RENDERER();
    if(!g_renderer) return JNI_TRUE;
    return g_renderer->getMeshVisible((int)idx) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetMeshColor(JNIEnv*,jclass,jint idx,jfloat r,jfloat g,jfloat b){
    LOCK_OR_VOID(); g_renderer->setMeshColor((int)idx,r,g,b);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetMeshScaleMM(JNIEnv*,jclass,jint idx,jfloat w,jfloat h,jfloat d){
    LOCK_OR_VOID(); g_renderer->setMeshScaleMM((int)idx,w,h,d);
}
JNIEXPORT jfloatArray JNICALL Java_com_modelviewer3d_NativeLib_nativeGetMeshSizeMM(JNIEnv* env,jclass,jint idx){
    float d[3]={1,1,1};
    {
        LOCK_RENDERER();
        if(g_renderer) g_renderer->getMeshSizeMM((int)idx,d[0],d[1],d[2]);
    }
    jfloatArray arr=env->NewFloatArray(3); env->SetFloatArrayRegion(arr,0,3,d); return arr;
}
JNIEXPORT jint JNICALL Java_com_modelviewer3d_NativeLib_nativeGetMeshVertexCount(JNIEnv*,jclass,jint idx){
    LOCK_OR_ZERO(); return (jint)g_renderer->getMeshVertexCount((int)idx);
}

// ── Export ───────────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeExportOBJ(JNIEnv* env,jclass,jstring path){
    LOCK_OR_FALSE();
    return g_renderer->exportOBJ(jstr(env,path)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeExportSTL(JNIEnv* env,jclass,jstring path){
    LOCK_OR_FALSE();
    return g_renderer->exportSTL(jstr(env,path)) ? JNI_TRUE : JNI_FALSE;
}

// ── Ruler ────────────────────────────────────────────────────────────────────
JNIEXPORT jfloatArray JNICALL Java_com_modelviewer3d_NativeLib_nativePickPoint(JNIEnv* env,jclass,jfloat sx,jfloat sy,jfloat sw,jfloat sh){
    float pt[3];
    {
        LOCK_RENDERER();
        if(!g_renderer) return nullptr;
        if(!g_renderer->pickPoint(sx,sy,sw,sh,pt)) return nullptr;
    }
    jfloatArray arr=env->NewFloatArray(3); env->SetFloatArrayRegion(arr,0,3,pt); return arr;
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetRulerPoints(JNIEnv* env,jclass,jboolean h1,jfloatArray p1,jboolean h2,jfloatArray p2){
    float a1[3]={},a2[3]={};
    if(h1==JNI_TRUE&&p1) env->GetFloatArrayRegion(p1,0,3,a1);
    if(h2==JNI_TRUE&&p2) env->GetFloatArrayRegion(p2,0,3,a2);
    LOCK_OR_VOID();
    g_renderer->setRulerPoints(h1==JNI_TRUE,a1,h2==JNI_TRUE,a2);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeClearRuler(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->clearRuler();
}

// ── Screenshot ───────────────────────────────────────────────────────────────
JNIEXPORT jbyteArray JNICALL Java_com_modelviewer3d_NativeLib_nativeTakeScreenshot(JNIEnv* env,jclass){
    std::vector<uint8_t> px;
    {
        LOCK_RENDERER();
        if(!g_renderer) return nullptr;
        px = g_renderer->takeScreenshot();
    }
    if(px.empty()) return nullptr;
    jbyteArray arr=env->NewByteArray((jsize)px.size());
    env->SetByteArrayRegion(arr,0,(jsize)px.size(),reinterpret_cast<const jbyte*>(px.data()));
    return arr;
}

// ── Ring tools ───────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeAnalyzeRing(JNIEnv*,jclass,jint meshIdx){
    LOCK_OR_FALSE(); return g_renderer->analyzeRing((int)meshIdx) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jfloatArray JNICALL Java_com_modelviewer3d_NativeLib_nativeGetRingParams(JNIEnv* env,jclass){
    float d[6]={0,0,0,0,0,0};
    { LOCK_RENDERER(); if(g_renderer) g_renderer->getRingParams(d); }
    jfloatArray arr=env->NewFloatArray(6); env->SetFloatArrayRegion(arr,0,6,d); return arr;
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetRingBandWidth(JNIEnv*,jclass,jfloat w){
    LOCK_OR_VOID(); g_renderer->setRingBandWidth((float)w);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetRingInnerDiameter(JNIEnv*,jclass,jfloat d){
    LOCK_OR_VOID(); g_renderer->setRingInnerDiameter((float)d);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeResetRingDeformation(JNIEnv*,jclass){
    LOCK_OR_VOID(); g_renderer->resetRingDeformation();
}
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeIsRingAnalyzed(JNIEnv*,jclass){
    LOCK_OR_FALSE(); return g_renderer->isRingAnalyzed() ? JNI_TRUE : JNI_FALSE;
}

// ── Mesh processing (MeshLab/OpenSCAD inspired) ──────────────────────────────
JNIEXPORT jboolean JNICALL Java_com_modelviewer3d_NativeLib_nativeDecimateMesh(JNIEnv*,jclass,jint idx,jfloat pct){
    LOCK_OR_FALSE();
    g_renderer->pushUndoState();
    return g_renderer->decimateMesh((int)idx,(float)pct) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jfloatArray JNICALL Java_com_modelviewer3d_NativeLib_nativeGetMeshStats(JNIEnv* env,jclass,jint idx){
    MeshStats s;
    { LOCK_RENDERER(); if(g_renderer) g_renderer->getMeshStats((int)idx, s); }
    float d[9]={s.surfaceAreaMM2, s.volumeMM3, s.bboxW, s.bboxH, s.bboxD,
                (float)s.vertCount, (float)s.triCount, (float)s.edgeCount,
                s.isWatertight?1.f:0.f};
    jfloatArray arr=env->NewFloatArray(9); env->SetFloatArrayRegion(arr,0,9,d); return arr;
}
JNIEXPORT jint JNICALL Java_com_modelviewer3d_NativeLib_nativeWeldVertices(JNIEnv*,jclass,jint idx,jfloat epsMM){
    LOCK_OR_ZERO();
    g_renderer->pushUndoState();
    return (jint)g_renderer->weldVertices((int)idx,(float)epsMM);
}
JNIEXPORT jint JNICALL Java_com_modelviewer3d_NativeLib_nativeRemoveZeroAreaFaces(JNIEnv*,jclass,jint idx){
    LOCK_OR_ZERO();
    g_renderer->pushUndoState();
    return (jint)g_renderer->removeZeroAreaFaces((int)idx);
}

JNIEXPORT jfloat JNICALL Java_com_modelviewer3d_NativeLib_nativeGetNormalizeScale(JNIEnv*,jclass){
    LOCK_OR_FALSE(); return g_renderer ? g_renderer->getNormalizeScale() : 1.f;
}

// ── Non-blocking ring deformation setters ─────────────────────────────────────
// These return instantly and schedule the actual deformation for the next draw()
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetRingBandWidthAsync(
    JNIEnv*,jclass,jfloat v) {
    if (g_renderer) g_renderer->setPendingBandWidth((float)v);
}
JNIEXPORT void JNICALL Java_com_modelviewer3d_NativeLib_nativeSetRingInnerDiameterAsync(
    JNIEnv*,jclass,jfloat v) {
    if (g_renderer) g_renderer->setPendingInnerDiameter((float)v);
}
} // extern "C"
