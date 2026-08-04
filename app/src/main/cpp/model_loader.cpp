#include "model_loader.h"
#include <fstream>
#include <sstream>
#include <unordered_map>
#include <cstring>
#include <cfloat>
#include <climits>
#include <android/log.h>
#include <algorithm>
#include <limits>
#include <cctype>
#include <functional>
#include <iterator>
#include <utility>
#include <cstdint>

#define TINYOBJLOADER_IMPLEMENTATION
#include "tiny_obj_loader.h"

#define TINYGLTF_IMPLEMENTATION
#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "tiny_gltf.h"

#define TAG "ModelLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Dispatch ─────────────────────────────────────────────────────────────────
bool ModelLoader::load(const std::string& path, ModelData& data) {
    data.clear();
    std::string ext = path;
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);

    bool ok = false;
    if      (ext.size()>=4 && ext.rfind(".obj")==ext.size()-4) ok=loadOBJ(path,data);
    else if (ext.size()>=4 && ext.rfind(".stl")==ext.size()-4) ok=loadSTL(path,data);
    else if (ext.size()>=4 && ext.rfind(".glb")==ext.size()-4) ok=loadGLB(path,data);
    else if (ext.size()>=4 && ext.rfind(".ply")==ext.size()-4) ok=loadPLY(path,data);
    else if (ext.size()>=4 && ext.rfind(".3ds")==ext.size()-4) ok=load3DS(path,data);
    else { LOGE("Unsupported: %s", path.c_str()); return false; }

    if (!ok) { LOGE("Failed: %s", path.c_str()); return false; }
    if (!data.hasNormals) generateFlatNormals(data);
    normalizeModel(data);
    LOGI("Loaded %zu verts, %zu idx | size %.1fx%.1fx%.1f mm",
         data.vertices.size(), data.indices.size(),
         data.widthMM(), data.heightMM(), data.depthMM());
    return true;
}

// ── OBJ ──────────────────────────────────────────────────────────────────────
bool ModelLoader::loadOBJ(const std::string& path, ModelData& data) {
    tinyobj::ObjReaderConfig cfg;
    cfg.triangulate   = true;
    cfg.vertex_color  = false;
    // Enable multi-threaded parsing in tinyobj (uses std::thread internally)
    tinyobj::ObjReader reader;
    if (!reader.ParseFromFile(path, cfg)) {
        LOGE("OBJ: %s", reader.Error().c_str()); return false;
    }
    const auto& attrib = reader.GetAttrib();
    const auto& shapes = reader.GetShapes();
    data.hasNormals = !attrib.normals.empty();
    data.hasTex     = !attrib.texcoords.empty();
    data.unitToMM   = 1.0f;

    // Count total indices so we can reserve upfront (zero reallocs)
    size_t totalIdx = 0;
    for (const auto& s : shapes) totalIdx += s.mesh.indices.size();
    data.indices.reserve(totalIdx);
    data.vertices.reserve(totalIdx / 3); // rough estimate

    // Fast integer-key hash map: encode (vi, ni, ti) as a single 64-bit key
    // Much faster than snprintf + unordered_map<string>
    struct IdxKey {
        int vi, ni, ti;
        bool operator==(const IdxKey& o) const {
            return vi==o.vi && ni==o.ni && ti==o.ti;
        }
    };
    struct IdxHash {
        size_t operator()(const IdxKey& k) const {
            // FNV-1a style mix
            size_t h = 2166136261u;
            h ^= (size_t)(k.vi+1); h *= 16777619u;
            h ^= (size_t)(k.ni+1); h *= 16777619u;
            h ^= (size_t)(k.ti+1); h *= 16777619u;
            return h;
        }
    };
    std::unordered_map<IdxKey, unsigned int, IdxHash> idxMap;
    idxMap.reserve(totalIdx);

    // Bounds-check guard: tinyobj has been seen to emit out-of-range indices on
    // malformed OBJ.  We bail with a logged error rather than UB-crash.
    const int nVerts = (int)(attrib.vertices.size() / 3);
    const int nNorms = (int)(attrib.normals .size() / 3);
    const int nTexs  = (int)(attrib.texcoords.size() / 2);

    for (const auto& shape : shapes) {
        for (const auto& idx : shape.mesh.indices) {
            if (idx.vertex_index < 0 || idx.vertex_index >= nVerts) {
                LOGE("OBJ: vertex_index %d out of range [0,%d) — skipping", idx.vertex_index, nVerts);
                continue;
            }
            IdxKey key{idx.vertex_index, idx.normal_index, idx.texcoord_index};
            auto [it, inserted] = idxMap.emplace(key, (unsigned int)data.vertices.size());
            if (!inserted) {
                data.indices.push_back(it->second);
                continue;
            }
            Vertex v{};
            int vi = idx.vertex_index;
            v.px = attrib.vertices[3*vi+0];
            v.py = attrib.vertices[3*vi+1];
            v.pz = attrib.vertices[3*vi+2];
            if (data.hasNormals && idx.normal_index >= 0 && idx.normal_index < nNorms) {
                int ni = idx.normal_index;
                v.nx = attrib.normals[3*ni+0];
                v.ny = attrib.normals[3*ni+1];
                v.nz = attrib.normals[3*ni+2];
            }
            if (data.hasTex && idx.texcoord_index >= 0 && idx.texcoord_index < nTexs) {
                int ti = idx.texcoord_index;
                v.u = attrib.texcoords[2*ti+0];
                v.v = attrib.texcoords[2*ti+1];
            }
            data.vertices.push_back(v);
            data.indices.push_back(it->second);
        }
    }
    // Free the dedup map immediately — for a 10M-vertex OBJ this is ~250 MB.
    idxMap.clear(); std::unordered_map<IdxKey, unsigned int, IdxHash>().swap(idxMap);
    return !data.vertices.empty();
}

