// Research-only full 512x512 Turbo path: external text conditions, no cloud/TE fallback.
#include "stable-diffusion.h"
#include "qwen_edge_latent_checkpoint.hpp"
#define STB_IMAGE_WRITE_STATIC
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct image_owner {
    sd_image_t* images{nullptr};
    int count{0};
    ~image_owner() { if (images != nullptr) { free_sd_images(images, count); } }
};

std::vector<float> read_conditions(const std::filesystem::path& path, std::size_t tokens) {
    if (tokens == 0 || tokens > 498 || std::filesystem::file_size(path) != tokens*4096*sizeof(float)) {
        throw std::invalid_argument("Condition file dimensions differ");
    }
    std::ifstream input(path, std::ios::binary);
    std::vector<float> data(tokens*4096);
    input.read(reinterpret_cast<char*>(data.data()), static_cast<std::streamsize>(data.size()*sizeof(float)));
    if (!input) { throw std::runtime_error("Cannot read conditions"); }
    for (const auto value : data) {
        if (!std::isfinite(value)) { throw std::invalid_argument("Non-finite condition"); }
    }
    return data;
}

struct png_sink { FILE* file; bool failed{false}; };
void write_png_chunk(void* context, void* data, int size) {
    auto& sink = *static_cast<png_sink*>(context);
    if (size < 0 || std::fwrite(data, 1, static_cast<std::size_t>(size), sink.file) != static_cast<std::size_t>(size)) {
        sink.failed = true;
    }
}

void publish_image(const std::filesystem::path& path, const sd_image_t& image) {
    if (image.width != 512 || image.height != 512 || (image.channel != 3 && image.channel != 4) || image.data == nullptr) {
        throw std::runtime_error("Runtime returned invalid output image");
    }
#ifdef _WIN32
    auto* file = _wfopen(path.c_str(), L"wbx");
#else
    auto* file = std::fopen(path.c_str(), "wbx");
#endif
    if (file == nullptr) { throw std::runtime_error("Cannot exclusively create PNG output"); }
    std::unique_ptr<FILE, decltype(&std::fclose)> owner(file, &std::fclose);
    png_sink sink{file};
    const auto written = stbi_write_png_to_func(write_png_chunk, &sink, 512, 512,
                                               static_cast<int>(image.channel), image.data, 512*image.channel);
    if (written == 0 || sink.failed || std::fflush(file) != 0) {
        throw std::runtime_error("PNG write failed; incomplete output retained");
    }
    if (std::fclose(owner.release()) != 0) { throw std::runtime_error("PNG close failed"); }
}

