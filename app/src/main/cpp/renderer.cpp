#include "renderer.h"

// Rhino 3DM (openNURBS) — ONX_Model / ON_Mesh used by export3DM().
#include "opennurbs.h"
static inline Vec3 applyMat4Point(const Mat4& mat, float x, float y, float z);
static inline Vec3 applyMat4Normal(const Mat4& mat, float x, float y, float z);
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
#include <iomanip>
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
    if(m_gizVao)   glDeleteVertexArrays(1,&m_gizVao);
    if(m_gizVbo)   glDeleteBuffers(1,&m_gizVbo);
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

// ── Transform gizmo ───────────────────────────────────────────────────────────
void Renderer::setGizmoMode(int mode){
    if(mode<0||mode>3) mode=0;
    if(mode==m_gizmoMode) return;
    m_gizmoMode=mode;
    if(m_gizmoMode!=0) buildGizmoGeometry();
}

// Rebuilds the gizmo vertex buffer for the current mode.  Geometry is grouped
// per axis (X,Y,Z) so each axis can be drawn with its own colour.
void Renderer::buildGizmoGeometry(){
    if(!m_gizVao){ glGenVertexArrays(1,&m_gizVao); glGenBuffers(1,&m_gizVbo); }
    std::vector<float> v; // xyz triplets
    auto line=[&](float ax,float ay,float az,float bx,float by,float bz){
        v.push_back(ax);v.push_back(ay);v.push_back(az);
        v.push_back(bx);v.push_back(by);v.push_back(bz);
    };
    int base=0;
    for(int axis=0;axis<3;++axis){
        m_gizAxisOffset[axis]=base;
        float dx=0,dy=0,dz=0;
        if(axis==0) dx=1; else if(axis==1) dy=1; else dz=1;
        if(m_gizmoMode==1){ // MOVE: shaft + arrowhead cone
            line(0,0,0, dx,dy,dz);
            float hx=dx*0.7f,hy=dy*0.7f,hz=dz*0.7f;
            float p1x=0,p1y=0,p1z=0,p2x=0,p2y=0,p2z=0;
            if(axis==0){ p1y=0.12f; p2z=0.12f; }
            else if(axis==1){ p1x=0.12f; p2z=0.12f; }
            else { p1x=0.12f; p2y=0.12f; }
            line(hx+p1x,hy+p1y,hz+p1z, dx,dy,dz);
            line(hx-p1x,hy-p1y,hz-p1z, dx,dy,dz);
            line(hx+p2x,hy+p2y,hz+p2z, dx,dy,dz);
            line(hx-p2x,hy-p2y,hz-p2z, dx,dy,dz);
        } else if(m_gizmoMode==2){ // ROTATE: ring around the axis
            const int seg=32;
            float ux=0,uy=0,uz=0,vx=0,vy=0,vz=0;
            if(axis==0){ ux=0;uy=1;uz=0; vx=0;vy=0;vz=1; }
            else if(axis==1){ ux=1;uy=0;uz=0; vx=0;vy=0;vz=1; }
            else { ux=1;uy=0;uz=0; vx=0;vy=1;vz=0; }
            for(int i=0;i<seg;++i){
                float t0=(float)i/seg*2.f*PI;
                float t1=(float)(i+1)/seg*2.f*PI;
                float c0=cosf(t0),s0=sinf(t0),c1=cosf(t1),s1=sinf(t1);
                line(c0*ux*0.95f+s0*vx*0.95f,c0*uy*0.95f+s0*vy*0.95f,c0*uz*0.95f+s0*vz*0.95f,
                     c1*ux*0.95f+s1*vx*0.95f,c1*uy*0.95f+s1*vy*0.95f,c1*uz*0.95f+s1*vz*0.95f);
            }
        } else if(m_gizmoMode==3){ // SCALE: shaft + wire cube at tip
            line(0,0,0, dx,dy,dz);
            float ux=0,uy=0,uz=0,vx=0,vy=0,vz=0;
            if(axis==0){ ux=0;uy=1;uz=0; vx=0;vy=0;vz=1; }
            else if(axis==1){ ux=1;uy=0;uz=0; vx=0;vy=0;vz=1; }
            else { ux=1;uy=0;uz=0; vx=0;vy=1;vz=0; }
            const float h=0.09f;
            float corners[8][3];
            int idx=0;
            for(int i=0;i<2;++i) for(int j=0;j<2;++j) for(int k=0;k<2;++k){
                corners[idx][0]=dx+(i?1.f:-1.f)*ux*h+(j?1.f:-1.f)*vx*h;
                corners[idx][1]=dy+(i?1.f:-1.f)*uy*h+(j?1.f:-1.f)*vy*h;
                corners[idx][2]=dz+(i?1.f:-1.f)*uz*h+(j?1.f:-1.f)*vz*h;
                idx++;
            }
            const int edges[12][2]={{0,1},{0,2},{1,3},{2,3},{4,5},{4,6},{5,7},{6,7},{0,4},{1,5},{2,6},{3,7}};
            for(auto& e:edges)
                line(corners[e[0]][0],corners[e[0]][1],corners[e[0]][2],
                     corners[e[1]][0],corners[e[1]][1],corners[e[1]][2]);
        }
        m_gizAxisCount[axis]=(int)(v.size()/3)-base;
        base=(int)(v.size()/3);
    }
    m_gizVertCount=(GLsizei)(v.size()/3);
    glBindVertexArray(m_gizVao);
    glBindBuffer(GL_ARRAY_BUFFER,m_gizVbo);
    glBufferData(GL_ARRAY_BUFFER,(GLsizeiptr)(v.size()*sizeof(float)),
                 v.empty()?nullptr:v.data(),GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,12,nullptr);
    glBindVertexArray(0);
}

void Renderer::drawGizmo(const Mat4& proj,const Mat4& view){
    if(m_gizmoMode<=0||m_gizVertCount<=0||!m_gizVao) return;
    // Anchor at the world-space centre of the visible meshes
    float mnX=FLT_MAX,mnY=FLT_MAX,mnZ=FLT_MAX,mxX=-FLT_MAX,mxY=-FLT_MAX,mxZ=-FLT_MAX;
    Mat4 global=buildGlobalMatrix();
    for(const auto& mo:m_meshes){
        if(!mo.visible) continue;
        Mat4 model=global*buildMeshMatrix(mo);
        for(const auto& v:mo.vertices){
            float px=model.m[0]*v.px+model.m[4]*v.py+model.m[8]*v.pz+model.m[12];
            float py=model.m[1]*v.px+model.m[5]*v.py+model.m[9]*v.pz+model.m[13];
            float pz=model.m[2]*v.px+model.m[6]*v.py+model.m[10]*v.pz+model.m[14];
            mnX=std::min(mnX,px);mxX=std::max(mxX,px);
            mnY=std::min(mnY,py);mxY=std::max(mxY,py);
            mnZ=std::min(mnZ,pz);mxZ=std::max(mxZ,pz);
        }
    }
    if(mxX<=mnX){ m_gizAnchorValid=false; return; }
    float cx=0.5f*(mnX+mxX), cy=0.5f*(mnY+mxY), cz=0.5f*(mnZ+mxZ);
    float size=std::max(mxX-mnX,std::max(mxY-mnY,mxZ-mnZ));
    if(size<1e-4f) size=1.0f;
    size*=0.55f;
    // Cache the anchor so hitTestGizmo() never re-scans every vertex.
    m_gizAnchorCx=cx; m_gizAnchorCy=cy; m_gizAnchorCz=cz; m_gizAnchorSize=size;
    m_gizAnchorValid=true;
    Mat4 giz=Mat4::translation(cx,cy,cz)*Mat4::scale(size,size,size);
    Mat4 mvp=proj*view*giz;
    glUseProgram(m_wireProg);
    glUniformMatrix4fv(m_uloc.wireMvp,1,GL_FALSE,mvp.m);
    glUniform1f(m_uloc.wirePointSize,1.0f);
    glDisable(GL_DEPTH_TEST);
    glLineWidth(2.5f);
    glBindVertexArray(m_gizVao);
    static const float kCols[3][3]={{1.0f,0.25f,0.25f},{0.25f,1.0f,0.3f},{0.3f,0.6f,1.0f}};
    for(int a=0;a<3;++a){
        if(m_gizAxisCount[a]<=0) continue;
        glUniform4f(m_uloc.wireColor,kCols[a][0],kCols[a][1],kCols[a][2],1.0f);
        glDrawArrays(GL_LINES,m_gizAxisOffset[a],m_gizAxisCount[a]);
    }
    glBindVertexArray(0);
    glEnable(GL_DEPTH_TEST);
    glLineWidth(1.0f);
}

