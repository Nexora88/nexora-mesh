#include <jni.h>
#include <cstdint>
#include <vector>
#include "nexora_mesh.h"

namespace {
constexpr jsize kMaxEnvelope = 256 * 1024;

jclass exception_class(JNIEnv* env) {
    return env->FindClass("java/lang/IllegalArgumentException");
}

jbyteArray forward_bytes(JNIEnv* env, jbyteArray input, jlong now_unix) {
    if (input == nullptr) {
        env->ThrowNew(exception_class(env), "Envelope is required");
        return nullptr;
    }
    const jsize length = env->GetArrayLength(input);
    if (length <= 0 || length > kMaxEnvelope) {
        env->ThrowNew(exception_class(env), "Envelope length is outside the allowed range");
        return nullptr;
    }
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return nullptr;
    if (nm_wire_validate(bytes.data(), bytes.size()) != 0) {
        env->ThrowNew(exception_class(env), "Rust rejected the wire envelope");
        return nullptr;
    }

    std::vector<std::uint8_t> output(bytes.size());
    const auto result = nm_wire_forward(bytes.data(), bytes.size(), output.data(), output.size(),
                                        now_unix < 0 ? 0 : static_cast<std::uint64_t>(now_unix));
    if (result < 0 || static_cast<std::size_t>(result) > output.size()) {
        env->ThrowNew(exception_class(env), "Rust refused to forward the envelope");
        return nullptr;
    }
    jbyteArray result_array = env->NewByteArray(static_cast<jsize>(result));
    if (result_array != nullptr) {
        env->SetByteArrayRegion(result_array, 0, static_cast<jsize>(result),
                                reinterpret_cast<const jbyte*>(output.data()));
    }
    return result_array;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_org_nexora_mesh_NativeMesh_validate(JNIEnv* env, jclass, jbyteArray input) {
    if (input == nullptr) return -1;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0 || length > kMaxEnvelope) return -1;
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return -1;
    return nm_wire_validate(bytes.data(), bytes.size());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_nexora_mesh_NativeMesh_forward(JNIEnv* env, jclass, jbyteArray input, jlong now_unix) {
    return forward_bytes(env, input, now_unix);
}
