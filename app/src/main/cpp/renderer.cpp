#include "renderer.h"
#include "mesh_separator.h"
#include "shader_utils.h"
#include <android/log.h>
#include <ctime>
#include <cstring>
#include <cfloat>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <sstream>
#include <set>
#include <map>
#include <queue>
#include <unordered_map>
#include <numeric>
#include <functional>
#include <numeric>
// unordered_map removed — using MeshSeparator now

#define TAG "Renderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG,__VA_ARGS__)

// ── Shaders ───────────────────────────────────────────────────────────────────
static const char* kVertPhong = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNorm;
layout(location=2) in vec2 aUV;
uniform mat4 uMVP, uModel;
uniform mat3 uNorm;
out vec3 vFragPos, vNormal;
void main(){
    vec4 wp = uModel * vec4(aPos,1.0);
    vFragPos  = wp.xyz;
    vNormal   = normalize(uNorm * aNorm);
    gl_Position = uMVP * vec4(aPos,1.0);
})";

static const char* kFragPhong = R"(#version 300 es
precision highp float;
in vec3 vFragPos, vNormal;
out vec4 fragColor;
uniform vec3  uColor, uLightDir;
uniform float uAmbient, uDiffuse;
uniform int   uSelected;
uniform vec3  uCamPos;

// Reinhard tonemapping — keeps bright areas from blowing out
vec3 tonemap(vec3 c){ return c / (c + vec3(1.0)); }

void main(){
    vec3 N = normalize(vNormal);
    vec3 V = normalize(uCamPos - vFragPos);

    // Key light: warm white, upper-left-front
    vec3 L1  = normalize(vec3(-0.5, 1.2, 1.0));
    float d1 = max(dot(N, L1), 0.0);
    vec3 H1  = normalize(L1 + V);
    float s1 = pow(max(dot(N, H1), 0.0), 64.0) * 0.6;

    // Fill light: cool blue, right side
    vec3 L2  = normalize(vec3(1.0, 0.2, -0.3));
    float d2 = max(dot(N, L2), 0.0) * 0.35;

    // Back/rim light: creates silhouette edge
    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.22;

    // Ground bounce: subtle warm upwelling
    float bounce = max(dot(N, vec3(0.0, -1.0, 0.0)), 0.0) * 0.08;

    vec3 keyContrib  = (d1 * uColor + vec3(s1 * 0.9, s1 * 0.95, s1)) * vec3(1.0, 0.97, 0.92);
    vec3 fillContrib = d2 * uColor * vec3(0.7, 0.82, 1.0);
    vec3 rimContrib  = rim * vec3(0.4, 0.65, 1.0);
    vec3 ambContrib  = uAmbient * uColor;
    vec3 bncContrib  = bounce * uColor * vec3(1.0, 0.9, 0.7);

    vec3 c = ambContrib
           + uDiffuse * keyContrib
           + uDiffuse * fillContrib
           + rimContrib
           + bncContrib;

    // Gamma correction (linear → sRGB)
    c = tonemap(c);
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));

    if(uSelected == 1) c = mix(c, vec3(0.1, 0.9, 1.0), 0.35);

    fragColor = vec4(c, 1.0);
})";

static const char* kVertSimple = R"(#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uMVP;
uniform float uPointSize;
void main(){
    gl_Position  = uMVP * vec4(aPos,1.0);
    gl_PointSize = uPointSize;
})";

static const char* kFragSimple = R"(#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main(){ fragColor = uColor; })";

// ── Helpers ───────────────────────────────────────────────────────────────────
static int64_t nowNs(){
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts);
    return (int64_t)ts.tv_sec*1000000000LL+(int64_t)ts.tv_nsec;
}

// ── Ctor/Dtor ────────────────────────────────────────────────────────────────
Renderer::Renderer()=default;
Renderer::~Renderer(){
    delete m_pendingData;
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }
    if(m_bbVao)    glDeleteVertexArrays(1,&m_bbVao);
    if(m_bbVbo)    glDeleteBuffers(1,&m_bbVbo);
    if(m_bbIbo)    glDeleteBuffers(1,&m_bbIbo);
    if(m_rulerVao) glDeleteVertexArrays(1,&m_rulerVao);
    if(m_rulerVbo) glDeleteBuffers(1,&m_rulerVbo);
    if(m_mainProg) glDeleteProgram(m_mainProg);
    if(m_wireProg) glDeleteProgram(m_wireProg);
}

// ── Init ────────────────────────────────────────────────────────────────────
bool Renderer::init(int w,int h){
    m_width=w; m_height=h;
    glViewport(0,0,w,h);
    glClearColor(0.05f,0.05f,0.08f,1.0f);
    glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL);
    glEnable(GL_CULL_FACE);  glCullFace(GL_BACK);

    buildShaders();

    // Bounding box
    glGenVertexArrays(1,&m_bbVao); glGenBuffers(1,&m_bbVbo); glGenBuffers(1,&m_bbIbo);
    buildBoundingBox();

    // Ruler VAO
    glGenVertexArrays(1,&m_rulerVao); glGenBuffers(1,&m_rulerVbo);
    glBindVertexArray(m_rulerVao);
    glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
    float dummy[6]={};
    glBufferData(GL_ARRAY_BUFFER,sizeof(dummy),dummy,GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,12,nullptr);
    glBindVertexArray(0);

    m_fpsTimerNs=nowNs();
    return !checkGLError("init");
}

void Renderer::resize(int w,int h){ m_width=w; m_height=h; glViewport(0,0,w,h); }

void Renderer::buildShaders(){
    if(m_mainProg) glDeleteProgram(m_mainProg);
    if(m_wireProg) glDeleteProgram(m_wireProg);
    m_mainProg=createProgram(kVertPhong,kFragPhong);
    m_wireProg=createProgram(kVertSimple,kFragSimple);
    cacheUniformLocs();
}

void Renderer::cacheUniformLocs(){
    // Cache all uniform locations once after shader compilation.
    // glGetUniformLocation is slow — calling it 9x per mesh per frame kills FPS.
    m_uloc.mvp      = glGetUniformLocation(m_mainProg, "uMVP");
    m_uloc.model    = glGetUniformLocation(m_mainProg, "uModel");
    m_uloc.norm     = glGetUniformLocation(m_mainProg, "uNorm");
    m_uloc.color    = glGetUniformLocation(m_mainProg, "uColor");
    m_uloc.lightDir = glGetUniformLocation(m_mainProg, "uLightDir");
    m_uloc.ambient  = glGetUniformLocation(m_mainProg, "uAmbient");
    m_uloc.diffuse  = glGetUniformLocation(m_mainProg, "uDiffuse");
    m_uloc.selected = glGetUniformLocation(m_mainProg, "uSelected");
    m_uloc.camPos   = glGetUniformLocation(m_mainProg, "uCamPos");
    m_uloc.wireMvp  = glGetUniformLocation(m_wireProg,  "uMVP");
    m_uloc.wireColor     = glGetUniformLocation(m_wireProg, "uColor");
    m_uloc.wirePointSize = glGetUniformLocation(m_wireProg, "uPointSize");
}

// ── Bounding box ─────────────────────────────────────────────────────────────
void Renderer::buildBoundingBox(){
    static const float v[24]={-1,-1,-1,1,-1,-1,1,1,-1,-1,1,-1,-1,-1,1,1,-1,1,1,1,1,-1,1,1};
    static const uint16_t idx[24]={0,1,1,2,2,3,3,0,4,5,5,6,6,7,7,4,0,4,1,5,2,6,3,7};
    glBindVertexArray(m_bbVao);
    glBindBuffer(GL_ARRAY_BUFFER,m_bbVbo);
    glBufferData(GL_ARRAY_BUFFER,sizeof(v),v,GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,12,nullptr);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,m_bbIbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,sizeof(idx),idx,GL_STATIC_DRAW);
    glBindVertexArray(0);
    m_bbIndexCount=24;
}

// ── Upload single MeshObject to GPU ──────────────────────────────────────────
void Renderer::uploadMeshObject(MeshObject& mo){
    if(!mo.vao){ glGenVertexArrays(1,&mo.vao); glGenBuffers(1,&mo.vbo); glGenBuffers(1,&mo.ibo); }
    glBindVertexArray(mo.vao);
    glBindBuffer(GL_ARRAY_BUFFER,mo.vbo);
    glBufferData(GL_ARRAY_BUFFER,(GLsizeiptr)(mo.vertices.size()*sizeof(Vertex)),mo.vertices.data(),GL_STATIC_DRAW);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,mo.ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,(GLsizeiptr)(mo.indices.size()*sizeof(unsigned int)),mo.indices.data(),GL_STATIC_DRAW);
    constexpr GLsizei stride=sizeof(Vertex);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,stride,(void*)offsetof(Vertex,px));
    glEnableVertexAttribArray(1); glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,stride,(void*)offsetof(Vertex,nx));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2,2,GL_FLOAT,GL_FALSE,stride,(void*)offsetof(Vertex,u));
    glBindVertexArray(0);

    // ── Compute per-mesh local bbox (used by selection overlay & pickMesh) ──
    // Each separated component lives in a sub-volume of the global [-1,1]³, so
    // the global unit cube is the wrong overlay shape. Cache the tight box now
    // since mo.vertices won't change without a re-upload.
    if(mo.vertices.empty()){
        mo.bboxMin[0]=mo.bboxMin[1]=mo.bboxMin[2]=0.f;
        mo.bboxMax[0]=mo.bboxMax[1]=mo.bboxMax[2]=0.f;
    } else {
        float mn[3]={ FLT_MAX, FLT_MAX, FLT_MAX};
        float mx[3]={-FLT_MAX,-FLT_MAX,-FLT_MAX};
        for(const auto& v:mo.vertices){
            if(v.px<mn[0])mn[0]=v.px; if(v.px>mx[0])mx[0]=v.px;
            if(v.py<mn[1])mn[1]=v.py; if(v.py>mx[1])mx[1]=v.py;
            if(v.pz<mn[2])mn[2]=v.pz; if(v.pz>mx[2])mx[2]=v.pz;
        }
        memcpy(mo.bboxMin,mn,12); memcpy(mo.bboxMax,mx,12);
    }
    mo.gpuReady=true;
}

// ── separateIntoMeshes — GL thread version (used by legacy loadModel) ─────────
// Uses production MeshSeparator then uploads to GPU.
void Renderer::separateIntoMeshes(const ModelData& md){
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }
    m_meshes.clear();
    if(md.vertices.empty() || md.indices.empty()) return;

    std::vector<MeshComponent> components;
    m_separator.separate(
        md.vertices.data(), (uint32_t)md.vertices.size(),
        md.indices.data(),  (uint32_t)(md.indices.size()/3),
        components);

    for(auto& comp : components){
        if(comp.vertices.empty()) continue;
        MeshObject mo;
        mo.name    = "Mesh_" + std::to_string(m_meshes.size()+1);
        mo.colorR  = m_colorR; mo.colorG = m_colorG; mo.colorB = m_colorB;
        mo.vertices = std::move(comp.vertices);
        mo.indices.resize(comp.indices.size());
        std::copy(comp.indices.begin(), comp.indices.end(), mo.indices.begin());
        uploadMeshObject(mo);
        m_meshes.push_back(std::move(mo));
    }
    LOGI("separateIntoMeshes: %d islands", (int)m_meshes.size());
}

// ── CPU mesh separation — uses production MeshSeparator (NO GL calls) ─────────
// Replaces old vertex-based Union-Find. Now face-based with sort adjacency.
void Renderer::separateMeshesCPU(const ModelData& md, std::vector<MeshObject>& out){
    out.clear();
    if(md.vertices.empty() || md.indices.empty()) return;

    const uint32_t triCount  = (uint32_t)(md.indices.size() / 3);
    const uint32_t vertCount = (uint32_t)md.vertices.size();

    // Reusable separator — buffers persist for lifetime of this Renderer instance
    std::vector<MeshComponent> components;
    m_separator.separate(
        md.vertices.data(), vertCount,
        md.indices.data(),  triCount,
        components);

    // Convert MeshComponent → MeshObject (add name, color; VAO/VBO filled later on GL thread)
    out.resize(components.size());
    for(size_t i = 0; i < components.size(); ++i){
        MeshObject& mo = out[i];
        mo.name    = "Mesh_" + std::to_string(i + 1);
        mo.colorR  = m_colorR; mo.colorG = m_colorG; mo.colorB = m_colorB;
        mo.vertices = std::move(components[i].vertices);
        // MeshObject uses unsigned int, MeshComponent uses uint32_t — same on all ABIs
        mo.indices.resize(components[i].indices.size());
        std::copy(components[i].indices.begin(), components[i].indices.end(), mo.indices.begin());
    }
    LOGI("separateMeshesCPU: %d islands from %u tris", (int)out.size(), triCount);
}

