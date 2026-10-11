#include "student_runtime.hpp"
#include <atomic>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
template<class Action> void rejects(Action action, const char * expected) {
    try { action(); } catch (const std::runtime_error & error) {
        if (std::string(error.what()).find(expected) != std::string::npos) return;
        throw;
    }
    throw std::runtime_error("invalid runtime request accepted");
}
}

int main() {
    try {
        rejects([] { qwen_edge::extract_student_features("missing.gguf", {}); }, "1..512 tokens");
        rejects([] { qwen_edge::extract_student_features("missing.gguf", std::vector<std::int32_t>(513)); }, "1..512 tokens");
        rejects([] { qwen_edge::extract_student_features("missing.gguf", {-1}); }, "negative token");
        rejects([] { qwen_edge::extract_student_features("missing.gguf", {1}); }, "model file");
        const std::atomic_bool cancelled{true};
        rejects([&] { qwen_edge::extract_student_features("missing.gguf", {1}, &cancelled); }, "cancelled");
        rejects([] { qwen_edge::tokenize_student_prompt("missing.gguf", ""); }, "prompt");
        rejects([] { qwen_edge::tokenize_student_prompt("missing.gguf", std::string(16001, 'a')); }, "prompt");
        rejects([&] { qwen_edge::tokenize_student_prompt("missing.gguf", "test", &cancelled); }, "cancelled");
        std::cout << "student runtime: 8 checks passed\n";
        return 0;
    } catch (const std::exception & error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
