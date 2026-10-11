#include "teacher_contract.hpp"
#include <iostream>
#include <limits>
#include <stdexcept>

int main() {
    int passed = 0;
    auto reject = [&](auto action) {
        try { action(); } catch (const std::invalid_argument&) { ++passed; return; }
        throw std::runtime_error("Invalid teacher input was accepted");
    };
    try {
        using qwen_edge::TeacherCapture;
        reject([] { TeacherCapture capture(28, 15); });
        reject([] { TeacherCapture capture(36, 14); });
        reject([] { TeacherCapture capture(36, 513); });
        TeacherCapture capture(36, 15);
        if (!capture.wants("l_out-35") || capture.wants("result_norm") || capture.wants("l_out-23")) {
            throw std::runtime_error("Teacher must capture pre-norm final hidden states");
        }
        ++passed;
        reject([&] { capture.accept(std::vector<float>(15*4096-1)); });
        reject([&] { auto bad = std::vector<float>(15*4096); bad[0] = std::numeric_limits<float>::infinity(); capture.accept(bad); });
        reject([&] { capture.conditions(); });
        auto values = std::vector<float>(15*4096, 1.0f);
        values[14*4096] = 2.0f;
        capture.accept(values);
        if (capture.conditions().size() != 4096 || capture.conditions()[0] != 2.0f) {
            throw std::runtime_error("Teacher token-major trimming differs");
        }
        ++passed;
        reject([&] { capture.accept(values); });
        TeacherCapture pruned(24, 15);
        if (!pruned.wants("l_out-23") || pruned.wants("l_out-35")) { throw std::runtime_error("Wrong L24 layer"); }
        ++passed;
        std::cout << passed << " teacher contract checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n'; return 1;
    }
}
