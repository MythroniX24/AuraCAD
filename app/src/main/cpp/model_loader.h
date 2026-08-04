#pragma once
#include <vector>
#include <string>
#include "math_utils.h"

// Interleaved vertex: pos(3) + normal(3) + uv(2)
struct Vertex {
    float px, py, pz;
    float nx, ny, nz;
    float u,  v;
};

struct ModelData {
    std::vector<Vertex>       vertices;
    std::vector<unsigned int> indices;

    // Normalization info
    Vec3  centerOffset;
    float normalizeScale = 1.0f;   // invScale applied to vertices

    // Original bounding box in MODEL FILE UNITS (mm for STL/OBJ, m for GLB)
    float origSizeX = 1.0f;   // width  (X)
    float origSizeY = 1.0f;   // height (Y)
    float origSizeZ = 1.0f;   // depth  (Z)
    float unitToMM  = 1.0f;   // multiply origSize* by this to get mm
                               // STL/OBJ = 1.0, GLB = 1000.0

    bool hasNormals = false;
    bool hasTex     = false;

    void clear() {
        vertices.clear(); indices.clear();
        centerOffset = {0,0,0}; normalizeScale = 1.0f;
        origSizeX = origSizeY = origSizeZ = 1.0f;
        unitToMM  = 1.0f;
        hasNormals = hasTex = false;
    }

    // Helpers: original size in mm
    float widthMM()  const { return origSizeX * unitToMM; }
    float heightMM() const { return origSizeY * unitToMM; }
    float depthMM()  const { return origSizeZ * unitToMM; }
};

class ModelLoader {
public:
    static bool load(const std::string& path, ModelData& data);
private:
    static bool loadOBJ(const std::string& path, ModelData& data);
    static bool loadSTL(const std::string& path, ModelData& data);
    static bool loadGLB(const std::string& path, ModelData& data);
    static bool loadPLY(const std::string& path, ModelData& data);   // ASCII + binary_little_endian
    static bool load3DS(const std::string& path, ModelData& data);   // chunk-based legacy 3DS
    static void generateFlatNormals(ModelData& data);
    static void normalizeModel(ModelData& data);
};
