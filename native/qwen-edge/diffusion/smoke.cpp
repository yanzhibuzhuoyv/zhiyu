// Research-only full 512x512 Turbo path: external text conditions, no cloud/TE fallback.
#include "stable-diffusion.h"
#define STB_IMAGE_WRITE_STATIC
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
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
};
smoke_config parse_config(int argc, char** argv) {
    if (argc != 7) { throw std::invalid_argument("Usage: qwen-edge-smoke DIT VAE CONDITIONS TOKENS SEED NEW_PNG"); }
    std::size_t parsed = 0;
    const auto tokens = std::stoul(argv[4], &parsed);
    if (parsed != std::string(argv[4]).size()) { throw std::invalid_argument("Invalid token count"); }
    const auto seed = std::stoll(argv[5], &parsed);
    if (parsed != std::string(argv[5]).size() || seed < 0) { throw std::invalid_argument("Invalid seed"); }
    const std::filesystem::path output(argv[6]);
    if (std::filesystem::exists(output)) { throw std::invalid_argument("Output already exists"); }
    return {argv[1], argv[2], argv[3], tokens, seed, output};
}
void configure_logging() {
    sd_set_log_callback([](sd_log_level_t level, const char* message, void*) {
        if (level >= SD_LOG_INFO) { std::cerr << message; }
    }, nullptr);
    sd_set_progress_callback([](int step, int steps, float time, void*) {
        std::cout << "STEP " << step << '/' << steps << " SECONDS " << time << std::endl;
    }, nullptr);
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
void generate_one(sd_ctx_t* context, const smoke_config& config, const std::vector<float>& data, image_owner& generated) {
    qwen_edge_external_condition_t condition{data.data(), data.size(), static_cast<std::uint32_t>(config.tokens), 4096, nullptr, 0};
    std::array<float, 9> sigmas{1.0F, 0.978453F, 0.95418F, 0.926626F, 0.89508F, 0.845148F, 0.704534F, 0.414568F, 0.0F};
    sd_img_gen_params_t request;
    sd_img_gen_params_init(&request);
    request.external_condition = &condition;
    request.width = 512;
    request.height = 512;
    request.batch_count = 1;
    request.seed = config.seed;
    request.sample_params.guidance.txt_cfg = 1.0F;
    request.sample_params.sample_method = EULER_SAMPLE_METHOD;
    request.sample_params.sample_steps = 8;
    request.sample_params.custom_sigmas = sigmas.data();
    request.sample_params.custom_sigmas_count = static_cast<int>(sigmas.size());
    if (!generate_image(context, &request, &generated.images, &generated.count) || generated.count != 1) {
        throw std::runtime_error("Full generation failed");
    }
}
void run_smoke(const smoke_config& config) {
    const auto data = read_conditions(config.conditions, config.tokens);
    configure_logging();
    const auto started = std::chrono::steady_clock::now();
    const auto context = load_context(config);
    std::cout << "LOAD_SECONDS " << seconds_since(started) << std::endl;
    image_owner generated;
    const auto generation_started = std::chrono::steady_clock::now();
    generate_one(context.get(), config, data, generated);
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
