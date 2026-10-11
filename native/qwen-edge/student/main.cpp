#include "feature_contract.hpp"
#include "student_runtime.hpp"
#include "hash/hash.h"
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <psapi.h>
#else
#include <sys/resource.h>
#endif

namespace fs = std::filesystem;
namespace {
using qwen_edge::ContractError;
using qwen_edge::FeatureCapture;
constexpr int max_tokens = 512;
#ifdef __ANDROID__
constexpr const char* platform = "android";
constexpr const char* android_student_executed = "true";
#else
constexpr const char* platform = "desktop";
constexpr const char* android_student_executed = "false";
#endif
struct Options { fs::path model; fs::path tokens; fs::path output; };

Options options(int argc, char ** argv) {
    if (argc != 7) throw ContractError("usage: --model MODEL --tokens INT32_LE_FILE --output NEW_DIRECTORY");
    Options result;
    for (int i = 1; i < argc; i += 2) {
        const auto flag = std::string_view(argv[i]);
        if (flag == "--model" && result.model.empty()) result.model = argv[i + 1];
        else if (flag == "--tokens" && result.tokens.empty()) result.tokens = argv[i + 1];
        else if (flag == "--output" && result.output.empty()) result.output = argv[i + 1];
        else throw ContractError("unknown or duplicate argument");
    }
    if (result.model.empty() || result.tokens.empty() || result.output.empty())
        throw ContractError("all arguments are required");
    if (!fs::is_regular_file(result.model) || !fs::is_regular_file(result.tokens))
        throw ContractError("model and token files must exist");
    if (fs::exists(result.output)) throw ContractError("output directory must not exist");
    return result;
}

std::vector<std::int32_t> read_tokens(const fs::path & path) {
    const auto size = fs::file_size(path);
    if (size < 4 || size > max_tokens * 4 || size % 4 != 0)
        throw ContractError("token file must contain 1..512 little-endian int32 ids");
    auto input = std::ifstream(path, std::ios::binary);
    auto tokens = std::vector<std::int32_t>(size / 4);
    for (auto & token : tokens) {
        std::array<unsigned char, 4> bytes{};
        if (!input.read(reinterpret_cast<char *>(bytes.data()), 4)) throw ContractError("truncated tokens");
        const std::uint32_t value = bytes[0] | (std::uint32_t(bytes[1]) << 8) |
            (std::uint32_t(bytes[2]) << 16) | (std::uint32_t(bytes[3]) << 24);
        if (value > INT32_MAX) throw ContractError("negative or overflowing token id");
        token = static_cast<std::int32_t>(value);
    }
    return tokens;
}

std::string metadata(const std::vector<std::int32_t> & tokens,
                     const std::vector<float> & features, double load_seconds, double forward_seconds, std::uint64_t peak_bytes) {
    auto result = std::ostringstream();
    result << "{\n  \"schema\": 1,\n  \"llama_commit\": \"" << QWEN_LLAMA_COMMIT
        << "\",\n  \"architecture\": \"qwen3\",\n  \"decoder_layers\": 28,\n"
        << "  \"shape\": [1," << tokens.size() << ",6144],\n"
        << "  \"dtype\": \"float32\",\n  \"byte_order\": \"little\",\n"
        << "  \"layout\": \"token_major_layer_then_hidden\",\n"
        << "  \"token_ids_sha256\": \"" << hash_sha256_hex(tokens.data(), tokens.size() * 4) << "\",\n"
        << "  \"features_sha256\": \"" << hash_sha256_hex(features.data(), features.size() * 4) << "\",\n"
        << "  \"hf_hidden_state_indices\": [-1,-4,-7,-10,-13,-16],\n"
        << "  \"hf_absolute_indices\": [28,25,22,19,16,13],\n"
        << "  \"tensor_names\": [\"result_norm\",\"l_out-24\",\"l_out-21\",\"l_out-18\",\"l_out-15\",\"l_out-12\"],\n"
        << "  \"final_tensor_callback_name\": \"result_embd_pooled\",\n  \"pooling\": \"NONE\",\n"
        << "  \"final_layer_normalized\": true,\n  \"intermediate_layers_normalized\": false,\n"
        << "  \"tokens_dropped\": 0,\n  \"adapter_drop_tokens_required\": 14,\n"
        << "  \"threads\": 4,\n  \"gpu_layers\": 0,\n  \"finite\": true,\n"
        << "  \"model_load_seconds\": " << load_seconds << ",\n"
        << "  \"forward_seconds\": " << forward_seconds << ",\n"
        << "  \"process_peak_working_set_bytes\": " << peak_bytes
        << ",\n  \"platform\": \"" << platform << "\",\n"
        << "  \"android_student_executed\": " << android_student_executed
        << ",\n  \"full_image_android_tested\": false\n}\n";
    return result.str();
}

void save(const fs::path & directory, const std::vector<float> & features, const std::string & info) {
    const std::uint16_t endian_probe = 1;
    if (*reinterpret_cast<const unsigned char *>(&endian_probe) != 1)
        throw ContractError("raw output requires a little-endian host");
    if (!fs::create_directories(directory)) throw ContractError("output directory creation failed");
    auto raw = std::ofstream(directory / "features.f32", std::ios::binary);
    raw.write(reinterpret_cast<const char *>(features.data()), static_cast<std::streamsize>(features.size() * 4));
    raw.close();
    if (!raw) throw ContractError("feature output write failed");
    auto metadata_file = std::ofstream(directory / "metadata.json");
    metadata_file << info;
    metadata_file.close();
    if (!metadata_file) throw ContractError("metadata output write failed");
}

int extract(const Options & args) {
    const auto tokens = read_tokens(args.tokens);
    const auto result = qwen_edge::extract_student_features(args.model, tokens);
    const auto info = metadata(tokens, result.features, result.load_seconds, result.forward_seconds, result.process_peak_bytes);
    save(args.output, result.features, info);
    std::cout << info;
    return 0;
}
} // namespace

int main(int argc, char ** argv) {
    try { return extract(options(argc, argv)); }
    catch (const std::exception & error) {
        std::cerr << "student feature extraction failed: " << error.what() << '\n';
        return 1;
    }
}
