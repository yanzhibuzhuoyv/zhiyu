#ifndef ZHIYU_QWEN_STUDENT_RUNTIME_HPP
#define ZHIYU_QWEN_STUDENT_RUNTIME_HPP
#include <atomic>
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace qwen_edge {
struct student_result {
    std::vector<float> features;
    std::vector<std::int32_t> tokens;
    double load_seconds;
    double forward_seconds;
    std::uint64_t process_peak_bytes;
};
// CPU-only, complete six-layer feature matrix; adapter must trim the first 14 tokens.
// Calls are serialized because the upstream backend/log state is process global.
student_result extract_student_features(const std::filesystem::path& model,
                                       const std::vector<std::int32_t>& tokens,
                                       const std::atomic_bool* cancelled = nullptr);
std::vector<std::int32_t> tokenize_student_prompt(const std::filesystem::path& model,
                                                const std::string& prompt,
                                                const std::atomic_bool* cancelled = nullptr);
}  // namespace qwen_edge
#endif
