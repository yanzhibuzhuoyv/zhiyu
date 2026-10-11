#include "adapter.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <fstream>
#include <span>
#include <stdexcept>
#include <utility>

namespace zhiyu::qwen {
namespace {
constexpr std::size_t input_dim = 6144;
constexpr std::size_t output_dim = 4096;
constexpr std::size_t payload_floats = 41963520;
static_assert(std::endian::native == std::endian::little);

void check_cancel(const std::atomic_bool* cancelled) {
    if (cancelled != nullptr && cancelled->load(std::memory_order_relaxed)) {
        throw std::runtime_error("Adapter operation cancelled");
    }
}

void validate_vector(const std::vector<float>& values, std::size_t expected,
                     const std::atomic_bool* cancelled = nullptr) {
    check_cancel(cancelled);
    if (values.size() != expected) { throw std::invalid_argument("Invalid adapter tensor size"); }
    for (std::size_t offset = 0; offset < values.size(); offset += 65536) {
        check_cancel(cancelled);
        const auto end = std::min(offset + 65536, values.size());
        if (!std::all_of(values.begin()+offset, values.begin()+end,
                         [](float x) { return std::isfinite(x); })) {
            throw std::invalid_argument("Non-finite adapter tensor");
        }
    }
}

std::vector<float> read_vector(std::ifstream& input, std::size_t count, const std::atomic_bool* cancelled) {
    check_cancel(cancelled);
    std::vector<float> values(count);
    for (std::size_t offset = 0; offset < count; offset += 65536) {
        check_cancel(cancelled);
        const auto chunk = std::min(std::size_t{65536}, count-offset);
        input.read(reinterpret_cast<char*>(values.data()+offset), static_cast<std::streamsize>(chunk*sizeof(float)));
        if (!input) { throw std::runtime_error("Truncated adapter tensor"); }
    }
    validate_vector(values, count, cancelled);
    return values;
}

std::vector<float> normalize(std::span<const float> features, const adapter_weights& weights) {
    double total = 0;
    for (const float value : features) { total += value; }
    const double mean = total / static_cast<double>(input_dim);
    double variance = 0;
    for (const float value : features) { variance += (value - mean) * (value - mean); }
    const float scale = static_cast<float>(1.0 / std::sqrt(variance / static_cast<double>(input_dim) + 1e-5));
    std::vector<float> normalized(input_dim);
    for (std::size_t index = 0; index < input_dim; ++index) {
        normalized[index] = (features[index] - static_cast<float>(mean)) * scale *
                            weights.norm_weight[index] + weights.norm_bias[index];
    }
    return normalized;
}

std::vector<float> dense(const std::vector<float>& inputs, const std::vector<float>& weights,
                         const std::vector<float>& biases, bool gelu, const std::atomic_bool* cancelled) {
    std::vector<float> outputs(biases.size());
    for (std::size_t row = 0; row < biases.size(); ++row) {
        if (row % 32 == 0) { check_cancel(cancelled); }
        const auto start = row * inputs.size();
        double sum = biases[row];
        for (std::size_t column = 0; column < inputs.size(); ++column) {
            sum += static_cast<double>(weights[start + column]) * inputs[column];
        }
        const float value = static_cast<float>(sum);
        outputs[row] = gelu ? value * 0.5F * (1.0F + std::erf(value / std::sqrt(2.0F))) : value;
    }
    return outputs;
}
}  // namespace

void validate_header(const adapter_header& header) {
    const auto expected = adapter_header{};
    if (header.magic != expected.magic || header.version != expected.version ||
        header.input_dim != expected.input_dim || header.output_dim != expected.output_dim ||
        header.hidden_dim != expected.hidden_dim || header.layer_count != expected.layer_count ||
        header.drop_tokens != expected.drop_tokens || header.scalar_type != expected.scalar_type ||
        header.layers != expected.layers) {
        throw std::invalid_argument("Unsupported adapter contract");
    }
}

float round_bfloat16(float value) {
    if (!std::isfinite(value)) { throw std::invalid_argument("Non-finite adapter output"); }
    const auto bits = std::bit_cast<std::uint32_t>(value);
    const auto rounded = (bits + 0x7fffU + ((bits >> 16U) & 1U)) & 0xffff0000U;
    const float output = std::bit_cast<float>(rounded);
    if (!std::isfinite(output)) { throw std::invalid_argument("Adapter output overflows BF16"); }
    return output;
}

adapter::adapter(adapter_weights weights, const std::atomic_bool* cancelled) : weights_(std::move(weights)) {
    validate_vector(weights_.norm_weight, input_dim, cancelled);
    validate_vector(weights_.norm_bias, input_dim, cancelled);
    validate_vector(weights_.first_weight, input_dim * output_dim, cancelled);
    validate_vector(weights_.first_bias, output_dim, cancelled);
    validate_vector(weights_.second_weight, output_dim * output_dim, cancelled);
    validate_vector(weights_.second_bias, output_dim, cancelled);
}

adapter adapter::load(const std::filesystem::path& path, const std::atomic_bool* cancelled) {
    check_cancel(cancelled);
    if (std::filesystem::file_size(path) != sizeof(adapter_header) + payload_floats * sizeof(float)) {
        throw std::invalid_argument("Adapter file length differs from fixed contract");
    }
    std::ifstream input(path, std::ios::binary);
    adapter_header header;
    input.read(reinterpret_cast<char*>(&header), sizeof(header));
    if (!input) { throw std::runtime_error("Cannot read adapter header"); }
    validate_header(header);
    adapter_weights weights;
    weights.norm_weight = read_vector(input, input_dim, cancelled);
    weights.norm_bias = read_vector(input, input_dim, cancelled);
    weights.first_weight = read_vector(input, input_dim * output_dim, cancelled);
    weights.first_bias = read_vector(input, output_dim, cancelled);
    weights.second_weight = read_vector(input, output_dim * output_dim, cancelled);
    weights.second_bias = read_vector(input, output_dim, cancelled);
    if (input.peek() != std::char_traits<char>::eof()) { throw std::invalid_argument("Trailing adapter bytes"); }
    return adapter(std::move(weights), cancelled);
}

std::vector<float> adapter::project(const std::vector<float>& features, std::size_t tokens,
                                   const std::atomic_bool* cancelled) const {
    check_cancel(cancelled);
    if (tokens <= 14 || tokens > 512) { throw std::invalid_argument("Expected 15..512 input tokens"); }
    validate_vector(features, tokens * input_dim, cancelled);
    std::vector<float> output((tokens - 14) * output_dim);
    for (std::size_t token = 14; token < tokens; ++token) {
        check_cancel(cancelled);
        const auto normalized = normalize(std::span<const float>(features).subspan(token * input_dim, input_dim), weights_);
        const auto hidden = dense(normalized, weights_.first_weight, weights_.first_bias, true, cancelled);
        const auto projected = dense(hidden, weights_.second_weight, weights_.second_bias, false, cancelled);
        for (std::size_t index = 0; index < output_dim; ++index) {
            output[(token - 14) * output_dim + index] = round_bfloat16(projected[index]);
        }
    }
    return output;
}
}  // namespace zhiyu::qwen
