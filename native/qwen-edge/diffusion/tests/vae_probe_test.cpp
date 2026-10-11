#include "qwen_edge_vae_probe.hpp"
#include <functional>
#include <chrono>
#include <fstream>
#include <iostream>
#include <limits>
#include <string>
#include <utility>

namespace {
using namespace zhiyu::qwen_edge::vae_probe;
void check(bool ok, const char* message) {
    if (!ok) { throw std::runtime_error(message); }
}
template<class F> void rejects(F action) {
    try { action(); } catch (const std::invalid_argument&) { return; }
    throw std::runtime_error("Expected rejection");
}
}  // namespace

namespace {
const std::vector<std::pair<std::string, std::function<void()>>> tests{
        {"full_and_tile256_are_explicit", [] {
            check(parse_decode_mode("full") == decode_mode::full, "Full mode changed");
            check(parse_decode_mode("tile256") == decode_mode::tile256, "Tile mode changed");
            rejects([] { parse_decode_mode("auto"); });
        }},
        {"latent_modes_are_explicit", [] {
            check(parse_latent_mode("zero") == latent_mode::zero, "Zero mode changed");
            check(parse_latent_mode("sine") == latent_mode::sine, "Sine mode changed");
            rejects([] { parse_latent_mode("noise"); });
        }},
        {"zero_is_diffusion_space_32_by_32_by_64", [] {
            const auto data = make_diffusion_latent(latent_mode::zero);
            check(data.size() == 32*32*64, "Wrong latent size");
            for (auto value : data) { check(value == 0, "Wrong zero latent"); }
        }},
        {"sine_is_deterministic_small_finite", [] {
            const auto a = make_diffusion_latent(latent_mode::sine);
            const auto b = make_diffusion_latent(latent_mode::sine);
            check(a == b && a[1] != 0, "Sine not deterministic/nonzero");
            for (auto value : a) { check(std::isfinite(value) && std::abs(value) <= .01F, "Bad sine"); }
        }},
        {"planar_rgba_interleaves_alpha_without_forcing_opaque", [] {
            const std::vector<float> data{0, 1, .25F, .75F, .5F, .5F, .1F, .9F};
            const auto packed = pack_rgba(data.data(), data.size(), 2, 1);
            check(packed == std::vector<unsigned char>{0, 64, 128, 26, 255, 191, 128, 230}, "Wrong RGBA order");
        }},
        {"pixel_clamp_preserves_finite_bounds", [] {
            const std::vector<float> data{-2, 2, 0, 1};
            check(pack_rgba(data.data(), 4, 1, 1) == std::vector<unsigned char>{0,255,0,255}, "Bad clamp");
        }},
        {"non_finite_alpha_rejected", [] {
            const std::vector<float> data{0, 0, 0, std::numeric_limits<float>::quiet_NaN()};
            rejects([&] { pack_rgba(data.data(), 4, 1, 1); });
        }},
        {"null_or_wrong_size_rejected", [] {
            rejects([] { pack_rgba(nullptr, 4, 1, 1); });
            const float data[4]{};
            rejects([&] { pack_rgba(data, 3, 1, 1); });
            rejects([&] { pack_rgba(data, 4, 0, 1); });
            rejects([&] { pack_rgba(data, 4, 513, 1); });
        }},
        {"exclusive_output_never_truncates_owned_file", [] {
            const auto serial = std::chrono::steady_clock::now().time_since_epoch().count();
            const auto path = std::filesystem::temp_directory_path() / ("zhiyu-vae-probe-contract-" + std::to_string(serial));
            exclusive_output output(path);
            try {
                check(std::fputs("owned", output.file()) >= 0, "Write failed");
                output.finish();
                bool rejected = false;
                try { exclusive_output duplicate(path); } catch (const std::runtime_error&) { rejected = true; }
                check(rejected, "Existing file overwritten");
                std::ifstream input(path); std::string value; input >> value;
                check(value == "owned", "Existing bytes changed");
                input.close();
            } catch (...) { std::filesystem::remove(path); throw; }
            std::filesystem::remove(path);
        }},
        {"registered_catalog_is_not_graph_used_tensor_count", [] {
            std::vector<std::string> required;
            for (int i=0; i<134; ++i) { required.push_back("first_stage_model.decoder.synthetic."+std::to_string(i)); }
            auto source = required;
            source.push_back("first_stage_model.decoder.unused_temporal.weight");
            validate_decoder_catalog(required, source);
        }},
        {"missing_decoder_weight_names_are_diagnostic", [] {
            rejects([] { validate_decoder_catalog({"first_stage_model.conv2.weight"}, {}); });
            rejects([] { validate_decoder_catalog({}, {}); });
            rejects([] { validate_decoder_catalog({"first_stage_model.encoder.conv1.weight"}, {"first_stage_model.encoder.conv1.weight"}); });
        }},
};
}  // namespace

int main() {
    int failures = 0;
    for (const auto& test : tests) {
        try { test.second(); std::cout << "PASS " << test.first << '\n'; }
        catch (const std::exception& error) { ++failures; std::cout << "FAIL " << test.first << ": " << error.what() << '\n'; }
    }
    std::cout << "tests=" << tests.size() << " failures=" << failures << '\n';
    return failures ? 1 : 0;
}
