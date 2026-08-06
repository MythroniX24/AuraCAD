cmake_minimum_required(VERSION 3.22.1)
project("modelviewer")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

FetchContent_Declare(
    tinyobjloader
    GIT_REPOSITORY https://github.com/tinyobjloader/tinyobjloader.git
    GIT_TAG        v2.0.0rc13
    GIT_SHALLOW    TRUE
)

FetchContent_Declare(
    tinygltf
    GIT_REPOSITORY https://github.com/syoyo/tinygltf.git
    GIT_TAG        v2.8.21
    GIT_SHALLOW    TRUE
)

# Rhino 3DM (openNURBS) — official McNeel SDK. Provides ONX_Model read/write
# for .3dm files (meshes + NURBS surfaces/breps tessellated on load).
# The opennurbsStatic target brings its own zlib / freetype / android_uuid
# sub-builds, so everything is self-contained per ABI.
FetchContent_Declare(
    opennurbs
    GIT_REPOSITORY https://github.com/mcneel/openNURBS.git
    GIT_TAG        v8.9.24194.18121
    GIT_SHALLOW    TRUE
    # opennurbs_freetype.cpp calls the Android NDK font APIs (AFontMatcher_*)
    # which only exist since API 29. We build with ANDROID_PLATFORM=android-24,
    # so the patch guards that code with __ANDROID_API__ >= 29 and stubs it
    # otherwise (font faces aren't needed by this app).
    # Idempotent: CI caches app/.cxx (incl. _deps/opennurbs-src), so the
    # checkout may already have the patch applied. The script reverse-checks
    # and only applies when needed (keeps `git apply` from failing on re-runs).
    # NOTE: don't inline shell with ';' here — FetchContent splits
    # PATCH_COMMAND on semicolons, so a helper script is used instead.
    PATCH_COMMAND sh "${CMAKE_CURRENT_SOURCE_DIR}/patch_opennurbs.sh" "${CMAKE_CURRENT_SOURCE_DIR}/opennurbs_android_font.patch"
)

FetchContent_MakeAvailable(tinyobjloader tinygltf opennurbs)

add_library(
    modelviewer
    SHARED
    jni_bridge.cpp
    renderer.cpp
    shader_utils.cpp
    model_loader.cpp
    mesh_separator.cpp
)

target_include_directories(modelviewer PRIVATE
    ${tinyobjloader_SOURCE_DIR}
    ${tinygltf_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib     log)
find_library(android-lib android)
find_library(gles3-lib   GLESv3)
find_library(egl-lib     EGL)

target_link_libraries(modelviewer
    ${log-lib}
    ${android-lib}
    ${gles3-lib}
    ${egl-lib}
    opennurbsStatic
)

# ── Base flags for ALL ABIs ────────────────────────────────────────────────────
target_compile_options(modelviewer PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -funroll-loops
)

# ── ABI-specific march flags ───────────────────────────────────────────────────
# ANDROID_ABI is set by the Android Gradle plugin per-ABI build.
# -march=armv8-a is ONLY valid for ARM targets, never for x86_64/x86.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(modelviewer PRIVATE -march=armv8-a)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(modelviewer PRIVATE -march=armv7-a -mfpu=neon)
elseif(ANDROID_ABI STREQUAL "x86_64")
    target_compile_options(modelviewer PRIVATE -march=x86-64)
elseif(ANDROID_ABI STREQUAL "x86")
    target_compile_options(modelviewer PRIVATE -march=i686)
endif()
