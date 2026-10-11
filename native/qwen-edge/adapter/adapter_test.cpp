#include "adapter.h"

#include <bit>
#include <cmath>
#include <functional>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
void reject(const zhiyu::qwen::adapter_header& value) {
    try {
        zhiyu::qwen::validate_header(value);
    } catch (const std::invalid_argument&) {
        return;
    }
    throw std::runtime_error("Invalid model header was accepted");
}

void require(bool value) { if (!value) { throw std::runtime_error("Adapter result differs"); } }
template<class Action> void rejects(Action action) {
    try { action(); } catch (const std::exception&) { return; }
    throw std::runtime_error("Invalid operation accepted");
}

const zhiyu::qwen::adapter& synthetic_adapter() {
    static const auto model = [] {
        zhiyu::qwen::adapter_weights weights;
        weights.norm_weight.resize(6144, 1.0F);
        weights.norm_bias.resize(6144, 0.0F);
        weights.first_weight.resize(6144*4096, 0.0F);
        weights.first_weight[0] = 1.0F;
        weights.first_bias.resize(4096, 0.0F);
        weights.second_weight.resize(4096*4096, 0.0F);
        weights.second_weight[0] = 1.0F;
        weights.second_bias.resize(4096, 0.25F);
        return zhiyu::qwen::adapter(std::move(weights));
    }();
    return model;
}
}

int main() {
    using zhiyu::qwen::adapter_header;
    const std::vector<std::pair<std::string, std::function<void()>>> cases{
        {"valid_exact_contract", [] { zhiyu::qwen::validate_header(adapter_header{}); }},
        {"wrong_magic", [] { auto value = adapter_header{}; value.magic[0] = 'X'; reject(value); }},
        {"wrong_version", [] { auto value = adapter_header{}; value.version = 2; reject(value); }},
        {"wrong_input", [] { auto value = adapter_header{}; value.input_dim = 1024; reject(value); }},
        {"wrong_output", [] { auto value = adapter_header{}; value.output_dim = 1; reject(value); }},
        {"wrong_hidden", [] { auto value = adapter_header{}; value.hidden_dim = 0; reject(value); }},
        {"wrong_layer_count", [] { auto value = adapter_header{}; value.layer_count = 1; reject(value); }},
        {"wrong_layers", [] { auto value = adapter_header{}; value.layers[1] = -2; reject(value); }},
        {"wrong_drop", [] { auto value = adapter_header{}; value.drop_tokens = 0; reject(value); }},
        {"wrong_scalar", [] { auto value = adapter_header{}; value.scalar_type = 2; reject(value); }},
        {"bf16_tie_even_down", [] { require(zhiyu::qwen::round_bfloat16(std::bit_cast<float>(0x3f808000U)) == 1.0F); }},
        {"bf16_tie_odd_up", [] { require(std::bit_cast<std::uint32_t>(zhiyu::qwen::round_bfloat16(std::bit_cast<float>(0x3f818000U))) == 0x3f820000U); }},
        {"bf16_negative_zero", [] { require(std::bit_cast<std::uint32_t>(zhiyu::qwen::round_bfloat16(-0.0F)) == 0x80000000U); }},
        {"bf16_nan", [] { rejects([] { zhiyu::qwen::round_bfloat16(std::numeric_limits<float>::quiet_NaN()); }); }},
        {"bf16_overflow", [] { rejects([] { zhiyu::qwen::round_bfloat16(std::numeric_limits<float>::max()); }); }},
        {"bad_weight_shape", [] { rejects([] { zhiyu::qwen::adapter model({}); }); }},
        {"cancel_load_before_io", [] {
            const std::atomic_bool cancel{true};
            try { zhiyu::qwen::adapter::load("missing.bin", &cancel); }
            catch (const std::runtime_error& error) { require(std::string(error.what()).find("cancelled") != std::string::npos); return; }
            throw std::runtime_error("Cancelled loading proceeded");
        }},
        {"too_short_sequence", [] { rejects([] { synthetic_adapter().project({}, 14); }); }},
        {"too_long_sequence", [] { rejects([] { synthetic_adapter().project({}, 513); }); }},
        {"truncated_sequence", [] { rejects([] { synthetic_adapter().project({}, 15); }); }},
        {"nonfinite_even_in_dropped_prefix", [] {
            auto features = std::vector<float>(15*6144, 0.0F);
            features[0] = std::numeric_limits<float>::infinity();
            rejects([&] { synthetic_adapter().project(features, 15); });
        }},
        {"cancel_projection_before_input", [] {
            const std::atomic_bool cancel{true};
            try { synthetic_adapter().project({}, 15, &cancel); }
            catch (const std::runtime_error& error) { require(std::string(error.what()).find("cancelled") != std::string::npos); return; }
            throw std::runtime_error("Cancelled projection proceeded");
        }},
        {"sparse_layernorm_gelu_and_prefix_drop", [] {
            auto features = std::vector<float>(16*6144, 99.0F);
            for (std::size_t i = 14*6144; i < features.size(); ++i) { features[i] = (i%6144)%2 == 0 ? 1.0F : -1.0F; }
            const auto output = synthetic_adapter().project(features, 16);
            const float normalized = 1.0F/std::sqrt(1.0F+1e-5F);
            const auto expected = zhiyu::qwen::round_bfloat16(normalized*0.5F*(1+std::erf(normalized/std::sqrt(2.0F)))+0.25F);
            require(output.size() == 2*4096);
            for (std::size_t i = 0; i < output.size(); ++i) { require(output[i] == (i%4096 == 0 ? expected : 0.25F)); }
        }},
    };
    unsigned failures = 0;
    for (const auto& [name, test] : cases) {
        try { test(); std::cout << "PASS " << name << '\n'; }
        catch (const std::exception& error) {
            ++failures; std::cerr << "FAIL " << name << ": " << error.what() << '\n';
        }
    }
    std::cout << "CASES " << cases.size() << " FAILURES " << failures << '\n';
    return failures == 0 ? 0 : 1;
}
