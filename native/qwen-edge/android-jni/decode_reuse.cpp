#define QWEN_EDGE_CHECKPOINT_DECODE 1
#define main zhiyu_qwen_research_decode_main
#include "../diffusion/vae_probe.cpp"
#undef main

int zhiyu_qwen_decode_stage(const std::vector<std::string>& arguments) {
    std::vector<char*> argv;
    for (const auto& argument:arguments) argv.push_back(const_cast<char*>(argument.c_str()));
    run_probe(parse_config(static_cast<int>(argv.size()),argv.data()),clock_type::now());
    return 0;
}