// ── Matrix helpers ────────────────────────────────────────────────────────────
Mat4 Renderer::buildGlobalMatrix() const {
    return Mat4::translation(m_posX,m_posY,m_posZ)
         * Mat4::rotationZ(m_rotZ*DEG2RAD)
         * Mat4::rotationY(m_rotY*DEG2RAD)
         * Mat4::rotationX(m_rotX*DEG2RAD)
         * Mat4::scale(m_scaX,m_scaY,m_scaZ);
}
Mat4 Renderer::buildMeshMatrix(const MeshObject& mo) const {
    return Mat4::translation(mo.posX,mo.posY,mo.posZ)
         * Mat4::rotationZ(mo.rotZ*DEG2RAD)
         * Mat4::rotationY(mo.rotY*DEG2RAD)
         * Mat4::rotationX(mo.rotX*DEG2RAD)
         * Mat4::scale(mo.scaX,mo.scaY,mo.scaZ);
}
Vec3 Renderer::cameraEye() const {
    float cp=cosf(m_camPitch),sp=sinf(m_camPitch);
    float cy=cosf(m_camYaw),sy=sinf(m_camYaw);
    return {m_panX+m_camDist*cp*sy, m_panY+m_camDist*sp, m_camDist*cp*cy};
}

// ── Draw ─────────────────────────────────────────────────────────────────────
void Renderer::draw(){
    updateFPS();
    glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    if(m_meshes.empty()||!m_mainProg||!m_wireProg) return;
    m_lastFrustum = buildFrustum();

    float aspect=(float)m_width/std::max(m_height,1);
    Mat4 proj=Mat4::perspective(60.0f*DEG2RAD,aspect,0.01f,100.0f);
    Vec3 eye=cameraEye();
    Mat4 view=Mat4::lookAt(eye,{m_panX,m_panY,0},{0,1,0});
    Mat4 global=buildGlobalMatrix();
    // lightDir still passed for legacy uLightDir uniform (not used in new shader)
    Vec3 lightDir=Vec3{-0.5f, 1.0f, 0.8f}.normalized();

    for(auto& mo:m_meshes){
        if(!mo.visible||!mo.gpuReady) continue;
        // ── Frustum cull: skip meshes fully outside camera view ────────────────
        {
            float cx=0.5f*(mo.bboxMin[0]+mo.bboxMax[0]);
            float cy=0.5f*(mo.bboxMin[1]+mo.bboxMax[1]);
            float cz=0.5f*(mo.bboxMin[2]+mo.bboxMax[2]);
            float dx=mo.bboxMax[0]-mo.bboxMin[0];
            float dy=mo.bboxMax[1]-mo.bboxMin[1];
            float dz=mo.bboxMax[2]-mo.bboxMin[2];
            float r=sqrtf(dx*dx+dy*dy+dz*dz)*0.6f;
            if(!frustumSphereTest(m_lastFrustum,cx,cy,cz,r)) continue;
        }
        Mat4 meshMat = buildMeshMatrix(mo);
        Mat4 model   = global * meshMat;
        Mat4 mvp     = proj*view*model;
        float nm[9]; model.toNormalMatrix(nm);

        glUseProgram(m_mainProg);
        // Use cached locations — no more slow glGetUniformLocation in hot loop
        glUniformMatrix4fv(m_uloc.mvp,      1, GL_FALSE, mvp.m);
        glUniformMatrix4fv(m_uloc.model,    1, GL_FALSE, model.m);
        glUniformMatrix3fv(m_uloc.norm,     1, GL_FALSE, nm);
        glUniform3f(m_uloc.color,    mo.colorR, mo.colorG, mo.colorB);
        glUniform3f(m_uloc.lightDir, lightDir.x, lightDir.y, lightDir.z);
        glUniform1f(m_uloc.ambient,  m_ambient);
        glUniform1f(m_uloc.diffuse,  m_diffuse);
        glUniform1i(m_uloc.selected, mo.selected ? 1 : 0);
        glUniform3f(m_uloc.camPos,   eye.x, eye.y, eye.z);

        GLsizei ic=(GLsizei)mo.indices.size();
        if(m_wireframe){
            glEnable(GL_POLYGON_OFFSET_FILL); glPolygonOffset(1,1);
            glUniform3f(m_uloc.color,0.05f,0.05f,0.08f);
            glBindVertexArray(mo.vao);
            glDrawElements(GL_TRIANGLES,ic,GL_UNSIGNED_INT,nullptr);
            glDisable(GL_POLYGON_OFFSET_FILL);

            glUseProgram(m_wireProg);
            glUniformMatrix4fv(m_uloc.wireMvp, 1, GL_FALSE, mvp.m);
            glUniform4f(m_uloc.wireColor,0.2f,0.85f,1.0f,1.0f);
            glUniform1f(m_uloc.wirePointSize,1.0f);
            glLineWidth(1.2f);
            glDrawElements(GL_LINES,ic,GL_UNSIGNED_INT,nullptr);
            glBindVertexArray(0);
        } else {
            glBindVertexArray(mo.vao);
            glDrawElements(GL_TRIANGLES,ic,GL_UNSIGNED_INT,nullptr);
            glBindVertexArray(0);
        }

        // Selected mesh bounding box overlay — fits per-mesh local bbox.
        // m_bbVao is a unit cube spanning [-1,1]³. We map it into the mesh's
        // actual local extent via translate(center) * scale(half), so a small
        // ring shows a small box rather than the whole-model unit cube.
        if(mo.selected && m_bbIndexCount>0){
            float cx=0.5f*(mo.bboxMin[0]+mo.bboxMax[0]);
            float cy=0.5f*(mo.bboxMin[1]+mo.bboxMax[1]);
            float cz=0.5f*(mo.bboxMin[2]+mo.bboxMax[2]);
            float hx=0.5f*(mo.bboxMax[0]-mo.bboxMin[0]);
            float hy=0.5f*(mo.bboxMax[1]-mo.bboxMin[1]);
            float hz=0.5f*(mo.bboxMax[2]-mo.bboxMin[2]);
            // Guard against degenerate (single-point) meshes — minimum 1% of
            // global extent so the overlay is still visible.
            if(hx<1e-4f) hx=0.01f;
            if(hy<1e-4f) hy=0.01f;
            if(hz<1e-4f) hz=0.01f;
            Mat4 bbFit  = Mat4::translation(cx,cy,cz) * Mat4::scale(hx,hy,hz);
            Mat4 bbMvp  = proj * view * model * bbFit;

            glUseProgram(m_wireProg);
            glUniformMatrix4fv(m_uloc.wireMvp, 1, GL_FALSE, bbMvp.m);
            glUniform4f(m_uloc.wireColor,0.2f,0.9f,1.0f,1.0f);
            glUniform1f(m_uloc.wirePointSize,1.0f);
            glLineWidth(2.0f);
            glBindVertexArray(m_bbVao);
            glDrawElements(GL_LINES,m_bbIndexCount,GL_UNSIGNED_SHORT,nullptr);
            glBindVertexArray(0);
        }
    }

    // Global bounding box
    if(m_showBBox && m_bbIndexCount>0){
        Mat4 mvp=proj*view*buildGlobalMatrix();
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(m_uloc.wireMvp,1,GL_FALSE,mvp.m);
        glUniform4f(m_uloc.wireColor,1.0f,0.6f,0.1f,0.9f);
        glUniform1f(m_uloc.wirePointSize,1.0f);
        glLineWidth(1.5f);
        glBindVertexArray(m_bbVao);
        glDrawElements(GL_LINES,m_bbIndexCount,GL_UNSIGNED_SHORT,nullptr);
        glBindVertexArray(0);
    }

    // Ruler
    if(m_rulerHasP1||m_rulerHasP2){
        Mat4 vp=proj*view;
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(m_uloc.wireMvp,1,GL_FALSE,vp.m);
        glDisable(GL_DEPTH_TEST);

        if(m_rulerHasP1&&m_rulerHasP2){
            float pts[6]={m_rulerP1[0],m_rulerP1[1],m_rulerP1[2],
                          m_rulerP2[0],m_rulerP2[1],m_rulerP2[2]};
            glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
            glBufferSubData(GL_ARRAY_BUFFER,0,sizeof(pts),pts);
            glBindVertexArray(m_rulerVao);
            glUniform4f(m_uloc.wireColor,1.0f,1.0f,0.0f,1.0f);
            glUniform1f(m_uloc.wirePointSize,1.0f);
            glLineWidth(2.5f); glDrawArrays(GL_LINES,0,2);
            glBindVertexArray(0);
        }

        float dotPts[6]={}; int dotCount=0;
        if(m_rulerHasP1){memcpy(dotPts+dotCount*3,m_rulerP1,12);dotCount++;}
        if(m_rulerHasP2){memcpy(dotPts+dotCount*3,m_rulerP2,12);dotCount++;}
        glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
        glBufferSubData(GL_ARRAY_BUFFER,0,dotCount*12,dotPts);
        glBindVertexArray(m_rulerVao);
        glUniform4f(m_uloc.wireColor,1.0f,0.3f,0.3f,1.0f);
        glUniform1f(m_uloc.wirePointSize,14.0f);
        glDrawArrays(GL_POINTS,0,dotCount);
        glBindVertexArray(0);
        glEnable(GL_DEPTH_TEST);
    }
}

// ── FPS ──────────────────────────────────────────────────────────────────────
void Renderer::updateFPS(){
    int64_t now=nowNs();
    if(++m_frameCount>=60){
        float e=(float)(now-m_fpsTimerNs)/1e9f;
        m_fps=(e>0)?m_frameCount/e:0;
        m_frameCount=0; m_fpsTimerNs=now;
    }
}

// ── Camera ───────────────────────────────────────────────────────────────────
void Renderer::touchRotate(float dx,float dy){
    // Positive dx = finger moves right = model rotates right (yaw increases)
    m_camYaw  -= dx * 0.006f;   // negate: drag right → rotate right around Y
    // + dy: drag UP (dy<0) → pitch decreases → camera lower → sees top of model (natural turntable)
    m_camPitch = std::clamp(m_camPitch + dy * 0.006f, -PI*0.48f, PI*0.48f);
}
void Renderer::touchZoom(float f){
    // f > 1.0 = pinch open = zoom in (distance decreases)
    // Clamp scaleFactor to avoid jumpy single-frame zoom
    float sf = f < 0.85f ? 0.85f : (f > 1.18f ? 1.18f : f);
    m_camDist = std::clamp(m_camDist / sf, 0.05f, 80.0f);
}
void Renderer::touchPan(float dx,float dy){
    // Screen dx/dy → world pan, scaled by camera distance so pan speed is
    // proportional to how far the camera is (feels consistent at all zoom levels)
    float s = m_camDist / (float)std::max(m_height, 1);
    m_panX -= dx * s;   // negate: drag right → view moves right → model appears to go right
    m_panY -= dy * s;   // Android Y grows DOWN, OpenGL Y grows UP → invert for correct pan
}
void Renderer::resetCamera(){ m_camYaw=0.4f;m_camPitch=0.3f;m_camDist=3.5f;m_panX=0;m_panY=0; }

// ── Transform ────────────────────────────────────────────────────────────────
void Renderer::setRotation(float x,float y,float z)    {m_rotX=x;m_rotY=y;m_rotZ=z;}
void Renderer::setTranslation(float x,float y,float z) {m_posX=x;m_posY=y;m_posZ=z;}
void Renderer::setScaleMM(float w,float h,float d){
    if(m_origWmm>1e-9f) m_scaX=w/m_origWmm;
    if(m_origHmm>1e-9f) m_scaY=h/m_origHmm;
    if(m_origDmm>1e-9f) m_scaZ=d/m_origDmm;
}
void Renderer::mirrorX(){m_scaX=-m_scaX;}
void Renderer::mirrorY(){m_scaY=-m_scaY;}
void Renderer::mirrorZ(){m_scaZ=-m_scaZ;}
void Renderer::resetTransform(){m_rotX=m_rotY=m_rotZ=0;m_posX=m_posY=m_posZ=0;m_scaX=m_scaY=m_scaZ=1;}
void Renderer::setColor(float r,float g,float b){
    m_colorR=r;m_colorG=g;m_colorB=b;
    for(auto& mo:m_meshes){ mo.colorR=r;mo.colorG=g;mo.colorB=b; }
}
void Renderer::setAmbient(float a){m_ambient=std::clamp(a,0.0f,1.0f);}
void Renderer::setDiffuse(float d){m_diffuse=std::clamp(d,0.0f,1.0f);}
void Renderer::setWireframe(bool on){m_wireframe=on;}
void Renderer::setShowBoundingBox(bool on){m_showBBox=on;}