// ── STL ──────────────────────────────────────────────────────────────────────
//
// Format detection adapted from OpenSCAD src/io/import_stl.cc — the "solid"
// prefix is NOT a reliable ASCII marker (some binary STL exporters from CAD
// software emit the literal "solid" in the header).  The robust check is:
//
//     binary STL file_size == 80 (header) + 4 (triCount) + 50 * triCount
//
// We also defend against:
//   • triCount lying about the file (truncated downloads)
//   • triCount > 100 M (≥ 5 GB of allocations, certain OOM on Android)
//   • mid-stream read failures (corrupted ZIPs, network filesystems)
bool ModelLoader::loadSTL(const std::string& path, ModelData& data) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    const std::streamoff fileSize = f.tellg();
    if (fileSize < 84) { LOGE("STL too small: %lld bytes", (long long)fileSize); return false; }
    f.seekg(0, std::ios::beg);

    char header[80] = {};
    f.read(header, 80);
    if (f.fail()) return false;
    data.unitToMM = 1.0f;  // STL standard = mm

    uint32_t triCount = 0;
    f.read(reinterpret_cast<char*>(&triCount), 4);
    if (f.fail()) return false;

    // Robust binary detection: file size matches the binary layout exactly.
    const std::streamoff binarySize = 84LL + 50LL * (std::streamoff)triCount;
    bool isBinary = (triCount > 0
                     && triCount < 100u * 1000u * 1000u   // sane cap: 100M tris
                     && fileSize == binarySize);

    // Fallback heuristic: if triCount is implausible OR file size doesn't match,
    // try ASCII parse (the "solid" prefix alone is unreliable — see above).
    if (!isBinary) {
        f.close();
        std::ifstream tf(path);
        if (!tf) return false;
        std::string line; Vec3 normal{};
        while (std::getline(tf, line)) {
            std::istringstream ss(line); std::string tok; ss >> tok;
            if (tok == "facet") { std::string n; ss >> n >> normal.x >> normal.y >> normal.z; }
            else if (tok == "vertex") {
                Vertex v{}; ss >> v.px >> v.py >> v.pz;
                v.nx = normal.x; v.ny = normal.y; v.nz = normal.z;
                data.indices.push_back((unsigned int)data.vertices.size());
                data.vertices.push_back(v);
            }
        }
        if (data.vertices.empty()) {
            LOGE("STL: not binary (size mismatch: file=%lld expected=%lld) AND ASCII parse produced no vertices",
                 (long long)fileSize, (long long)binarySize);
            return false;
        }
        data.hasNormals = true;
        return true;
    }

    // Binary path with vertex dedup — STL stores 3 UNIQUE vertices per triangle,
    // so a 10M-tri file arrives as 30M vertices with most bit-identical. Sharing
    // them (keyed on exact float bits) cuts memory + GPU load ~3x, which is what
    // lets genuinely large models load at all on a phone. Normals are left to
    // generateFlatNormals() afterwards (hasNormals=false) since per-vertex
    // normals from a faceted format are meaningless once vertices are shared.
    std::unordered_map<uint64_t, uint32_t> weld;
    weld.reserve((size_t)triCount * 3);
    data.vertices.reserve((size_t)triCount + (size_t)triCount/2);
    data.indices .reserve((size_t)triCount * 3);
    for (uint32_t i = 0; i < triCount; ++i) {
        float n[3], p[3][3];
        f.read(reinterpret_cast<char*>(n), 12);
        for (int j = 0; j < 3; ++j) f.read(reinterpret_cast<char*>(p[j]), 12);
        uint16_t att; f.read(reinterpret_cast<char*>(&att), 2);
        if (f.fail()) {
            LOGE("STL: read failed at triangle %u/%u — file truncated?", i, triCount);
            break;   // keep what we have so far
        }
        for (int j = 0; j < 3; ++j) {
            uint64_t key = 1469598103934665603ull;
            for (int c = 0; c < 3; ++c) {
                uint32_t bits; memcpy(&bits, &p[j][c], 4);
                key ^= bits; key *= 1099511628211ull;
            }
            auto [it, inserted] = weld.emplace(key, (uint32_t)data.vertices.size());
            if (inserted) {
                Vertex v{}; v.px = p[j][0]; v.py = p[j][1]; v.pz = p[j][2];
                v.nx = n[0]; v.ny = n[1]; v.nz = n[2];
                data.vertices.push_back(v);
            }
            data.indices.push_back(it->second);
        }
    }
    weld.clear(); std::unordered_map<uint64_t,uint32_t>().swap(weld);
    data.hasNormals = false;   // flat normals regenerated in load()
    return !data.vertices.empty();
}

