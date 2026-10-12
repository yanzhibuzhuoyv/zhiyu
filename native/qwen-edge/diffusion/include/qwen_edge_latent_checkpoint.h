#ifndef ZHIYU_QWEN_EDGE_LATENT_CHECKPOINT_H
#define ZHIYU_QWEN_EDGE_LATENT_CHECKPOINT_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Research fork ABI only. The synchronous callback borrows width-fast F32
 * values for its duration. Qwen2.1 final diffusion-normalized latent, before
 * WanVAERunner::diffusion_to_vae_latents. Never pass VAE-space values here. */
enum { QWEN_EDGE_MODEL_2_1 = 210, QWEN_EDGE_DIFFUSION_NORMALIZED = 1 };
typedef struct {
    const float* values;
    size_t value_count;
    uint32_t width, height, channels, batch;
    uint32_t model_version, stage, sampling_steps;
    int64_t seed;
} qwen_edge_latent_view_t;
typedef struct {
    bool (*write)(const qwen_edge_latent_view_t* view, void* user_data);
    void* user_data;
    /* true: success has zero images and a completed checkpoint. Explicit opt-in. */
    bool sample_only;
} qwen_edge_latent_checkpoint_t;
#endif
