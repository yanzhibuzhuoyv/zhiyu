#include "jni_utils.hpp"
#include "qwen_edge_latent_checkpoint.hpp"
#include <mutex>
#include <sstream>

int zhiyu_qwen_sample_stage(const std::vector<std::string>& arguments);
int zhiyu_qwen_decode_stage(const std::vector<std::string>& arguments);
namespace { std::mutex image_operation_mutex; }

extern "C" JNIEXPORT jint JNICALL Java_com_zhiyu_app_data_local_qwen_QwenImageNative_sample(
    JNIEnv* env,jclass,jstring dit,jstring vae,jstring conditions,jint tokens,jlong seed,jstring unused_png,jstring checkpoint) {
    try {
        if (tokens<1 || tokens>498 || seed<0) throw std::invalid_argument("Invalid tokens or seed");
        const std::vector<std::string> arguments={"qwen-jni-sample",zhiyu::qwen_jni::path_string(env,dit),
            zhiyu::qwen_jni::path_string(env,vae),zhiyu::qwen_jni::path_string(env,conditions),std::to_string(tokens),
            std::to_string(seed),zhiyu::qwen_jni::path_string(env,unused_png),"--sample-only",
            zhiyu::qwen_jni::path_string(env,checkpoint)};
        std::lock_guard<std::mutex> lock(image_operation_mutex);
        return zhiyu_qwen_sample_stage(arguments);
    } catch (const std::exception& error) { zhiyu::qwen_jni::throw_java(env,error); return -1; }
}
extern "C" JNIEXPORT jint JNICALL Java_com_zhiyu_app_data_local_qwen_QwenImageNative_decode(
    JNIEnv* env,jclass,jstring vae,jstring checkpoint,jstring png) {
    try {
        const std::vector<std::string> arguments={"qwen-jni-decode",zhiyu::qwen_jni::path_string(env,vae),"full",
            zhiyu::qwen_jni::path_string(env,checkpoint),zhiyu::qwen_jni::path_string(env,png)};
        std::lock_guard<std::mutex> lock(image_operation_mutex);
        return zhiyu_qwen_decode_stage(arguments);
    } catch (const std::exception& error) { zhiyu::qwen_jni::throw_java(env,error); return -1; }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_zhiyu_app_data_local_qwen_QwenImageNative_inspectCP(
    JNIEnv* env,jclass,jstring checkpoint) {
    try {
        const auto record=zhiyu::qwen_edge::checkpoint::read_checkpoint(zhiyu::qwen_jni::path_string(env,checkpoint));
        std::ostringstream json;
        json<<"{\"version\":1,\"model\":\"QwenImage2.1\",\"stage\":\"diffusion_normalized\","
            <<"\"shape\":[32,32,64,1],\"steps\":8,\"seed\":"<<record.seed
            <<",\"crc32\":"<<record.checksum<<",\"bytes\":"<<zhiyu::qwen_edge::checkpoint::file_bytes
            <<",\"crc_verified\":true,\"finite\":true,\"model_loaded\":false}";
        return env->NewStringUTF(json.str().c_str());
    } catch (const std::exception& error) { zhiyu::qwen_jni::throw_java(env,error); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_zhiyu_app_data_local_qwen_QwenImageNative_runtimeIdentity(
    JNIEnv* env,jclass) { return zhiyu::qwen_jni::identity(env); }
