#include "feature_contract.hpp"
#include "ggml-backend.h"
#include "llama.h"
#include "student_runtime.hpp"
#include <mutex>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <psapi.h>
#else
#include <sys/resource.h>
#endif

namespace fs = std::filesystem;
namespace {
using qwen_edge::ContractError;
using qwen_edge::FeatureCapture;
constexpr int max_tokens = 512;
constexpr int threads = 4;
std::mutex runtime_mutex;
struct CallbackState {
    FeatureCapture capture;
    std::string error;
    const std::atomic_bool* cancelled{nullptr};
    explicit CallbackState(int tokens) : capture(tokens) {}
};

bool capture_callback(ggml_tensor * tensor, bool ask, void * user) noexcept {
    auto & state = *static_cast<CallbackState *>(user);
    if (state.cancelled != nullptr && state.cancelled->load(std::memory_order_relaxed)) {
        state.error = "Student operation cancelled"; return false;
    }
    const int slot = FeatureCapture::target_slot(ggml_get_name(tensor));
    if (ask) return slot >= 0;
    if (slot < 0 || !state.error.empty()) return state.error.empty();
    try {
        if (tensor->type != GGML_TYPE_F32 || !ggml_is_contiguous(tensor) ||
            tensor->ne[0] != 1024 || tensor->ne[1] != state.capture.tokens() ||
            tensor->ne[2] != 1 || tensor->ne[3] != 1)
            throw ContractError("captured tensor type/layout differs from expected F32 [1024,tokens,1,1]");
        auto values = std::vector<float>(1024 * state.capture.tokens());
        if (ggml_nbytes(tensor) != values.size() * sizeof(float)) throw ContractError("tensor bytes differ");
        ggml_backend_tensor_get(tensor, values.data(), 0, values.size() * sizeof(float));
        state.capture.accept(slot, 1024, state.capture.tokens(), std::move(values));
        return true;
    } catch (const std::exception & error) {
        state.error = error.what(); // never propagate a C++ exception through the scheduler's C callback
        return false;
    }
}

void validate_model(const llama_model * model, const std::vector<llama_token> & tokens) {
    std::array<char, 32> architecture{};
    if (llama_model_meta_val_str(model, "general.architecture", architecture.data(), architecture.size()) < 0 ||
        std::string_view(architecture.data()) != "qwen3" || llama_model_n_layer(model) != 28 ||
        llama_model_n_embd(model) != 1024)
        throw ContractError("this extractor supports only the Qwen3 28-layer 1024-dimensional student");
    const auto vocab = llama_model_get_vocab(model);
    for (const auto token : tokens) {
        if (token < 0 || token >= llama_vocab_n_tokens(vocab)) throw ContractError("token outside model vocabulary");
    }
}

class OwnedBatch {
public:
    explicit OwnedBatch(const std::vector<llama_token> & tokens)
        : value_(llama_batch_init(static_cast<int>(tokens.size()), 0, 1)) {
        if (!value_.token || !value_.pos || !value_.n_seq_id || !value_.seq_id || !value_.logits) {
            llama_batch_free(value_);
            throw ContractError("batch allocation failed");
        }
        value_.n_tokens = static_cast<int>(tokens.size());
        for (int i = 0; i < value_.n_tokens; ++i) {
            value_.token[i] = tokens[i]; value_.pos[i] = i;
            value_.n_seq_id[i] = 1; value_.seq_id[i][0] = 0;
            value_.logits[i] = 1; // all tokens, including final layer: do not collapse to last token
        }
    }
    ~OwnedBatch() { llama_batch_free(value_); }
    OwnedBatch(const OwnedBatch &) = delete;
    OwnedBatch & operator=(const OwnedBatch &) = delete;
    llama_batch value() const noexcept { return value_; }
private:
    llama_batch value_{};
};

class BackendSession {
public:
    BackendSession() { llama_backend_init(); }
    ~BackendSession() { llama_backend_free(); }
    BackendSession(const BackendSession &) = delete;
    BackendSession & operator=(const BackendSession &) = delete;
};

llama_context_params context_parameters(CallbackState & state, const std::atomic_bool * cancelled) {
    auto params = llama_context_default_params();
    params.n_ctx = max_tokens; params.n_batch = max_tokens; params.n_ubatch = max_tokens;
    params.n_threads = threads; params.n_threads_batch = threads;
    params.embeddings = true; params.pooling_type = LLAMA_POOLING_TYPE_NONE;
    params.attention_type = LLAMA_ATTENTION_TYPE_CAUSAL;
    params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    params.offload_kqv = false; params.op_offload = false;
    params.abort_callback = [](void* data) {
        const auto* cancellation = static_cast<const std::atomic_bool*>(data);
        return cancellation != nullptr && cancellation->load(std::memory_order_relaxed);
    };
    params.abort_callback_data = const_cast<std::atomic_bool*>(cancelled);
    params.cb_eval = capture_callback; params.cb_eval_user_data = &state;
    return params;
}

std::uint64_t peak_memory() {
#ifdef _WIN32
    PROCESS_MEMORY_COUNTERS info{};
    if (!GetProcessMemoryInfo(GetCurrentProcess(), &info, sizeof(info))) return 0;
    return info.PeakWorkingSetSize;
#else
    rusage info{};
    if (getrusage(RUSAGE_SELF, &info) != 0) return 0;
    return std::uint64_t(info.ru_maxrss) * 1024;
#endif
}

}  // namespace
namespace qwen_edge {
std::vector<std::int32_t> tokenize_student_prompt(const fs::path& model_path, const std::string& prompt,
                                                const std::atomic_bool* cancelled) {
    if (prompt.empty() || prompt.size() > 16000 || prompt.find('\0') != std::string::npos) {
        throw ContractError("Expected bounded, nonempty prompt");
    }
    std::lock_guard<std::mutex> lock(runtime_mutex);
    if (cancelled != nullptr && cancelled->load(std::memory_order_relaxed)) { throw ContractError("Student operation cancelled"); }
    if (!fs::is_regular_file(model_path)) { throw ContractError("Missing model file"); }
    const auto backend_guard = BackendSession();
    auto parameters = llama_model_default_params();
    parameters.n_gpu_layers = 0;
    parameters.progress_callback = [](float, void* data) {
        const auto* cancellation = static_cast<const std::atomic_bool*>(data);
        return cancellation == nullptr || !cancellation->load(std::memory_order_relaxed);
    };
    parameters.progress_callback_user_data = const_cast<std::atomic_bool*>(cancelled);
    auto model = std::unique_ptr<llama_model, decltype(&llama_model_free)>(
        llama_model_load_from_file(model_path.string().c_str(), parameters), llama_model_free);
    if (!model) { throw ContractError("model load failed"); }
    validate_model(model.get(), {});
    const std::string wrapped = "<|im_start|>system\nComprehend and analyze the provided prompt.<|im_end|>\n"
                                "<|im_start|>user\n" + prompt + "<|im_end|>\n<|im_start|>assistant\n";
    std::vector<std::int32_t> tokens(max_tokens);
    const int count = llama_tokenize(llama_model_get_vocab(model.get()), wrapped.data(),
        static_cast<int>(wrapped.size()), tokens.data(), max_tokens, true, true);
    if (count <= 14 || count > max_tokens) { throw ContractError("Prompt exceeds 512 tokens or lacks content"); }
    if (cancelled != nullptr && cancelled->load(std::memory_order_relaxed)) { throw ContractError("Student operation cancelled"); }
    tokens.resize(count);
    return tokens;
}

student_result extract_student_features(const fs::path& model_path, const std::vector<std::int32_t>& tokens,
                                       const std::atomic_bool* cancelled) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    const auto check_cancel = [&] {
        if (cancelled != nullptr && cancelled->load(std::memory_order_relaxed)) throw ContractError("Student operation cancelled");
    };
    check_cancel();
    if (tokens.empty() || tokens.size() > max_tokens) throw ContractError("Expected 1..512 tokens");
    for (const auto token : tokens) { if (token < 0) throw ContractError("negative token"); }
    if (!fs::is_regular_file(model_path)) throw ContractError("Missing model file");
    auto state = CallbackState(static_cast<int>(tokens.size()));
    state.cancelled = cancelled;
    llama_log_set([](ggml_log_level level, const char * message, void *) {
        if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) std::cerr << message;
    }, nullptr);
    const auto backend_guard = BackendSession();
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.progress_callback = [](float, void* data) {
        const auto* cancellation = static_cast<const std::atomic_bool*>(data);
        return cancellation == nullptr || !cancellation->load(std::memory_order_relaxed);
    };
    model_params.progress_callback_user_data = const_cast<std::atomic_bool*>(cancelled);
    const auto started = std::chrono::steady_clock::now();
    auto model = std::unique_ptr<llama_model, decltype(&llama_model_free)>(
        llama_model_load_from_file(model_path.string().c_str(), model_params), llama_model_free);
    if (!model) throw ContractError("model load failed");
    check_cancel();
    validate_model(model.get(), tokens);
    const auto params = context_parameters(state, cancelled);
    auto context = std::unique_ptr<llama_context, decltype(&llama_free)>(
        llama_init_from_model(model.get(), params), llama_free);
    if (!context) throw ContractError("context creation failed");
    const auto ready = std::chrono::steady_clock::now();
    const auto batch = OwnedBatch(tokens);
    const int status = llama_decode(context.get(), batch.value());
    llama_synchronize(context.get());
    check_cancel();
    if (!state.error.empty()) throw ContractError(state.error);
    if (status != 0) throw ContractError("llama_decode failed: " + std::to_string(status));
    const auto finished = std::chrono::steady_clock::now();
    const auto features = state.capture.features();
    return {features, tokens, std::chrono::duration<double>(ready-started).count(),
            std::chrono::duration<double>(finished-ready).count(), peak_memory()};
}
}  // namespace qwen_edge
