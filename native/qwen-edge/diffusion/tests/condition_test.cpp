#include "qwen_edge_condition.hpp"
#include <functional>
#include <iostream>
#include <limits>
#include <string>
#include <utility>

namespace {
using namespace zhiyu::qwen_edge;
void check(bool actual, const char* reason) {
    if (!actual) throw std::runtime_error(reason);
}
template<class F> void rejects(F fn) {
    bool rejected = false;
    try { fn(); } catch (const std::invalid_argument&) { rejected = true; }
    check(rejected, "expected invalid_argument");
}
struct fixture {
    std::vector<float> values = std::vector<float>(3 * condition_channels, .25f);
    std::vector<uint8_t> mask{1, 0, 1};
    qwen_edge_external_condition_t input() {
        return {values.data(), values.size(), 3, condition_channels, mask.data(), mask.size()};
    }
};
bool cancel_always(void*) { return true; }
bool cancel_second(void* data) { return ++*static_cast<int*>(data) == 2; }
}

namespace {
const std::vector<std::pair<std::string, std::function<void()>>> tests{
        {"mask_compacts_in_original_order", [] {
            fixture f; f.values[2 * condition_channels] = .75f;
            const auto out = copy_condition(f.input());
            check(out.tokens == 2, "wrong selected tokens");
            check(out.values.size() == 8192 && out.values[4096] == .75f, "wrong row layout");
        }},
        {"copied_storage_survives_caller_mutation", [] {
            fixture f; const auto out = copy_condition(f.input());
            f.values[0] = 7.f; check(out.values[0] == .25f, "borrowed result storage");
        }},
        {"optional_mask", [] {
            fixture f; auto in = f.input(); in.mask = nullptr; in.mask_count = 0;
            const auto out = copy_condition(in); check(out.tokens == 3, "missing optional mask");
        }},
        {"wrong_channels", [] {fixture f; auto in=f.input(); in.channel_count=1024; rejects([&]{copy_condition(in);});}},
        {"zero_tokens", [] {fixture f; auto in=f.input(); in.token_count=0; rejects([&]{copy_condition(in);});}},
        {"too_many_tokens", [] {fixture f; auto in=f.input(); in.token_count=499; rejects([&]{copy_condition(in);});}},
        {"short_values", [] {fixture f; auto in=f.input(); in.value_count--; rejects([&]{copy_condition(in);});}},
        {"short_mask", [] {fixture f; auto in=f.input(); in.mask_count=2; rejects([&]{copy_condition(in);});}},
        {"null_mask_with_count", [] {fixture f; auto in=f.input(); in.mask=nullptr; rejects([&]{copy_condition(in);});}},
        {"mask_must_be_boolean", [] {fixture f; f.mask[1]=2; rejects([&]{copy_condition(f.input());});}},
        {"all_masked_tokens", [] {fixture f; f.mask={0,0,0}; rejects([&]{copy_condition(f.input());});}},
        {"nan_visible", [] {fixture f; f.values[0]=std::numeric_limits<float>::quiet_NaN(); rejects([&]{copy_condition(f.input());});}},
        {"infinite_masked_row", [] {fixture f; f.values[4096]=std::numeric_limits<float>::infinity(); rejects([&]{copy_condition(f.input());});}},
        {"pre_cancelled", [] {fixture f; rejects([&]{copy_condition(f.input(), cancel_always);});}},
        {"cancel_during_copy", [] {fixture f; int count=0; rejects([&]{copy_condition(f.input(), cancel_second, &count);});}},
        {"sigma_boundaries_keep_eight_steps", [] {validate_turbo_sigmas(turbo_sigma_boundaries.data(), 9);}},
        {"eight_positive_sigmas_need_terminal_zero", [] {rejects([&]{validate_turbo_sigmas(turbo_sigma_boundaries.data(), 8);});}},
        {"modified_sigma_rejected", [] {auto sigmas=turbo_sigma_boundaries; sigmas[2]+=.001f; rejects([&]{validate_turbo_sigmas(sigmas.data(), 9);});}},
        {"sigma_nan_rejected", [] {auto sigmas=turbo_sigma_boundaries; sigmas[2]=std::numeric_limits<float>::quiet_NaN(); rejects([&]{validate_turbo_sigmas(sigmas.data(), 9);});}},
};
}  // namespace

int main() {
    int failures=0;
    for(const auto& test : tests) {
        try { test.second(); std::cout << "PASS " << test.first << '\n'; }
        catch(const std::exception& e) { ++failures; std::cout << "FAIL " << test.first << ": " << e.what() << '\n'; }
    }
    std::cout << "tests=" << tests.size() << " failures=" << failures << '\n';
    return failures == 0 ? 0 : 1;
}