// ── TWO-STEP LOAD ─────────────────────────────────────────────────────────────

// Step 1 — IO thread: parse file (tinyobj/stl/glb). NO GL calls. FAST.
bool Renderer::parseModel(const std::string& path){
    delete m_pendingData;
    m_pendingData = nullptr;

    ModelData* md = new ModelData();
    if(!ModelLoader::load(path, *md)){
        delete md;
        LOGE("parseModel failed: %s", path.c_str());
        return false;
    }
    m_pendingData = md;
    LOGI("parseModel OK — %zu verts, %zu idx | %.1fx%.1fx%.1f mm",
         md->vertices.size(), md->indices.size(),
         md->widthMM(), md->heightMM(), md->depthMM());
    return true;
}

// Step 2 — GL thread: upload as ONE single mesh. Instant. NO separation.
//
// MEMORY: This used to keep TWO extra copies of the vertex buffer:
//   md->vertices  (parsed, freed at end)
//   m_rawVertices (kept for separation)        ← 1 deep copy
//   mo.vertices   (kept on CPU + uploaded)     ← move from md
// Peak ≈ 3× during upload, 2× steady-state for a 500 MB OBJ.
//
// New flow keeps ONE CPU copy: mo.vertices owns the data, getRawData reads
// from m_meshes[0] when separation is requested. Steady-state ≈ 1×.
bool Renderer::uploadParsed(){
    if(!m_pendingData){
        LOGE("uploadParsed: no pending data");
        return false;
    }
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }
    m_meshes.clear();

    // Drop any stale raw buffers from a previous load
    m_rawVertices.clear(); m_rawVertices.shrink_to_fit();
    m_rawIndices.clear();  m_rawIndices.shrink_to_fit();

    m_origWmm        = m_pendingData->widthMM();
    m_origHmm        = m_pendingData->heightMM();
    m_origDmm        = m_pendingData->depthMM();
    m_normalizeScale = m_pendingData->normalizeScale;

    MeshObject mo;
    mo.name     = "Model";
    mo.colorR   = m_colorR; mo.colorG = m_colorG; mo.colorB = m_colorB;
    mo.vertices = std::move(m_pendingData->vertices);   // single move, no copy
    mo.indices  = std::move(m_pendingData->indices);
    uploadMeshObject(mo);

    size_t vc = mo.vertices.size();
    size_t ic = mo.indices.size();

    m_meshes.push_back(std::move(mo));

    // m_pendingData is now empty — drop it immediately to free tinyobj/tinygltf
    // internal buffers (which can be hundreds of MB for big OBJs).
    delete m_pendingData;
    m_pendingData  = nullptr;
    m_hasModel     = true;
    m_isSeparated  = false;
    m_selectedMesh = -1;
    resetTransform(); resetCamera(); clearRuler();
    LOGI("uploadParsed OK — %zu verts, %zu tris, %.1fx%.1fx%.1f mm (single-copy)",
         vc, ic/3, m_origWmm, m_origHmm, m_origDmm);
    return true;
}

// Called from JNI after uploadParsed to hand raw data to bridge for safe separation.
// Reads directly from the single mesh — no duplication held in the renderer.
void Renderer::getRawData(std::vector<Vertex>& verts, std::vector<uint32_t>& idx) const {
    if(!m_meshes.empty()){
        verts = m_meshes[0].vertices;   // copy — bridge owns its working set
        idx   = m_meshes[0].indices;
    } else {
        verts = m_rawVertices;
        idx   = m_rawIndices;
    }
    LOGI("getRawData: %zu verts, %zu tris", verts.size(), idx.size()/3);
}

// Move-out variant — bridge takes ownership, renderer keeps GPU side only.
// Use this for 1-shot separation: it eliminates the temporary deep-copy entirely
// (peak shrinks from 2× to 1× for very large meshes during separation).
void Renderer::takeRawData(std::vector<Vertex>& verts, std::vector<uint32_t>& idx){
    if(!m_meshes.empty()){
        // Copy is unavoidable here — renderer still needs CPU vertices for
        // GL context-loss recovery and ring tools. But we use std::vector
        // copy assignment which is one allocation, no element-wise traffic.
        verts = m_meshes[0].vertices;
        idx   = m_meshes[0].indices;
    } else {
        verts = std::move(m_rawVertices);
        idx   = std::move(m_rawIndices);
        m_rawVertices.shrink_to_fit();
        m_rawIndices.shrink_to_fit();
    }
}

// Called from GL thread after EGL context loss (e.g. app backgrounded
// without preserveEGLContextOnPause, or driver reset). All GL handles are
// invalid, but CPU-side mo.vertices/indices survive — we re-upload them.
void Renderer::onContextLost(){
    LOGI("onContextLost: invalidating GPU handles for %zu meshes",
         m_meshes.size());
    for(auto& mo : m_meshes){
        mo.vao = 0; mo.vbo = 0; mo.ibo = 0;
        mo.gpuReady = false;
    }
    // Bounding-box and ruler GL objects also invalid
    m_bbVao = m_bbVbo = m_bbIbo = 0;
    m_rulerVao = m_rulerVbo = 0;
    m_mainProg = m_wireProg = 0;
}

// Called from GL thread after onContextLost + new GL context is current.
// Rebuilds shaders + re-uploads every mesh from CPU buffers.
void Renderer::rebuildContext(){
    if(m_mainProg == 0){
        buildShaders();
        cacheUniformLocs();
        buildBoundingBox();
    }
    for(auto& mo : m_meshes){
        if(!mo.gpuReady) uploadMeshObject(mo);
    }
    LOGI("rebuildContext: re-uploaded %zu meshes", m_meshes.size());
}

// Reset BOTH global transform AND per-mesh transforms (Reset-All button).
// Saves a single undo snapshot before mutating.
void Renderer::resetAllTransforms(){
    pushUndoState();
    resetTransform();
    for(auto& mo : m_meshes){
        mo.rotX=mo.rotY=mo.rotZ=0;
        mo.posX=mo.posY=mo.posZ=0;
        mo.scaX=mo.scaY=mo.scaZ=1;
    }
}

// GL thread: load pre-separated components from JNI bridge onto GPU
bool Renderer::loadSeparatedComponents(std::vector<MeshComponent>& comps){
    if(comps.empty()) return false;
    // Free old GPU objects
    for(auto& mo:m_meshes){
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }
    m_meshes.clear();

    static const float kColors[][3] = {
        {0.0f,0.83f,1.0f},{1.0f,0.44f,0.26f},{0.30f,0.69f,0.51f},
        {1.0f,0.84f,0.31f},{0.67f,0.48f,0.74f},{0.93f,0.25f,0.48f},
        {0.15f,0.78f,0.85f},{0.83f,0.88f,0.34f}
    };
    const int NC = (int)(sizeof(kColors)/sizeof(kColors[0]));

    for(int i=0;i<(int)comps.size();++i){
        auto& comp = comps[i];
        if(comp.vertices.empty()) continue;
        MeshObject mo;
        mo.name   = "Mesh_" + std::to_string(i+1);
        mo.colorR = kColors[i%NC][0];
        mo.colorG = kColors[i%NC][1];
        mo.colorB = kColors[i%NC][2];
        mo.vertices = std::move(comp.vertices);
        mo.indices.resize(comp.indices.size());
        for(size_t k=0;k<comp.indices.size();++k)
            mo.indices[k] = (unsigned int)comp.indices[k];
        uploadMeshObject(mo);
        m_meshes.push_back(std::move(mo));
    }
    m_isSeparated  = true;
    m_selectedMesh = -1;
    // Free raw data — separation complete
    m_rawVertices.clear(); m_rawVertices.shrink_to_fit();
    m_rawIndices.clear();  m_rawIndices.shrink_to_fit();
    LOGI("loadSeparatedComponents: %d meshes on GPU", (int)m_meshes.size());
    return !m_meshes.empty();
}

// ── Load model ───────────────────────────────────────────────────────────────
bool Renderer::loadModel(const std::string& path){
    ModelData md;
    if(!ModelLoader::load(path,md)) return false;
    m_origWmm=md.widthMM(); m_origHmm=md.heightMM(); m_origDmm=md.depthMM();
    m_normalizeScale=md.normalizeScale;
    separateIntoMeshes(md);
    m_hasModel=!m_meshes.empty();
    m_selectedMesh=-1;
    resetTransform(); resetCamera(); clearRuler();
    return m_hasModel;
}

// ── Mesh management ───────────────────────────────────────────────────────────
void Renderer::getMeshName(int idx,char* buf,int bufLen) const {
    if(idx<0||idx>=(int)m_meshes.size()){snprintf(buf,bufLen,"?");return;}
    snprintf(buf,bufLen,"%s",m_meshes[idx].name.c_str());
}
void Renderer::selectMesh(int idx){
    // Clamp to valid range or -1 (no selection). idx == -1 deselects all.
    if(idx < -1 || idx >= (int)m_meshes.size()) idx = -1;
    m_selectedMesh=idx;
    for(int i=0;i<(int)m_meshes.size();++i) m_meshes[i].selected=(i==idx);
}
void Renderer::deleteMesh(int idx){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    auto& mo=m_meshes[idx];
    if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
    if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
    if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    m_meshes.erase(m_meshes.begin()+idx);
    m_selectedMesh=-1;
    if(m_meshes.empty()) m_hasModel=false;
}
void Renderer::setMeshVisible(int idx,bool v){
    if(idx>=0&&idx<(int)m_meshes.size()) m_meshes[idx].visible=v;
}
bool Renderer::getMeshVisible(int idx) const {
    if(idx<0||idx>=(int)m_meshes.size()) return false;
    return m_meshes[idx].visible;
}
void Renderer::setMeshColor(int idx,float r,float g,float b){
    if(idx>=0&&idx<(int)m_meshes.size()){ m_meshes[idx].colorR=r;m_meshes[idx].colorG=g;m_meshes[idx].colorB=b; }
}
void Renderer::setMeshScaleMM(int idx,float w,float h,float d){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    auto& mo=m_meshes[idx];
    // Compute mesh original size from its vertices
    float minX=FLT_MAX,minY=FLT_MAX,minZ=FLT_MAX;
    float maxX=-FLT_MAX,maxY=-FLT_MAX,maxZ=-FLT_MAX;
    for(auto& v:mo.vertices){
        minX=std::min(minX,v.px);maxX=std::max(maxX,v.px);
        minY=std::min(minY,v.py);maxY=std::max(maxY,v.py);
        minZ=std::min(minZ,v.pz);maxZ=std::max(maxZ,v.pz);
    }
    float sx=maxX-minX, sy=maxY-minY, sz=maxZ-minZ;
    // sx is in normalized units; convert to mm via normalizeScale
    float uToMM = (m_normalizeScale>1e-9f)? (1.0f/m_normalizeScale) : 1.0f;
    // origMeshMm = sx/normalizeScale * unitToMM... simplified:
    // desired world size = scaX * sx → desired world * uToMM/m_origWmm… 
    // Simpler: scaX = desired_mm / (sx * mmPerUnit)
    float mmPerUnit = (m_origWmm>1e-9f)?m_origWmm/2.0f:1.0f;
    if(sx>1e-9f) mo.scaX=w/(sx*mmPerUnit);
    if(sy>1e-9f) mo.scaY=h/(sy*mmPerUnit);
    if(sz>1e-9f) mo.scaZ=d/(sz*mmPerUnit);
}
void Renderer::getMeshSizeMM(int idx,float& w,float& h,float& d) const {
    if(idx<0||idx>=(int)m_meshes.size()){w=h=d=0;return;}
    const auto& mo=m_meshes[idx];
    float minX=FLT_MAX,minY=FLT_MAX,minZ=FLT_MAX;
    float maxX=-FLT_MAX,maxY=-FLT_MAX,maxZ=-FLT_MAX;
    for(auto& v:mo.vertices){
        minX=std::min(minX,v.px);maxX=std::max(maxX,v.px);
        minY=std::min(minY,v.py);maxY=std::max(maxY,v.py);
        minZ=std::min(minZ,v.pz);maxZ=std::max(maxZ,v.pz);
    }
    float mmPerUnit=(m_origWmm>1e-9f)?m_origWmm/2.0f:1.0f;
    w=(maxX-minX)*fabsf(mo.scaX)*mmPerUnit;
    h=(maxY-minY)*fabsf(mo.scaY)*mmPerUnit;
    d=(maxZ-minZ)*fabsf(mo.scaZ)*mmPerUnit;
}

