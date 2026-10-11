#ifndef ZHIYU_QWEN_EDGE_EXTERNAL_CONDITION_H
#define ZHIYU_QWEN_EDGE_EXTERNAL_CONDITION_H
#include <stddef.h>
#include <stdint.h>

/* Borrowed, token-major F32 [token_count, 4096], after the student's 14-token
 * system-prefix trim. Pointers must remain valid during synchronous generation.
 * mask is optional; if supplied, 0/1 entries select tokens in original order.
 * The runtime copies selected rows before diffusion. This API is text-only. */
typedef struct {
    const float* values;
    size_t value_count;
    uint32_t token_count;
    uint32_t channel_count;
    const uint8_t* mask;
    size_t mask_count;
} qwen_edge_external_condition_t;
#endif
