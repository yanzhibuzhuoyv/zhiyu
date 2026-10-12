#include "jni_utils.hpp"
#include "student_runtime.hpp"
#include "adapter.h"
#include "qwen_edge_latent_checkpoint.hpp"
#include <cmath>
#include <cstdio>
#include <mutex>
#include <unistd.h>

namespace {
std::mutex text_operation_mutex;
class conditions_output {
public:
    conditions_output(const std::filesystem::path& path, const std::vector<std::filesystem::path>& inputs) {
        zhiyu::qwen_edge::checkpoint::validate_output_path(path,inputs);
        file_=std::fopen(path.c_str(),"wbx");
        if (!file_) throw std::runtime_error("Cannot exclusively claim new conditions file");
    }
    ~conditions_output() { if (file_) std::fclose(file_); }
    void finish(const std::vector<float>& values) {
        for (float v:values) if (!std::isfinite(v)) throw std::runtime_error("Non-finite projected condition");
        if (std::fwrite(values.data(),sizeof(float),values.size(),file_)!=values.size() ||
            std::fflush(file_)!=0 || ::fsync(::fileno(file_))!=0) {
            throw std::runtime_error("Conditions write/fsync failed; partial output retained");
        }
        auto* closing=file_; file_=nullptr;
        if (std::fclose(closing)!=0) throw std::runtime_error("Conditions close failed");
    }
private:
    FILE* file_{nullptr};
};
}

extern "C" JNIEXPORT jint JNICALL Java_com_zhiyu_app_data_local_qwen_QwenTextNative_encode(
    JNIEnv* env,jclass,jstring model,jstring adapter,jbyteArray prompt,jstring output) {
    try {
        const auto model_path=zhiyu::qwen_jni::path_string(env,model);
        const auto adapter_path=zhiyu::qwen_jni::path_string(env,adapter);
        const auto target=zhiyu::qwen_jni::path_string(env,output);
        const auto text=zhiyu::qwen_jni::prompt_bytes(env,prompt);
        std::lock_guard<std::mutex> lock(text_operation_mutex);
        conditions_output sink(target,{model_path,adapter_path});
        const auto tokens=qwen_edge::tokenize_student_prompt(model_path,text);
        const auto extracted=qwen_edge::extract_student_features(model_path,tokens);
        // adapter.project exclusively owns removal of the first 14 tokens.
        const auto weights=zhiyu::qwen::adapter::load(adapter_path);
        const auto values=weights.project(extracted.features,tokens.size());
        const auto actual=tokens.size()-14;
        if (actual<1 || actual>498 || values.size()!=actual*4096) throw std::runtime_error("Projected dimensions differ");
        sink.finish(values);
        if (std::filesystem::file_size(target)!=values.size()*sizeof(float)) throw std::runtime_error("Conditions bytes differ");
        return static_cast<jint>(actual);
    } catch (const std::exception& error) { zhiyu::qwen_jni::throw_java(env,error); return -1; }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_zhiyu_app_data_local_qwen_QwenTextNative_runtimeIdentity(
    JNIEnv* env,jclass) { return zhiyu::qwen_jni::identity(env); }