// ── Per-mesh independent transforms (Phase 2 Transform Tool) ────────────────
// These mutate ONLY the per-mesh transform fields — global m_rot/m_pos are
// untouched, so the overall scene transform stays put while the user nudges
// just the long-pressed mesh.  Undo state is pushed by the JNI layer at slider
// DOWN, mirroring the convention used by EditorPanelFragment.
void Renderer::setMeshRotation(int idx, float rx, float ry, float rz){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    auto& mo = m_meshes[idx];
    mo.rotX = rx; mo.rotY = ry; mo.rotZ = rz;
}
void Renderer::setMeshTranslation(int idx, float px, float py, float pz){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    auto& mo = m_meshes[idx];
    mo.posX = px; mo.posY = py; mo.posZ = pz;
}
void Renderer::getMeshTransform(int idx, float out9[9]) const {
    if(idx<0||idx>=(int)m_meshes.size()){
        out9[0]=out9[1]=out9[2]=0.f;
        out9[3]=out9[4]=out9[5]=0.f;
        out9[6]=out9[7]=out9[8]=1.f;
        return;
    }
    const auto& mo = m_meshes[idx];
    out9[0]=mo.rotX; out9[1]=mo.rotY; out9[2]=mo.rotZ;
    out9[3]=mo.posX; out9[4]=mo.posY; out9[5]=mo.posZ;
    out9[6]=mo.scaX; out9[7]=mo.scaY; out9[8]=mo.scaZ;
}
void Renderer::resetMeshTransform(int idx){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    auto& mo = m_meshes[idx];
    mo.rotX = mo.rotY = mo.rotZ = 0.f;
    mo.posX = mo.posY = mo.posZ = 0.f;
    mo.scaX = mo.scaY = mo.scaZ = 1.f;
}

// Ray-pick the front-most mesh under the given screen-space point.
// Returns the mesh index (suitable for selectMesh), or -1 if nothing was hit.
// Implementation walks every triangle of every visible mesh through the
// shared screenToRay/rayTriangle helpers (Möller–Trumbore), tracking the
// closest valid hit by tParam.  O(T) per call but only fires on long-press,
// so cost is dwarfed by frame budget.
int Renderer::pickMesh(float sx, float sy, float sw, float sh){
    if(m_meshes.empty()) return -1;
    Ray ray = screenToRay(sx, sy, sw, sh);
    Mat4 global = buildGlobalMatrix();
    float bestT = FLT_MAX;
    int   bestMesh = -1;

    for(size_t mi = 0; mi < m_meshes.size(); ++mi){
        const auto& mo = m_meshes[mi];
        if(!mo.visible || !mo.gpuReady) continue;
        Mat4 model = global * buildMeshMatrix(mo);
        auto tfm = [&](const Vertex& v) -> Vec3 {
            return { model.m[0]*v.px + model.m[4]*v.py + model.m[8] *v.pz + model.m[12],
                     model.m[1]*v.px + model.m[5]*v.py + model.m[9] *v.pz + model.m[13],
                     model.m[2]*v.px + model.m[6]*v.py + model.m[10]*v.pz + model.m[14] };
        };
        for(size_t i = 0; i + 2 < mo.indices.size(); i += 3){
            float t;
            if(rayTriangle(ray,
                           tfm(mo.vertices[mo.indices[i  ]]),
                           tfm(mo.vertices[mo.indices[i+1]]),
                           tfm(mo.vertices[mo.indices[i+2]]),
                           t)){
                if(t < bestT){ bestT = t; bestMesh = (int)mi; }
            }
        }
    }
    return bestMesh;
}

// ── Size ─────────────────────────────────────────────────────────────────────
void Renderer::getModelSizeMM(float& w,float& h,float& d) const {w=m_origWmm;h=m_origHmm;d=m_origDmm;}
void Renderer::getCurrentSizeMM(float& w,float& h,float& d) const {
    w=fabsf(m_scaX)*m_origWmm; h=fabsf(m_scaY)*m_origHmm; d=fabsf(m_scaZ)*m_origDmm;
}

// ── Export helpers ────────────────────────────────────────────────────────────
// Transform a 3D point by a column-major 4x4 matrix (with perspective divide)
static Vec3 applyMat4Point(const Mat4& mat, float x, float y, float z) {
    return {
        mat.m[0]*x + mat.m[4]*y + mat.m[8]*z  + mat.m[12],
        mat.m[1]*x + mat.m[5]*y + mat.m[9]*z  + mat.m[13],
        mat.m[2]*x + mat.m[6]*y + mat.m[10]*z + mat.m[14]
    };
}
// Transform a normal using the upper-left 3x3 of the matrix, then renormalize
static Vec3 applyMat4Normal(const Mat4& mat, float nx, float ny, float nz) {
    Vec3 n{
        mat.m[0]*nx + mat.m[4]*ny + mat.m[8]*nz,
        mat.m[1]*nx + mat.m[5]*ny + mat.m[9]*nz,
        mat.m[2]*nx + mat.m[6]*ny + mat.m[10]*nz
    };
    float len = sqrtf(n.x*n.x + n.y*n.y + n.z*n.z);
    if(len > 1e-9f){ n.x /= len; n.y /= len; n.z /= len; }
    return n;
}

// ── Export ───────────────────────────────────────────────────────────────────
bool Renderer::exportOBJ(const std::string& path) const {
    std::ofstream f(path);
    if(!f) return false;
    f << "# Exported by 3D Model Viewer\n";
    f << std::fixed; f.precision(6);

    Mat4 global = buildGlobalMatrix();
    int baseVertex = 1;

    for(const auto& mo : m_meshes){
        if(!mo.visible) continue;
        f << "o " << mo.name << "\n";

        // Combined transform: global(userScale/rot/trans) * meshTransform * mmConversion
        // mmConversion converts from normalized [-1,1] coords back to real-world mm units.
        // This ensures exported files are correctly sized (1 OBJ unit = 1 mm).
        float toMM = (m_normalizeScale > 1e-9f) ? (1.0f / m_normalizeScale) : 1.0f;
        Mat4 mmConv = Mat4::scale(toMM, toMM, toMM);
        Mat4 model  = global * buildMeshMatrix(mo) * mmConv;

        for(const auto& v : mo.vertices){
            Vec3 p = applyMat4Point(model, v.px, v.py, v.pz);
            f << "v " << p.x << " " << p.y << " " << p.z << "\n";
        }
        for(const auto& v : mo.vertices){
            Vec3 n = applyMat4Normal(model, v.nx, v.ny, v.nz);
            f << "vn " << n.x << " " << n.y << " " << n.z << "\n";
        }
        for(size_t i = 0; i+2 < mo.indices.size(); i+=3){
            int a = (int)mo.indices[i+0] + baseVertex;
            int b = (int)mo.indices[i+1] + baseVertex;
            int c = (int)mo.indices[i+2] + baseVertex;
            f << "f " << a << "//" << a << " " << b << "//" << b << " " << c << "//" << c << "\n";
        }
        baseVertex += (int)mo.vertices.size();
    }
    return true;
}

bool Renderer::exportSTL(const std::string& path) const {
    std::ofstream f(path, std::ios::binary);
    if(!f) return false;

    Mat4 global = buildGlobalMatrix();

    // Count total visible triangles
    uint32_t total = 0;
    for(const auto& mo : m_meshes) if(mo.visible) total += (uint32_t)(mo.indices.size()/3);

    char header[80] = "3D Model Viewer Export";
    f.write(header, 80);
    f.write(reinterpret_cast<const char*>(&total), 4);

    for(const auto& mo : m_meshes){
        if(!mo.visible) continue;
        float toMM = (m_normalizeScale > 1e-9f) ? (1.0f / m_normalizeScale) : 1.0f;
        Mat4 mmConv = Mat4::scale(toMM, toMM, toMM);
        Mat4 model  = global * buildMeshMatrix(mo) * mmConv;

        for(size_t i = 0; i+2 < mo.indices.size(); i+=3){
            const auto& v0 = mo.vertices[mo.indices[i+0]];
            const auto& v1 = mo.vertices[mo.indices[i+1]];
            const auto& v2 = mo.vertices[mo.indices[i+2]];

            // Transform positions by the full model matrix
            Vec3 p0 = applyMat4Point(model, v0.px, v0.py, v0.pz);
            Vec3 p1 = applyMat4Point(model, v1.px, v1.py, v1.pz);
            Vec3 p2 = applyMat4Point(model, v2.px, v2.py, v2.pz);

            // Recompute face normal from transformed positions
            Vec3 e1{p1.x-p0.x, p1.y-p0.y, p1.z-p0.z};
            Vec3 e2{p2.x-p0.x, p2.y-p0.y, p2.z-p0.z};
            Vec3 n = e1.cross(e2).normalized();

            float nf[3]  = {n.x,  n.y,  n.z};
            float pf0[3] = {p0.x, p0.y, p0.z};
            float pf1[3] = {p1.x, p1.y, p1.z};
            float pf2[3] = {p2.x, p2.y, p2.z};
            uint16_t att = 0;
            f.write(reinterpret_cast<const char*>(nf),  12);
            f.write(reinterpret_cast<const char*>(pf0), 12);
            f.write(reinterpret_cast<const char*>(pf1), 12);
            f.write(reinterpret_cast<const char*>(pf2), 12);
            f.write(reinterpret_cast<const char*>(&att), 2);
        }
    }
    return true;
}


// ══════════════════════════════════════════════════════════════════════════════
// MESH PROCESSING ENGINE
// Algorithms derived from MeshLab (Quadric Simplification) and
// OpenSCAD (edge detection, winding, polygon validation) principles.
// Adapted as self-contained C++ for Android NDK (no VCG/CGAL dependency).
// ══════════════════════════════════════════════════════════════════════════════

// ── Symmetric 4×4 quadric matrix (Garland-Heckbert 1997) ─────────────────────
// Stores Q = Σ(plane_i^T * plane_i) for each vertex.
// plane = [a,b,c,d] where ax+by+cz+d=0.
// Q is symmetric: only 10 unique entries stored.
struct Quadric4 {
    double a2,ab,ac,ad, b2,bc,bd, c2,cd, d2;
    Quadric4() : a2(0),ab(0),ac(0),ad(0),b2(0),bc(0),bd(0),c2(0),cd(0),d2(0) {}

    void addPlane(float a, float b, float c, float d) {
        a2+=a*a; ab+=a*b; ac+=a*c; ad+=a*d;
        b2+=b*b; bc+=b*c; bd+=b*d;
        c2+=c*c; cd+=c*d;
        d2+=d*d;
    }
    void operator+=(const Quadric4& o) {
        a2+=o.a2; ab+=o.ab; ac+=o.ac; ad+=o.ad;
        b2+=o.b2; bc+=o.bc; bd+=o.bd;
        c2+=o.c2; cd+=o.cd;
        d2+=o.d2;
    }
    // Evaluate quadric error for point (x,y,z)
    double eval(float x, float y, float z) const {
        double v = a2*x*x + 2*ab*x*y + 2*ac*x*z + 2*ad*x
                 + b2*y*y + 2*bc*y*z + 2*bd*y
                 + c2*z*z + 2*cd*z
                 + d2;
        return v < 0.0 ? 0.0 : v;
    }
    // Optimal collapse position: solve 3x3 linear system
    // [2a2  2ab  2ac] [x]   [-2ad]
    // [2ab  2b2  2bc] [y] = [-2bd]
    // [2ac  2bc  2c2] [z]   [-2cd]
    bool optimalPosition(float& ox, float& oy, float& oz) const {
        // Cramer's rule on the 3x3 system
        double det = 2*(2*a2*(b2*c2 - bc*bc) - 2*ab*(ab*c2 - bc*ac) + 2*ac*(ab*bc - b2*ac));
        if (std::fabs(det) < 1e-15) return false;
        double invDet = 1.0 / det;
        ox = (float)((-2*ad*(b2*c2-bc*bc) + 2*ab*(bd*c2-bc*cd) - 2*ac*(bd*bc-b2*cd)) * invDet);
        oy = (float)((2*a2*(-bd*c2+cd*bc) + (-2*ad)*(ab*c2-bc*ac) + 2*ac*(ab*cd-bd*ac)) * invDet);
        oz = (float)((2*a2*(bc*bd-b2*cd) - 2*ab*(ab*bd-bd*ac) + (-2*ad)*(ab*bc-b2*ac)) * invDet);
        return true;
    }
};

