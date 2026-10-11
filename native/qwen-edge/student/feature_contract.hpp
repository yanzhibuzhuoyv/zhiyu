#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace qwen_edge {
class ContractError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// Qwen3 has 28 decoder layers. HF hidden_states contains embedding (0),
// layer inputs (1..27), and final RMS-normalized output (28).
// [-1,-4,-7,-10,-13,-16] therefore maps to [28,25,22,19,16,13].
class FeatureCapture {
public:
    static constexpr int hidden_size = 1024;
    static constexpr int layer_count = 6;
    static constexpr int feature_size = hidden_size * layer_count;
    static constexpr std::array<std::string_view, layer_count> names{
        "result_norm", "l_out-24", "l_out-21", "l_out-18", "l_out-15", "l_out-12"};

    explicit FeatureCapture(int tokens) : tokens_(tokens) {
        if (tokens < 1 || tokens > 512) throw ContractError("expected 1..512 tokens");
    }

    static int target_slot(std::string_view name) noexcept {
        // Locked llama.cpp build_pooling(NONE) aliases t_embd and renames
        // result_norm to result_embd_pooled without any reduction/normalization.
        if (name == "result_embd_pooled") return 0;
        for (int i = 0; i < layer_count; ++i) {
            if (names[i] == name) return i;
        }
        return -1;
    }

    void accept(int slot, int dimensions, int tokens, std::vector<float> values) {
        if (slot < 0 || slot >= layer_count) throw ContractError("unknown feature layer");
        if (dimensions != hidden_size || tokens != tokens_)
            throw ContractError("feature layer shape differs; all tokens are required");
        if (values.size() != static_cast<std::size_t>(tokens_) * hidden_size)
            throw ContractError("feature layer payload length differs");
        if (present_[slot]) throw ContractError("duplicate feature layer");
        if (!std::all_of(values.begin(), values.end(), [](float v) { return std::isfinite(v); }))
            throw ContractError("non-finite feature layer");
        blocks_[slot] = std::move(values);
        present_[slot] = true;
    }

    std::vector<float> features() const {
        for (int slot = 0; slot < layer_count; ++slot) {
            if (!present_[slot]) throw ContractError("missing requested feature layer: " +
                std::string(names[slot]) + "; no last-layer fallback");
        }
        auto result = std::vector<float>(static_cast<std::size_t>(tokens_) * feature_size);
        for (int token = 0; token < tokens_; ++token) {
            for (int slot = 0; slot < layer_count; ++slot) {
                const auto source = blocks_[slot].begin() + token * hidden_size;
                const auto destination = result.begin() + token * feature_size + slot * hidden_size;
                std::copy_n(source, hidden_size, destination);
            }
        }
        return result;
    }

    int tokens() const noexcept { return tokens_; }
private:
    const int tokens_;
    std::array<std::vector<float>, layer_count> blocks_{};
    std::array<bool, layer_count> present_{};
};
} // namespace qwen_edge