// ── GLB ──────────────────────────────────────────────────────────────────────
bool ModelLoader::loadGLB(const std::string& path, ModelData& data) {
    tinygltf::Model model; tinygltf::TinyGLTF loader;
    std::string err,warn;
    if (!loader.LoadBinaryFromFile(&model,&err,&warn,path)) { LOGE("GLB: %s",err.c_str()); return false; }
    data.unitToMM = 1000.0f;  // GLB uses meters → convert to mm

    for (const auto& mesh : model.meshes) {
        for (const auto& prim : mesh.primitives) {
            if (prim.mode!=TINYGLTF_MODE_TRIANGLES && prim.mode!=TINYGLTF_MODE_TRIANGLE_STRIP) continue;
            unsigned int baseVertex=(unsigned int)data.vertices.size();
            auto posIt=prim.attributes.find("POSITION");
            if (posIt==prim.attributes.end()) continue;
            const auto& posAcc=model.accessors[posIt->second];
            const auto& posView=model.bufferViews[posAcc.bufferView];
            const float* positions=reinterpret_cast<const float*>(
                model.buffers[posView.buffer].data.data()+posView.byteOffset+posAcc.byteOffset);
            const float* normals=nullptr;
            auto normIt=prim.attributes.find("NORMAL");
            if (normIt!=prim.attributes.end()) {
                const auto& acc=model.accessors[normIt->second];
                const auto& view=model.bufferViews[acc.bufferView];
                normals=reinterpret_cast<const float*>(model.buffers[view.buffer].data.data()+view.byteOffset+acc.byteOffset);
                data.hasNormals=true;
            }
            const float* uvs=nullptr;
            auto uvIt=prim.attributes.find("TEXCOORD_0");
            if (uvIt!=prim.attributes.end()) {
                const auto& acc=model.accessors[uvIt->second];
                const auto& view=model.bufferViews[acc.bufferView];
                uvs=reinterpret_cast<const float*>(model.buffers[view.buffer].data.data()+view.byteOffset+acc.byteOffset);
                data.hasTex=true;
            }
            for (size_t vi=0;vi<posAcc.count;++vi) {
                Vertex v{};
                v.px=positions[vi*3+0]; v.py=positions[vi*3+1]; v.pz=positions[vi*3+2];
                if (normals){v.nx=normals[vi*3+0];v.ny=normals[vi*3+1];v.nz=normals[vi*3+2];}
                if (uvs)    {v.u=uvs[vi*2+0];     v.v=uvs[vi*2+1];}
                data.vertices.push_back(v);
            }
            if (prim.indices>=0) {
                const auto& idxAcc=model.accessors[prim.indices];
                const auto& idxView=model.bufferViews[idxAcc.bufferView];
                const uint8_t* raw=model.buffers[idxView.buffer].data.data()+idxView.byteOffset+idxAcc.byteOffset;
                for (size_t ii=0;ii<idxAcc.count;++ii) {
                    unsigned int idx;
                    if      (idxAcc.componentType==TINYGLTF_COMPONENT_TYPE_UNSIGNED_SHORT) idx=reinterpret_cast<const uint16_t*>(raw)[ii];
                    else if (idxAcc.componentType==TINYGLTF_COMPONENT_TYPE_UNSIGNED_INT)   idx=reinterpret_cast<const uint32_t*>(raw)[ii];
                    else                                                                    idx=reinterpret_cast<const uint8_t*> (raw)[ii];
                    data.indices.push_back(baseVertex+idx);
                }
            } else {
                for (unsigned int ii=baseVertex;ii<(unsigned int)data.vertices.size();++ii) data.indices.push_back(ii);
            }
        }
    }
    return !data.vertices.empty();
}

