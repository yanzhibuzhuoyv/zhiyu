// Bounded offline Q4_K_M export through the pinned llama.cpp API.
#include "llama.h"
#include <chrono>
#include <filesystem>
#include <iostream>
#include <stdexcept>

int main(int argc, char** argv) {
    try {
        if (argc != 3) { throw std::invalid_argument("Usage: teacher-quantize SOURCE_BF16_GGUF NEW_DIRECTORY"); }
        const std::filesystem::path source(argv[1]), directory(argv[2]);
        if (!std::filesystem::is_regular_file(source)) { throw std::invalid_argument("Missing source GGUF"); }
        if (!std::filesystem::create_directory(directory)) { throw std::invalid_argument("Output directory already exists"); }
        const auto output = directory / "encoder-q4_K_M.gguf";
        auto options = llama_model_quantize_default_params();
        options.nthread = 4;
        options.ftype = LLAMA_FTYPE_MOSTLY_Q4_K_M;
        const auto started = std::chrono::steady_clock::now();
        if (llama_model_quantize(source.string().c_str(), output.string().c_str(), &options) != 0) {
            throw std::runtime_error("Quantization failed; private partial directory retained");
        }
        std::cout << "Q4_K_M_BYTES " << std::filesystem::file_size(output)
                  << " SECONDS " << std::chrono::duration<double>(std::chrono::steady_clock::now()-started).count()
                  << std::endl;
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << std::endl;
        return 1;
    }
}
