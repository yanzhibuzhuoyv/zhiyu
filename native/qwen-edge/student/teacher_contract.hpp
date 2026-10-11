#pragma once
#include <bit>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace qwen_edge {
class TeacherCapture {
public:
    TeacherCapture(int layers, int tokens) : tokens_(tokens), target_("l_out-" + std::to_string(layers-1)) {
        if ((layers != 36 && layers != 24) || tokens < 15 || tokens > 512) {
            throw std::invalid_argument("Expected L36/L24 teacher and 15..512 tokens");
        }
    }
    bool wants(std::string_view name) const noexcept { return name == target_; }
    int tokens() const noexcept { return tokens_; }
    void accept(const std::vector<float>& features) {
        if (ready_ || features.size() != static_cast<std::size_t>(tokens_)*4096) {
            throw std::invalid_argument("Duplicate or malformed teacher features");
        }
        for (const auto value : features) {
            if (!std::isfinite(value)) { throw std::invalid_argument("Non-finite teacher features"); }
        }
        std::vector<float> output(features.size()-14*4096);
        for (std::size_t i = 0; i < output.size(); ++i) {
            auto bits = std::bit_cast<std::uint32_t>(features[i+14*4096]);
            bits = (bits + 0x7fffU + ((bits >> 16U) & 1U)) & 0xffff0000U;
            output[i] = std::bit_cast<float>(bits);
            if (!std::isfinite(output[i])) { throw std::invalid_argument("Teacher BF16 overflow"); }
        }
        conditions_ = std::move(output);
        ready_ = true;
    }
    const std::vector<float>& conditions() const {
        if (!ready_) { throw std::invalid_argument("Missing teacher final pre-norm layer"); }
        return conditions_;
    }
private:
    int tokens_;
    std::string target_;
    std::vector<float> conditions_;
    bool ready_{false};
};
}  // namespace qwen_edge
