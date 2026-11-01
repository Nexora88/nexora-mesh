#ifndef NEXORA_MESH_FFI_H
#define NEXORA_MESH_FFI_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Returns 0 for a valid v1 envelope, otherwise a negative error code. */
int32_t nm_wire_validate(const uint8_t *input, size_t input_len);
/* Returns output length, a negative error, or required capacity if too small.
   Input and output buffers must be valid and non-overlapping. */
intptr_t nm_wire_forward(const uint8_t *input, size_t input_len,
                         uint8_t *output, size_t output_capacity,
                         uint64_t now_unix);
uint64_t nm_relay_scan_interval_seconds(uint8_t battery_percent, uint8_t charging,
    uint8_t low_power_mode, uint8_t app_foreground, uint8_t recently_connected,
    uint8_t urgent_pending);
uint8_t nm_relay_may_forward(uint8_t opted_in, uint8_t battery_percent,
    uint8_t charging, uint8_t low_power_mode, uint8_t app_foreground);
/* Versioned 105-byte HMAC-SHA256 next-hop STORE_ACK. Hop key must be pairwise and >=32 bytes. */
intptr_t nm_store_ack_create(const uint8_t *message_id, const uint8_t *from_node,
    const uint8_t *to_node, uint64_t stored_at_unix, const uint8_t *nonce,
    const uint8_t *hop_key, size_t key_len, uint8_t *output, size_t output_capacity);
int32_t nm_store_ack_verify(const uint8_t *input, size_t input_len,
    const uint8_t *hop_key, size_t key_len, const uint8_t *expected_from,
    const uint8_t *expected_to, uint64_t now_unix, uint64_t max_age_seconds);
#ifdef __cplusplus
}
#endif
#endif