// ── PLY ──────────────────────────────────────────────────────────────────────
// Supports ASCII and binary_little_endian with any property layout. Only
// x/y/z (and optional nx/ny/nz) are read; colour and other properties are
// skipped via record stride. Faces are read as "3 a b c" (ASCII) or
// "list uchar int" (binary); non-triangle faces are skipped.
bool ModelLoader::loadPLY(const std::string& path, ModelData& data) {
    std::ifstream f(path, std::ios::binary);
    if(!f) return false;

    std::string line;
    std::getline(f, line);
    if(line.find("ply")==std::string::npos) return false;
    std::getline(f, line);
    bool isAscii   = line.find("ascii")!=std::string::npos;
    bool isBinLE   = line.find("binary_little_endian")!=std::string::npos;
    if(!isAscii && !isBinLE){ LOGE("PLY: unsupported format line: %s", line.c_str()); return false; }

    auto typeSize = [](const std::string& t)->int{
        if(t=="char"||t=="uchar"||t=="int8"||t=="uint8") return 1;
        if(t=="short"||t=="ushort"||t=="int16"||t=="uint16") return 2;
        if(t=="double"||t=="float64") return 8;
        return 4;  // int/uint/float/float32/float64-named
    };

    int vertexCount=-1, faceCount=-1;
    std::vector<std::pair<std::string,int>> vProps;  // (name, byte size)
    int listCountSize=1, indexSize=4;                // face list layout
    std::string tok;
    bool done=false;
    while(!done && std::getline(f, line)){
        std::istringstream ss(line);
        ss>>tok;
        if(tok=="comment") continue;
        else if(tok=="element"){
            std::string name; ss>>name;
            int n=0; ss>>n;
            if(name=="vertex") vertexCount=n;
            else if(name=="face") faceCount=n;
        }
        else if(tok=="property"){
            std::string a; ss>>a;
            if(a=="list"){
                std::string ct,it,nm; ss>>ct>>it>>nm;
                listCountSize=typeSize(ct); indexSize=typeSize(it);
            } else {
                std::string nm; ss>>nm;
                if(vertexCount>=0 && faceCount<0) vProps.push_back({nm,typeSize(a)});
            }
        }
        else if(tok=="end_header") done=true;
    }
    // Stream is positioned exactly after the "end_header\n" line — that is
    // where the payload begins for BOTH ascii and binary formats.
    std::streamoff dataStart = f.tellg();
    if(vertexCount<=0 || vertexCount>50*1000*1000){ LOGE("PLY: bad vertex count %d", vertexCount); return false; }

    int offX=-1,offY=-1,offZ=-1,offNX=-1,offNY=-1,offNZ=-1;
    int stride=0;
    for(auto& p : vProps){
        if(p.first=="x")  offX=stride;
        if(p.first=="y")  offY=stride;
        if(p.first=="z")  offZ=stride;
        if(p.first=="nx") offNX=stride;
        if(p.first=="ny") offNY=stride;
        if(p.first=="nz") offNZ=stride;
        stride+=p.second;
    }
    if(stride<=0) stride=12;
    if(offX<0||offY<0||offZ<0){ LOGE("PLY: missing x/y/z properties"); return false; }
    bool hasN = (offNX>=0&&offNY>=0&&offNZ>=0);

    data.unitToMM = 1.0f;   // PLY carries no unit metadata; treat as mm
    data.vertices.reserve((size_t)vertexCount);
    if(faceCount>0) data.indices.reserve((size_t)faceCount*3);

    if(isAscii){
        for(int i=0;i<vertexCount && std::getline(f,line);++i){
            std::istringstream ss(line);
            float vv[6]={0,0,0,0,0,0}; int vi=0; float tmp;
            while(vi<6 && (ss>>tmp)) vv[vi++]=tmp;
            Vertex v{}; v.px=vv[0]; v.py=vv[1]; v.pz=vv[2];
            if(hasN && vi>=6){ v.nx=vv[3]; v.ny=vv[4]; v.nz=vv[5]; data.hasNormals=true; }
            data.vertices.push_back(v);
        }
        if(faceCount>0){
            for(int i=0;i<faceCount && std::getline(f,line);++i){
                std::istringstream ss(line);
                int cnt; ss>>cnt;
                if(cnt!=3) continue;
                unsigned int a,b,c; ss>>a>>b>>c;
                if(a<(unsigned)data.vertices.size() && b<(unsigned)data.vertices.size() && c<(unsigned)data.vertices.size()){
                    data.indices.push_back(a); data.indices.push_back(b); data.indices.push_back(c);
                }
            }
        }
        return !data.vertices.empty();
    }

    // Binary little-endian — read ONLY the payload that follows the header
    // (dataStart was captured right after "end_header\n" was consumed).
    f.seekg(0, std::ios::end);
    size_t fsize=(size_t)f.tellg();
    size_t payload = (fsize > (size_t)dataStart) ? (fsize-(size_t)dataStart) : 0;
    f.seekg(dataStart);
    std::vector<uint8_t> buf(payload);
    f.read(reinterpret_cast<char*>(buf.data()), (std::streamsize)payload);
    const uint8_t* p=buf.data();
    const uint8_t* end=buf.data()+buf.size();
    auto rdF32=[&](const uint8_t* q)->float{ uint32_t u; memcpy(&u,q,4); float fv; memcpy(&fv,&u,4); return fv; };
    const uint8_t* q=p;
    for(int i=0;i<vertexCount && q+stride<=end;++i,q+=stride){
        Vertex v{};
        v.px=rdF32(q+offX); v.py=rdF32(q+offY); v.pz=rdF32(q+offZ);
        if(hasN){ v.nx=rdF32(q+offNX); v.ny=rdF32(q+offNY); v.nz=rdF32(q+offNZ); data.hasNormals=true; }
        data.vertices.push_back(v);
    }
    // Faces: [count-type][count][indices...]
    if(faceCount>0){
        for(int i=0;i<faceCount && q<end;++i){
            int cnt=0;
            if(listCountSize==1)      cnt=*q++;
            else if(listCountSize==2){ uint16_t c; memcpy(&c,q,2); cnt=(int)c; q+=2; }
            else                     { uint32_t c; memcpy(&c,q,4); cnt=(int)c; q+=4; }
            if(cnt!=3){ q+=(size_t)cnt*indexSize; continue; }   // skip non-tri faces
            unsigned int a=0,b=0,c=0;
            if(indexSize==2){ memcpy(&a,q,2); memcpy(&b,q+2,2); memcpy(&c,q+4,2); }
            else            { memcpy(&a,q,4); memcpy(&b,q+4,4); memcpy(&c,q+8,4); }
            q+=(size_t)cnt*indexSize;
            if(a<(unsigned)data.vertices.size() && b<(unsigned)data.vertices.size() && c<(unsigned)data.vertices.size()){
                data.indices.push_back(a); data.indices.push_back(b); data.indices.push_back(c);
            }
        }
    }
    return !data.vertices.empty();
}

