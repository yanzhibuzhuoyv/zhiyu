#pragma once
#include "qwen_edge_latent_checkpoint.h"
#include <cmath>
#include <stdexcept>

namespace zhiyu::qwen_edge::checkpoint {
inline void require_eight_progress(int step, int steps) {
    if (step != 8 || steps != 8) { throw std::invalid_argument("Checkpoint requires observed complete sampling progress 8/8"); }
}
inline void validate_view(const qwen_edge_latent_view_t& view) {
    if (view.values == nullptr || view.value_count != 32*32*64 || view.width != 32 ||
        view.height != 32 || view.channels != 64 || view.batch != 1 ||
        view.model_version != QWEN_EDGE_MODEL_2_1 || view.stage != QWEN_EDGE_DIFFUSION_NORMALIZED ||
        view.sampling_steps != 8 || view.seed < 0) {
        throw std::invalid_argument("Checkpoint requires Qwen2.1 completed Euler8 32x32x64x1 diffusion-normalized latent");
    }
    for (std::size_t i=0; i<view.value_count; ++i) {
        if (!std::isfinite(view.values[i])) { throw std::invalid_argument("Non-finite checkpoint latent"); }
    }
}
inline void dispatch_checkpoint(const qwen_edge_latent_checkpoint_t& request,
                                const qwen_edge_latent_view_t& view, bool cancelled) {
    if (cancelled || request.write == nullptr) {
        throw std::invalid_argument("Checkpoint cancelled or callback absent");
    }
    validate_view(view);
    if (!request.write(&view, request.user_data)) { throw std::runtime_error("Checkpoint callback failed"); }
}
}  // namespace zhiyu::qwen_edge::checkpoint
