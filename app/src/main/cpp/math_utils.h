#pragma once
#include <cmath>
#include <cstring>
#include <algorithm>

static constexpr float PI = 3.14159265358979323846f;
static constexpr float DEG2RAD = PI / 180.0f;
static constexpr float RAD2DEG = 180.0f / PI;

// ── Vec3 ─────────────────────────────────────────────────────────────────────
struct Vec3 {
    float x = 0, y = 0, z = 0;
    Vec3() = default;
    Vec3(float x, float y, float z) : x(x), y(y), z(z) {}

    Vec3 operator+(const Vec3& o) const { return {x+o.x, y+o.y, z+o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x-o.x, y-o.y, z-o.z}; }
    Vec3 operator*(float s)        const { return {x*s, y*s, z*s}; }
    Vec3 operator-()               const { return {-x, -y, -z}; }
    Vec3& operator+=(const Vec3& o){ x+=o.x; y+=o.y; z+=o.z; return *this; }
    Vec3& operator/=(float s)      { x/=s; y/=s; z/=s; return *this; }

    float dot(const Vec3& o)  const { return x*o.x + y*o.y + z*o.z; }
    Vec3  cross(const Vec3& o) const {
        return { y*o.z - z*o.y, z*o.x - x*o.z, x*o.y - y*o.x };
    }
    float length() const { return std::sqrt(x*x + y*y + z*z); }
    Vec3 normalized() const {
        float l = length();
        if (l < 1e-9f) return {0,0,0};
        return {x/l, y/l, z/l};
    }
};

// ── Mat4 (column-major, matching OpenGL) ─────────────────────────────────────
struct Mat4 {
    float m[16];   // col0, col1, col2, col3

    Mat4() { identity(); }

    void identity() {
        memset(m, 0, sizeof(m));
        m[0] = m[5] = m[10] = m[15] = 1.0f;
    }

    // Mat4 * Mat4
    Mat4 operator*(const Mat4& r) const {
        Mat4 out;
        for (int col = 0; col < 4; ++col)
            for (int row = 0; row < 4; ++row) {
                float sum = 0;
                for (int k = 0; k < 4; ++k)
                    sum += m[k*4 + row] * r.m[col*4 + k];
                out.m[col*4 + row] = sum;
            }
        return out;
    }

    // Static constructors ────────────────────────────────────────────────────

    static Mat4 translation(float tx, float ty, float tz) {
        Mat4 t; // identity
        t.m[12] = tx; t.m[13] = ty; t.m[14] = tz;
        return t;
    }

    static Mat4 scale(float sx, float sy, float sz) {
        Mat4 s;
        s.m[0] = sx; s.m[5] = sy; s.m[10] = sz;
        return s;
    }

    static Mat4 rotationX(float rad) {
        Mat4 r;
        float c = cosf(rad), s = sinf(rad);
        r.m[5] =  c; r.m[9]  = -s;
        r.m[6] =  s; r.m[10] =  c;
        return r;
    }
    static Mat4 rotationY(float rad) {
        Mat4 r;
        float c = cosf(rad), s = sinf(rad);
        r.m[0] =  c; r.m[8]  =  s;
        r.m[2] = -s; r.m[10] =  c;
        return r;
    }
    static Mat4 rotationZ(float rad) {
        Mat4 r;
        float c = cosf(rad), s = sinf(rad);
        r.m[0] =  c; r.m[4] = -s;
        r.m[1] =  s; r.m[5] =  c;
        return r;
    }

    // Perspective projection
    static Mat4 perspective(float fovY_rad, float aspect, float near, float far) {
        Mat4 p; memset(p.m, 0, sizeof(p.m));
        float f = 1.0f / tanf(fovY_rad * 0.5f);
        p.m[0]  = f / aspect;
        p.m[5]  = f;
        p.m[10] = (far + near) / (near - far);
        p.m[11] = -1.0f;
        p.m[14] = (2.0f * far * near) / (near - far);
        return p;
    }

    // LookAt view matrix
    static Mat4 lookAt(Vec3 eye, Vec3 center, Vec3 up) {
        Vec3 f = (center - eye).normalized();
        Vec3 s = f.cross(up).normalized();
        Vec3 u = s.cross(f);
        Mat4 v;
        v.m[0]  =  s.x; v.m[4]  =  s.y; v.m[8]  =  s.z;
        v.m[1]  =  u.x; v.m[5]  =  u.y; v.m[9]  =  u.z;
        v.m[2]  = -f.x; v.m[6]  = -f.y; v.m[10] = -f.z;
        v.m[12] = -s.dot(eye);
        v.m[13] = -u.dot(eye);
        v.m[14] =  f.dot(eye);
        v.m[15] =  1.0f;
        return v;
    }

    // Normal matrix = inverse-transpose of the upper-left 3x3 of the model
    // matrix, returned as a column-major float[9] for upload as a mat3 uniform.
    //
    // Using the raw 3x3 is only correct for uniform scale; under NON-uniform
    // scale (resizing W/H/D independently) it skews normals and wrecks lighting,
    // which is why a resized model looks "ugly"/faceted.  The inverse-transpose
    // keeps normals perpendicular to the surface for any affine transform.
    void toNormalMatrix(float out[9]) const {
        // Upper-left 3x3, A[row][col] (column-major storage: m[col*4+row]).
        float a00 = m[0], a01 = m[4], a02 = m[8];
        float a10 = m[1], a11 = m[5], a12 = m[9];
        float a20 = m[2], a21 = m[6], a22 = m[10];

        float det = a00 * (a11 * a22 - a12 * a21)
                  - a01 * (a10 * a22 - a12 * a20)
                  + a02 * (a10 * a21 - a11 * a20);

        if (std::fabs(det) < 1e-12f) {
            // Degenerate (an axis scaled to ~0) — fall back to the raw 3x3.
            out[0] = a00; out[1] = a10; out[2] = a20;
            out[3] = a01; out[4] = a11; out[5] = a21;
            out[6] = a02; out[7] = a12; out[8] = a22;
            return;
        }
        float invDet = 1.0f / det;
        // Normal matrix N = cofactor(A) / det  ==  transpose(inverse(A)).
        float c00 =  (a11 * a22 - a12 * a21);
        float c01 = -(a10 * a22 - a12 * a20);
        float c02 =  (a10 * a21 - a11 * a20);
        float c10 = -(a01 * a22 - a02 * a21);
        float c11 =  (a00 * a22 - a02 * a20);
        float c12 = -(a00 * a21 - a01 * a20);
        float c20 =  (a01 * a12 - a02 * a11);
        float c21 = -(a00 * a12 - a02 * a10);
        float c22 =  (a00 * a11 - a01 * a10);
        // Store column-major: out[col*3+row] = N[row][col].
        out[0] = c00 * invDet; out[1] = c10 * invDet; out[2] = c20 * invDet;
        out[3] = c01 * invDet; out[4] = c11 * invDet; out[5] = c21 * invDet;
        out[6] = c02 * invDet; out[7] = c12 * invDet; out[8] = c22 * invDet;
    }
};