// ── Quadric Error Metric Mesh Decimation ──────────────────────────────────────
// Based on: Garland & Heckbert "Surface Simplification Using Quadric Error Metrics"
// Adapted from MeshLab's quadric_simp.cpp algorithm.
//
// Steps:
// 1. Compute per-vertex quadric: sum of face-plane quadrics at all adjacent faces
// 2. For each edge, compute collapse cost = Q(v1)+Q(v2) evaluated at optimal point
// 3. Priority queue: collapse minimum-cost edge, update neighbors, repeat
// 4. Stop at targetFaceCount
//
// This is O(n log n) using a priority queue.
// File-scope EdgeCollapse (must be outside function for use with std::priority_queue)
struct EdgeCollapse {
    double   cost;
    uint32_t v1, v2;
    float    ox, oy, oz;
    bool operator>(const EdgeCollapse& o) const { return cost > o.cost; }
};

bool Renderer::decimateMesh(int meshIdx, float targetPercent) {
    if (meshIdx < 0 || meshIdx >= (int)m_meshes.size()) return false;
    auto& mo = m_meshes[meshIdx];
    const size_t N  = mo.vertices.size();
    const size_t nT = mo.indices.size() / 3;
    if (N < 4 || nT < 2) return false;

    size_t targetFaces = std::max((size_t)4, (size_t)(nT * targetPercent));
    if (targetFaces >= nT) return true;  // nothing to do

    LOGI("Decimating mesh %d: %zu tris → %zu tris (%.0f%%)",
         meshIdx, nT, targetFaces, targetPercent*100.f);

    // Build adjacency: vertex → list of face indices
    std::vector<std::vector<uint32_t>> v2f(N);
    for (size_t i = 0; i < nT; i++) {
        v2f[mo.indices[i*3+0]].push_back((uint32_t)i);
        v2f[mo.indices[i*3+1]].push_back((uint32_t)i);
        v2f[mo.indices[i*3+2]].push_back((uint32_t)i);
    }

    // Compute per-vertex quadrics from adjacent face planes
    std::vector<Quadric4> Q(N);
    std::vector<bool> faceAlive(nT, true);
    std::vector<bool> vertAlive(N, true);

    auto addFaceToQuadrics = [&](size_t fi) {
        uint32_t i0=mo.indices[fi*3+0], i1=mo.indices[fi*3+1], i2=mo.indices[fi*3+2];
        const auto& v0=mo.vertices[i0]; const auto& v1=mo.vertices[i1]; const auto& v2=mo.vertices[i2];
        float ax=v1.px-v0.px, ay=v1.py-v0.py, az=v1.pz-v0.pz;
        float bx=v2.px-v0.px, by=v2.py-v0.py, bz=v2.pz-v0.pz;
        float nx=ay*bz-az*by, ny=az*bx-ax*bz, nz=ax*by-ay*bx;
        float len=sqrtf(nx*nx+ny*ny+nz*nz);
        if (len<1e-12f) return;
        nx/=len; ny/=len; nz/=len;
        float d = -(nx*v0.px + ny*v0.py + nz*v0.pz);
        Q[i0].addPlane(nx,ny,nz,d);
        Q[i1].addPlane(nx,ny,nz,d);
        Q[i2].addPlane(nx,ny,nz,d);
    };
    for (size_t i=0;i<nT;i++) addFaceToQuadrics(i);

    // Compute edge collapse cost
    auto computeCollapse = [&](uint32_t a, uint32_t b) -> EdgeCollapse {
        EdgeCollapse ec; ec.v1=a; ec.v2=b;
        Quadric4 qab = Q[a]; qab += Q[b];
        float mx=(mo.vertices[a].px+mo.vertices[b].px)*0.5f;
        float my=(mo.vertices[a].py+mo.vertices[b].py)*0.5f;
        float mz=(mo.vertices[a].pz+mo.vertices[b].pz)*0.5f;
        if (!qab.optimalPosition(ec.ox, ec.oy, ec.oz)) { ec.ox=mx; ec.oy=my; ec.oz=mz; }
        ec.cost = qab.eval(ec.ox, ec.oy, ec.oz);
        return ec;
    };

    // Build edge set (unique undirected edges)
    std::set<std::pair<uint32_t,uint32_t>> edgeSet;
    for (size_t i=0;i<nT;i++) {
        for (int k=0;k<3;k++) {
            uint32_t a=mo.indices[i*3+k], b=mo.indices[i*3+(k+1)%3];
            if (a>b) std::swap(a,b);
            edgeSet.insert({a,b});
        }
    }

    // Priority queue: min cost first
    std::priority_queue<EdgeCollapse, std::vector<EdgeCollapse>, std::greater<EdgeCollapse>> pq;
    for (auto& e : edgeSet) pq.push(computeCollapse(e.first, e.second));

    // Collapse loop
    size_t liveFaces = nT;
    std::vector<uint32_t> remap(N);
    std::iota(remap.begin(), remap.end(), 0u);  // identity

    // Find canonical vertex (path-compressed union-find)
    std::function<uint32_t(uint32_t)> root = [&](uint32_t x) -> uint32_t {
        while (remap[x] != x) { remap[x]=remap[remap[x]]; x=remap[x]; }
        return x;
    };

    int iters=0, maxIters=(int)(nT*3);
    while (liveFaces > targetFaces && !pq.empty() && iters++ < maxIters) {
        EdgeCollapse ec = pq.top(); pq.pop();
        uint32_t a = root(ec.v1), b = root(ec.v2);
        if (a==b) continue;
        if (!vertAlive[a] || !vertAlive[b]) continue;

        // Perform collapse: merge b into a, place a at optimal position
        mo.vertices[a].px = ec.ox;
        mo.vertices[a].py = ec.oy;
        mo.vertices[a].pz = ec.oz;
        Q[a] += Q[b];
        remap[b] = a;
        vertAlive[b] = false;

        // Rewrite faces: replace b with a, mark degenerate faces dead
        for (uint32_t fi : v2f[b]) {
            if (!faceAlive[fi]) continue;
            bool hasDegen = false;
            for (int k=0;k<3;k++) {
                uint32_t rv = root(mo.indices[fi*3+k]);
                mo.indices[fi*3+k] = rv;
            }
            // Degenerate if any two vertices equal
            if (mo.indices[fi*3+0]==mo.indices[fi*3+1] ||
                mo.indices[fi*3+1]==mo.indices[fi*3+2] ||
                mo.indices[fi*3+0]==mo.indices[fi*3+2]) {
                faceAlive[fi]=false; --liveFaces; hasDegen=true;
            }
            if (!hasDegen) v2f[a].push_back(fi);
        }

        // Push new collapse candidates for a's edges
        std::set<uint32_t> neighbors;
        for (uint32_t fi : v2f[a]) {
            if (!faceAlive[fi]) continue;
            for (int k=0;k<3;k++) {
                uint32_t nb = root(mo.indices[fi*3+k]);
                if (nb != a) neighbors.insert(nb);
            }
        }
        for (uint32_t nb : neighbors) {
            if (vertAlive[nb]) pq.push(computeCollapse(a, nb));
        }
    }

    // Compact: rebuild vertex + index arrays removing dead entries
    std::vector<uint32_t> newIdx(N, UINT32_MAX);
    std::vector<Vertex> newVerts;
    newVerts.reserve(liveFaces * 2);
    for (size_t i=0;i<N;i++) {
        if (vertAlive[i] && root((uint32_t)i)==(uint32_t)i) {
            newIdx[i] = (uint32_t)newVerts.size();
            newVerts.push_back(mo.vertices[i]);
        }
    }
    std::vector<unsigned int> newFaces;
    newFaces.reserve(liveFaces*3);
    for (size_t i=0;i<nT;i++) {
        if (!faceAlive[i]) continue;
        uint32_t ia=root(mo.indices[i*3+0]);
        uint32_t ib=root(mo.indices[i*3+1]);
        uint32_t ic=root(mo.indices[i*3+2]);
        if (newIdx[ia]==UINT32_MAX||newIdx[ib]==UINT32_MAX||newIdx[ic]==UINT32_MAX) continue;
        if (ia==ib||ib==ic||ia==ic) continue;
        newFaces.push_back(newIdx[ia]);
        newFaces.push_back(newIdx[ib]);
        newFaces.push_back(newIdx[ic]);
    }

    mo.vertices = std::move(newVerts);
    mo.indices  = std::move(newFaces);

    // Rebuild GPU buffers
    if (mo.vao) glDeleteVertexArrays(1,&mo.vao);
    if (mo.vbo) glDeleteBuffers(1,&mo.vbo);
    if (mo.ibo) glDeleteBuffers(1,&mo.ibo);
    mo.vao=mo.vbo=mo.ibo=0; mo.gpuReady=false;
    uploadMeshObject(mo);

    regenerateNormals(mo);
    updateMeshVBO(mo);

    LOGI("Decimation done: %zu→%zu verts, %zu→%zu tris",
         N, mo.vertices.size(), nT, mo.indices.size()/3);
    return true;
}

// ── Mesh Statistics (MeshLab-style) ──────────────────────────────────────────
// Computes: surface area, enclosed volume (signed, using divergence theorem),
// bounding box dims, edge count, and watertight check.
void Renderer::getMeshStats(int meshIdx, MeshStats& out) const {
    out = MeshStats{};
    if (meshIdx < 0 || meshIdx >= (int)m_meshes.size()) return;
    const auto& mo = m_meshes[meshIdx];
    const size_t nT = mo.indices.size() / 3;
    if (mo.vertices.empty()) return;

    float toMM = (m_normalizeScale>1e-9f) ? (1.f/m_normalizeScale) : 1.f;

    // Bounding box
    float mnX=FLT_MAX,mnY=FLT_MAX,mnZ=FLT_MAX;
    float mxX=-FLT_MAX,mxY=-FLT_MAX,mxZ=-FLT_MAX;
    for (const auto& v : mo.vertices) {
        mnX=std::min(mnX,v.px); mxX=std::max(mxX,v.px);
        mnY=std::min(mnY,v.py); mxY=std::max(mxY,v.py);
        mnZ=std::min(mnZ,v.pz); mxZ=std::max(mxZ,v.pz);
    }
    out.bboxW = (mxX-mnX)*toMM;
    out.bboxH = (mxY-mnY)*toMM;
    out.bboxD = (mxZ-mnZ)*toMM;
    out.vertCount = (int)mo.vertices.size();
    out.triCount  = (int)nT;

    // Surface area + signed volume (divergence theorem)
    // area = Σ |e1 × e2| / 2
    // vol  = Σ (v0 · (v1 × v2)) / 6
    double area = 0, vol = 0;
    // Edge count (unique) and manifold check
    std::map<std::pair<uint32_t,uint32_t>,int> edgeCnt;
    for (size_t i=0;i<nT;i++) {
        uint32_t i0=mo.indices[i*3+0], i1=mo.indices[i*3+1], i2=mo.indices[i*3+2];
        const auto& v0=mo.vertices[i0]; const auto& v1=mo.vertices[i1]; const auto& v2=mo.vertices[i2];
        float ax=v1.px-v0.px, ay=v1.py-v0.py, az=v1.pz-v0.pz;
        float bx=v2.px-v0.px, by=v2.py-v0.py, bz=v2.pz-v0.pz;
        float cx=ay*bz-az*by, cy=az*bx-ax*bz, cz=ax*by-ay*bx;
        area += sqrtf(cx*cx+cy*cy+cz*cz) * 0.5;
        vol  += (v0.px*(double)(v1.py*v2.pz-v1.pz*v2.py)
               + v0.py*(double)(v1.pz*v2.px-v1.px*v2.pz)
               + v0.pz*(double)(v1.px*v2.py-v1.py*v2.px)) / 6.0;
        // Edge manifold check
        for (int k=0;k<3;k++) {
            uint32_t ea=mo.indices[i*3+k], eb=mo.indices[i*3+(k+1)%3];
            edgeCnt[{ea,eb}]++;
        }
    }
    // Convert to mm² and mm³
    double toMM2 = (double)toMM*(double)toMM;
    double toMM3 = toMM2*(double)toMM;
    out.surfaceAreaMM2 = (float)(area * toMM2);
    out.volumeMM3      = (float)(std::fabs(vol) * toMM3);
    out.edgeCount      = (int)edgeCnt.size();

    // Watertight = every edge appears exactly twice (once in each direction)
    out.isWatertight = true;
    for (auto& kv : edgeCnt) {
        if (kv.second != 1) { out.isWatertight = false; break; }
        // Check reverse edge
        auto rev = edgeCnt.find({kv.first.second, kv.first.first});
        if (rev == edgeCnt.end() || rev->second != 1) { out.isWatertight=false; break; }
    }
}

