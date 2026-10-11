#ifndef ZHIYU_QWEN_EDGE_CONDITION_HPP
#define ZHIYU_QWEN_EDGE_CONDITION_HPP
#include "qwen_edge_external_condition.h"
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace zhiyu::qwen_edge {
constexpr std::uint32_t condition_channels = 4096;
constexpr std::uint32_t max_condition_tokens = 498;
constexpr std::array<float, 9> turbo_sigma_boundaries{
    1.f, .978453f, .95418f, .926626f, .89508f,
    .845148f, .704534f, .414568f, 0.f};
struct condition {
    std::vector<float> values;
    std::uint32_t tokens = 0;
};
using cancel_callback = bool (*)(void*);

inline void validate_shape(const qwen_edge_external_condition_t& input) {
    if (input.values == nullptr || input.token_count == 0 ||
        input.token_count > max_condition_tokens || input.channel_count != condition_channels ||
        input.value_count != static_cast<std::size_t>(input.token_count) * condition_channels) {
        throw std::invalid_argument("Qwen Edge expects 1..498 tokens of finite token-major 4096-dimensional F32");
    }
    if ((input.mask == nullptr && input.mask_count != 0) ||
        (input.mask != nullptr && input.mask_count != input.token_count)) {
        throw std::invalid_argument("Qwen Edge mask length differs from token count");
    }
}

inline condition copy_condition(const qwen_edge_external_condition_t& input,
                                cancel_callback cancelled = nullptr, void* cancel_data = nullptr) {
    validate_shape(input);
    condition output;
    output.values.reserve(input.value_count);
    for (std::uint32_t token = 0; token < input.token_count; ++token) {
        if (cancelled != nullptr && cancelled(cancel_data)) {
            throw std::invalid_argument("Qwen Edge condition copy cancelled");
        }
        const uint8_t selected = input.mask == nullptr ? 1 : input.mask[token];
        if (selected > 1) throw std::invalid_argument("Qwen Edge mask entries must be 0 or 1");
        const std::size_t begin = static_cast<std::size_t>(token) * condition_channels;
        for (std::size_t channel = 0; channel < condition_channels; ++channel) {
            if (!std::isfinite(input.values[begin + channel])) {
                throw std::invalid_argument("Qwen Edge condition contains nonfinite values");
            }
        }
        if (selected != 0) {
            output.values.insert(output.values.end(), input.values + begin,
                                 input.values + begin + condition_channels);
            ++output.tokens;
        }
    }
    if (output.tokens == 0) throw std::invalid_argument("Qwen Edge condition has no unmasked tokens");
    return output;
}

inline void validate_turbo_sigmas(const float* sigmas, std::size_t count) {
    if (sigmas == nullptr || count != turbo_sigma_boundaries.size()) {
        throw std::invalid_argument("Qwen Edge Turbo requires eight positive sigmas plus terminal zero");
    }
    for (std::size_t step = 0; step < count; ++step) {
        if (!std::isfinite(sigmas[step]) ||
            std::fabs(sigmas[step] - turbo_sigma_boundaries[step]) > 1.e-6f) {
            throw std::invalid_argument("Qwen Edge Turbo sigma boundaries differ from model_index");
        }
    }
}
}  // namespace zhiyu::qwen_edge
#endif
