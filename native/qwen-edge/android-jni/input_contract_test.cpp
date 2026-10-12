#include "input_contract.hpp"
#include <iostream>
#include <stdexcept>
#include <functional>

int main() {
    int checks=0;
    const auto require=[&](bool accepted) { if (!accepted) throw std::runtime_error("contract failed"); ++checks; };
    const auto reject=[&](const std::function<void()>& action) {
        try { action(); } catch (const std::invalid_argument&) { ++checks; return; }
        throw std::runtime_error("invalid external input was accepted");
    };
    using zhiyu::qwen_jni::validated_utf8;
    require(validated_utf8("garden",16000)=="garden");
    require(validated_utf8("\xe5\xad\x97\xf0\x9f\x99\x82",16000).size()==7);
    reject([&]{validated_utf8("",16000);});
    reject([&]{validated_utf8(std::string("a\0b",3),16000);});
    reject([&]{validated_utf8("\xc0\xaf",16000);});
    reject([&]{validated_utf8("\xed\xa0\x80",16000);});
    reject([&]{validated_utf8("\xf4\x90\x80\x80",16000);});
    reject([&]{validated_utf8("\xe5\xad",16000);});
    reject([&]{validated_utf8("\x80",16000);});
    reject([&]{validated_utf8("abc",2);});
    std::cout<<"QWEN_JNI_INPUT_CONTRACT_CHECKS "<<checks<<" NO_MODEL true\n";
}
