#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <filesystem>
#include <stdexcept>
#include <set>
#include <string>
#include <vector>

namespace zhiyu::qwen_edge::vae_probe {
enum class decode_mode { full, tile256 };
enum class latent_mode { zero, sine };
constexpr std::size_t latent_width = 32;
constexpr std::size_t latent_channels = 64;
constexpr std::size_t image_width = 512;

inline bool is_decoder_name(const std::string& name) {
    return name.rfind("first_stage_model.decoder.", 0) == 0 ||
           name.rfind("first_stage_model.conv2.", 0) == 0;
}
inline void validate_decoder_catalog(const std::vector<std::string>& required,
                                    const std::vector<std::string>& source) {
    if (required.empty()) { throw std::invalid_argument("Decoder builder produced no parameters"); }
    const std::set<std::string> available(source.begin(), source.end());
    for (const auto& name : required) {
        if (!is_decoder_name(name)) { throw std::invalid_argument("Unexpected non-decoder parameter: " + name); }
        if (available.count(name) == 0) { throw std::invalid_argument("Missing source decoder tensor: " + name); }
    }
}

inline decode_mode parse_decode_mode(const std::string& value) {
    if (value == "full") { return decode_mode::full; }
    if (value == "tile256") { return decode_mode::tile256; }
    throw std::invalid_argument("Decode mode must be full or tile256");
}
inline latent_mode parse_latent_mode(const std::string& value) {
    if (value == "zero") { return latent_mode::zero; }
    if (value == "sine") { return latent_mode::sine; }
    throw std::invalid_argument("Latent mode must be zero or sine");
}
inline std::vector<float> make_diffusion_latent(latent_mode mode) {
    std::vector<float> data(latent_width * latent_width * latent_channels, 0.F);
    if (mode == latent_mode::sine) {
        for (std::size_t i = 0; i < data.size(); ++i) {
            data[i] = .01F * std::sin(static_cast<float>(i % 4096) * .03125F);
        }
    }
    return data;
}

// sd::Tensor is contiguous with the first (width) dimension varying fastest.
inline std::vector<unsigned char> pack_rgba(const float* planar, std::size_t count,
                                          std::size_t width, std::size_t height) {
    if (planar == nullptr || width == 0 || height == 0 || width > image_width ||
        height > image_width || count != width * height * 4) {
        throw std::invalid_argument("Invalid planar RGBA image dimensions");
    }
    const auto pixels = width * height;
    std::vector<unsigned char> result(count);
    for (std::size_t channel = 0; channel < 4; ++channel) {
        for (std::size_t pixel = 0; pixel < pixels; ++pixel) {
            const auto value = planar[channel * pixels + pixel];
            if (!std::isfinite(value)) { throw std::invalid_argument("Non-finite VAE pixel"); }
            result[pixel * 4 + channel] = static_cast<unsigned char>(
                std::lround(std::clamp(value, 0.F, 1.F) * 255.F));
        }
    }
    return result;
}

// Claim the new output before any expensive model work. Never truncate a file.
class exclusive_output {
public:
    explicit exclusive_output(const std::filesystem::path& path) {
#ifdef _WIN32
        file_ = _wfopen(path.c_str(), L"wbx");
#else
        file_ = std::fopen(path.c_str(), "wbx");
#endif
        if (file_ == nullptr) { throw std::runtime_error("Cannot exclusively create output PNG"); }
    }
    exclusive_output(const exclusive_output&) = delete;
    exclusive_output& operator=(const exclusive_output&) = delete;
    ~exclusive_output() { if (file_ != nullptr) { std::fclose(file_); } }
    FILE* file() const { return file_; }
    void finish() {
        if (file_ == nullptr) { throw std::runtime_error("Output is already closed"); }
        const auto flushed = std::fflush(file_);
        const auto closed = std::fclose(file_);
        file_ = nullptr;
        if (flushed != 0 || closed != 0) { throw std::runtime_error("PNG flush/close failed"); }
    }
private:
    FILE* file_{nullptr};
};
}  // namespace zhiyu::qwen_edge::vae_probe
