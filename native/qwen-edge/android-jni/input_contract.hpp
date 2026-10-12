#pragma once
#include <cstdint>
#include <stdexcept>
#include <string>

namespace zhiyu::qwen_jni {
inline std::string validated_utf8(const std::string& value, std::size_t maximum) {
    if (value.empty() || value.size()>maximum || value.find('\0')!=std::string::npos) {
        throw std::invalid_argument("Expected bounded nonempty UTF-8 without NUL");
    }
    for (std::size_t at=0; at<value.size();) {
        const auto first=static_cast<unsigned char>(value[at++]);
        if (first<0x80) continue;
        std::uint32_t scalar=0, minimum=0; std::size_t following=0;
        if (first>=0xc2 && first<=0xdf) { scalar=first&0x1f; minimum=0x80; following=1; }
        else if (first>=0xe0 && first<=0xef) { scalar=first&0x0f; minimum=0x800; following=2; }
        else if (first>=0xf0 && first<=0xf4) { scalar=first&0x07; minimum=0x10000; following=3; }
        else throw std::invalid_argument("Malformed UTF-8 lead byte");
        if (following>value.size()-at) throw std::invalid_argument("Truncated UTF-8");
        for (std::size_t n=0; n<following; ++n) {
            const auto next=static_cast<unsigned char>(value[at++]);
            if ((next&0xc0)!=0x80) throw std::invalid_argument("Malformed UTF-8 continuation");
            scalar=(scalar<<6)|(next&0x3f);
        }
        if (scalar<minimum || scalar>0x10ffff || (scalar>=0xd800 && scalar<=0xdfff)) {
            throw std::invalid_argument("Invalid UTF-8 scalar");
        }
    }
    return value;
}
}
