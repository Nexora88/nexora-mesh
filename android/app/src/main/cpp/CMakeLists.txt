cmake_minimum_required(VERSION 3.22.1)
project(nexora_mesh_jni LANGUAGES C CXX)

option(MESH_NATIVE_ENABLED "Build Rust-backed relay JNI support" ON)

if(NOT MESH_NATIVE_ENABLED)
    message(STATUS "Building direct Bluetooth Classic chat only; Rust relay JNI is disabled.")
    add_library(mesh_jni SHARED mesh_jni_direct_stub.cpp)
    target_compile_features(mesh_jni PRIVATE cxx_std_17)
    target_compile_options(mesh_jni PRIVATE -Wall -Wextra -Werror)
    return()
endif()

set(MESH_CORE_LIBRARY "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmesh_core.so")
if(NOT EXISTS "${MESH_CORE_LIBRARY}")
    message(FATAL_ERROR "Rust mesh-core library missing for ABI ${ANDROID_ABI}: ${MESH_CORE_LIBRARY}")
endif()

add_library(mesh_core SHARED IMPORTED)
set_target_properties(mesh_core PROPERTIES IMPORTED_LOCATION "${MESH_CORE_LIBRARY}")

add_library(mesh_jni SHARED mesh_jni.cpp)
target_compile_features(mesh_jni PRIVATE cxx_std_17)
target_compile_options(mesh_jni PRIVATE -Wall -Wextra -Werror)
target_include_directories(mesh_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../crates/mesh-core/include")
find_library(android_log log REQUIRED)
target_link_libraries(mesh_jni PRIVATE mesh_core ${android_log})
