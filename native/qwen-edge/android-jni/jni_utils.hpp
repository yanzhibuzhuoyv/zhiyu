#pragma once
#include "input_contract.hpp"
#include <jni.h>
#include <memory>
#include <string>
#include <vector>

namespace zhiyu::qwen_jni {
inline void append_scalar(std::string& out, std::uint32_t scalar) {
    if (scalar<0x80) out.push_back(static_cast<char>(scalar));
    else if (scalar<0x800) {
        out.push_back(static_cast<char>(0xc0|(scalar>>6))); out.push_back(static_cast<char>(0x80|(scalar&0x3f)));
    } else if (scalar<0x10000) {
        out.push_back(static_cast<char>(0xe0|(scalar>>12))); out.push_back(static_cast<char>(0x80|((scalar>>6)&0x3f)));
        out.push_back(static_cast<char>(0x80|(scalar&0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0|(scalar>>18))); out.push_back(static_cast<char>(0x80|((scalar>>12)&0x3f)));
        out.push_back(static_cast<char>(0x80|((scalar>>6)&0x3f))); out.push_back(static_cast<char>(0x80|(scalar&0x3f)));
    }
}
inline std::string path_string(JNIEnv* env, jstring value) {
    if (!value || env->GetStringLength(value)>4096) throw std::invalid_argument("Missing or oversized path");
    const auto length=env->GetStringLength(value);
    const auto* chars=env->GetStringChars(value,nullptr);
    if (!chars) throw std::runtime_error("Cannot access Java path");
    const auto release=[&](const jchar* p){env->ReleaseStringChars(value,p);};
    std::unique_ptr<const jchar,decltype(release)> owned(chars,release);
    std::string result;
    for (jsize i=0; i<length; ++i) {
        std::uint32_t scalar=chars[i];
        if (scalar>=0xd800 && scalar<=0xdbff) {
            if (++i==length || chars[i]<0xdc00 || chars[i]>0xdfff) throw std::invalid_argument("Unpaired UTF-16 path");
            scalar=0x10000+((scalar-0xd800)<<10)+(chars[i]-0xdc00);
        } else if (scalar>=0xdc00 && scalar<=0xdfff) throw std::invalid_argument("Unpaired UTF-16 path");
        append_scalar(result,scalar);
    }
    return validated_utf8(result,4096);
}
inline std::string prompt_bytes(JNIEnv* env, jbyteArray value) {
    if (!value) throw std::invalid_argument("Missing prompt");
    const auto size=env->GetArrayLength(value);
    if (size<=0 || size>16000) throw std::invalid_argument("Prompt must contain 1..16000 UTF-8 bytes");
    std::string result(static_cast<std::size_t>(size),'\0');
    env->GetByteArrayRegion(value,0,size,reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read prompt bytes");
    return validated_utf8(result,16000);
}
inline void throw_java(JNIEnv* env, const std::exception& error) noexcept {
    if (env->ExceptionCheck()) return;
    // ThrowNew expects modified UTF-8; keep native diagnostics bounded ASCII, including filesystem errors.
    char message[512]{};
    const auto* original=error.what();
    for (std::size_t i=0; i<sizeof(message)-1 && original[i]!='\0'; ++i) {
        const auto byte=static_cast<unsigned char>(original[i]);
        message[i]=(byte>=0x20 && byte<0x7f) ? static_cast<char>(byte) : '?';
    }
    const auto type=env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type,message);
}
inline jstring identity(JNIEnv* env) { return env->NewStringUTF(ZHIYU_QWEN_RUNTIME_IDENTITY); }
}