// ── 3DS ──────────────────────────────────────────────────────────────────────
// Legacy Autodesk 3D Studio chunk format. We walk the chunk tree by declared
// length (skipping unknown chunks), reading triangular meshes: 0x4110 vertex
// lists + 0x4120 face lists under each 0x4100 tri-mesh under each 0x4000
// object. 3DS has no unit metadata — treated as mm (1 unit = 1 mm).
namespace {
struct Chunk3DS {
    const uint8_t* base;
    const uint8_t* p;
    const uint8_t* end;
    bool ok() const { return p<=end; }
    uint16_t u16(){ if(p+2>end) return 0; uint16_t v; memcpy(&v,p,2); p+=2; return v; }
    uint32_t u32(){ if(p+4>end) return 0; uint32_t v; memcpy(&v,p,4); p+=4; return v; }
    float    f32(){ if(p+4>end) return 0; float v; memcpy(&v,p,4); p+=4; return v; }
    size_t offset() const { return (size_t)(p-base); }
    std::string name(){
        std::string s;
        while(p<end && *p) s.push_back((char)*p++);
        if(p<end) ++p;                 // NUL
        if(offset() & 1) ++p;          // legacy padding to even length
        return s;
    }
    void skip(size_t n){ p+=n; if(p>end) p=end; }
};
}  // namespace

