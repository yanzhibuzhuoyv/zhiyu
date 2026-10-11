#include "adapter.h"

#include <chrono>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

namespace {
std::vector<float> read_features(const std::filesystem::path& path, std::size_t tokens) {
    const auto count = tokens * 6144;
    if (tokens <= 14 || tokens > 512 || std::filesystem::file_size(path) != count * sizeof(float)) {
        throw std::invalid_argument("Feature file dimensions differ");
    }
    std::ifstream input(path, std::ios::binary);
    std::vector<float> values(count);
    input.read(reinterpret_cast<char*>(values.data()), static_cast<std::streamsize>(count * sizeof(float)));
    if (!input) { throw std::runtime_error("Cannot read student features"); }
    return values;
}

void write_new_file(const std::filesystem::path& target, const std::vector<float>& values) {
#if defined(_WIN32)
    auto* file = _wfopen(target.c_str(), L"wbx");
#else
    auto* file = std::fopen(target.c_str(), "wbx");
#endif
    if (file == nullptr) { throw std::runtime_error("Cannot exclusively create new output"); }
    std::unique_ptr<FILE, decltype(&std::fclose)> output(file, &std::fclose);
    if (std::fwrite(values.data(), sizeof(float), values.size(), output.get()) != values.size() ||
        std::fflush(output.get()) != 0) {
        throw std::runtime_error("Cannot write condition file; incomplete output retained");
    }
    if (std::fclose(output.release()) != 0) {
        throw std::runtime_error("Cannot close condition file; incomplete output retained");
    }
}
}

int main(int argc, char** argv) {
    try {
        if (argc != 5) { throw std::invalid_argument("Usage: qwen-adapter-project ADAPTER FEATURES TOKENS NEW_OUTPUT"); }
        std::size_t parsed = 0;
        const auto tokens = std::stoul(argv[3], &parsed);
        if (parsed != std::string(argv[3]).size() || tokens <= 14 || tokens > 512) {
            throw std::invalid_argument("Invalid token count");
        }
        const std::filesystem::path target(argv[4]);
        if (std::filesystem::exists(target)) { throw std::invalid_argument("Output already exists"); }
        const auto features = read_features(argv[2], tokens);
        const auto model = zhiyu::qwen::adapter::load(argv[1]);
        const auto started = std::chrono::steady_clock::now();
        const auto conditions = model.project(features, tokens);
        const auto seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
        write_new_file(target, conditions);
        std::cout << "condition_tokens=" << tokens - 14 << " channels=4096 seconds=" << seconds << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Adapter failed: " << error.what() << '\n';
        return 1;
    }
}
