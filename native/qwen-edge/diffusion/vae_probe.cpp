// Research-only decoder isolation; no DiT, text encoder, GPU or cloud fallback.
#include "qwen_edge_vae_probe.hpp"
#include "qwen_edge_latent_checkpoint.hpp"
#include "stable-diffusion.h"
#include "ggml-cpu.h"
#include "model_manager.h"
#include "model/vae/wan_vae.hpp"
#define STB_IMAGE_WRITE_STATIC
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#include <chrono>
#include <fstream>
#include <iostream>
#include <memory>
#include <optional>
#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#endif

namespace {
using namespace zhiyu::qwen_edge::vae_probe;
using clock_type = std::chrono::steady_clock;
double elapsed(clock_type::time_point start) {
    return std::chrono::duration<double>(clock_type::now() - start).count();
}
std::optional<std::uint64_t> peak_rss() {
#ifdef _WIN32
    PROCESS_MEMORY_COUNTERS counters{};
    if (GetProcessMemoryInfo(GetCurrentProcess(), &counters, sizeof(counters))) {
        return counters.PeakWorkingSetSize;
    }
#else
    std::ifstream input("/proc/self/status");
    std::string line;
    while (std::getline(input, line)) {
        if (line.rfind("VmHWM:", 0) == 0) {
            return std::stoull(line.substr(6)) * 1024;
        }
    }
#endif
    return std::nullopt;
}
void memory_marker() {
    const auto bytes = peak_rss();
    std::cout << "PEAK_RSS_BYTES " << (bytes ? std::to_string(*bytes) : "unknown") << std::endl;
}

struct decoder_runtime {
    std::unique_ptr<ggml_backend, decltype(&ggml_backend_free)> backend{nullptr, &ggml_backend_free};
    std::shared_ptr<ModelManager> manager;
    std::unique_ptr<WAN::WanVAERunner> runner;
    ~decoder_runtime() {
        // Free manager storage while its ggml tensor metadata is still alive.
        if (runner) { runner->runner_end(); }
        manager.reset();
        runner.reset();
    }
    void load(const std::string& path) {
        ModelLoader loader;
        if (!loader.init_from_file_and_convert_name(path, "vae.", VERSION_QWEN_IMAGE_2_1)) {
            throw std::runtime_error("Cannot parse Qwen Image 2.1 VAE");
        }
        backend.reset(ggml_backend_cpu_init());
        if (!backend) { throw std::runtime_error("Cannot initialize CPU backend"); }
        manager = std::make_shared<ModelManager>();
        manager->set_n_threads(4);
        manager->set_enable_mmap(true);
        manager->set_writable_mmap(false);
        if (!manager->set_loader(std::move(loader))) { throw std::runtime_error("VAE loader registration failed"); }
        manager->prepare_file_io();
        runner = std::make_unique<WAN::WanVAERunner>(backend.get(),
            manager->loader().get_tensor_storage_map(), "first_stage_model", true,
            VERSION_QWEN_IMAGE_2_1, manager);
        if (runner->get_scale_factor() != 16 || runner->get_encoder_output_channels(4) != 64 ||
            runner->ae.input_channels != 4) { throw std::runtime_error("Wrong Qwen2.1 VAE geometry"); }
        runner->set_flash_attention_enabled(true);
        register_decoder();
        std::cout << "VAE_METADATA_READY LATENT 32x32x64 SCALE 16 RGBA 4" << std::endl;
    }
    void register_decoder() {
        std::map<std::string, ggml_tensor*> params;
        runner->get_param_tensors(params);
        std::vector<std::string> required, source;
        for (const auto& entry : params) { required.push_back(entry.first); }
        const auto& catalog = manager->loader().get_tensor_storage_map();
        for (const auto& entry : catalog) {
            if (is_decoder_name(entry.first)) { source.push_back(entry.first); }
        }
        std::cout << "DECODER_CATALOG SOURCE_ALL " << catalog.size() << " SOURCE_DECODER "
                  << source.size() << " BUILDER_PARAMS " << params.size() << std::endl;
        validate_decoder_catalog(required, source);
        if (!manager->register_param_tensors(ModelComponent::VAE,
            std::move(params), ModelManager::ResidencyMode::ParamBackend,
            backend.get(), backend.get())) {
            throw std::runtime_error("Decoder register_param_tensors failed; builder_params=" + std::to_string(required.size()));
        }
        std::cout << "DECODER_REGISTERED PARAMS " << required.size() << std::endl;
        if (!manager->validate_registered_tensors()) {
            throw std::runtime_error("Decoder validate_registered_tensors failed; inspect preceding shape/name errors");
        }
        std::cout << "DECODER_VALIDATED PARAMS " << required.size() << std::endl;
    }
};

struct png_sink { FILE* file; bool failed{false}; };
void png_chunk(void* context, void* data, int size) {
    auto& sink = *static_cast<png_sink*>(context);
    if (size < 0 || std::fwrite(data, 1, static_cast<std::size_t>(size), sink.file) != static_cast<std::size_t>(size)) {
        sink.failed = true;
    }
}
void publish(exclusive_output& output, const sd::Tensor<float>& image) {
    if (image.shape() != std::vector<std::int64_t>{512, 512, 4, 1}) {
        throw std::runtime_error("VAE output must be 512x512x4x1");
    }
    const auto rgba = pack_rgba(image.data(), static_cast<std::size_t>(image.numel()), 512, 512);
    png_sink sink{output.file()};
    if (!stbi_write_png_to_func(png_chunk, &sink, 512, 512, 4, rgba.data(), 512*4) || sink.failed) {
        throw std::runtime_error("PNG write failed; incomplete output retained");
    }
    output.finish();
}
sd_tiling_params_t tiling_params(decode_mode mode) {
    sd_tiling_params_t tiling{};
    tiling.enabled = mode == decode_mode::tile256;
    tiling.tile_size_w = 256;
    tiling.tile_size_h = 256;
    tiling.target_overlap = .25F;
    return tiling;
}
struct probe_config {
    std::string vae;
    decode_mode mode;
    latent_mode pattern;
    std::filesystem::path output;
    std::string mode_label, pattern_label;
    std::optional<std::filesystem::path> checkpoint;
};
probe_config parse_config(int argc, char** argv) {
#ifdef QWEN_EDGE_CHECKPOINT_DECODE
    if (argc != 5) { throw std::invalid_argument("Usage: qwen-edge-latent-decode VAE full|tile256 CHECKPOINT NEW_PNG"); }
    zhiyu::qwen_edge::checkpoint::validate_source_path(argv[1]);
    zhiyu::qwen_edge::checkpoint::validate_output_path(argv[4], {argv[1],argv[3]});
    return {argv[1], parse_decode_mode(argv[2]), latent_mode::zero, argv[4], argv[2], "checkpoint", std::filesystem::path(argv[3])};
#else
    if (argc != 5) {
        throw std::invalid_argument("Usage: qwen-edge-vae-probe VAE full|tile256 zero|sine NEW_PNG");
    }
    return {argv[1], parse_decode_mode(argv[2]), parse_latent_mode(argv[3]), argv[4], argv[2], argv[3], std::nullopt};
#endif
}
void configure_logging() {
    sd_set_log_callback([](sd_log_level_t level, const char* message, void*) {
        if (level >= SD_LOG_VERBOSE) { std::cerr << message; }
    }, nullptr);
    sd_set_progress_callback([](int step, int steps, float time, void*) {
        std::cout << "LOADER_PROGRESS " << step << '/' << steps << " CALLBACK_VALUE " << time << std::endl;
    }, nullptr);
}
void run_probe(const probe_config& config, clock_type::time_point started) {
    if (!std::filesystem::is_regular_file(config.vae)) { throw std::invalid_argument("VAE file is absent"); }
    auto values=make_diffusion_latent(config.pattern);
    if (config.checkpoint) {
        auto record=zhiyu::qwen_edge::checkpoint::read_checkpoint(*config.checkpoint);
        values=std::move(record.values);
        std::cout << "CHECKPOINT_VALIDATED true STAGE diffusion_normalized SEED " << record.seed
                  << " CRC32 " << record.checksum << std::endl;
    }
    exclusive_output output(config.output);
    configure_logging();
    std::cout << (config.checkpoint ? "LATENT_DECODE_BEGIN" : "VAE_PROBE_BEGIN")
              << " VERSION QWEN_IMAGE_2_1 MODE " << config.mode_label
              << (config.checkpoint ? " CHECKPOINT_DIFFUSION_LATENT " : " SYNTHETIC_DIFFUSION_LATENT ")
              << config.pattern_label << " THREADS 4" << std::endl;
    decoder_runtime runtime;
    runtime.load(config.vae);
    std::cout << "METADATA_SECONDS " << elapsed(started) << std::endl;
    const sd::Tensor<float> latent({32,32,64,1}, std::move(values));
    const auto decoded_input = runtime.runner->diffusion_to_vae_latents(latent);
    std::cout << "DECODE_BEGIN ELAPSED_SECONDS " << elapsed(started) << std::endl;
    memory_marker();
    const auto decode_start = clock_type::now();
    const auto image = runtime.runner->decode(4, decoded_input, tiling_params(config.mode));
    if (image.empty()) { throw std::runtime_error("VAE returned empty image; ggml status=" +
        std::to_string(runtime.runner->last_compute_status())); }
    std::cout << "DECODE_COMPLETE SECONDS " << elapsed(decode_start) << std::endl;
    memory_marker();
    publish(output, image);
    std::cout << "PNG_COMPLETE true PNG_BYTES " << std::filesystem::file_size(config.output)
              << " TOTAL_SECONDS " << elapsed(started) << std::endl;
}
#ifdef QWEN_EDGE_CHECKPOINT_DECODE
void inspect_checkpoint(const char* path, bool json) {
    const auto record=zhiyu::qwen_edge::checkpoint::read_checkpoint(path);
    if (json) {
        std::cout << "{\"scope\":\"research_checkpoint_inspection\",\"version\":1,\"format_version\":1,\"model\":\"QwenImage2.1\",\"stage\":\"diffusion_normalized\","
                  << "\"width\":32,\"height\":32,\"channels\":64,\"batch\":1,\"steps\":8,"
                  << "\"shape\":[32,32,64,1],\"value_count\":65536,\"dtype\":\"F32LE\",\"layout\":\"width_fast\","
                  << "\"sampling_steps\":8,\"seed\":" << record.seed << ",\"crc32\":" << record.checksum
                  << ",\"bytes\":" << zhiyu::qwen_edge::checkpoint::file_bytes
                  << ",\"crc_verified\":true,\"finite\":true,\"model_loaded\":false}" << std::endl;
        return;
    }
    std::cout << "CHECKPOINT_VALIDATED true STAGE diffusion_normalized SEED " << record.seed
              << " CRC32 " << record.checksum << " BYTES " << zhiyu::qwen_edge::checkpoint::file_bytes << std::endl;
}
#endif
}  // namespace

int main(int argc, char** argv) {
    const auto started = clock_type::now();
    try {
#ifdef QWEN_EDGE_CHECKPOINT_DECODE
        if (argc == 3 && (std::string(argv[1]) == "--verify" || std::string(argv[1]) == "--inspect")) {
            inspect_checkpoint(argv[2], std::string(argv[1]) == "--inspect");
            return 0;
        }
#endif
        run_probe(parse_config(argc, argv), started);
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "VAE_PROBE_FAILED " << error.what() << " ELAPSED_SECONDS " << elapsed(started) << std::endl;
        memory_marker();
        return 1;
    }
}
