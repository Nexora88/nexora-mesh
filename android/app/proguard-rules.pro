# Keep JNI entry points and Rust bridge methods used through JNI.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class org.nexora.mesh.NativeMesh { *; }
-keep class org.nexora.mesh.MeshNative { *; }