bool ModelLoader::load3DS(const std::string& path, ModelData& data){
    std::ifstream f(path, std::ios::binary);
    if(!f) return false;
    std::vector<uint8_t> buf((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if(buf.size()<16) return false;
    Chunk3DS c; c.base=buf.data(); c.p=buf.data(); c.end=buf.data()+buf.size();
    data.unitToMM = 1.0f;

    unsigned int meshBase=0;
    std::function<void(Chunk3DS&, const uint8_t*)> walk = [&](Chunk3DS& cc, const uint8_t* limit){
        while(cc.ok() && cc.p+6<=limit){
            uint16_t id=cc.u16();
            uint32_t len=cc.u32();
            if(len<6) break;                                   // corrupt chunk
            const uint8_t* chunkEnd = cc.p + (len-6);
            if(chunkEnd>limit) chunkEnd=limit;                 // truncated file
            switch(id){
                case 0x4D4D:  // main
                case 0x3D3D:  // 3D editor
                    walk(cc, chunkEnd);
                    break;
                case 0x4000:  // object → name + children
                    cc.name();
                    walk(cc, chunkEnd);
                    break;
                case 0x4100:  // triangular mesh → children (verts/faces)
                    meshBase=(unsigned int)data.vertices.size();
                    walk(cc, chunkEnd);
                    break;
                case 0x4110: { // vertex list: u16 count + 3*count floats
                    uint16_t n=cc.u16();
                    data.vertices.reserve(data.vertices.size()+n);
                    for(uint16_t i=0;i<n && cc.p+12<=chunkEnd;++i){
                        Vertex v{}; v.px=cc.f32(); v.py=cc.f32(); v.pz=cc.f32();
                        data.vertices.push_back(v);
                    }
                    break;
                }
                case 0x4120: { // face list: u16 count + (3*u16 idx + u16 flags)
                    uint16_t n=cc.u16();
                    for(uint16_t i=0;i<n && cc.p+8<=chunkEnd;++i){
                        unsigned int a=meshBase+cc.u16(), b=meshBase+cc.u16(), d=meshBase+cc.u16();
                        cc.u16();  // edge visibility flags
                        if(a<(unsigned)data.vertices.size() && b<(unsigned)data.vertices.size() && d<(unsigned)data.vertices.size()){
                            data.indices.push_back(a); data.indices.push_back(b); data.indices.push_back(d);
                        }
                    }
                    break;
                }
                default:
                    cc.skip(chunkEnd-cc.p);
                    break;
            }
            if(cc.p<chunkEnd) cc.p=chunkEnd;   // resync on the declared length
        }
    };
    walk(c, c.end);
    return !data.vertices.empty() && !data.indices.empty();
}

// ── Flat normals ─────────────────────────────────────────────────────────────
void ModelLoader::generateFlatNormals(ModelData& data) {
    for (size_t i=0;i+2<data.indices.size();i+=3) {
        auto& v0=data.vertices[data.indices[i+0]];
        auto& v1=data.vertices[data.indices[i+1]];
        auto& v2=data.vertices[data.indices[i+2]];
        Vec3 a{v1.px-v0.px,v1.py-v0.py,v1.pz-v0.pz};
        Vec3 b{v2.px-v0.px,v2.py-v0.py,v2.pz-v0.pz};
        Vec3 n=a.cross(b).normalized();
        for (int k=0;k<3;++k) {
            auto& vk=data.vertices[data.indices[i+k]];
            vk.nx=n.x; vk.ny=n.y; vk.nz=n.z;
        }
    }
    data.hasNormals=true;
}

// ── Normalize to [-1,1] sphere + store original bounds ──────────────────────
void ModelLoader::normalizeModel(ModelData& data) {
    if (data.vertices.empty()) return;
    float minX=FLT_MAX,minY=FLT_MAX,minZ=FLT_MAX;
    float maxX=-FLT_MAX,maxY=-FLT_MAX,maxZ=-FLT_MAX;
    for (const auto& v : data.vertices) {
        minX=std::min(minX,v.px); maxX=std::max(maxX,v.px);
        minY=std::min(minY,v.py); maxY=std::max(maxY,v.py);
        minZ=std::min(minZ,v.pz); maxZ=std::max(maxZ,v.pz);
    }
    // Store ORIGINAL sizes in model-file units (before any scaling)
    data.origSizeX = maxX - minX;
    data.origSizeY = maxY - minY;
    data.origSizeZ = maxZ - minZ;

    Vec3 center{(minX+maxX)*0.5f,(minY+maxY)*0.5f,(minZ+maxZ)*0.5f};
    float maxSize=std::max({data.origSizeX,data.origSizeY,data.origSizeZ});
    float invScale=(maxSize>1e-9f)?(2.0f/maxSize):1.0f;

    for (auto& v : data.vertices) {
        v.px=(v.px-center.x)*invScale;
        v.py=(v.py-center.y)*invScale;
        v.pz=(v.pz-center.z)*invScale;
    }
    data.centerOffset   = center;
    data.normalizeScale = invScale;
}
