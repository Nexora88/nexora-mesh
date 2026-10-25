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
#ifdef __cplusplus
}
#endif
#endif