// Hit-test the visible gizmo in screen space. A transform tool being selected
// is not enough to mutate the model: the user must touch an axis handle.
int Renderer::hitTestGizmo(float sx,float sy,float sw,float sh) const {
    if (m_gizmoMode <= 0 || sw <= 1.f || sh <= 1.f || m_meshes.empty()) return -1;

    // Reuse the anchor cached by drawGizmo() (same GL thread, refreshed every
    // frame the gizmo is visible) instead of scanning every mesh vertex.
    float cx=m_gizAnchorCx, cy=m_gizAnchorCy, cz=m_gizAnchorCz;
    float size=m_gizAnchorSize;
    if (!m_gizAnchorValid || size < 1e-4f) {
        float mnX=FLT_MAX,mnY=FLT_MAX,mnZ=FLT_MAX;
        float mxX=-FLT_MAX,mxY=-FLT_MAX,mxZ=-FLT_MAX;
        Mat4 global = buildGlobalMatrix();
        for (const auto& mo : m_meshes) {
            if (!mo.visible || mo.vertices.empty()) continue;
            Mat4 model = global * buildMeshMatrix(mo);
            for (const auto& v : mo.vertices) {
                float x=model.m[0]*v.px+model.m[4]*v.py+model.m[8]*v.pz+model.m[12];
                float y=model.m[1]*v.px+model.m[5]*v.py+model.m[9]*v.pz+model.m[13];
                float z=model.m[2]*v.px+model.m[6]*v.py+model.m[10]*v.pz+model.m[14];
                mnX=std::min(mnX,x); mxX=std::max(mxX,x);
                mnY=std::min(mnY,y); mxY=std::max(mxY,y);
                mnZ=std::min(mnZ,z); mxZ=std::max(mxZ,z);
            }
        }
        if (mxX <= mnX) return -1;
        cx=0.5f*(mnX+mxX); cy=0.5f*(mnY+mxY); cz=0.5f*(mnZ+mxZ);
        size=std::max(mxX-mnX,std::max(mxY-mnY,mxZ-mnZ))*0.55f;
        if (size < 1e-4f) size=1.f;
    }

    const float aspect=sw/std::max(sh,1.f);
    Mat4 proj=Mat4::perspective(60.f*DEG2RAD,aspect,0.01f,100.f);
    Vec3 eye=cameraEye();
    Mat4 view=Mat4::lookAt(eye,{m_panX,m_panY,0},{0,1,0});
    auto screenPoint = [&](float x,float y,float z, float& ox,float& oy)->bool {
        // Transform to view space, then project. Computes proj*view*point
        // without needing a Mat4 inverse in the touch path.
        float vx=view.m[0]*x+view.m[4]*y+view.m[8]*z+view.m[12];
        float vy=view.m[1]*x+view.m[5]*y+view.m[9]*z+view.m[13];
        float vz=view.m[2]*x+view.m[6]*y+view.m[10]*z+view.m[14];
        float px=proj.m[0]*vx+proj.m[4]*vy+proj.m[8]*vz+proj.m[12];
        float py=proj.m[1]*vx+proj.m[5]*vy+proj.m[9]*vz+proj.m[13];
        float pw=proj.m[3]*vx+proj.m[7]*vy+proj.m[11]*vz+proj.m[15];
        if (pw <= 1e-5f) return false;
        ox=(px/pw+1.f)*0.5f*sw;
        oy=(1.f-py/pw)*0.5f*sh;
        return true;
    };
    auto segmentDistance = [](float px,float py,float ax,float ay,float bx,float by)->float {
        float dx=bx-ax,dy=by-ay, len2=dx*dx+dy*dy;
        float t=len2>1e-6f ? ((px-ax)*dx+(py-ay)*dy)/len2 : 0.f;
        t=std::clamp(t,0.f,1.f);
        float qx=ax+t*dx,qy=ay+t*dy;
        return std::sqrt((px-qx)*(px-qx)+(py-qy)*(py-qy));
    };

    float best=std::max(28.f,std::min(sw,sh)*0.045f); int bestAxis=-1;
    float ox,oy,cxScreen,cyScreen;
    if (!screenPoint(cx,cy,cz,cxScreen,cyScreen)) return -1;
    for (int axis=0; axis<3; ++axis) {
        // ROTATE draws ring handles only — never test the invisible centre
        // shaft, otherwise touching near the middle would grab a phantom axis.
        if(m_gizmoMode!=2){
            float ex=cx,ey=cy,ez=cz;
            if(axis==0) ex+=size; else if(axis==1) ey+=size; else ez+=size;
            if (!screenPoint(ex,ey,ez,ox,oy)) continue;
            float d=segmentDistance(sx,sy,cxScreen,cyScreen,ox,oy);
            if(d<best){best=d;bestAxis=axis;}
        }
        if(m_gizmoMode==2){
            float ux=0,uy=0,uz=0,vx=0,vy=0,vz=0;
            if(axis==0){uy=1;vz=1;} else if(axis==1){ux=1;vz=1;} else {ux=1;vy=1;}
            // Rings are drawn at 0.95*size in buildGizmoGeometry — test the
            // same radius so the hit area matches the visible handle.
            const float ringR=size*0.95f;
            for(int i=0;i<32;++i){
                float a0=(float)i/32.f*2.f*PI,a1=(float)(i+1)/32.f*2.f*PI;
                float x0=cx+ringR*(std::cos(a0)*ux+std::sin(a0)*vx);
                float y0=cy+ringR*(std::cos(a0)*uy+std::sin(a0)*vy);
                float z0=cz+ringR*(std::cos(a0)*uz+std::sin(a0)*vz);
                float x1=cx+ringR*(std::cos(a1)*ux+std::sin(a1)*vx);
                float y1=cy+ringR*(std::cos(a1)*uy+std::sin(a1)*vy);
                float z1=cz+ringR*(std::cos(a1)*uz+std::sin(a1)*vz);
                float aX,aY,bX,bY;
                if(screenPoint(x0,y0,z0,aX,aY)&&screenPoint(x1,y1,z1,bX,bY)) {
                    const float d = segmentDistance(sx,sy,aX,aY,bX,bY);
                    if (d < best) { best = d; bestAxis = axis; }
                }
            }
        }
    }
    return bestAxis;
}

