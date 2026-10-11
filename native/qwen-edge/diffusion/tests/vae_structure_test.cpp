// Small synthetic tensor metadata only: no model file or graph execution.
#include "qwen_edge_vae_probe.hpp"
#include "ggml-cpu.h"
#include "model/vae/wan_vae.hpp"
#include <iostream>

int main() {
    try {
        std::unique_ptr<ggml_backend, decltype(&ggml_backend_free)> backend(ggml_backend_cpu_init(), &ggml_backend_free);
        if (!backend) { throw std::runtime_error("CPU initialization failed"); }
        String2TensorStorage synthetic;
        const std::int64_t shape[]{3,3,64,1152};
        const std::string conv = "first_stage_model.decoder.conv1.weight";
        synthetic.emplace(conv, TensorStorage(conv, GGML_TYPE_BF16, shape, 4, 0));
        WAN::WanVAERunner runner(backend.get(), synthetic, "first_stage_model", true, VERSION_QWEN_IMAGE_2_1);
        std::map<std::string, ggml_tensor*> params;
        runner.get_param_tensors(params);
        std::vector<std::string> names;
        for (const auto& entry : params) { names.push_back(entry.first); }
        zhiyu::qwen_edge::vae_probe::validate_decoder_catalog(names, names);
        if (runner.get_scale_factor() != 16 || runner.ae.z_dim != 64 || runner.ae.input_channels != 4) {
            throw std::runtime_error("Qwen2.1 synthetic geometry changed");
        }
        std::cout << "PASS QWEN_IMAGE_2_1 SYNTHETIC_METADATA BUILDER_PARAMS " << params.size()
                  << " LATENT_CHANNELS 64 SCALE 16 RGBA 4 WEIGHT_LOADS 0 GRAPH_RUNS 0" << std::endl;
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << std::endl; return 1; }
}