// ── Duplicate Vertex Weld (from OpenSCAD RemoveDuplicateVertex principle) ─────
// Merges vertices closer than epsilon using a spatial hash grid.
// Prevents z-fighting, fixes cracked seams.
int Renderer::weldVertices(int meshIdx, float epsilonMM) {
    if (meshIdx < 0 || meshIdx >= (int)m_meshes.size()) return 0;
    auto& mo = m_meshes[meshIdx];
    if (mo.vertices.empty()) return 0;

    float toNorm = (m_normalizeScale>1e-9f) ? m_normalizeScale : 1.f;
    float eps    = epsilonMM * toNorm;
    float cellSz = eps * 2.f;

    // Spatial hash: quantize position to grid, group nearby vertices
    using Cell = std::tuple<int,int,int>;
    struct CellHash {
        size_t operator()(const Cell& c) const {
            size_t h = 2166136261u;
            h ^= std::get<0>(c)*16777619u; h *= 16777619u;
            h ^= std::get<1>(c)*2246822519u; h *= 2246822519u;
            h ^= std::get<2>(c)*3266489917u;
            return h;
        }
    };
    std::unordered_map<Cell,std::vector<uint32_t>,CellHash> grid;
    const size_t N = mo.vertices.size();
    for (size_t i=0;i<N;i++) {
        const auto& v = mo.vertices[i];
        int cx=(int)std::floor(v.px/cellSz);
        int cy=(int)std::floor(v.py/cellSz);
        int cz=(int)std::floor(v.pz/cellSz);
        grid[{cx,cy,cz}].push_back((uint32_t)i);
    }

    std::vector<uint32_t> remap(N);
    std::iota(remap.begin(), remap.end(), 0u);
    int merged=0;

    for (auto& kv : grid) {
        auto& group = kv.second;
        for (size_t a=0;a<group.size();a++) {
            for (size_t b=a+1;b<group.size();b++) {
                uint32_t ia=group[a], ib=group[b];
                const auto& va=mo.vertices[ia]; const auto& vb=mo.vertices[ib];
                float dx=va.px-vb.px, dy=va.py-vb.py, dz=va.pz-vb.pz;
                if (sqrtf(dx*dx+dy*dy+dz*dz) < eps) {
                    remap[ib]=ia; merged++;
                }
            }
        }
    }
    if (merged==0) return 0;

    // Path-compress remap
    for (size_t i=0;i<N;i++) {
        while (remap[remap[i]] != remap[i]) remap[i]=remap[remap[i]];
    }

    // Rebuild compact vertex+index arrays
    std::vector<uint32_t> newIdx(N, UINT32_MAX);
    std::vector<Vertex>   newVerts;
    for (size_t i=0;i<N;i++) {
        if (remap[i]==i) { newIdx[i]=(uint32_t)newVerts.size(); newVerts.push_back(mo.vertices[i]); }
    }
    for (auto& idx : mo.indices) {
        idx = newIdx[remap[idx]];
    }
    mo.vertices = std::move(newVerts);

    // Rebuild GPU
    if (mo.vao) glDeleteVertexArrays(1,&mo.vao);
    if (mo.vbo) glDeleteBuffers(1,&mo.vbo);
    if (mo.ibo) glDeleteBuffers(1,&mo.ibo);
    mo.vao=mo.vbo=mo.ibo=0; mo.gpuReady=false;
    uploadMeshObject(mo);
    regenerateNormals(mo);
    updateMeshVBO(mo);

    LOGI("WeldVertices mesh %d: merged %d duplicates", meshIdx, merged);
    return merged;
}

// ── Zero-area face removal ─────────────────────────────────────────────────────
// From MeshLab's RemoveFaceOutOfRangeArea / OpenSCAD degenerate polygon removal
int Renderer::removeZeroAreaFaces(int meshIdx) {
    if (meshIdx < 0 || meshIdx >= (int)m_meshes.size()) return 0;
    auto& mo = m_meshes[meshIdx];
    const size_t nT = mo.indices.size()/3;
    std::vector<unsigned int> newIdx;
    newIdx.reserve(nT*3);
    int removed=0;
    for (size_t i=0;i<nT;i++) {
        uint32_t i0=mo.indices[i*3+0], i1=mo.indices[i*3+1], i2=mo.indices[i*3+2];
        if (i0==i1||i1==i2||i0==i2) { removed++; continue; }
        const auto& v0=mo.vertices[i0]; const auto& v1=mo.vertices[i1]; const auto& v2=mo.vertices[i2];
        float ax=v1.px-v0.px, ay=v1.py-v0.py, az=v1.pz-v0.pz;
        float bx=v2.px-v0.px, by=v2.py-v0.py, bz=v2.pz-v0.pz;
        float cx=ay*bz-az*by, cy=az*bx-ax*bz, cz=ax*by-ay*bx;
        if (cx*cx+cy*cy+cz*cz < 1e-20f) { removed++; continue; }
        newIdx.push_back(i0); newIdx.push_back(i1); newIdx.push_back(i2);
    }
    if (removed>0) {
        mo.indices = std::move(newIdx);
        regenerateNormals(mo);
        updateMeshVBO(mo);
        LOGI("RemoveZeroArea mesh %d: removed %d degenerate faces", meshIdx, removed);
    }
    return removed;
}



// ══════════════════════════════════════════════════════════════════════════════
// RING DEFORMATION ENGINE
// ══════════════════════════════════════════════════════════════════════════════

// ── Jacobi 3×3 symmetric eigenvalue decomposition ────────────────────────────
// On exit: eigenvalues in A[0][0], A[1][1], A[2][2] (ascending)
//          eigenvectors in columns of V
static void jacobiEigen3(float A[3][3], float V[3][3]) {
    // Identity for V
    for(int i=0;i<3;i++) for(int j=0;j<3;j++) V[i][j]=(i==j)?1.f:0.f;

    for(int iter=0;iter<64;iter++){
        // Find largest off-diagonal |A[p][q]|
        int p=0,q=1;
        float maxOff=fabsf(A[0][1]);
        if(fabsf(A[0][2])>maxOff){maxOff=fabsf(A[0][2]);p=0;q=2;}
        if(fabsf(A[1][2])>maxOff){maxOff=fabsf(A[1][2]);p=1;q=2;}
        if(maxOff<1e-12f) break;

        // Compute Givens angle
        float denom = A[q][q]-A[p][p];
        float theta = (fabsf(denom)<1e-30f) ? (float)M_PI/4.f
                      : 0.5f*atanf(2.f*A[p][q]/denom);
        float c=cosf(theta), s=sinf(theta);

        // Update symmetric matrix A ← G^T A G
        float newApp = c*c*A[p][p] + 2.f*s*c*A[p][q] + s*s*A[q][q];
        float newAqq = s*s*A[p][p] - 2.f*s*c*A[p][q] + c*c*A[q][q];
        float newApq = 0.f;

        for(int k=0;k<3;k++){
            if(k==p||k==q) continue;
            float Akp =  c*A[k][p] + s*A[k][q];
            float Akq = -s*A[k][p] + c*A[k][q];
            A[k][p]=A[p][k]=Akp; A[k][q]=A[q][k]=Akq;
        }
        A[p][p]=newApp; A[q][q]=newAqq; A[p][q]=A[q][p]=newApq;

        // Update eigenvector matrix V ← V G
        for(int k=0;k<3;k++){
            float Vkp =  c*V[k][p] + s*V[k][q];
            float Vkq = -s*V[k][p] + c*V[k][q];
            V[k][p]=Vkp; V[k][q]=Vkq;
        }
    }

    // Sort eigenpairs ascending by eigenvalue (bubble-sort 3 elements)
    float ev[3]={A[0][0],A[1][1],A[2][2]};
    int   idx[3]={0,1,2};
    for(int i=0;i<2;i++) for(int j=i+1;j<3;j++)
        if(ev[idx[i]]>ev[idx[j]]) { int t=idx[i];idx[i]=idx[j];idx[j]=t; }

    // Reorder V columns
    float Vs[3][3];
    for(int i=0;i<3;i++) for(int j=0;j<3;j++) Vs[j][i]=V[j][idx[i]];
    for(int i=0;i<3;i++) for(int j=0;j<3;j++) V[i][j]=Vs[i][j];
}
// ══════════════════════════════════════════════════════════════════════════════
// RING DEFORMATION ENGINE  v3 — Mathematically correct texture-preserving resize
// ══════════════════════════════════════════════════════════════════════════════
//
// THE MATHEMATICAL PROOF OF CORRECTNESS:
//
//  A ring can be described in cylindrical coordinates (r, θ, z_axial).
//  Surface detail (texture/carving) lives as perturbations Δr(θ,z) on top of
//  a base cylinder radius r_base(z).
//
//  For INNER DIAMETER change (keeping wall thickness constant):
//    Desired: shift the entire ring outward/inward by delta = newInnerR - origInnerR
//    Transform: r_new = r_orig + delta   ← UNIFORM SHIFT
//    Why this preserves texture:
//      If two adjacent vertices have r₁=innerR+Δr₁ and r₂=innerR+Δr₂,
//      after transform: r₁'=r₁+δ, r₂'=r₂+δ
//      Their relative difference (r₂-r₁) is UNCHANGED → no tearing
//
//  For BAND WIDTH change (keeping inner bore constant):
//    Desired: change wall thickness from origBand to newBand, inner bore fixed
//    Transform: r_new = origInnerR + (r_orig - origInnerR) × scale
//               where scale = newBand / origBand
//    Why this preserves texture:
//      Relative radial position within the wall: f = (r-innerR)/origBand ∈ [0,1]
//      r_new = innerR + f × newBand
//      Two adjacent vertices: f₁, f₂; after: r₁'=innerR+f₁×newBand, r₂'=innerR+f₂×newBand
//      Their new difference = (f₂-f₁)×newBand → uniform scale, no tearing
//
//  KEY INSIGHT: v1 and v2 used SMOOTHSTEP weights → different displacement
//  per vertex → neighboring vertices diverge → spikes seen in image.
//  v3 uses UNIFORM (inner dia) or LINEAR (band width) → no differential → no spikes.
//
//  BOTH operations restart from origVerts every time → zero cumulative error.
// ══════════════════════════════════════════════════════════════════════════════

// ── Area-weighted smooth normals ──────────────────────────────────────────────
void Renderer::regenerateNormals(MeshObject& mo) {
    const size_t N = mo.vertices.size();
    for (size_t i = 0; i < N; ++i) {
        mo.vertices[i].nx = 0.f;
        mo.vertices[i].ny = 0.f;
        mo.vertices[i].nz = 0.f;
    }
    const size_t triN = mo.indices.size();
    for (size_t i = 0; i + 2 < triN; i += 3) {
        const Vertex& v0 = mo.vertices[mo.indices[i+0]];
        const Vertex& v1 = mo.vertices[mo.indices[i+1]];
        const Vertex& v2 = mo.vertices[mo.indices[i+2]];
        float ax = v1.px-v0.px, ay = v1.py-v0.py, az = v1.pz-v0.pz;
        float bx = v2.px-v0.px, by = v2.py-v0.py, bz = v2.pz-v0.pz;
        // Cross product a×b — magnitude proportional to face area (no normalisation)
        float nx = ay*bz - az*by;
        float ny = az*bx - ax*bz;
        float nz = ax*by - ay*bx;
        for (int k = 0; k < 3; ++k) {
            Vertex& vk = mo.vertices[mo.indices[i+k]];
            vk.nx += nx; vk.ny += ny; vk.nz += nz;
        }
    }
    for (size_t i = 0; i < N; ++i) {
        Vertex& v = mo.vertices[i];
        float L = sqrtf(v.nx*v.nx + v.ny*v.ny + v.nz*v.nz);
        if (L > 1e-15f) { v.nx /= L; v.ny /= L; v.nz /= L; }
        else             { v.nx = 0.f; v.ny = 0.f; v.nz = 1.f; }
    }
}

