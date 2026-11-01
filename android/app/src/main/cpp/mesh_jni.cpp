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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_nexora_mesh_NativeMesh_deriveHopKey(JNIEnv* env, jclass, jbyteArray secret,
    jbyteArray peer_a, jbyteArray peer_b, jlong epoch) {
    if (secret == nullptr || peer_a == nullptr || peer_b == nullptr || epoch < 0) return nullptr;
    const jsize secret_len = env->GetArrayLength(secret);
    if (secret_len < 32 || env->GetArrayLength(peer_a) != 16 || env->GetArrayLength(peer_b) != 16) return nullptr;
    std::vector<std::uint8_t> secret_bytes(static_cast<std::size_t>(secret_len));
    std::vector<std::uint8_t> a(16), b(16), output(32);
    env->GetByteArrayRegion(secret, 0, secret_len, reinterpret_cast<jbyte*>(secret_bytes.data()));
    env->GetByteArrayRegion(peer_a, 0, 16, reinterpret_cast<jbyte*>(a.data()));
    env->GetByteArrayRegion(peer_b, 0, 16, reinterpret_cast<jbyte*>(b.data()));
    if (env->ExceptionCheck()) return nullptr;
    const auto written = nm_derive_hop_key(secret_bytes.data(), secret_bytes.size(),
        a.data(), b.data(), static_cast<std::uint64_t>(epoch), output.data(), output.size());
    if (written != 32) return nullptr;
    jbyteArray result = env->NewByteArray(32);
    if (result != nullptr) env->SetByteArrayRegion(result, 0, 32, reinterpret_cast<const jbyte*>(output.data()));
    return result;
}

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

extern "C" JNIEXPORT jlong JNICALL
Java_org_nexora_mesh_NativeMesh_recommendedScanIntervalSeconds(
    JNIEnv*, jclass, jint battery, jboolean charging, jboolean low_power,
    jboolean foreground, jboolean recently_connected, jboolean urgent_pending) {
    const auto value = nm_relay_scan_interval_seconds(
        static_cast<std::uint8_t>(battery < 0 ? 0 : battery > 100 ? 100 : battery),
        charging ? 1 : 0, low_power ? 1 : 0, foreground ? 1 : 0,
        recently_connected ? 1 : 0, urgent_pending ? 1 : 0);
    return static_cast<jlong>(value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nexora_mesh_NativeMesh_mayRelay(
    JNIEnv*, jclass, jboolean opted_in, jint battery, jboolean charging,
    jboolean low_power, jboolean foreground) {
    const auto value = nm_relay_may_forward(
        opted_in ? 1 : 0,
        static_cast<std::uint8_t>(battery < 0 ? 0 : battery > 100 ? 100 : battery),
        charging ? 1 : 0, low_power ? 1 : 0, foreground ? 1 : 0);
    return value == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_nexora_mesh_NativeMesh_verifyStoreAck(JNIEnv* env, jclass, jbyteArray ack,
    jbyteArray key, jbyteArray expected_from, jbyteArray expected_to,
    jlong now_unix, jlong max_age) {
    if (ack == nullptr || key == nullptr || expected_from == nullptr || expected_to == nullptr) return -23;
    const jsize ack_len = env->GetArrayLength(ack);
    const jsize key_len = env->GetArrayLength(key);
    if (ack_len != 105 || key_len < 32 || env->GetArrayLength(expected_from) != 16 ||
        env->GetArrayLength(expected_to) != 16 || now_unix < 0 || max_age < 0) return -23;
    std::vector<std::uint8_t> ack_bytes(static_cast<std::size_t>(ack_len));
    std::vector<std::uint8_t> key_bytes(static_cast<std::size_t>(key_len));
    std::vector<std::uint8_t> from_bytes(16), to_bytes(16);
    env->GetByteArrayRegion(ack, 0, ack_len, reinterpret_cast<jbyte*>(ack_bytes.data()));
    env->GetByteArrayRegion(key, 0, key_len, reinterpret_cast<jbyte*>(key_bytes.data()));
    env->GetByteArrayRegion(expected_from, 0, 16, reinterpret_cast<jbyte*>(from_bytes.data()));
    env->GetByteArrayRegion(expected_to, 0, 16, reinterpret_cast<jbyte*>(to_bytes.data()));
    if (env->ExceptionCheck()) return -23;
    return nm_store_ack_verify(ack_bytes.data(), ack_bytes.size(), key_bytes.data(), key_bytes.size(),
        from_bytes.data(), to_bytes.data(), static_cast<std::uint64_t>(now_unix),
        static_cast<std::uint64_t>(max_age));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_nexora_mesh_NativeMesh_createStoreAck(JNIEnv* env, jclass, jbyteArray message_id,
    jbyteArray from_node, jbyteArray to_node, jlong stored_at, jbyteArray nonce, jbyteArray key) {
    if (message_id == nullptr || from_node == nullptr || to_node == nullptr ||
        nonce == nullptr || key == nullptr || stored_at < 0) return nullptr;
    if (env->GetArrayLength(message_id) != 16 || env->GetArrayLength(from_node) != 16 ||
        env->GetArrayLength(to_node) != 16 || env->GetArrayLength(nonce) != 16 ||
        env->GetArrayLength(key) < 32) return nullptr;
    std::vector<std::uint8_t> id(16), from(16), to(16), nonce_bytes(16);
    const jsize key_len = env->GetArrayLength(key);
    std::vector<std::uint8_t> key_bytes(static_cast<std::size_t>(key_len));
    env->GetByteArrayRegion(message_id, 0, 16, reinterpret_cast<jbyte*>(id.data()));
    env->GetByteArrayRegion(from_node, 0, 16, reinterpret_cast<jbyte*>(from.data()));
    env->GetByteArrayRegion(to_node, 0, 16, reinterpret_cast<jbyte*>(to.data()));
    env->GetByteArrayRegion(nonce, 0, 16, reinterpret_cast<jbyte*>(nonce_bytes.data()));
    env->GetByteArrayRegion(key, 0, key_len, reinterpret_cast<jbyte*>(key_bytes.data()));
    if (env->ExceptionCheck()) return nullptr;
    std::vector<std::uint8_t> output(105);
    const auto written = nm_store_ack_create(id.data(), from.data(), to.data(),
        static_cast<std::uint64_t>(stored_at), nonce_bytes.data(), key_bytes.data(),
        key_bytes.size(), output.data(), output.size());
    if (written != 105) return nullptr;
    jbyteArray result = env->NewByteArray(105);
    if (result != nullptr) env->SetByteArrayRegion(result, 0, 105, reinterpret_cast<const jbyte*>(output.data()));
    return result;
}
