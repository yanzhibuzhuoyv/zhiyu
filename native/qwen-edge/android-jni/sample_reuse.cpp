// A separate translation unit preserves the tested CLI's anonymous implementation unchanged.
#define main zhiyu_qwen_research_sample_main
#include "../diffusion/smoke.cpp"
#undef main

int zhiyu_qwen_sample_stage(const std::vector<std::string>& arguments) {
    std::vector<char*> argv;
    for (const auto& argument:arguments) argv.push_back(const_cast<char*>(argument.c_str()));
    run_smoke(parse_config(static_cast<int>(argv.size()),argv.data()));
    return 0;
}