// ── Stream vertices to GPU ────────────────────────────────────────────────────
void Renderer::updateMeshVBO(MeshObject& mo) {
    if (!mo.vbo || mo.vertices.empty()) return;
    glBindBuffer(GL_ARRAY_BUFFER, mo.vbo);
    glBufferData(GL_ARRAY_BUFFER,
                 (GLsizeiptr)(mo.vertices.size() * sizeof(Vertex)),
                 mo.vertices.data(), GL_DYNAMIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

// ── Low-level radial transform (core of all deformation) ─────────────────────
// Transforms each vertex's radial distance according to a user-supplied
// function object: r_new = mapR(r_orig).
// axial component (along ring axis) is NEVER touched.
// tangential direction (θ) is NEVER touched — only r changes.
// This is the ONLY correct way to resize a ring without tearing its texture.
template<typename RadialMap>
static void applyRadialMap(
    MeshObject&       mo,
    const std::vector<Vertex>& orig,   // always deform from backup
    const Vec3&       cen,             // ring centroid (on bore axis)
    const Vec3&       axis,            // unit ring axis
    const RadialMap&  mapR             // r_orig → r_new
) {
    const size_t N = orig.size();
    mo.vertices.resize(N);

    for (size_t i = 0; i < N; ++i) {
        const Vertex& src = orig[i];
        Vertex&       dst = mo.vertices[i];
        dst = src;  // copy UV and other fields

        // Decompose into axial + radial components
        float dx = src.px - cen.x;
        float dy = src.py - cen.y;
        float dz = src.pz - cen.z;

        float along = dx*axis.x + dy*axis.y + dz*axis.z;

        float rx = dx - along * axis.x;
        float ry = dy - along * axis.y;
        float rz = dz - along * axis.z;
        float r  = sqrtf(rx*rx + ry*ry + rz*rz);

        if (r < 1e-14f) {
            // Vertex exactly on axis — don't move (e.g. degenerate tri center)
            continue;
        }

        float rNew   = mapR(r);
        float scale  = rNew / r;   // only radial direction scaled

        dst.px = cen.x + along * axis.x + rx * scale;
        dst.py = cen.y + along * axis.y + ry * scale;
        dst.pz = cen.z + along * axis.z + rz * scale;
    }
}

// ── Ring Analysis v3 ──────────────────────────────────────────────────────────
// Robust axis + centroid from inner-bore vertices.
// Percentile radii: immune to stone carvings and outer texture complexity.
bool Renderer::analyzeRing(int meshIdx) {
    if (meshIdx < 0 || meshIdx >= (int)m_meshes.size()) return false;
    const MeshObject& mo = m_meshes[meshIdx];
    const size_t N = mo.vertices.size();
    if (N < 64) return false;

    // ── Pass 1: centroid ─────────────────────────────────────────────────────
    double cx=0,cy=0,cz=0;
    for (const auto& v:mo.vertices){ cx+=v.px; cy+=v.py; cz+=v.pz; }
    cx/=N; cy/=N; cz/=N;
    Vec3 cen{(float)cx,(float)cy,(float)cz};

    // ── Pass 2: PCA to get initial axis (smallest eigenvalue = through-hole) ─
    float C[3][3]={};
    for (const auto& v:mo.vertices){
        float d[3]={v.px-(float)cx,v.py-(float)cy,v.pz-(float)cz};
        for(int i=0;i<3;i++) for(int j=0;j<3;j++) C[i][j]+=d[i]*d[j];
    }
    float Vm[3][3]; jacobiEigen3(C,Vm);
    Vec3 axis{Vm[0][0],Vm[1][0],Vm[2][0]};
    float al=sqrtf(axis.x*axis.x+axis.y*axis.y+axis.z*axis.z);
    if(al<1e-9f) return false;
    axis.x/=al; axis.y/=al; axis.z/=al;

    // ── Pass 3: radial distances → median-based robust inner/outer radius ────
    // Median is more robust than percentile for rings with stone settings
    // because the stone creates a cluster of near-zero-radius vertices (prongs)
    // that corrupt the percentile estimate.
    std::vector<float> radii(N);
    float axMin=FLT_MAX,axMax=-FLT_MAX;
    for(size_t i=0;i<N;i++){
        const auto& v=mo.vertices[i];
        float dx=v.px-cen.x,dy=v.py-cen.y,dz=v.pz-cen.z;
        float along=dx*axis.x+dy*axis.y+dz*axis.z;
        axMin=std::min(axMin,along); axMax=std::max(axMax,along);
        float rx=dx-along*axis.x,ry=dy-along*axis.y,rz=dz-along*axis.z;
        radii[i]=sqrtf(rx*rx+ry*ry+rz*rz);
    }
    // Partial sort — only need 3rd/10th/90th/97th percentile values
    std::vector<float> sR=radii;
    std::sort(sR.begin(),sR.end());
    float p03=sR[(size_t)(N*0.03f)];
    float p10=sR[(size_t)(N*0.10f)];
    float p90=sR[(size_t)(N*0.90f)];
    float p97=sR[(size_t)(N*0.97f)];
    if(p97-p03<1e-7f) return false;

    // ── Pass 4: refine axis using INNER BORE vertices only ───────────────────
    // Inner bore is a clean circle → most stable for axis detection.
    // Reject stone settings (extreme inner/outer outliers) by using p10..p90 band.
    float boreCut=p10*1.5f;
    double icx=0,icy=0,icz=0; int nB=0;
    for(size_t i=0;i<N;i++){
        if(radii[i]<=boreCut){icx+=mo.vertices[i].px;icy+=mo.vertices[i].py;icz+=mo.vertices[i].pz;++nB;}
    }
    if(nB>=32){
        icx/=nB; icy/=nB; icz/=nB;
        Vec3 bCen{(float)icx,(float)icy,(float)icz};
        float Cb[3][3]={};
        for(size_t i=0;i<N;i++){
            if(radii[i]>boreCut) continue;
            float d[3]={mo.vertices[i].px-(float)icx,mo.vertices[i].py-(float)icy,mo.vertices[i].pz-(float)icz};
            for(int ii=0;ii<3;ii++) for(int jj=0;jj<3;jj++) Cb[ii][jj]+=d[ii]*d[jj];
        }
        float Vb[3][3]; jacobiEigen3(Cb,Vb);
        Vec3 axB{Vb[0][0],Vb[1][0],Vb[2][0]};
        float abL=sqrtf(axB.x*axB.x+axB.y*axB.y+axB.z*axB.z);
        if(abL>1e-9f){
            axB.x/=abL; axB.y/=abL; axB.z/=abL;
            float dot=fabsf(axB.x*axis.x+axB.y*axis.y+axB.z*axis.z);
            if(dot>0.766f){ // within 40° of coarse axis
                axis=axB; cen=bCen;
            }
        }
    }

    // ── Pass 5: recompute radii with refined axis ─────────────────────────────
    axMin=FLT_MAX; axMax=-FLT_MAX;
    for(size_t i=0;i<N;i++){
        const auto& v=mo.vertices[i];
        float dx=v.px-cen.x,dy=v.py-cen.y,dz=v.pz-cen.z;
        float along=dx*axis.x+dy*axis.y+dz*axis.z;
        axMin=std::min(axMin,along); axMax=std::max(axMax,along);
        float rx=dx-along*axis.x,ry=dy-along*axis.y,rz=dz-along*axis.z;
        radii[i]=sqrtf(rx*rx+ry*ry+rz*rz);
    }
    // Recompute percentiles
    std::copy(radii.begin(),radii.end(),sR.begin());
    std::sort(sR.begin(),sR.end());
    float innerR=sR[(size_t)(N*0.03f)];
    float outerR=sR[(size_t)(N*0.97f)];
    if(outerR-innerR<1e-7f) return false;

    // ── Store results ─────────────────────────────────────────────────────────
    m_ring.center=cen; m_ring.axis=axis;
    m_ring.innerR=innerR; m_ring.outerR=outerR;
    m_ring.origInnerR=innerR; m_ring.origOuterR=outerR;
    m_ring.currentInnerR=innerR; m_ring.currentOuterR=outerR;
    m_ring.heightAx=axMax-axMin;
    m_ring.valid=true; m_ring.meshIdx=meshIdx;
    m_ring.origVerts=mo.vertices;

    float toMM=(m_normalizeScale>1e-9f)?(1.f/m_normalizeScale):1.f;
    LOGI("Ring v3 (median): inner=%.3fmm outer=%.3fmm band=%.3fmm h=%.3fmm N=%zu borePts=%d",
         innerR*toMM,outerR*toMM,(outerR-innerR)*toMM,(axMax-axMin)*toMM,N,nB);
    return true;
}


bool Renderer::getRingParams(float out[6]) const {
    if (!m_ring.valid) return false;
    float toMM = (m_normalizeScale > 1e-9f) ? (1.f / m_normalizeScale) : 1.f;
    float curInnerMM = m_ring.currentInnerR * toMM;
    float curOuterMM = m_ring.currentOuterR * toMM;
    float curBandMM  = (m_ring.currentOuterR - m_ring.currentInnerR) * toMM;
    out[0] = curInnerMM;        // inner radius (mm)
    out[1] = curOuterMM;        // outer radius (mm)
    out[2] = curBandMM;         // band width = wall thickness (mm)
    out[3] = curInnerMM * 2.f;  // inner diameter (mm)
    out[4] = curOuterMM * 2.f;  // outer diameter (mm)
    out[5] = m_ring.heightAx * toMM;  // ring height (mm)
    return true;
}

// ── Set band width (wall thickness) ──────────────────────────────────────────
// Math: r_new = origInnerR + (r_orig - origInnerR) * scale
//       scale = newBand / origBand
// Effect: inner bore FIXED at origInnerR, outer wall moves to origInnerR + newBand
// Texture: each vertex retains SAME FRACTIONAL position within the wall
//          f = (r-innerR)/origBand  →  r_new = innerR + f*newBand
//          Adjacent vertices: same f difference → no tearing
void Renderer::setRingBandWidth(float newWidthMM) {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty()) return;

    float toNorm    = (m_normalizeScale > 1e-9f) ? m_normalizeScale : 1.f;
    float newWidthN = newWidthMM * toNorm;
    float origBandN = m_ring.origOuterR - m_ring.origInnerR;
    if (newWidthN < 1e-9f || origBandN < 1e-9f) return;

    float scale     = newWidthN / origBandN;
    float innerFixed = m_ring.origInnerR;   // inner bore NEVER changes

    auto& mo = m_meshes[m_ring.meshIdx];
    const Vec3& cen  = m_ring.center;
    const Vec3& axis = m_ring.axis;

    // Linear scale from inner bore surface
    applyRadialMap(mo, m_ring.origVerts, cen, axis,
        [innerFixed, scale](float r) -> float {
            float rRel = r - innerFixed;  // distance from inner bore
            return innerFixed + rRel * scale;
        });

    m_ring.currentInnerR = m_ring.origInnerR;
    m_ring.currentOuterR = m_ring.origInnerR + newWidthN;
    regenerateNormals(mo);
    updateMeshVBO(mo);
}

// ── Set inner diameter ────────────────────────────────────────────────────────
// Math: r_new = r_orig + delta    where delta = newInnerR - origInnerR
// Effect: UNIFORM radial shift of ALL vertices by the same delta
//         outer radius also shifts by delta → outer diameter changes too
//         This is correct: "ring size" is inner bore, everything else follows
// Texture: r₁_new - r₂_new = (r₁+δ) - (r₂+δ) = r₁-r₂ → UNCHANGED → no tearing
void Renderer::setRingInnerDiameter(float newDiamMM) {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty()) return;

    float toNorm    = (m_normalizeScale > 1e-9f) ? m_normalizeScale : 1.f;
    float newInnerN = (newDiamMM * 0.5f) * toNorm;
    float delta     = newInnerN - m_ring.origInnerR;  // positive = expand, negative = shrink

    auto& mo = m_meshes[m_ring.meshIdx];
    const Vec3& cen  = m_ring.center;
    const Vec3& axis = m_ring.axis;

    // Uniform radial shift — SAME delta for every vertex
    applyRadialMap(mo, m_ring.origVerts, cen, axis,
        [delta](float r) -> float {
            return std::max(r + delta, 1e-9f);  // guard against negative radius
        });

    m_ring.currentInnerR = newInnerN;
    m_ring.currentOuterR = m_ring.origOuterR + delta;  // outer shifts by same delta
    regenerateNormals(mo);
    updateMeshVBO(mo);
}

// ── Reset to original shape ───────────────────────────────────────────────────
void Renderer::resetRingDeformation() {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty()) return;
    auto& mo = m_meshes[m_ring.meshIdx];
    mo.vertices      = m_ring.origVerts;
    m_ring.currentInnerR = m_ring.origInnerR;
    m_ring.currentOuterR = m_ring.origOuterR;
    regenerateNormals(mo);
    updateMeshVBO(mo);
}

