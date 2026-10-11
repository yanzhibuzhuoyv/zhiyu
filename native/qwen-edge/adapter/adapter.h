#ifndef ZHIYU_QWEN_ADAPTER_H
#define ZHIYU_QWEN_ADAPTER_H

#include <array>
#include <atomic>
#include <cstdint>
#include <filesystem>
#include <vector>

namespace zhiyu::qwen {
struct adapter_header {
    std::array<char, 8> magic{'Z', 'Q', 'A', 'D', 'A', 'P', '1', '\0'};
    std::uint32_t version{1};
    std::uint32_t input_dim{6144};
    std::uint32_t output_dim{4096};
    std::uint32_t hidden_dim{4096};
    std::uint32_t layer_count{6};
    std::uint32_t drop_tokens{14};
    std::uint32_t scalar_type{1};
    std::array<std::int32_t, 6> layers{-1, -4, -7, -10, -13, -16};
};
static_assert(sizeof(adapter_header) == 60);

void validate_header(const adapter_header& header);
float round_bfloat16(float value);

struct adapter_weights {
    std::vector<float> norm_weight;
    std::vector<float> norm_bias;
    std::vector<float> first_weight;
    std::vector<float> first_bias;
    std::vector<float> second_weight;
    std::vector<float> second_bias;
};

class adapter {
public:
    explicit adapter(adapter_weights weights, const std::atomic_bool* cancelled = nullptr);
    static adapter load(const std::filesystem::path& path, const std::atomic_bool* cancelled = nullptr);
    std::vector<float> project(const std::vector<float>& features, std::size_t tokens,
                               const std::atomic_bool* cancelled = nullptr) const;
private:
    adapter_weights weights_;
};
}  // namespace zhiyu::qwen
#endif
