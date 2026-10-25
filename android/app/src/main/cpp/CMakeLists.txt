cmake_minimum_required(VERSION 3.22.1)
project(nexora_mesh_jni LANGUAGES C CXX)

add_library(mesh_jni SHARED mesh_jni.cpp)
target_compile_features(mesh_jni PRIVATE cxx_std_17)
target_compile_options(mesh_jni PRIVATE -Wall -Wextra -Werror)
find_library(android_log log REQUIRED)
target_link_libraries(mesh_jni PRIVATE ${android_log} dl)