// One-finger drag while a gizmo tool is active.  start=true = push one undo
// snapshot and return; subsequent calls stream deltas on the GLOBAL transform.
void Renderer::gizmoDrag(float dx,float dy,bool start){
    if(m_gizmoMode<=0) return;
    if(start){ pushUndoState(); return; }
    const float k = 2.0f*m_camDist/(float)std::max(m_height,1);
    if(m_gizmoMode==1){ // MOVE — translate in the camera's right/up plane
        float cp=cosf(m_camPitch),sp=sinf(m_camPitch);
        float cy=cosf(m_camYaw), sy=sinf(m_camYaw);
        Vec3 fwd{cp*sy,sp,cp*cy};
        Vec3 up{0,1,0};
        Vec3 right=fwd.cross(up).normalized();
        Vec3 upw=up - fwd*up.dot(fwd);
        upw=upw.normalized();
        Vec3 d = right*(dx*k) - upw*(dy*k);
        m_posX+=d.x; m_posY+=d.y; m_posZ+=d.z;
    } else if(m_gizmoMode==2){ // ROTATE — yaw around world Y, pitch around view axis
        m_rotY+=dx*0.4f;
        m_rotX-=dy*0.4f;
        m_rotX=std::fmodf(m_rotX+180.f,360.f); if(m_rotX<0) m_rotX+=360.f; m_rotX-=180.f;
        m_rotY=std::fmodf(m_rotY+180.f,360.f); if(m_rotY<0) m_rotY+=360.f; m_rotY-=180.f;
    } else if(m_gizmoMode==3){ // SCALE — uniform by vertical drag
        float f=1.0f+dy*0.01f;
        if(f<0.95f) f=0.95f;
        if(f>1.05f) f=1.05f;
        m_scaX*=f; m_scaY*=f; m_scaZ*=f;
        float m=std::max(std::fabsf(m_scaX),std::max(std::fabsf(m_scaY),std::fabsf(m_scaZ)));
        if(m>8.0f){ float r=8.0f/m; m_scaX*=r;m_scaY*=r;m_scaZ*=r; }
        if(m<0.02f){ float r=0.02f/m; m_scaX*=r;m_scaY*=r;m_scaZ*=r; }
    }
}

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
    applyPendingRingDeformation();
    glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    if(m_meshes.empty()||!m_mainProg||!m_wireProg) return;

    float aspect=(float)m_width/std::max(m_height,1);
    Mat4 proj=Mat4::perspective(60.0f*DEG2RAD,aspect,0.01f,100.0f);
    Vec3 eye=cameraEye();
    Mat4 view=Mat4::lookAt(eye,{m_panX,m_panY,0},{0,1,0});
    Mat4 global=buildGlobalMatrix();
    // lightDir still passed for legacy uLightDir uniform (not used in new shader)
    Vec3 lightDir=Vec3{-0.5f, 1.0f, 0.8f}.normalized();

    for(auto& mo:m_meshes){
        if(!mo.visible||!mo.gpuReady) continue;
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

    // Transform gizmo overlay (on top, depth-test off)
    drawGizmo(proj, view);

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
    m_gizAnchorValid=false;  // mesh set changed — drop the cached gizmo anchor
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
    m_unitToMM       = m_pendingData->unitToMM;
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
    m_gizVao = m_gizVbo = 0;
    m_gizVertCount = 0;
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
    if(m_gizmoMode!=0) buildGizmoGeometry();
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
        if((int)comp.faceCount < m_sepMinFaces) continue;  // island filter: skip dust
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
    m_unitToMM=md.unitToMM;
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
    m_gizAnchorValid=false;  // mesh removed — stale anchor would point at nothing
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
    // sx is the mesh's extent in normalized units. mm = sx * mmPerUnit()
    // at scale=1, so to reach the requested w/h/d mm we scale by
    // requested_mm / (extent * mmPerUnit()).
    float mpu = mmPerUnit();
    if(sx>1e-9f) mo.scaX=w/(sx*mpu);
    if(sy>1e-9f) mo.scaY=h/(sy*mpu);
    if(sz>1e-9f) mo.scaZ=d/(sz*mpu);
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
    float mpu=mmPerUnit();
    w=(maxX-minX)*fabsf(mo.scaX)*mpu;
    h=(maxY-minY)*fabsf(mo.scaY)*mpu;
    d=(maxZ-minZ)*fabsf(mo.scaZ)*mpu;
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
// ORIGINAL size in mm — stored at load time, never changes (source of truth
// for the "Original" readout and for ratio-based uniform scaling).
void Renderer::getModelSizeMM(float& w,float& h,float& d) const {
    w=m_origWmm; h=m_origHmm; d=m_origDmm;
}
// CURRENT size in mm — world-space bounding box after ALL transforms
// (global rotation/translation/scale + per-mesh transforms).
void Renderer::getCurrentSizeMM(float& w,float& h,float& d) const {
    if (m_meshes.empty()){w=m_origWmm;h=m_origHmm;d=m_origDmm;return;}
    float mpu=mmPerUnit();
    float mnX=FLT_MAX,mnY=FLT_MAX,mnZ=FLT_MAX,mxX=-FLT_MAX,mxY=-FLT_MAX,mxZ=-FLT_MAX;
    Mat4 global=buildGlobalMatrix();
    for(const auto& mo:m_meshes){if(!mo.visible)continue;
        Mat4 model=global*buildMeshMatrix(mo);
        for(const auto& v:mo.vertices){
            float px=model.m[0]*v.px+model.m[4]*v.py+model.m[8]*v.pz+model.m[12];
            float py=model.m[1]*v.px+model.m[5]*v.py+model.m[9]*v.pz+model.m[13];
            float pz=model.m[2]*v.px+model.m[6]*v.py+model.m[10]*v.pz+model.m[14];
            mnX=std::min(mnX,px);mxX=std::max(mxX,px);
            mnY=std::min(mnY,py);mxY=std::max(mxY,py);
            mnZ=std::min(mnZ,pz);mxZ=std::max(mxZ,pz);}}
    if(mxX>mnX){w=(mxX-mnX)*mpu;h=(mxY-mnY)*mpu;d=(mxZ-mnZ)*mpu;}
    else{w=m_origWmm;h=m_origHmm;d=m_origDmm;}
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
// Transform a normal using the upper-left 3x3 of the matrix, then renormalize.
// NOTE: this is only correct for uniform scale / rotation.  For export under
// non-uniform resize, use applyNormalMat3() with a precomputed inverse-transpose
// normal matrix (Mat4::toNormalMatrix) so surface normals stay perpendicular.
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

// Transform a normal by a column-major 3x3 normal matrix (nm[col*3+row]),
// then renormalize.  nm is produced by Mat4::toNormalMatrix (inverse-transpose),
// so this stays correct for non-uniform scale — the reason resized exports no
// longer look faceted / wrongly lit.
static inline Vec3 applyNormalMat3(const float nm[9], float nx, float ny, float nz) {
    Vec3 n{
        nm[0]*nx + nm[3]*ny + nm[6]*nz,
        nm[1]*nx + nm[4]*ny + nm[7]*nz,
        nm[2]*nx + nm[5]*ny + nm[8]*nz
    };
    float len = sqrtf(n.x*n.x + n.y*n.y + n.z*n.z);
    if(len > 1e-9f){ n.x /= len; n.y /= len; n.z /= len; }
    return n;
}

// ── Export ───────────────────────────────────────────────────────────────────
bool Renderer::exportOBJ(const std::string& path) const {
    std::ofstream f(path);
    if(!f) return false;
    f << "# Exported by AuraCAD\n";
    f << std::fixed; f.precision(6);

    Mat4 global = buildGlobalMatrix();
    int baseVertex = 1;

    for(const auto& mo : m_meshes){
        if(!mo.visible) continue;
        f << "o " << mo.name << "\n";

        // Combined transform: global(userScale/rot/trans) * meshTransform * mmConversion
        // mmConversion converts from normalized [-1,1] coords back to real-world mm units.
        // This ensures exported files are correctly sized (1 OBJ unit = 1 mm).
        float toMM = mmPerUnit();
        Mat4 mmConv = Mat4::scale(toMM, toMM, toMM);
        Mat4 model  = global * buildMeshMatrix(mo) * mmConv;
        float nm[9]; model.toNormalMatrix(nm);   // inverse-transpose: correct under non-uniform scale

        for(const auto& v : mo.vertices){
            Vec3 p = applyMat4Point(model, v.px, v.py, v.pz);
            f << "v " << p.x << " " << p.y << " " << p.z << "\n";
        }
        for(const auto& v : mo.vertices){
            Vec3 n = applyNormalMat3(nm, v.nx, v.ny, v.nz);
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

    char header[80] = "AuraCAD Export";
    f.write(header, 80);
    f.write(reinterpret_cast<const char*>(&total), 4);

    for(const auto& mo : m_meshes){
        if(!mo.visible) continue;
        float toMM = mmPerUnit();
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

bool Renderer::exportPLY(const std::string& path) const {
    std::ofstream f(path);
    if(!f) return false;
    f << "ply\nformat ascii 1.0\ncomment Exported by AuraCAD\n";

    Mat4 global = buildGlobalMatrix();
    size_t totalV=0, totalI=0;
    for(const auto& mo : m_meshes) if(mo.visible){ totalV+=mo.vertices.size(); totalI+=mo.indices.size(); }
    if(totalV==0 || totalI==0) return false;

    f << "element vertex " << totalV << "\n";
    f << "property float x\nproperty float y\nproperty float z\n";
    f << "property float nx\nproperty float ny\nproperty float nz\n";
    f << "element face " << (totalI/3) << "\n";
    f << "property list uchar int vertex_indices\n";
    f << "end_header\n";

    float toMM = mmPerUnit();
    Mat4 mmConv = Mat4::scale(toMM, toMM, toMM);
    f << std::fixed; f.precision(6);
    for(const auto& mo : m_meshes){
        if(!mo.visible) continue;
        Mat4 model = global * buildMeshMatrix(mo) * mmConv;
        float nm[9]; model.toNormalMatrix(nm);   // inverse-transpose: correct under non-uniform scale
        for(const auto& v : mo.vertices){
            Vec3 p = applyMat4Point(model, v.px, v.py, v.pz);
            Vec3 n = applyNormalMat3(nm, v.nx, v.ny, v.nz);
            f << p.x << " " << p.y << " " << p.z << " "
              << n.x << " " << n.y << " " << n.z << "\n";
        }
    }
    size_t base = 0;
    for(const auto& mo : m_meshes){
        if(!mo.visible) continue;
        for(size_t i=0;i+2<mo.indices.size();i+=3)
            f << "3 " << (mo.indices[i]+(unsigned int)base) << " "
              << (mo.indices[i+1]+(unsigned int)base) << " "
              << (mo.indices[i+2]+(unsigned int)base) << "\n";
        base += mo.vertices.size();
    }
    return true;
}

// ── 3DM export (openNURBS) ───────────────────────────────────────────────────
// Writes the current scene as Rhino 3DM v6. Every visible mesh becomes an
// ON_Mesh object in document units of millimetres. The same global × mesh
// × mm conversion used by the OBJ/STL/PLY exporters keeps the file true to
// the on-screen size.
bool Renderer::export3DM(const std::string& path) const {
    bool anyMesh = false;
    for (const auto& mo : m_meshes) if (mo.visible) { anyMesh = true; break; }
    if (!anyMesh) return false;

    ONX_Model model;
    model.m_settings.m_ModelUnitsAndTolerances.m_unit_system =
        ON::LengthUnitSystem::Millimeters;

    Mat4 global = buildGlobalMatrix();
    float toMM = mmPerUnit();
    Mat4 mmConv = Mat4::scale(toMM, toMM, toMM);

    for (const auto& mo : m_meshes) {
        if (!mo.visible) continue;
        Mat4 mtx = global * buildMeshMatrix(mo) * mmConv;

        ON_Mesh mesh;
        for (const auto& v : mo.vertices) {
            Vec3 p = applyMat4Point(mtx, v.px, v.py, v.pz);
            ON_3fPoint fp; fp.x = (float)p.x; fp.y = (float)p.y; fp.z = (float)p.z;
            mesh.m_V.Append(fp);
        }
        for (size_t i = 0; i + 2 < mo.indices.size(); i += 3) {
            ON_MeshFace f;
            f.vi[0] = (int)mo.indices[i+0];
            f.vi[1] = (int)mo.indices[i+1];
            f.vi[2] = (int)mo.indices[i+2];
            f.vi[3] = f.vi[2];   // triangle
            mesh.m_F.Append(f);
        }
        if (mesh.m_F.Count() == 0 || mesh.m_V.Count() == 0) continue;
        mesh.ComputeVertexNormals();
        // Model takes ownership (bManageGeometry=true) and deletes the mesh.
        ON_Mesh* managed = new ON_Mesh(mesh);
        model.AddModelGeometryComponentForExperts(true, managed, false, nullptr, false);
    }

    return model.Write(path.c_str(), 6);
}

// ── GLB export (glTF 2.0 binary) ─────────────────────────────────────────────
// Minimal but valid glTF 2.0 container: one buffer with interleaved-free
// POSITION/NORMAL/INDICES per visible mesh, u16 indices when possible.
// Same global × mesh × mm conversion as the other exporters.
bool Renderer::exportGLB(const std::string& path) const {
    struct MeshOut { uint64_t posOff, norOff, idxOff;
                     uint32_t vCount, iCount; bool u16; };
    std::vector<MeshOut> outs;
    std::vector<uint8_t> bin;
    Mat4 global = buildGlobalMatrix();
    float toMM = mmPerUnit();
    Mat4 mmConv = Mat4::scale(toMM, toMM, toMM);

    for (const auto& mo : m_meshes) {
        if (!mo.visible || mo.vertices.empty() || mo.indices.empty()) continue;
        bool u16 = mo.vertices.size() <= 65535;
        Mat4 mtx = global * buildMeshMatrix(mo) * mmConv;
        float nm[9]; mtx.toNormalMatrix(nm);   // inverse-transpose: correct under non-uniform scale

        uint64_t posOff = bin.size();
        for (const auto& v : mo.vertices) {
            Vec3 p = applyMat4Point(mtx, v.px, v.py, v.pz);
            float f[3] = {p.x, p.y, p.z};
            bin.insert(bin.end(), reinterpret_cast<const uint8_t*>(f),
                       reinterpret_cast<const uint8_t*>(f) + 12);
        }
        uint64_t norOff = bin.size();
        for (const auto& v : mo.vertices) {
            Vec3 n = applyNormalMat3(nm, v.nx, v.ny, v.nz);
            float f[3] = {n.x, n.y, n.z};
            bin.insert(bin.end(), reinterpret_cast<const uint8_t*>(f),
                       reinterpret_cast<const uint8_t*>(f) + 12);
        }
        uint64_t idxOff = bin.size();
        size_t tris = mo.indices.size() / 3;
        if (u16) {
            for (size_t i = 0; i < tris; ++i) {
                uint16_t t[3] = {(uint16_t)mo.indices[i*3+0],
                                 (uint16_t)mo.indices[i*3+1],
                                 (uint16_t)mo.indices[i*3+2]};
                bin.insert(bin.end(), reinterpret_cast<const uint8_t*>(t),
                           reinterpret_cast<const uint8_t*>(t) + 6);
            }
        } else {
            for (size_t i = 0; i < tris; ++i) {
                uint32_t t[3] = {mo.indices[i*3+0], mo.indices[i*3+1], mo.indices[i*3+2]};
                bin.insert(bin.end(), reinterpret_cast<const uint8_t*>(t),
                           reinterpret_cast<const uint8_t*>(t) + 12);
            }
        }
        outs.push_back({posOff, norOff, idxOff,
                        (uint32_t)mo.vertices.size(), (uint32_t)(tris * 3), u16});
    }
    if (outs.empty()) return false;

    // Build JSON with precise min/max per POSITION accessor
    std::ostringstream js;
    js << std::fixed << std::setprecision(6);
    js << "{\"asset\":{\"version\":\"2.0\",\"generator\":\"AuraCAD\"},"
       << "\"scene\":0,\"scenes\":[{\"nodes\":[";
    for (size_t i = 0; i < outs.size(); ++i) { if (i) js << ","; js << i; }
    js << "]}],\"nodes\":[";
    for (size_t i = 0; i < outs.size(); ++i) { if (i) js << ","; js << "{\"mesh\":" << i << "}"; }
    js << "],\"meshes\":[";
    for (size_t i = 0; i < outs.size(); ++i) {
        if (i) js << ",";
        js << "{\"primitives\":[{\"attributes\":{\"POSITION\":" << (3*i)
           << ",\"NORMAL\":" << (3*i+1) << "},\"indices\":" << (3*i+2)
           << ",\"mode\":4}]}";
    }
    js << "],\"accessors\":[";
    for (size_t i = 0; i < outs.size(); ++i) {
        const MeshOut& o = outs[i];
        // min/max from the stored positions
        float mn[3] = {FLT_MAX, FLT_MAX, FLT_MAX}, mx[3] = {-FLT_MAX, -FLT_MAX, -FLT_MAX};
        for (uint32_t k = 0; k < o.vCount; ++k) {
            const float* p = reinterpret_cast<const float*>(bin.data() + o.posOff + (size_t)k * 12);
            for (int c = 0; c < 3; ++c) { mn[c] = std::min(mn[c], p[c]); mx[c] = std::max(mx[c], p[c]); }
        }
        js << "{\"bufferView\":" << (3*i) << ",\"componentType\":5126,\"count\":" << o.vCount
           << ",\"type\":\"VEC3\",\"min\":[" << mn[0] << "," << mn[1] << "," << mn[2]
           << "],\"max\":[" << mx[0] << "," << mx[1] << "," << mx[2] << "]},";
        js << "{\"bufferView\":" << (3*i+1) << ",\"componentType\":5126,\"count\":" << o.vCount
           << ",\"type\":\"VEC3\"},";
        js << "{\"bufferView\":" << (3*i+2) << ",\"componentType\":" << (o.u16 ? 5123 : 5125)
           << ",\"count\":" << o.iCount << ",\"type\":\"SCALAR\"}";
        if (i + 1 < outs.size()) js << ",";
    }
    js << "],\"bufferViews\":[";
    for (size_t i = 0; i < outs.size(); ++i) {
        const MeshOut& o = outs[i];
        js << "{\"buffer\":0,\"byteOffset\":" << o.posOff << ",\"byteLength\":" << (o.vCount*12)
           << ",\"target\":34962},";
        js << "{\"buffer\":0,\"byteOffset\":" << o.norOff << ",\"byteLength\":" << (o.vCount*12)
           << ",\"target\":34962},";
        js << "{\"buffer\":0,\"byteOffset\":" << o.idxOff << ",\"byteLength\":"
           << (o.iCount * (o.u16 ? 2u : 4u)) << ",\"target\":34963}";
        if (i + 1 < outs.size()) js << ",";
    }
    js << "],\"buffers\":[{\"byteLength\":" << bin.size() << "}]}";

    std::string json = js.str();
    // GLB container with 4-byte chunk alignment
    const uint32_t magic = 0x46546C67u;  // 'glTF'
    const uint32_t jsonLen = (uint32_t)json.size();
    const uint32_t jsonPad = (4 - (jsonLen & 3)) & 3;
    const uint32_t binLen = (uint32_t)bin.size();
    const uint32_t binPad = (4 - (binLen & 3)) & 3;
    const uint32_t total = 12 + 8 + jsonLen + jsonPad + 8 + binLen + binPad;

    std::ofstream f(path, std::ios::binary);
    if (!f) return false;
    uint32_t v2 = 2;
    f.write(reinterpret_cast<const char*>(&magic), 4);
    f.write(reinterpret_cast<const char*>(&v2), 4);
    f.write(reinterpret_cast<const char*>(&total), 4);
    uint32_t cjLen = jsonLen + jsonPad, cjType = 0x4E4F534Au;  // 'JSON'
    f.write(reinterpret_cast<const char*>(&cjLen), 4);
    f.write(reinterpret_cast<const char*>(&cjType), 4);
    f.write(json.c_str(), json.size());
    for (uint32_t i = 0; i < jsonPad; ++i) f.put(' ');
    uint32_t cbLen = binLen + binPad, cbType = 0x004E4942u;  // 'BIN\0'
    f.write(reinterpret_cast<const char*>(&cbLen), 4);
    f.write(reinterpret_cast<const char*>(&cbType), 4);
    if (!bin.empty()) f.write(reinterpret_cast<const char*>(bin.data()), bin.size());
    for (uint32_t i = 0; i < binPad; ++i) f.put('\0');
    return true;
}

// ── 3DS export (legacy chunk format) ─────────────────────────────────────────
bool Renderer::export3DS(const std::string& path) const {
    Mat4 global = buildGlobalMatrix();
    float toMM = mmPerUnit();
    Mat4 mmConv = Mat4::scale(toMM, toMM, toMM);

    auto appendChunk = [](std::vector<uint8_t>& parent, uint16_t id,
                          const std::vector<uint8_t>& data) {
        uint32_t len = 6u + (uint32_t)data.size();
        uint8_t hdr[6];
        memcpy(hdr, &id, 2);
        memcpy(hdr + 2, &len, 4);
        parent.insert(parent.end(), hdr, hdr + 6);
        parent.insert(parent.end(), data.begin(), data.end());
    };

    std::vector<uint8_t> editor;   // 0x3D3D content
    int meshIndex = 0;

    // Emit one 0x4000 object (name + 0x4100 tri-mesh) from a set of triangles
    // that reference at most 65535 unique vertices. positions are already in
    // world/mm space. localIdx maps triangle corners to compacted vertex ids.
    auto emitObject = [&](const std::string& name,
                          const std::vector<Vec3>& verts,
                          const std::vector<uint16_t>& idx) {
        std::vector<uint8_t> objData;   // 0x4000 content: name + 0x4100
        for (char ch : name) objData.push_back((uint8_t)ch);
        objData.push_back(0);
        if (objData.size() & 1) objData.push_back(0);   // pad name to even length

        std::vector<uint8_t> triData;   // 0x4100 content: 0x4110 + 0x4120
        {
            std::vector<uint8_t> vd;    // 0x4110: u16 count + vertices
            uint16_t vc = (uint16_t)verts.size();
            vd.insert(vd.end(), reinterpret_cast<const uint8_t*>(&vc),
                      reinterpret_cast<const uint8_t*>(&vc) + 2);
            for (const auto& p : verts) {
                float f3[3] = {p.x, p.y, p.z};
                vd.insert(vd.end(), reinterpret_cast<const uint8_t*>(f3),
                          reinterpret_cast<const uint8_t*>(f3) + 12);
            }
            appendChunk(triData, 0x4110, vd);
        }
        {
            std::vector<uint8_t> fd;    // 0x4120: u16 count + (3×u16 idx + u16 flags)
            uint16_t fc = (uint16_t)(idx.size() / 3);
            fd.insert(fd.end(), reinterpret_cast<const uint8_t*>(&fc),
                      reinterpret_cast<const uint8_t*>(&fc) + 2);
            for (size_t i = 0; i + 2 < idx.size(); i += 3) {
                uint16_t t[4] = {idx[i+0], idx[i+1], idx[i+2], 0};
                fd.insert(fd.end(), reinterpret_cast<const uint8_t*>(t),
                          reinterpret_cast<const uint8_t*>(t) + 8);
            }
            appendChunk(triData, 0x4120, fd);
        }
        appendChunk(objData, 0x4100, triData);
        appendChunk(editor, 0x4000, objData);
    };

    for (const auto& mo : m_meshes) {
        if (!mo.visible || mo.vertices.empty() || mo.indices.empty()) continue;
        Mat4 mtx = global * buildMeshMatrix(mo) * mmConv;
        std::string baseName = mo.name.empty() ? "Mesh" + std::to_string(meshIndex) : mo.name;

        // 3DS uses u16 vertex indices (max 65535 verts per object). Rather than
        // DROP meshes above that limit (which silently reduced poly count and
        // file size after loading detailed models), split them into as many
        // sub-objects as needed, re-indexing each chunk locally. No geometry is
        // ever lost — the whole mesh survives the round-trip.
        constexpr uint32_t kMaxVerts = 65535;
        std::vector<int32_t> remap(mo.vertices.size(), -1);
        std::vector<Vec3>    chunkVerts;
        std::vector<uint16_t> chunkIdx;
        int part = 0;

        auto flushChunk = [&]() {
            if (chunkIdx.empty()) return;
            std::string nm = (part == 0) ? baseName
                                         : baseName + "_" + std::to_string(part);
            emitObject(nm, chunkVerts, chunkIdx);
            ++part;
            // reset for the next chunk
            std::fill(remap.begin(), remap.end(), -1);
            chunkVerts.clear();
            chunkIdx.clear();
        };

        for (size_t i = 0; i + 2 < mo.indices.size(); i += 3) {
            unsigned int tri[3] = { mo.indices[i+0], mo.indices[i+1], mo.indices[i+2] };
            // How many NEW vertices would this triangle add to the current chunk?
            uint32_t newVerts = 0;
            for (int k = 0; k < 3; ++k)
                if (tri[k] < remap.size() && remap[tri[k]] < 0) ++newVerts;
            if (chunkVerts.size() + newVerts > kMaxVerts) flushChunk();

            uint16_t local[3];
            for (int k = 0; k < 3; ++k) {
                unsigned int gi = tri[k];
                if (gi >= mo.vertices.size()) { local[k] = 0; continue; }
                if (remap[gi] < 0) {
                    const auto& v = mo.vertices[gi];
                    Vec3 p = applyMat4Point(mtx, v.px, v.py, v.pz);
                    remap[gi] = (int32_t)chunkVerts.size();
                    chunkVerts.push_back(p);
                }
                local[k] = (uint16_t)remap[gi];
            }
            chunkIdx.push_back(local[0]);
            chunkIdx.push_back(local[1]);
            chunkIdx.push_back(local[2]);
        }
        flushChunk();
        ++meshIndex;
    }
    if (editor.empty()) return false;

    std::vector<uint8_t> mainChunk;    // 0x4D4D content: 0x3D3D
    appendChunk(mainChunk, 0x3D3D, editor);

    std::ofstream f(path, std::ios::binary);
    if (!f) return false;
    uint16_t id = 0x4D4D;
    uint32_t len = 6u + (uint32_t)mainChunk.size();
    f.write(reinterpret_cast<const char*>(&id), 2);
    f.write(reinterpret_cast<const char*>(&len), 4);
    f.write(reinterpret_cast<const char*>(mainChunk.data()),
            (std::streamsize)mainChunk.size());
    return true;
}

// ── Combine meshes ───────────────────────────────────────────────────────────
bool Renderer::combineMeshes(const std::vector<int>& indices){
    m_gizAnchorValid=false;  // mesh topology changed — invalidate cached anchor
    std::vector<int> idxs;
    for(int i : indices){
        if(i>=0 && i<(int)m_meshes.size() &&
           std::find(idxs.begin(), idxs.end(), i)==idxs.end()) idxs.push_back(i);
    }
    if(idxs.size()<2) return false;

    pushUndoState();

    MeshObject merged;
    merged.name = "Combined_" + std::to_string((int)idxs.size()) + "M";
    size_t base = 0;
    for(int i : idxs){
        const MeshObject& mo = m_meshes[i];
        if(mo.vertices.empty()) continue;
        merged.vertices.insert(merged.vertices.end(), mo.vertices.begin(), mo.vertices.end());
        for(unsigned int idx : mo.indices)
            merged.indices.push_back((unsigned int)(idx + base));
        base += mo.vertices.size();
    }
    if(merged.vertices.empty()) return false;
    merged.colorR = m_meshes[idxs[0]].colorR;
    merged.colorG = m_meshes[idxs[0]].colorG;
    merged.colorB = m_meshes[idxs[0]].colorB;

    // Free GL objects of the originals, then remove them (descending so the
    // smaller indices stay valid while erasing).
    for(int i : idxs){
        auto& mo = m_meshes[i];
        if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
        if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
        if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
    }
    int insertAt = *std::min_element(idxs.begin(), idxs.end());
    std::sort(idxs.begin(), idxs.end(), std::greater<int>());
    for(int i : idxs) m_meshes.erase(m_meshes.begin()+i);

    uploadMeshObject(merged);
    m_meshes.insert(m_meshes.begin()+insertAt, std::move(merged));
    m_selectedMesh = insertAt;
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

    float toMM = mmPerUnit();

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

    float toNorm = unitPerMM();
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
// BRUSH TOOLS
// ══════════════════════════════════════════════════════════════════════════════

// ── applySmooth ───────────────────────────────────────────────────────────────
// Laplacian smoothing brush.  For every vertex of mesh [meshIdx] whose distance
// from (cx,cy,cz) in normalised space is <= radius*normalizeScale, the vertex
// position is blended towards the average of its ring-1 neighbours weighted by
// [strength] (0-1).  A falloff curve (cos²) is applied so the effect is smooth
// at the brush boundary.
bool Renderer::applySmooth(int meshIdx, float cx, float cy, float cz,
                           float radius, float strength)
{
    if (meshIdx < 0 || meshIdx >= (int)m_meshes.size()) return false;
    auto& mo = m_meshes[meshIdx];
    const size_t N = mo.vertices.size();
    if (N < 3 || mo.indices.empty()) return false;

    // Build adjacency: for each vertex, collect its neighbour indices.
    std::vector<std::vector<uint32_t>> adj(N);
    const size_t nTri = mo.indices.size() / 3;
    for (size_t t = 0; t < nTri; ++t) {
        uint32_t i0 = mo.indices[t*3+0];
        uint32_t i1 = mo.indices[t*3+1];
        uint32_t i2 = mo.indices[t*3+2];
        adj[i0].push_back(i1); adj[i0].push_back(i2);
        adj[i1].push_back(i0); adj[i1].push_back(i2);
        adj[i2].push_back(i0); adj[i2].push_back(i1);
    }

    const float radiusN = radius * unitPerMM();   // mm → normalized units
    const float radiusSq = radiusN * radiusN;
    // Work on the mesh vertices in place; only commit if something changed.
    std::vector<Vertex> newPos(N);
    for (size_t i = 0; i < N; ++i) newPos[i] = mo.vertices[i];

    for (size_t i = 0; i < N; ++i) {
        const Vertex& v = mo.vertices[i];
        float dx = v.px - cx, dy = v.py - cy, dz = v.pz - cz;
        float distSq = dx*dx + dy*dy + dz*dz;
        if (distSq > radiusSq || adj[i].empty()) continue;

        // Cosine falloff: 1 at centre, 0 at edge
        float t = 1.f - (distSq / radiusSq);
        float w  = t * t * strength;

        float ax = 0.f, ay = 0.f, az = 0.f;
        // Deduplicate neighbours
        auto& nbrs = adj[i];
        std::sort(nbrs.begin(), nbrs.end());
        nbrs.erase(std::unique(nbrs.begin(), nbrs.end()), nbrs.end());
        for (uint32_t nb : nbrs) {
            ax += mo.vertices[nb].px;
            ay += mo.vertices[nb].py;
            az += mo.vertices[nb].pz;
        }
        ax /= (float)nbrs.size();
        ay /= (float)nbrs.size();
        az /= (float)nbrs.size();

        newPos[i].px = v.px + (ax - v.px) * w;
        newPos[i].py = v.py + (ay - v.py) * w;
        newPos[i].pz = v.pz + (az - v.pz) * w;
    }

    bool changed = false;
    for (size_t i = 0; i < N; ++i) {
        if (newPos[i].px != mo.vertices[i].px ||
            newPos[i].py != mo.vertices[i].py ||
            newPos[i].pz != mo.vertices[i].pz) {
            mo.vertices[i].px = newPos[i].px;
            mo.vertices[i].py = newPos[i].py;
            mo.vertices[i].pz = newPos[i].pz;
            changed = true;
        }
    }
    if (!changed) return false;

    regenerateNormals(mo);
    updateMeshVBO(mo);
    recomputeBounds(mo);
    return true;
}

// ── applySculpt ───────────────────────────────────────────────────────────────
// Inflate/deflate brush.  Vertices within [radius] of (cx,cy,cz) are displaced
// along their vertex normal by [amount] mm, attenuated by a cos² falloff.
bool Renderer::applySculpt(int meshIdx, float cx, float cy, float cz,
                           float radius, float amount)
{
    if (meshIdx < 0 || meshIdx >= (int)m_meshes.size()) return false;
    auto& mo = m_meshes[meshIdx];
    const size_t N = mo.vertices.size();
    if (N < 3) return false;

    const float radiusN = radius * unitPerMM();   // mm → normalized units
    const float radiusSq = radiusN * radiusN;
    bool changed = false;

    for (size_t i = 0; i < N; ++i) {
        Vertex& v = mo.vertices[i];
        float dx = v.px - cx, dy = v.py - cy, dz = v.pz - cz;
        float distSq = dx*dx + dy*dy + dz*dz;
        if (distSq > radiusSq) continue;

        float t = 1.f - (distSq / radiusSq);
        float w = t * t;   // cos² falloff

        // Normalize the stored vertex normal
        float nl = sqrtf(v.nx*v.nx + v.ny*v.ny + v.nz*v.nz);
        float nxf = (nl > 1e-12f) ? v.nx/nl : 0.f;
        float nyf = (nl > 1e-12f) ? v.ny/nl : 0.f;
        float nzf = (nl > 1e-12f) ? v.nz/nl : 0.f;

        v.px += nxf * (amount * w);
        v.py += nyf * (amount * w);
        v.pz += nzf * (amount * w);
        changed = true;
    }

    if (!changed) return false;

    regenerateNormals(mo);
    updateMeshVBO(mo);
    recomputeBounds(mo);
    return true;
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
    float             axialScale,      // stretch factor along axis (1 = keep)
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
            // Vertex exactly on axis — only axial stretch applies
            float aNew = along * axialScale;
            dst.px = cen.x + aNew * axis.x;
            dst.py = cen.y + aNew * axis.y;
            dst.pz = cen.z + aNew * axis.z;
            continue;
        }

        float rNew   = mapR(r);
        float scale  = rNew / r;   // only radial direction scaled
        float aNew   = along * axialScale;

        dst.px = cen.x + aNew * axis.x + rx * scale;
        dst.py = cen.y + aNew * axis.y + ry * scale;
        dst.pz = cen.z + aNew * axis.z + rz * scale;
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

    // ── Pass 1: crude centroid + covariance for initial axis ─────────────────
    double cx = 0, cy = 0, cz = 0;
    for (const auto& v : mo.vertices) { cx += v.px; cy += v.py; cz += v.pz; }
    cx /= (double)N; cy /= (double)N; cz /= (double)N;
    Vec3 cen0{(float)cx, (float)cy, (float)cz};

    float C[3][3] = {};
    for (const auto& v : mo.vertices) {
        float d[3] = {v.px-(float)cx, v.py-(float)cy, v.pz-(float)cz};
        for (int i=0;i<3;i++) for (int j=0;j<3;j++) C[i][j] += d[i]*d[j];
    }
    float Vmat[3][3];
    jacobiEigen3(C, Vmat);
    // Smallest eigenvalue → axis (least variance = through-hole direction)
    Vec3 axis {Vmat[0][0], Vmat[1][0], Vmat[2][0]};
    {
        float alen = sqrtf(axis.x*axis.x + axis.y*axis.y + axis.z*axis.z);
        if (alen < 1e-9f) return false;
        axis.x /= alen; axis.y /= alen; axis.z /= alen;
    }

    // ── Pass 2: compute radial distances, find innerR via 5th percentile ─────
    std::vector<float> radii(N);
    float axMin = FLT_MAX, axMax = -FLT_MAX;
    for (size_t i = 0; i < N; ++i) {
        const auto& v = mo.vertices[i];
        float dx = v.px-cen0.x, dy = v.py-cen0.y, dz = v.pz-cen0.z;
        float along = dx*axis.x + dy*axis.y + dz*axis.z;
        axMin = std::min(axMin, along); axMax = std::max(axMax, along);
        float rx = dx-along*axis.x, ry = dy-along*axis.y, rz = dz-along*axis.z;
        radii[i] = sqrtf(rx*rx + ry*ry + rz*rz);
    }
    std::vector<float> sortedR = radii;
    std::sort(sortedR.begin(), sortedR.end());
    float p05 = sortedR[(size_t)(N * 0.05)];  // inner bore radius estimate
    float p95 = sortedR[(size_t)(N * 0.95)];  // outer surface radius estimate
    if (p95 - p05 < 1e-7f) return false;

    // ── Pass 3: refine centroid + axis using INNER BORE vertices ONLY ─────────
    // Inner bore is a clean cylinder — texture only affects outer geometry.
    // This makes axis detection immune to carved/embossed patterns.
    float boreCut = p05 * 1.35f;  // inner bore: all verts with r < 135% of p05
    double icx = 0, icy = 0, icz = 0;
    int nBore = 0;
    for (size_t i = 0; i < N; ++i) {
        if (radii[i] <= boreCut) {
            icx += mo.vertices[i].px;
            icy += mo.vertices[i].py;
            icz += mo.vertices[i].pz;
            ++nBore;
        }
    }
    Vec3 cen = cen0;  // default to crude centroid
    Vec3 axisRefined = axis;

    if (nBore >= 64) {
        icx /= nBore; icy /= nBore; icz /= nBore;
        Vec3 boreCen{(float)icx, (float)icy, (float)icz};

        // Covariance from bore vertices only
        float Cb[3][3] = {};
        for (size_t i = 0; i < N; ++i) {
            if (radii[i] > boreCut) continue;
            float d[3] = {mo.vertices[i].px-(float)icx,
                          mo.vertices[i].py-(float)icy,
                          mo.vertices[i].pz-(float)icz};
            for (int ii=0;ii<3;ii++) for (int jj=0;jj<3;jj++) Cb[ii][jj] += d[ii]*d[jj];
        }
        float Vb[3][3];
        jacobiEigen3(Cb, Vb);
        Vec3 axB{Vb[0][0], Vb[1][0], Vb[2][0]};
        float abLen = sqrtf(axB.x*axB.x + axB.y*axB.y + axB.z*axB.z);
        if (abLen > 1e-9f) {
            axB.x /= abLen; axB.y /= abLen; axB.z /= abLen;
            // Accept refined axis only if aligned within 30° of crude axis
            float dot = fabsf(axB.x*axis.x + axB.y*axis.y + axB.z*axis.z);
            if (dot > 0.866f) {  // cos(30°)
                axisRefined = axB;
                cen = boreCen;
                LOGI("Ring: bore-refined axis & centroid from %d/%zu verts", nBore, N);
            }
        }
    }

    // ── Pass 4: recompute radii & percentiles with refined axis ──────────────
    axMin = FLT_MAX; axMax = -FLT_MAX;
    for (size_t i = 0; i < N; ++i) {
        const auto& v = mo.vertices[i];
        float dx = v.px-cen.x, dy = v.py-cen.y, dz = v.pz-cen.z;
        float along = dx*axisRefined.x + dy*axisRefined.y + dz*axisRefined.z;
        axMin = std::min(axMin, along); axMax = std::max(axMax, along);
        float rx = dx-along*axisRefined.x, ry = dy-along*axisRefined.y, rz = dz-along*axisRefined.z;
        radii[i] = sqrtf(rx*rx + ry*ry + rz*rz);
    }
    std::copy(radii.begin(), radii.end(), sortedR.begin());
    std::sort(sortedR.begin(), sortedR.end());

    // Use conservative percentiles: 3rd and 97th for cleaner inner/outer boundaries
    float innerR = sortedR[(size_t)(N * 0.03f)];
    float outerR = sortedR[(size_t)(N * 0.97f)];
    if (outerR - innerR < 1e-7f) return false;

    // ── Store ─────────────────────────────────────────────────────────────────
    m_ring.center        = cen;
    m_ring.axis          = axisRefined;
    m_ring.innerR        = innerR;
    m_ring.outerR        = outerR;
    m_ring.origInnerR    = innerR;
    m_ring.origOuterR    = outerR;
    m_ring.currentInnerR = innerR;
    m_ring.currentOuterR = outerR;
    m_ring.heightAx      = axMax - axMin;
    m_ring.currentHeight = axMax - axMin;
    m_ring.valid         = true;
    m_ring.meshIdx       = meshIdx;
    m_ring.origVerts     = mo.vertices;  // ← FULL backup, deformation always starts here

    float toMM = mmPerUnit();

    // ── Seed the desired-state record with the ring's original parameters.
    // This is essential: if the user later moves only the BW slider, m_desiredIDmm
    // must already hold the original inner diameter so applyCombinedRingDeformation
    // can apply both parameters together without losing the other one.
    m_desiredBWmm = (outerR - innerR) * toMM;   // original band width in mm
    m_desiredIDmm = innerR * 2.f        * toMM; // original inner diameter in mm
    m_desiredHmm  = (axMax - axMin)     * toMM; // original height in mm

    // Clear any stale pending values left over from a previous ring session
    m_pendingBW.store(-1.f, std::memory_order_relaxed);
    m_pendingID.store(-1.f, std::memory_order_relaxed);
    m_pendingH.store(-1.f, std::memory_order_relaxed);
    m_ringDirty.store(false, std::memory_order_relaxed);

    LOGI("Ring v3: innerR=%.3fmm outerR=%.3fmm band=%.3fmm h=%.3fmm axis=(%.3f,%.3f,%.3f) N=%zu borePts=%d",
         innerR*toMM, outerR*toMM, (outerR-innerR)*toMM, (axMax-axMin)*toMM,
         axisRefined.x, axisRefined.y, axisRefined.z, N, nBore);
    return true;
}

// ── Parameters in mm ─────────────────────────────────────────────────────────
bool Renderer::getRingParams(float out[6]) const {
    if (!m_ring.valid) return false;
    float toMM = mmPerUnit();
    float curInnerMM = m_ring.currentInnerR * toMM;
    float curOuterMM = m_ring.currentOuterR * toMM;
    float curBandMM  = (m_ring.currentOuterR - m_ring.currentInnerR) * toMM;
    out[0] = curInnerMM;        // inner radius (mm)
    out[1] = curOuterMM;        // outer radius (mm)
    out[2] = curBandMM;         // band width = wall thickness (mm)
    out[3] = curInnerMM * 2.f;  // inner diameter (mm)
    out[4] = curOuterMM * 2.f;  // outer diameter (mm)
    out[5] = m_ring.currentHeight * toMM;  // ring height (mm)
    return true;
}

// ── Set band width (wall thickness) — synchronous GL-thread API ──────────────
// Updates the desired BW and applies the combined deformation so that the
// current inner-diameter setting is preserved.  Delegates all math to
// applyCombinedRingDeformation which is the single authoritative deformation
// entry point.
void Renderer::setRingBandWidth(float newWidthMM) {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty() || newWidthMM < 1e-9f) return;
    m_desiredBWmm = newWidthMM;
    applyCombinedRingDeformation(m_desiredBWmm, m_desiredIDmm, m_desiredHmm);
}

// ── Set inner diameter — synchronous GL-thread API ───────────────────────────
// Updates the desired ID and applies the combined deformation so that the
// current band-width and height settings are preserved.  Delegates all math to
// applyCombinedRingDeformation which is the single authoritative deformation
// entry point.
void Renderer::setRingInnerDiameter(float newDiamMM) {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty() || newDiamMM < 1e-9f) return;
    m_desiredIDmm = newDiamMM;
    applyCombinedRingDeformation(m_desiredBWmm, m_desiredIDmm, m_desiredHmm);
}

// ── Set height (axial extent) — synchronous GL-thread API ────────────────────
// Stretches/squashes the ring along its hole axis.  Radial map (band width +
// inner diameter) is preserved because applyCombinedRingDeformation applies
// all three parameters together from origVerts.
void Renderer::setRingHeight(float newHeightMM) {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty() || newHeightMM < 1e-9f) return;
    m_desiredHmm = newHeightMM;
    applyCombinedRingDeformation(m_desiredBWmm, m_desiredIDmm, m_desiredHmm);
}

// ── Reset to original shape ───────────────────────────────────────────────────
void Renderer::resetRingDeformation() {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty()) return;

    auto& mo = m_meshes[m_ring.meshIdx];
    mo.vertices          = m_ring.origVerts;
    m_ring.currentInnerR = m_ring.origInnerR;
    m_ring.currentOuterR = m_ring.origOuterR;
    m_ring.currentHeight = m_ring.heightAx;

    // Reset desired state back to original ring parameters so that
    // subsequent slider moves start from the correct baseline
    float toMM = mmPerUnit();
    m_desiredBWmm = (m_ring.origOuterR - m_ring.origInnerR) * toMM;
    m_desiredIDmm =  m_ring.origInnerR * 2.f                * toMM;
    m_desiredHmm  =  m_ring.heightAx                        * toMM;

    // Discard any in-flight pending values so the next draw() doesn't
    // immediately re-apply a stale deformation on top of the reset
    m_pendingBW.store(-1.f, std::memory_order_relaxed);
    m_pendingID.store(-1.f, std::memory_order_relaxed);
    m_pendingH.store(-1.f, std::memory_order_relaxed);
    m_ringDirty.store(false, std::memory_order_relaxed);

    regenerateNormals(mo);
    updateMeshVBO(mo);
    recomputeBounds(mo);   // keep selection overlay in sync
}

// applyRingDeformation removed — declaration dropped from header.
// All callers use setRingBandWidth / setRingInnerDiameter or the
// async pending path which converges on applyCombinedRingDeformation.


// ── Bounding-box rebuild ───────────────────────────────────────────────────────
// Must be called after ANY operation that modifies vertex positions so that
// the selection overlay, ray-pick AABB test, and mesh-stats display stay correct.
void Renderer::recomputeBounds(MeshObject& mo) {
    if (mo.vertices.empty()) return;
    float mnX = FLT_MAX, mnY = FLT_MAX, mnZ = FLT_MAX;
    float mxX = -FLT_MAX, mxY = -FLT_MAX, mxZ = -FLT_MAX;
    for (const auto& v : mo.vertices) {
        mnX = std::min(mnX, v.px); mxX = std::max(mxX, v.px);
        mnY = std::min(mnY, v.py); mxY = std::max(mxY, v.py);
        mnZ = std::min(mnZ, v.pz); mxZ = std::max(mxZ, v.pz);
    }
    mo.bboxMin[0] = mnX; mo.bboxMin[1] = mnY; mo.bboxMin[2] = mnZ;
    mo.bboxMax[0] = mxX; mo.bboxMax[1] = mxY; mo.bboxMax[2] = mxZ;
}

// ── Combined ring deformation ─────────────────────────────────────────────────
// This is THE single authoritative deformation entry point.
//
// CRITICAL DESIGN NOTE:
// The old code applied BW and ID separately from origVerts, so the second
// operation always overwrote the first (e.g. moving the ID slider would
// silently undo any BW change because setRingInnerDiameter also starts from
// origVerts).  This function fixes that by applying ALL parameters in one
// unified radial+axial map pass:
//
//   r_new = newInnerR + (r_orig - origInnerR) * bandScale
//   h_new = h_orig * heightScale
//
// where:
//   newInnerR   = (desiredIDmm / 2) * unitPerMM  (shifted bore radius)
//   bandScale   = desiredBWmm / origBandMM       (wall-thickness scale)
//   heightScale = desiredHmm  / origHeightMM     (axial stretch factor)
//
// Properties:
//   • When only BW changed  → newInnerR == origInnerR, only wall scales
//   • When only ID changed  → bandScale == 1,           pure uniform shift
//   • When only H changed   → both radial scales == 1,  pure axial stretch
//   • When multiple changed → all applied simultaneously, zero cumulative error
//   • Always deforms from origVerts → zero cumulative error
//   • After the map, bbox is rebuilt so the selection overlay is correct
void Renderer::applyCombinedRingDeformation(float bwMM, float idMM, float hMM) {
    if (!m_ring.valid || m_ring.meshIdx < 0 ||
        m_ring.meshIdx >= (int)m_meshes.size()) return;
    if (m_ring.origVerts.empty() || bwMM < 1e-9f || idMM < 1e-9f || hMM < 1e-9f) return;

    float upm        = unitPerMM();
    float newInnerN  = (idMM * 0.5f) * upm;          // desired bore radius (norm)
    float origBandN  = m_ring.origOuterR - m_ring.origInnerR;
    float newBandN   = bwMM * upm;
    float bandScale  = (origBandN > 1e-9f) ? (newBandN / origBandN) : 1.f;
    float heightScale= (m_ring.heightAx > 1e-9f) ? ((hMM * upm) / m_ring.heightAx) : 1.f;
    float origInner  = m_ring.origInnerR;

    auto& mo = m_meshes[m_ring.meshIdx];

    applyRadialMap(mo, m_ring.origVerts, m_ring.center, m_ring.axis,
        heightScale,
        [newInnerN, origInner, bandScale](float r) -> float {
            if (r <= origInner) {
                // Vertices inside the bore (r = distance from the axis). Map
                // them proportionally so they NEVER collapse onto the axis or
                // cross it when resizing: axis (r=0) stays at the axis, the
                // bore surface (r=origInner) lands exactly on the new bore
                // radius. Without this, interior vertices could go negative
                // and the whole ring structure gets destroyed.
                float t = (origInner > 1e-9f) ? (r / origInner) : 0.f;
                return newInnerN * t;
            }
            float rRel = r - origInner;          // distance from original bore surface
            float rNew = newInnerN + rRel * bandScale;
            return (rNew > 1e-9f) ? rNew : 1e-9f; // guard against negative radius
        });

    m_ring.currentInnerR = newInnerN;
    m_ring.currentOuterR = newInnerN + newBandN;
    m_ring.currentHeight = m_ring.heightAx * heightScale;

    regenerateNormals(mo);
    updateMeshVBO(mo);
    recomputeBounds(mo);   // ← keeps selection-overlay bbox in sync
}

// ── Pending async deformation (called at top of draw() from GL thread) ────────
// Consumes atomic pending values posted by setPendingBandWidth/InnerDiameter
// from the UI thread, updates the full desired-state record, and calls
// applyCombinedRingDeformation once with both parameters together.
//
// KEY FIX: we now track m_desiredBWmm and m_desiredIDmm across frames.
// Updating only one slider only modifies that one field; the other retains
// its previous value.  Both are forwarded to applyCombinedRingDeformation so
// neither can silently cancel the other.
void Renderer::applyPendingRingDeformation() {
    if (!m_ringDirty.load(std::memory_order_acquire)) return;
    m_ringDirty.store(false, std::memory_order_relaxed);

    float bw = m_pendingBW.exchange(-1.f, std::memory_order_acquire);
    float id = m_pendingID.exchange(-1.f, std::memory_order_acquire);
    float h  = m_pendingH.exchange(-1.f, std::memory_order_acquire);

    if (bw > 0.f) m_desiredBWmm = bw;
    if (id > 0.f) m_desiredIDmm = id;
    if (h  > 0.f) m_desiredHmm  = h;

    // Only act if the ring has been analyzed and desired state is valid
    if (m_ring.valid && m_desiredBWmm > 0.f && m_desiredIDmm > 0.f && m_desiredHmm > 0.f) {
        applyCombinedRingDeformation(m_desiredBWmm, m_desiredIDmm, m_desiredHmm);
    }
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
// captureState: global + per-mesh transforms. Restoring a captured state
// brings transforms back exactly — this is the base for ALL undo entries.
UndoEntry Renderer::captureState() const{
    UndoEntry e;
    e.global = getTransform();
    e.meshes.resize(m_meshes.size());
    for(size_t i=0;i<m_meshes.size();++i){
        const auto& mo = m_meshes[i];
        e.meshes[i] = {mo.rotX,mo.rotY,mo.rotZ,
                       mo.posX,mo.posY,mo.posZ,
                       mo.scaX,mo.scaY,mo.scaZ};
    }
    return e;
}

void Renderer::pushUndoState(){
    m_undoStack.push_back(captureState());
    if((int)m_undoStack.size()>MAX_UNDO) m_undoStack.erase(m_undoStack.begin());
    m_redoStack.clear();
}

void Renderer::pushMeshUndo(int idx){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    UndoEntry e = captureState();
    e.vertMeshIdx = idx;
    e.verts       = m_meshes[idx].vertices;  // before-state of deformation
    m_undoStack.push_back(std::move(e));
    if((int)m_undoStack.size()>MAX_UNDO) m_undoStack.erase(m_undoStack.begin());
    m_redoStack.clear();
}

void Renderer::pushDeleteUndo(int idx){
    if(idx<0||idx>=(int)m_meshes.size()) return;
    UndoEntry e = captureState();
    e.hasDeletedMesh = true;
    e.meshAbsent     = false;   // this entry describes the PRE-delete state
    e.deletedMeshIdx = idx;
    e.deleted        = m_meshes[idx];  // full CPU copy (verts/indices/transform)
    // Do NOT carry GL handles across the delete — the mesh is freed, so the
    // copy must be re-uploaded fresh when restored.
    e.deleted.vao=e.deleted.vbo=e.deleted.ibo=0;
    e.deleted.gpuReady=false;
    m_undoStack.push_back(std::move(e));
    if((int)m_undoStack.size()>MAX_UNDO) m_undoStack.erase(m_undoStack.begin());
    m_redoStack.clear();
}

void Renderer::restoreState(const UndoEntry& e){
    m_gizAnchorValid=false;  // geometry restored — recompute anchor next draw
    // 1. Global transform
    m_rotX=e.global.rotX;m_rotY=e.global.rotY;m_rotZ=e.global.rotZ;
    m_posX=e.global.posX;m_posY=e.global.posY;m_posZ=e.global.posZ;
    m_scaX=e.global.scaX;m_scaY=e.global.scaY;m_scaZ=e.global.scaZ;

    // 2. Per-mesh transforms (best-effort across changed mesh counts)
    for(size_t i=0;i<e.meshes.size() && i<m_meshes.size();++i){
        m_meshes[i].rotX=e.meshes[i].rotX;m_meshes[i].rotY=e.meshes[i].rotY;m_meshes[i].rotZ=e.meshes[i].rotZ;
        m_meshes[i].posX=e.meshes[i].posX;m_meshes[i].posY=e.meshes[i].posY;m_meshes[i].posZ=e.meshes[i].posZ;
        m_meshes[i].scaX=e.meshes[i].scaX;m_meshes[i].scaY=e.meshes[i].scaY;m_meshes[i].scaZ=e.meshes[i].scaZ;
    }

    // 3. Vertex snapshot (brush / ring / weld / decimate / cleanup)
    if(e.vertMeshIdx>=0 && e.vertMeshIdx<(int)m_meshes.size()){
        m_meshes[e.vertMeshIdx].vertices = e.verts;
        updateMeshVBO(m_meshes[e.vertMeshIdx]);
        recomputeBounds(m_meshes[e.vertMeshIdx]);
    }

    // 4. Delete payload
    if(e.hasDeletedMesh){
        int idx = e.deletedMeshIdx;
        if(e.meshAbsent){
            // Entry describes the POST-delete state → ensure mesh is gone
            if(idx>=0 && idx<(int)m_meshes.size()){
                auto& mo = m_meshes[idx];
                if(mo.vao) glDeleteVertexArrays(1,&mo.vao);
                if(mo.vbo) glDeleteBuffers(1,&mo.vbo);
                if(mo.ibo) glDeleteBuffers(1,&mo.ibo);
                m_meshes.erase(m_meshes.begin()+idx);
            }
        } else {
            // Entry describes the PRE-delete state → ensure mesh exists
            if(idx<0 || idx>=(int)m_meshes.size()){
                MeshObject mo = e.deleted;
                uploadMeshObject(mo);
                m_meshes.insert(m_meshes.begin()+idx, std::move(mo));
            }
        }
    }

    if(m_meshes.empty()) m_hasModel=false; else m_hasModel=true;
    if(m_selectedMesh<0 || m_selectedMesh>=(int)m_meshes.size()) m_selectedMesh=-1;
    m_isSeparated = (m_meshes.size()>1);
}

void Renderer::undo(){
    if(m_undoStack.empty()) return;
    UndoEntry e = std::move(m_undoStack.back()); m_undoStack.pop_back();
    // The current (post-op) state becomes the redo entry.
    UndoEntry r = captureState();
    if(e.hasDeletedMesh){
        // current state has the mesh missing → redo must re-delete it
        r.hasDeletedMesh = true;
        r.meshAbsent     = true;
        r.deletedMeshIdx = e.deletedMeshIdx;
        r.deleted        = e.deleted;
    }
    m_redoStack.push_back(std::move(r));
    if((int)m_redoStack.size()>MAX_UNDO) m_redoStack.erase(m_redoStack.begin());
    restoreState(e);
}

void Renderer::redo(){
    if(m_redoStack.empty()) return;
    UndoEntry e = std::move(m_redoStack.back()); m_redoStack.pop_back();
    // Current state becomes the undo entry (mirror of undo()).
    UndoEntry u = captureState();
    if(e.hasDeletedMesh && e.meshAbsent){
        u.hasDeletedMesh = true;
        u.meshAbsent     = false;
        u.deletedMeshIdx = e.deletedMeshIdx;
        u.deleted        = e.deleted;
    }
    m_undoStack.push_back(std::move(u));
    if((int)m_undoStack.size()>MAX_UNDO) m_undoStack.erase(m_undoStack.begin());
    restoreState(e);
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

// Close-up AI inspection shots of the analyzed ring for multi-angle vision.
// view 0 = TOP: the camera looks along the ring axis so the inner diameter
// across the opening is seen; view 1 = SIDE: the camera looks edge-on so the
// height and band profile are visible. Both frames carry world-space callouts
// (red = inner diameter, cyan = band width, green = height) plus a white bar
// that is EXACTLY 10 mm, giving the vision model a real pixel→mm calibration.
// Camera + overlay state are restored after each shot.
std::vector<uint8_t> Renderer::takeRingInspectionShot(int meshIdx, int view){
    if(meshIdx<0 || meshIdx>=(int)m_meshes.size() || !m_ring.valid || m_ring.meshIdx!=meshIdx)
        return {};
    const MeshObject& mo = m_meshes[meshIdx];
    if(mo.vertices.empty()) return {};

    // ── Save state we temporarily override ───────────────────────────────────
    const float saveYaw=m_camYaw, savePitch=m_camPitch, saveDist=m_camDist;
    const float savePanX=m_panX,  savePanY=m_panY;
    const int   saveGizmo=m_gizmoMode;
    const bool  saveR1=m_rulerHasP1, saveR2=m_rulerHasP2;

    // ── World-space bounds of the ring mesh (drives the fit distance) ────────
    Mat4 global = buildGlobalMatrix();
    Mat4 model  = global * buildMeshMatrix(mo);
    float mnX=FLT_MAX,mnY=FLT_MAX,mnZ=FLT_MAX,mxX=-FLT_MAX,mxY=-FLT_MAX,mxZ=-FLT_MAX;
    for(const auto& v:mo.vertices){
        Vec3 p=applyMat4Point(model,v.px,v.py,v.pz);
        mnX=std::min(mnX,p.x); mxX=std::max(mxX,p.x);
        mnY=std::min(mnY,p.y); mxY=std::max(mxY,p.y);
        mnZ=std::min(mnZ,p.z); mxZ=std::max(mxZ,p.z);
    }
    if(mxX<=mnX||mxY<=mnY||mxZ<=mnZ) return {};
    const float radius=0.5f*std::sqrt((mxX-mnX)*(mxX-mnX)+(mxY-mnY)*(mxY-mnY)+(mxZ-mnZ)*(mxZ-mnZ));
    if(radius<1e-6f) return {};

    // ── World-space ring frame: centre, axis, two in-plane basis vectors ─────
    Vec3 cW=applyMat4Point(model, m_ring.center.x, m_ring.center.y, m_ring.center.z);
    Vec3 aW=(applyMat4Point(model, m_ring.center.x+m_ring.axis.x,
                            m_ring.center.y+m_ring.axis.y,
                            m_ring.center.z+m_ring.axis.z)-cW).normalized();
    if(aW.length()<1e-6f) aW={0,1,0};
    Vec3 up{0,1,0};
    Vec3 u = aW.cross(up);
    if(u.length()<1e-6f) u = aW.cross(Vec3{1,0,0});
    u=u.normalized();
    Vec3 v = aW.cross(u).normalized();

    // World length of one normalized unit along each ring direction.
    float scaleU=(applyMat4Point(model, m_ring.center.x+u.x, m_ring.center.y+u.y,
                                 m_ring.center.z+u.z)-cW).length();
    if(scaleU<1e-9f) scaleU=1.f;
    float scaleA=(applyMat4Point(model, m_ring.center.x+aW.x, m_ring.center.y+aW.y,
                                 m_ring.center.z+aW.z)-cW).length();
    if(scaleA<1e-9f) scaleA=1.f;

    float iRN=m_ring.currentInnerR>0.f?m_ring.currentInnerR:m_ring.innerR;
    float oRN=m_ring.currentOuterR>0.f?m_ring.currentOuterR:m_ring.outerR;
    float hN =m_ring.currentHeight>0.f?m_ring.currentHeight:m_ring.heightAx;
    float iR=iRN*scaleU, oR=oRN*scaleU, hh=hN*scaleA*0.5f;
    float barWorld = 10.0f*scaleU/std::max(mmPerUnit(),1e-6f); // 10 mm bar, world units

    // ── Fit camera to the ring centre ────────────────────────────────────────
    m_panX=cW.x; m_panY=cW.y;
    m_camDist=2.0f*radius/std::tan(60.0f*0.5f*DEG2RAD)*0.6f;   // ring fills ~60%
    if(m_camDist<0.01f) m_camDist=0.01f;
    // Aim the fixed-up camera so the view direction (eye→target) equals d.
    // Pitch is capped just short of ±90° so up=(0,1,0) never goes parallel to
    // the view direction (that would make lookAt degenerate for a top view).
    auto aimAt=[&](const Vec3& d){
        if(d.y>=0.9999f){ m_camPitch=-PI*0.5f+0.15f; m_camYaw=0.f; }
        else if(d.y<=-0.9999f){ m_camPitch=PI*0.5f-0.15f; m_camYaw=0.f; }
        else { m_camPitch=asinf(-d.y); m_camYaw=atan2f(-d.x,-d.z); }
    };
    Vec3 viewDir;
    if(view==1){ // SIDE: look edge-on along an in-plane axis (never world-up)
        Vec3 cands[4]={u, Vec3{-u.x,-u.y,-u.z}, v, Vec3{-v.x,-v.y,-v.z}};
        float bestScore=-1.f;
        for(int i=0;i<4;i++){
            const float dy=fabsf(cands[i].y);
            if(dy>0.999f) continue;         // degenerate with fixed up=(0,1,0)
            const float score=1.f-dy;       // prefer a level camera
            if(score>bestScore){ bestScore=score; viewDir=cands[i]; }
        }
        if(bestScore<0.f) viewDir=u;        // safety fallback
    } else {        // TOP: look along the ring axis (eye above the ring)
        viewDir = (aW.y>0.f) ? Vec3{-aW.x,-aW.y,-aW.z} : aW;
    }
    aimAt(viewDir);
    m_gizmoMode=0; m_rulerHasP1=false; m_rulerHasP2=false;

    // Render the fitted view, then draw the callouts on top.
    draw();

    // Per-view callout axis: red/cyan lines must lie perpendicular to the view
    // direction or they vanish (seen end-on). Height stays along the ring axis.
    Vec3 dia=u;                             // TOP: u crosses the opening
    if(view==1 && fabsf(viewDir.dot(u)) >= fabsf(viewDir.dot(v))) dia=v;
    Vec3 barC = cW - dia*(oR*1.5f);         // beside the ring, in the clear

    Mat4 proj=Mat4::perspective(60.0f*DEG2RAD,(float)m_width/std::max(m_height,1),0.01f,100.0f);
    Vec3 eye=cameraEye();
    Mat4 lookView=Mat4::lookAt(eye,{m_panX,m_panY,0},{0,1,0});
    Mat4 vp=proj*lookView;

    auto line=[&](const Vec3& a,const Vec3& b,float r,float g,float bl,float wdt){
        float pts[6]={a.x,a.y,a.z,b.x,b.y,b.z};
        glUseProgram(m_wireProg);
        glUniformMatrix4fv(m_uloc.wireMvp,1,GL_FALSE,vp.m);
        glUniform4f(m_uloc.wireColor,r,g,bl,1.0f);
        glUniform1f(m_uloc.wirePointSize,1.0f);
        glDisable(GL_DEPTH_TEST);
        glLineWidth(wdt);
        glBindVertexArray(m_rulerVao);
        glBindBuffer(GL_ARRAY_BUFFER,m_rulerVbo);
        glBufferSubData(GL_ARRAY_BUFFER,0,sizeof(pts),pts);
        glDrawArrays(GL_LINES,0,2);
        glBindVertexArray(0);
        glEnable(GL_DEPTH_TEST);
    };

    line(cW-dia*iR, cW+dia*iR, 1.0f,0.25f,0.25f, 3.0f);          // red: inner diameter
    line(cW+dia*iR, cW+dia*oR, 0.2f,0.85f,1.0f, 3.0f);           // cyan: band width
    line(cW-aW*hh, cW+aW*hh, 0.3f,1.0f,0.45f, 3.0f);             // green: height (along axis)
    line(barC-dia*(barWorld*0.5f), barC+dia*(barWorld*0.5f), 1.0f,1.0f,1.0f, 4.0f); // white: 10 mm
    glLineWidth(1.0f);

    std::vector<uint8_t> p((size_t)m_width*m_height*4);
    glReadPixels(0,0,m_width,m_height,GL_RGBA,GL_UNSIGNED_BYTE,p.data());
    int rb=m_width*4; std::vector<uint8_t> row((size_t)rb);
    for(int y=0;y<m_height/2;++y){
        uint8_t* top=p.data()+y*rb,*bot=p.data()+(m_height-1-y)*rb;
        memcpy(row.data(),top,rb); memcpy(top,bot,rb); memcpy(bot,row.data(),rb);
    }

    // ── Restore camera + overlay state ──────────────────────────────────────
    m_camYaw=saveYaw; m_camPitch=savePitch; m_camDist=saveDist;
    m_panX=savePanX; m_panY=savePanY;
    m_gizmoMode=saveGizmo;
    m_rulerHasP1=saveR1; m_rulerHasP2=saveR2;
    return p;
}

int Renderer::getMeshVertexCount(int idx) const {
    if (idx < 0 || idx >= (int)m_meshes.size()) return 0;
    return (int)m_meshes[idx].vertices.size();
}
