#include "feature_contract.hpp"
#include <cmath>
#include <iostream>

namespace {
void require(bool condition, const char * message) {
    if (!condition) throw std::runtime_error(message);
}
template<class Action> void rejects(Action action) {
    try { action(); } catch (const qwen_edge::ContractError &) { return; }
    throw std::runtime_error("invalid capture accepted");
}
}

int main() {
    using qwen_edge::FeatureCapture;
    int checks = 0;
    try {
        require(FeatureCapture::target_slot("result_norm") == 0, "final norm index"); ++checks;
        require(FeatureCapture::target_slot("result_embd_pooled") == 0, "pooling NONE alias"); ++checks;
        require(FeatureCapture::target_slot("l_out-24") == 1, "HF index 25"); ++checks;
        require(FeatureCapture::target_slot("l_out-12") == 5, "HF index 13"); ++checks;
        require(FeatureCapture::target_slot("l_out-27") == -1, "unnormalized final rejected"); ++checks;
        FeatureCapture capture(2);
        rejects([&] { capture.features(); }); ++checks;
        rejects([&] { capture.accept(0, 1024, 1, std::vector<float>(1024)); }); ++checks;
        rejects([&] { capture.accept(0, 2048, 2, std::vector<float>(4096)); }); ++checks;
        for (int slot = 0; slot < 6; ++slot) {
            std::vector<float> values(2048);
            for (int token = 0; token < 2; ++token) {
                for (int dim = 0; dim < 1024; ++dim) {
                    values[token * 1024 + dim] = float(100000 * token + 10000 * slot + dim);
                }
            }
            capture.accept(slot, 1024, 2, std::move(values));
        }
        const auto features = capture.features();
        require(features.size() == 2 * 6144, "complete token matrix"); ++checks;
        require(features[0] == 0 && features[1024] == 10000, "layer concatenation order"); ++checks;
        require(features[6144] == 100000 && features.back() == 151023, "token-major layout"); ++checks;
        rejects([&] { capture.accept(0, 1024, 2, std::vector<float>(2048)); }); ++checks;
        FeatureCapture nonfinite(1);
        auto invalid = std::vector<float>(1024);
        invalid[10] = INFINITY;
        rejects([&] { nonfinite.accept(0, 1024, 1, std::move(invalid)); }); ++checks;
        rejects([] { FeatureCapture zero(0); }); ++checks;
        rejects([] { FeatureCapture huge(513); }); ++checks;
        std::cout << "feature contract: " << checks << " checks passed\n";
        return 0;
    } catch (const std::exception & error) {
        std::cerr << "feature contract failed: " << error.what() << '\n';
        return 1;
    }
}