double seconds_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
}
struct smoke_config {
    const char* dit;
    const char* vae;
    const char* conditions;
    std::size_t tokens;
    std::int64_t seed;
    std::filesystem::path output;
    std::optional<std::filesystem::path> checkpoint;
    bool sample_only{false};
};
smoke_config parse_config(int argc, char** argv) {
    if (argc != 7 && argc != 9) {
        throw std::invalid_argument("Usage: qwen-edge-smoke DIT VAE CONDITIONS TOKENS SEED NEW_PNG [--checkpoint|--sample-only NEW_CHECKPOINT]");
    }
    std::size_t parsed = 0;
    const auto tokens = std::stoul(argv[4], &parsed);
    if (parsed != std::string(argv[4]).size()) { throw std::invalid_argument("Invalid token count"); }
    const auto seed = std::stoll(argv[5], &parsed);
    if (parsed != std::string(argv[5]).size() || seed < 0) { throw std::invalid_argument("Invalid seed"); }
    const std::filesystem::path output(argv[6]);
    if (std::filesystem::exists(output)) { throw std::invalid_argument("Output already exists"); }
    smoke_config config{argv[1], argv[2], argv[3], tokens, seed, output, std::nullopt, false};
    if (argc == 9) {
        const std::string option(argv[7]);
        if (option != "--checkpoint" && option != "--sample-only") { throw std::invalid_argument("Unknown checkpoint option"); }
        config.checkpoint = std::filesystem::path(argv[8]);
        config.sample_only = option == "--sample-only";
        if (*config.checkpoint == output) { throw std::invalid_argument("PNG and checkpoint paths overlap"); }
        zhiyu::qwen_edge::checkpoint::validate_output_path(output, {config.dit, config.vae, config.conditions});
    }
    return config;
}
struct checkpoint_state {
    zhiyu::qwen_edge::checkpoint::checkpoint_writer* file{nullptr};
    std::atomic<int> last_step{0}, total_steps{0};
};
void configure_logging(checkpoint_state* checkpoint) {
    sd_set_log_callback([](sd_log_level_t level, const char* message, void*) {
        if (level >= SD_LOG_INFO) { std::cerr << message; }
    }, nullptr);
    sd_set_progress_callback([](int step, int steps, float time, void* context) {
        std::cout << "STEP " << step << '/' << steps << " SECONDS " << time << std::endl;
        if (context && steps == 8) {
            auto& state=*static_cast<checkpoint_state*>(context);
            state.last_step.store(step); state.total_steps.store(steps);
        }
    }, checkpoint);
}
using context_owner = std::unique_ptr<sd_ctx_t, decltype(&free_sd_ctx)>;
context_owner load_context(const smoke_config& config) {
    sd_ctx_params_t options;
    sd_ctx_params_init(&options);
    options.qwen_edge_external_condition = true;
    options.diffusion_model_path = config.dit;
    options.vae_path = config.vae;
    options.n_threads = 4;
    options.wtype = SD_TYPE_COUNT;
    options.enable_mmap = true;
    options.flash_attn = true;
    options.diffusion_flash_attn = true;
    context_owner context(new_sd_ctx(&options), &free_sd_ctx);
    if (context == nullptr) { throw std::runtime_error("Diffusion/VAE load failed"); }
    return context;
}
bool save_checkpoint(const qwen_edge_latent_view_t* view, void* context) {
    try {
        if (view == nullptr) { return false; }
        auto& state=*static_cast<checkpoint_state*>(context);
        zhiyu::qwen_edge::checkpoint::require_eight_progress(state.last_step.load(),state.total_steps.load());
        state.file->write(*view);
        std::cout << "LATENT_CHECKPOINT_COMPLETE true BYTES " << zhiyu::qwen_edge::checkpoint::file_bytes
                  << " STAGE diffusion_normalized SEED " << view->seed << std::endl;
        return true;
    } catch (const std::exception& error) {
        std::cerr << "LATENT_CHECKPOINT_FAILED " << error.what() << std::endl;
        return false;
    }
}
void generate_one(sd_ctx_t* context, const smoke_config& config, const std::vector<float>& data,
                  image_owner& generated, checkpoint_state* checkpoint) {
    qwen_edge_external_condition_t condition{data.data(), data.size(), static_cast<std::uint32_t>(config.tokens), 4096, nullptr, 0};
    std::array<float, 9> sigmas{1.0F, 0.978453F, 0.95418F, 0.926626F, 0.89508F, 0.845148F, 0.704534F, 0.414568F, 0.0F};
    sd_img_gen_params_t request;
    sd_img_gen_params_init(&request);
    request.external_condition = &condition;
    qwen_edge_latent_checkpoint_t sink{save_checkpoint, checkpoint, config.sample_only};
    request.latent_checkpoint = checkpoint ? &sink : nullptr;
    request.width = 512;
    request.height = 512;
    request.batch_count = 1;
    request.seed = config.seed;
    request.sample_params.guidance.txt_cfg = 1.0F;
    request.sample_params.sample_method = EULER_SAMPLE_METHOD;
    request.sample_params.sample_steps = 8;
    request.sample_params.custom_sigmas = sigmas.data();
    request.sample_params.custom_sigmas_count = static_cast<int>(sigmas.size());
    const bool generated_ok=generate_image(context, &request, &generated.images, &generated.count);
    if (!generated_ok || (config.sample_only ? generated.count != 0 || generated.images != nullptr : generated.count != 1)) {
        throw std::runtime_error("Full generation failed");
    }
    if (checkpoint && !checkpoint->file->complete()) { throw std::runtime_error("Checkpoint callback did not complete"); }
}
void run_smoke(const smoke_config& config) {
    std::unique_ptr<zhiyu::qwen_edge::checkpoint::checkpoint_writer> checkpoint;
    if (config.checkpoint) {
        checkpoint=std::make_unique<zhiyu::qwen_edge::checkpoint::checkpoint_writer>(
            *config.checkpoint, config.seed, std::vector<std::filesystem::path>{config.dit, config.vae, config.conditions});
    }
    const auto data = read_conditions(config.conditions, config.tokens);
    checkpoint_state callback_state;
    callback_state.file=checkpoint.get();
    configure_logging(checkpoint ? &callback_state : nullptr);
    const auto started = std::chrono::steady_clock::now();
    const auto context = load_context(config);
    std::cout << "LOAD_SECONDS " << seconds_since(started) << std::endl;
    image_owner generated;
    const auto generation_started = std::chrono::steady_clock::now();
    generate_one(context.get(), config, data, generated, checkpoint ? &callback_state : nullptr);
    if (config.checkpoint) {
        const auto verified=zhiyu::qwen_edge::checkpoint::read_checkpoint(*config.checkpoint);
        if (verified.seed!=config.seed) { throw std::runtime_error("Persisted checkpoint seed differs"); }
        std::cout << "LATENT_CHECKPOINT_VERIFIED true CRC32 " << verified.checksum << std::endl;
    }
    if (config.sample_only) {
        std::cout << "SAMPLE_ONLY_COMPLETE true STEPS 8 PNG_ARGUMENT_UNUSED true TOTAL_SECONDS " << seconds_since(started) << std::endl;
        return;
    }
    publish_image(config.output, generated.images[0]);
    std::cout << "GENERATION_SECONDS " << seconds_since(generation_started) << std::endl;
    std::cout << "TOTAL_SECONDS " << seconds_since(started) << " PNG_BYTES " << std::filesystem::file_size(config.output) << std::endl;
}
}  // namespace

int main(int argc, char** argv) {
    try {
        run_smoke(parse_config(argc, argv));
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Full generation failed: " << error.what() << '\n';
        return 1;
    }
}
