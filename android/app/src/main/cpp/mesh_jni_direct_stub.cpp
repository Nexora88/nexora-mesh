#include <jni.h>

// Direct-chat-only debug builds intentionally disable the Rust relay layer.
// Keep the JNI library loadable so accidental relay calls fail closed.
extern "C" JNIEXPORT jint JNICALL
Java_org_nexora_mesh_NativeMesh_validate(JNIEnv*, jclass, jbyteArray) {
    return -1;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_nexora_mesh_NativeMesh_encodeEnvelope(JNIEnv*, jclass, jbyteArray, jbyteArray,
                                               jint, jlong, jbyteArray) {
    return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_nexora_mesh_NativeMesh_deriveHopKey(JNIEnv*, jclass, jbyteArray, jbyteArray,
                                             jbyteArray, jlong) {
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_nexora_mesh_NativeMesh_verifyStoreAck(JNIEnv*, jclass, jbyteArray, jbyteArray,
                                               jbyteArray, jbyteArray, jlong, jlong) {
    return -1;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_nexora_mesh_NativeMesh_createStoreAck(JNIEnv*, jclass, jbyteArray, jbyteArray,
                                               jbyteArray, jlong, jbyteArray, jbyteArray) {
    return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_nexora_mesh_NativeMesh_forward(JNIEnv* env, jclass, jbyteArray, jlong) {
    return env->NewByteArray(0);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_nexora_mesh_NativeMesh_recommendedScanIntervalSeconds(JNIEnv*, jclass, jint,
                                                               jboolean, jboolean,
                                                               jboolean, jboolean,
                                                               jboolean) {
    return 60;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nexora_mesh_NativeMesh_mayRelay(JNIEnv*, jclass, jboolean, jint,
                                         jboolean, jboolean, jboolean) {
    return JNI_FALSE;
}
