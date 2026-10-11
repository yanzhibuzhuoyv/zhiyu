// Research CLI: L36/L24 Qwen3-VL text conditions, preserving Qwen Image's pre-norm contract.
#include "teacher_contract.hpp"
#include "ggml-backend.h"
#include "llama.h"
#include <array>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>

namespace {
namespace fs = std::filesystem;
struct backend_owner { backend_owner() { llama_backend_init(); } ~backend_owner() { llama_backend_free(); } };
struct capture_state { qwen_edge::TeacherCapture capture; bool invalid{false}; };
struct batch_owner {
    llama_batch value;
    explicit batch_owner(int tokens) : value(llama_batch_init(tokens, 0, 1)) {
        if (!value.token || !value.pos || !value.n_seq_id || !value.seq_id || !value.logits) {
            llama_batch_free(value); throw std::runtime_error("Batch allocation failed");
        }
    }
    ~batch_owner() { llama_batch_free(value); }
    batch_owner(const batch_owner&) = delete;
    batch_owner& operator=(const batch_owner&) = delete;
};

bool capture_hidden(ggml_tensor* tensor, bool ask, void* user) noexcept {
    auto& state = *static_cast<capture_state*>(user);
    if (ask) { return state.capture.wants(ggml_get_name(tensor)); }
    if (!state.capture.wants(ggml_get_name(tensor))) { return !state.invalid; }
    try {
        if (tensor->type != GGML_TYPE_F32 || !ggml_is_contiguous(tensor) || tensor->ne[0] != 4096 ||
            tensor->ne[1] != state.capture.tokens() || tensor->ne[2] != 1 || tensor->ne[3] != 1) {
            throw std::invalid_argument("Teacher tensor dimensions differ");
        }
        std::vector<float> features(static_cast<std::size_t>(state.capture.tokens())*4096);
        if (ggml_nbytes(tensor) != features.size()*sizeof(float)) { throw std::invalid_argument("Teacher tensor bytes differ"); }
        ggml_backend_tensor_get(tensor, features.data(), 0, features.size()*sizeof(float));
        state.capture.accept(features);
        return true;
    } catch (...) { state.invalid = true; return false; }
}

void require_model(const llama_model* model, int layers) {
    std::array<char, 32> architecture{};
    if (llama_model_meta_val_str(model, "general.architecture", architecture.data(), architecture.size()) < 0 ||
        std::string_view(architecture.data()) != "qwen3vl" || llama_model_n_embd(model) != 4096 ||
        llama_model_n_layer(model) != layers || (layers != 36 && layers != 24)) {
        throw std::invalid_argument("Expected the explicitly requested L36/L24 Qwen3-VL encoder");
    }
}

std::vector<llama_token> tokenize(const llama_model* model, const std::string& prompt) {
    if (prompt.empty() || prompt.size() > 16000 || prompt.find('\0') != std::string::npos) {
        throw std::invalid_argument("Expected bounded nonempty UTF-8 prompt on stdin");
    }
    const auto wrapped = "<|im_start|>system\nComprehend and analyze the provided prompt.<|im_end|>\n"
                         "<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n";
    std::vector<llama_token> tokens(512);
    const auto count = llama_tokenize(llama_model_get_vocab(model), wrapped.data(), static_cast<int>(wrapped.size()),
                                      tokens.data(), 512, true, true);
    if (count < 15 || count > 512) { throw std::invalid_argument("Expected 15..512 template tokens"); }
    tokens.resize(count);
    return tokens;
}

std::vector<float> encode(llama_model* model, const std::vector<llama_token>& tokens, int layers) {
    capture_state state{qwen_edge::TeacherCapture(layers, static_cast<int>(tokens.size()))};
    auto parameters = llama_context_default_params();
    parameters.n_ctx = 512; parameters.n_batch = 512; parameters.n_ubatch = 512;
    parameters.n_threads = 4; parameters.n_threads_batch = 4;
    parameters.embeddings = true; parameters.pooling_type = LLAMA_POOLING_TYPE_NONE;
    parameters.attention_type = LLAMA_ATTENTION_TYPE_CAUSAL;
    parameters.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    parameters.offload_kqv = false; parameters.op_offload = false;
    parameters.cb_eval = capture_hidden; parameters.cb_eval_user_data = &state;
    std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model, parameters), llama_free);
    if (!context) { throw std::runtime_error("Cannot create teacher context"); }
    batch_owner batch(static_cast<int>(tokens.size()));
    batch.value.n_tokens = static_cast<int>(tokens.size());
    for (int i = 0; i < batch.value.n_tokens; ++i) {
        batch.value.token[i] = tokens[i]; batch.value.pos[i] = i;
        batch.value.n_seq_id[i] = 1; batch.value.seq_id[i][0] = 0; batch.value.logits[i] = 1;
    }
    const auto code = llama_decode(context.get(), batch.value);
    llama_synchronize(context.get());
    if (code != 0 || state.invalid) { throw std::runtime_error("Teacher hidden-state capture failed"); }
    return state.capture.conditions();
}

template<class T> void write_values(const fs::path& path, const std::vector<T>& values) {
#ifdef _WIN32
    auto* raw = _wfopen(path.c_str(), L"wbx");
#else
    auto* raw = std::fopen(path.c_str(), "wbx");
#endif
    if (!raw) { throw std::runtime_error("Cannot exclusively create research output"); }
    std::unique_ptr<FILE, decltype(&std::fclose)> output(raw, &std::fclose);
    if (std::fwrite(values.data(), sizeof(T), values.size(), raw) != values.size() || std::fflush(raw) != 0) {
        throw std::runtime_error("Research output write failed; partial retained");
    }
    if (std::fclose(output.release()) != 0) { throw std::runtime_error("Research output close failed"); }
}

std::string read_prompt() {
    std::array<char, 16001> buffer{};
    std::cin.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
    if (std::cin.gcount() > 16000 || std::cin.bad()) { throw std::invalid_argument("Prompt exceeds input byte limit"); }
    return std::string(buffer.data(), static_cast<std::size_t>(std::cin.gcount()));
}

double elapsed(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
}
}  // namespace

int main(int argc, char** argv) {
    try {
        if (argc != 4) { throw std::invalid_argument("Usage: teacher-extract GGUF LAYERS NEW_DIRECTORY < UTF8_PROMPT"); }
        std::size_t parsed = 0;
        const auto layers = std::stoi(argv[2], &parsed);
        if (parsed != std::string(argv[2]).size() || (layers != 36 && layers != 24)) { throw std::invalid_argument("Invalid layers"); }
        const fs::path output(argv[3]);
        if (!fs::create_directory(output)) { throw std::invalid_argument("Output directory already exists"); }
        const auto prompt = read_prompt();
        const auto started = std::chrono::steady_clock::now();
        const auto backend = backend_owner();
        auto parameters = llama_model_default_params(); parameters.n_gpu_layers = 0;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(argv[1], parameters), llama_model_free);
        if (!model) { throw std::runtime_error("Cannot load teacher model"); }
        require_model(model.get(), layers);
        const auto tokens = tokenize(model.get(), prompt);
        const auto ready = std::chrono::steady_clock::now();
        const auto conditions = encode(model.get(), tokens, layers);
        write_values(output / "conditions.f32", conditions); write_values(output / "tokens.i32", tokens);
        std::cout << "LAYERS " << layers << " CONDITION_TOKENS " << tokens.size()-14
                  << " LOAD_SECONDS " << std::chrono::duration<double>(ready-started).count()
                  << " FORWARD_SECONDS " << elapsed(ready) << " TOTAL_SECONDS " << elapsed(started) << std::endl;
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