// ── applyRingDeformation (stub — kept for header compat, delegates above) ─────
void Renderer::applyRingDeformation(float newInnerN, float newOuterN) {
    // This entry point is no longer used; Band/InnerDia APIs call directly.
    // Kept to satisfy the header declaration.
    (void)newInnerN; (void)newOuterN;
}



// ══════════════════════════════════════════════════════════════════════════════
// FRUSTUM CULLING + BRUSH SCULPTING ENGINE
// ══════════════════════════════════════════════════════════════════════════════

Frustum Renderer::buildFrustum() const {
    float aspect=(float)m_width/std::max(m_height,1);
    Mat4 view=Mat4::lookAt(cameraEye(),Vec3{m_panX,m_panY,0.f},Vec3{0,1,0});
    Mat4 proj=Mat4::perspective(45.f*(3.14159265f/180.f),aspect,0.01f,100.f);
    Mat4 vp=proj*view;
    Frustum f;
    // Gribb-Hartmann frustum plane extraction
    for(int i=0;i<4;i++){f.planes[0][i]=vp.m[i*4+3]+vp.m[i*4+0]; // left
                           f.planes[1][i]=vp.m[i*4+3]-vp.m[i*4+0]; // right
                           f.planes[2][i]=vp.m[i*4+3]+vp.m[i*4+1]; // bottom
                           f.planes[3][i]=vp.m[i*4+3]-vp.m[i*4+1]; // top
                           f.planes[4][i]=vp.m[i*4+3]+vp.m[i*4+2]; // near
                           f.planes[5][i]=vp.m[i*4+3]-vp.m[i*4+2]; // far
    }
    for(int p=0;p<6;p++){
        float len=sqrtf(f.planes[p][0]*f.planes[p][0]+f.planes[p][1]*f.planes[p][1]+f.planes[p][2]*f.planes[p][2]);
        if(len>1e-9f) for(int i=0;i<4;i++) f.planes[p][i]/=len;
    }
    return f;
}

bool Renderer::frustumSphereTest(const Frustum& f,float cx,float cy,float cz,float r) const {
    for(int p=0;p<6;p++){
        float d=f.planes[p][0]*cx+f.planes[p][1]*cy+f.planes[p][2]*cz+f.planes[p][3];
        if(d < -r) return false;
    }
    return true;
}

bool Renderer::isMeshVisible(const MeshObject& mo,const Frustum& f) const {
    float cx=0.5f*(mo.bboxMin[0]+mo.bboxMax[0]);
    float cy=0.5f*(mo.bboxMin[1]+mo.bboxMax[1]);
    float cz=0.5f*(mo.bboxMin[2]+mo.bboxMax[2]);
    float dx=mo.bboxMax[0]-mo.bboxMin[0],dy=mo.bboxMax[1]-mo.bboxMin[1],dz=mo.bboxMax[2]-mo.bboxMin[2];
    return frustumSphereTest(f,cx,cy,cz,sqrtf(dx*dx+dy*dy+dz*dz)*0.55f);
}

// ── Brush adjacency ────────────────────────────────────────────────────────────
void Renderer::buildMeshAdjacency(const MeshObject& mo,
                                   std::vector<std::vector<uint32_t>>& adj) const {
    const size_t N=mo.vertices.size();
    adj.assign(N,{});
    for(size_t i=0;i+2<mo.indices.size();i+=3){
        uint32_t a=mo.indices[i],b=mo.indices[i+1],c2=mo.indices[i+2];
        adj[a].push_back(b); adj[a].push_back(c2);
        adj[b].push_back(a); adj[b].push_back(c2);
        adj[c2].push_back(a); adj[c2].push_back(b);
    }
}

static inline float gaussFalloff(float d2,float r2){
    float t=d2/r2; if(t>=1.f) return 0.f;
    float t3=t*t*t;
    return 1.f-t3*(10.f-t*(15.f-t*6.f));
}

// ── Smooth brush (Laplacian relaxation) ────────────────────────────────────────
void Renderer::applySmooth(int meshIdx,float wx,float wy,float wz,float radius,float intensity){
    if(meshIdx<0||meshIdx>=(int)m_meshes.size()) return;
    auto& mo=m_meshes[meshIdx];
    if(mo.vertices.empty()) return;
    float r2=radius*radius;
    std::vector<std::vector<uint32_t>> adj;
    buildMeshAdjacency(mo,adj);
    const size_t N=mo.vertices.size();
    // Collect affected vertices
    struct VW{size_t i;float w;};
    std::vector<VW> affected; affected.reserve(512);
    for(size_t i=0;i<N;i++){
        const auto& v=mo.vertices[i];
        float dx=v.px-wx,dy=v.py-wy,dz=v.pz-wz;
        float w=gaussFalloff(dx*dx+dy*dy+dz*dz,r2);
        if(w>0.001f) affected.push_back({i,w});
    }
    if(affected.empty()) return;
    // Laplacian step
    std::vector<Vec3> np(N);
    for(size_t i=0;i<N;i++) np[i]={mo.vertices[i].px,mo.vertices[i].py,mo.vertices[i].pz};
    for(auto& vw:affected){
        const auto& nb=adj[vw.i]; if(nb.empty()) continue;
        float cx=0,cy=0,cz=0;
        for(uint32_t n:nb){cx+=mo.vertices[n].px;cy+=mo.vertices[n].py;cz+=mo.vertices[n].pz;}
        float inv=1.f/(float)nb.size(); cx*=inv;cy*=inv;cz*=inv;
        float d=intensity*vw.w;
        np[vw.i].x+=d*(cx-np[vw.i].x);
        np[vw.i].y+=d*(cy-np[vw.i].y);
        np[vw.i].z+=d*(cz-np[vw.i].z);
    }
    for(auto& vw:affected){mo.vertices[vw.i].px=np[vw.i].x;mo.vertices[vw.i].py=np[vw.i].y;mo.vertices[vw.i].pz=np[vw.i].z;}
    regenerateNormals(mo); updateMeshVBO(mo);
}

// ── Sculpt brush (normal displacement: +1=raise, -1=lower) ────────────────────
void Renderer::applySculpt(int meshIdx,float wx,float wy,float wz,float radius,float intensity,float sign){
    if(meshIdx<0||meshIdx>=(int)m_meshes.size()) return;
    auto& mo=m_meshes[meshIdx];
    if(mo.vertices.empty()) return;
    float r2=radius*radius;
    const size_t N=mo.vertices.size();
    bool any=false;
    for(size_t i=0;i<N;i++){
        auto& v=mo.vertices[i];
        float dx=v.px-wx,dy=v.py-wy,dz=v.pz-wz;
        float w=gaussFalloff(dx*dx+dy*dy+dz*dz,r2);
        if(w<0.001f) continue;
        float d=sign*intensity*w;
        v.px+=d*v.nx; v.py+=d*v.ny; v.pz+=d*v.nz; any=true;
    }
    if(any){regenerateNormals(mo); updateMeshVBO(mo);}
}

// ── Raycasting ───────────────────────────────────────────────────────────────
Renderer::Ray Renderer::screenToRay(float sx,float sy,float sw,float sh) const {
    float ndcX=(2.0f*sx/sw)-1.0f;
    float ndcY=1.0f-(2.0f*sy/sh);
    float aspect=sw/std::max(sh,1.0f);
    float tanH=tanf(60.0f*DEG2RAD*0.5f);
    Vec3 eye=cameraEye();
    Vec3 center{m_panX,m_panY,0};
    Vec3 fwd=(center-eye).normalized();
    Vec3 right=fwd.cross({0,1,0}).normalized();
    Vec3 up=right.cross(fwd).normalized();
    Vec3 dir=(fwd+right*(ndcX*aspect*tanH)+up*(ndcY*tanH)).normalized();
    return {eye,dir};
}
bool Renderer::rayTriangle(const Ray& ray,const Vec3& v0,const Vec3& v1,const Vec3& v2,float& t) const {
    const float EPS=1e-7f;
    Vec3 e1=v1-v0,e2=v2-v0,h=ray.dir.cross(e2);
    float a=e1.dot(h); if(fabsf(a)<EPS) return false;
    float f=1.0f/a; Vec3 s=ray.origin-v0;
    float u=f*s.dot(h); if(u<0||u>1) return false;
    Vec3 q=s.cross(e1); float v=f*ray.dir.dot(q);
    if(v<0||u+v>1) return false;
    t=f*e2.dot(q); return t>EPS;
}
bool Renderer::pickPoint(float sx,float sy,float sw,float sh,float out[3]){
    if(m_meshes.empty()) return false;
    Ray ray=screenToRay(sx,sy,sw,sh);
    Mat4 global=buildGlobalMatrix();
    float bestT=FLT_MAX; bool hit=false;
    for(const auto& mo:m_meshes){
        if(!mo.visible) continue;
        Mat4 model=global*buildMeshMatrix(mo);
        auto tfm=[&](const Vertex& v)->Vec3{
            return {model.m[0]*v.px+model.m[4]*v.py+model.m[8]*v.pz+model.m[12],
                    model.m[1]*v.px+model.m[5]*v.py+model.m[9]*v.pz+model.m[13],
                    model.m[2]*v.px+model.m[6]*v.py+model.m[10]*v.pz+model.m[14]};
        };
        for(size_t i=0;i+2<mo.indices.size();i+=3){
            float t;
            if(rayTriangle(ray,tfm(mo.vertices[mo.indices[i]]),
                              tfm(mo.vertices[mo.indices[i+1]]),
                              tfm(mo.vertices[mo.indices[i+2]]),t)){
                if(t<bestT){bestT=t;hit=true;}
            }
        }
    }
    if(hit){out[0]=ray.origin.x+ray.dir.x*bestT;out[1]=ray.origin.y+ray.dir.y*bestT;out[2]=ray.origin.z+ray.dir.z*bestT;}
    return hit;
}

// ── Ruler ─────────────────────────────────────────────────────────────────────
void Renderer::setRulerPoints(bool h1,float* p1,bool h2,float* p2){
    m_rulerHasP1=h1; m_rulerHasP2=h2;
    if(h1&&p1) memcpy(m_rulerP1,p1,12);
    if(h2&&p2) memcpy(m_rulerP2,p2,12);
}
void Renderer::clearRuler(){m_rulerHasP1=m_rulerHasP2=false;}

// ── Undo/Redo ────────────────────────────────────────────────────────────────
void Renderer::pushUndoState(){
    m_undoStack.push_back(getTransform());
    if((int)m_undoStack.size()>MAX_UNDO) m_undoStack.erase(m_undoStack.begin());
    m_redoStack.clear();
}
void Renderer::undo(){
    if(m_undoStack.empty()) return;
    m_redoStack.push_back(getTransform());
    auto s=m_undoStack.back(); m_undoStack.pop_back();
    m_rotX=s.rotX;m_rotY=s.rotY;m_rotZ=s.rotZ;
    m_posX=s.posX;m_posY=s.posY;m_posZ=s.posZ;
    m_scaX=s.scaX;m_scaY=s.scaY;m_scaZ=s.scaZ;
}
void Renderer::redo(){
    if(m_redoStack.empty()) return;
    m_undoStack.push_back(getTransform());
    auto s=m_redoStack.back(); m_redoStack.pop_back();
    m_rotX=s.rotX;m_rotY=s.rotY;m_rotZ=s.rotZ;
    m_posX=s.posX;m_posY=s.posY;m_posZ=s.posZ;
    m_scaX=s.scaX;m_scaY=s.scaY;m_scaZ=s.scaZ;
}
TransformState Renderer::getTransform() const{
    return{m_rotX,m_rotY,m_rotZ,m_posX,m_posY,m_posZ,m_scaX,m_scaY,m_scaZ};
}

// ── Screenshot ───────────────────────────────────────────────────────────────
std::vector<uint8_t> Renderer::takeScreenshot(){
    std::vector<uint8_t> p((size_t)m_width*m_height*4);
    glReadPixels(0,0,m_width,m_height,GL_RGBA,GL_UNSIGNED_BYTE,p.data());
    int rb=m_width*4; std::vector<uint8_t> row((size_t)rb);
    for(int y=0;y<m_height/2;++y){
        uint8_t* top=p.data()+y*rb,*bot=p.data()+(m_height-1-y)*rb;
        memcpy(row.data(),top,rb); memcpy(top,bot,rb); memcpy(bot,row.data(),rb);
    }
    return p;
}

int Renderer::getMeshVertexCount(int idx) const {
    if (idx < 0 || idx >= (int)m_meshes.size()) return 0;
    return (int)m_meshes[idx].vertices.size();
}
