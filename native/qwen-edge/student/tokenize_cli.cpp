#include "student_runtime.hpp"
#include <array>
#include <iostream>
#include <stdexcept>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

namespace {
std::string read_prompt() {
#ifdef _WIN32
    if (_setmode(_fileno(stdin), _O_BINARY) == -1) { throw std::runtime_error("Cannot read binary UTF8 input"); }
#endif
    std::array<char, 1024> chunk;
    std::string prompt;
    while (std::cin.read(chunk.data(), chunk.size()) || std::cin.gcount() > 0) {
        if (prompt.size()+static_cast<std::size_t>(std::cin.gcount()) > 16000) { throw std::invalid_argument("Prompt too large"); }
        prompt.append(chunk.data(), static_cast<std::size_t>(std::cin.gcount()));
    }
    if (std::cin.bad()) { throw std::runtime_error("Cannot read prompt"); }
    return prompt;
}
}

int main(int argc, char** argv) {
    try {
        if (argc != 2) { throw std::invalid_argument("Usage: qwen-student-tokenize MODEL (UTF8 prompt on stdin)"); }
        const auto tokens = qwen_edge::tokenize_student_prompt(argv[1], read_prompt());
        for (const auto token : tokens) { std::cout << token << '\n'; }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
