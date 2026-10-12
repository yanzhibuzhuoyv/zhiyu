#pragma once
#include "qwen_edge_latent_boundary.hpp"
#include <algorithm>
#include <array>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <string>
#include <vector>
#ifdef _WIN32
#include <io.h>
#else
#include <unistd.h>
#endif

namespace zhiyu::qwen_edge::checkpoint {
constexpr std::size_t header_bytes=128, value_count=32*32*64, footer_bytes=16;
constexpr std::size_t payload_bytes=value_count*4, file_bytes=header_bytes+payload_bytes+footer_bytes;
static_assert(sizeof(float)==4 && std::numeric_limits<float>::is_iec559, "IEEE754 F32 required");
inline void put32(std::vector<unsigned char>& bytes, std::size_t at, std::uint32_t value) {
    for (std::size_t i=0; i<4; ++i) { bytes[at+i]=static_cast<unsigned char>(value>>(i*8)); }
}
inline std::uint32_t get32(const std::vector<unsigned char>& bytes, std::size_t at) {
    std::uint32_t value=0;
    for (std::size_t i=0; i<4; ++i) { value|=std::uint32_t(bytes[at+i])<<(i*8); }
    return value;
}
inline void put64(std::vector<unsigned char>& bytes, std::size_t at, std::uint64_t value) {
    put32(bytes,at,static_cast<std::uint32_t>(value)); put32(bytes,at+4,static_cast<std::uint32_t>(value>>32));
}
inline std::uint64_t get64(const std::vector<unsigned char>& bytes, std::size_t at) {
    return std::uint64_t(get32(bytes,at)) | (std::uint64_t(get32(bytes,at+4))<<32);
}
inline std::uint32_t crc32(const unsigned char* data, std::size_t count) {
    std::uint32_t crc=0xffffffffU;
    for (std::size_t i=0; i<count; ++i) {
        crc^=data[i];
        for (int bit=0; bit<8; ++bit) { crc=(crc>>1) ^ ((crc&1U) ? 0xedb88320U : 0U); }
    }
    return crc^0xffffffffU;
}
inline bool is_original_model_path(const std::filesystem::path& path) {
    for (const auto& part:path) {
        auto value=part.string();
        std::transform(value.begin(),value.end(),value.begin(),[](unsigned char c){return std::tolower(c);});
        if (value=="qwen-image-edge") { return true; }
    }
    return false;
}
inline std::filesystem::path physical_parent(const std::filesystem::path& path) {
    if (!path.is_absolute() || path.filename().empty()) { throw std::invalid_argument("Absolute file path required"); }
    for (const auto& part:path) {
        if (part=="." || part=="..") { throw std::invalid_argument("Traversal path rejected"); }
    }
    const auto parent=std::filesystem::canonical(path.parent_path());
    if (parent!=path.parent_path()) { throw std::invalid_argument("Non-physical parent path rejected"); }
    return parent;
}
inline void validate_source_path(const std::filesystem::path& path) {
    physical_parent(path);
    const auto status=std::filesystem::symlink_status(path);
    if (std::filesystem::is_symlink(status) || !std::filesystem::is_regular_file(status)) {
        throw std::invalid_argument("Source must be a regular file without symlinks");
    }
}
inline bool inside_directory(std::filesystem::path directory, const std::filesystem::path& root) {
    while (!directory.empty()) {
        if (std::filesystem::equivalent(directory,root)) { return true; }
        const auto parent=directory.parent_path();
        if (parent==directory) { break; }
        directory=parent;
    }
    return false;
}
inline void validate_output_path(const std::filesystem::path& path,
                                 const std::vector<std::filesystem::path>& protected_paths={}) {
    const auto parent=physical_parent(path);
    if (is_original_model_path(parent)) { throw std::invalid_argument("Cannot write original model directory"); }
    const auto status=std::filesystem::symlink_status(path);
    if (std::filesystem::is_symlink(status) || std::filesystem::exists(status)) {
        throw std::invalid_argument("Output already exists or is a symlink");
    }
    for (const auto& source:protected_paths) {
        validate_source_path(source);
        if (path==source || inside_directory(parent,source.parent_path())) {
            throw std::invalid_argument("Output overlaps a protected source directory");
        }
    }
}
inline std::vector<unsigned char> serialize(const qwen_edge_latent_view_t& view) {
    validate_view(view);
    std::vector<unsigned char> bytes(file_bytes,0);
    std::memcpy(bytes.data(),"ZHQWLAT1",8);
    const std::array<std::uint32_t,10> fields{1,header_bytes,view.model_version,view.stage,1,1,32,32,64,1};
    for (std::size_t i=0; i<fields.size(); ++i) { put32(bytes,8+i*4,fields[i]); }
    put64(bytes,48,value_count); put64(bytes,56,static_cast<std::uint64_t>(view.seed)); put32(bytes,64,8);
    for (std::size_t i=0; i<view.value_count; ++i) {
        std::uint32_t bits=0; std::memcpy(&bits,view.values+i,4); put32(bytes,header_bytes+i*4,bits);
    }
    const auto footer=header_bytes+payload_bytes;
    std::memcpy(bytes.data()+footer,"ZHQWEND1",8);
    put32(bytes,footer+8,crc32(bytes.data(),footer));
    return bytes;
}
inline void validate_header(const std::vector<unsigned char>& bytes) {
    if (bytes.size()!=file_bytes || std::memcmp(bytes.data(),"ZHQWLAT1",8)!=0) {
        throw std::invalid_argument("Partial, oversized or invalid checkpoint");
    }
    const std::array<std::uint32_t,10> expected{1,header_bytes,210,1,1,1,32,32,64,1};
    for (std::size_t i=0; i<expected.size(); ++i) {
        if (get32(bytes,8+i*4)!=expected[i]) { throw std::invalid_argument("Checkpoint version/shape/stage differs"); }
    }
    if (get64(bytes,48)!=value_count || get64(bytes,56)>std::uint64_t(std::numeric_limits<std::int64_t>::max()) || get32(bytes,64)!=8) {
        throw std::invalid_argument("Checkpoint count/seed/steps differs");
    }
    for (std::size_t i=68; i<header_bytes; ++i) {
        if (bytes[i]!=0) { throw std::invalid_argument("Unknown checkpoint extension"); }
    }
    const auto footer=header_bytes+payload_bytes;
    if (std::memcmp(bytes.data()+footer,"ZHQWEND1",8)!=0 || get32(bytes,footer+12)!=0 ||
        get32(bytes,footer+8)!=crc32(bytes.data(),footer)) {
        throw std::invalid_argument("Checkpoint completion footer/CRC differs");
    }
}
struct checkpoint_record { std::vector<float> values; std::int64_t seed; std::uint32_t checksum; };
inline checkpoint_record read_checkpoint(const std::filesystem::path& path) {
    validate_source_path(path);
    std::ifstream input(path,std::ios::binary|std::ios::ate);
    if (!input || input.tellg()!=static_cast<std::streamoff>(file_bytes)) { throw std::invalid_argument("Partial or oversized checkpoint file"); }
    std::vector<unsigned char> bytes(file_bytes); input.seekg(0);
    input.read(reinterpret_cast<char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
    if (!input) { throw std::runtime_error("Checkpoint read failed"); }
    validate_header(bytes);
    std::vector<float> values(value_count);
    for (std::size_t i=0; i<values.size(); ++i) {
        const auto bits=get32(bytes,header_bytes+i*4); std::memcpy(values.data()+i,&bits,4);
        if (!std::isfinite(values[i])) { throw std::invalid_argument("Non-finite serialized latent"); }
    }
    return {std::move(values),static_cast<std::int64_t>(get64(bytes,56)),get32(bytes,header_bytes+payload_bytes+8)};
}
class checkpoint_writer {
public:
    checkpoint_writer(const std::filesystem::path& path, std::int64_t seed,
                      const std::vector<std::filesystem::path>& protected_paths={}) : seed_(seed) {
        if (seed<0) { throw std::invalid_argument("Invalid checkpoint seed"); }
        validate_output_path(path,protected_paths);
#ifdef _WIN32
        file_=_wfopen(path.c_str(),L"wbx");
#else
        file_=std::fopen(path.c_str(),"wbx");
#endif
        if (!file_) { throw std::runtime_error("Cannot exclusively claim checkpoint"); }
    }
    checkpoint_writer(const checkpoint_writer&)=delete;
    checkpoint_writer& operator=(const checkpoint_writer&)=delete;
    ~checkpoint_writer() { if (file_) { std::fclose(file_); } }
    bool complete() const { return complete_; }
    void write(const qwen_edge_latent_view_t& view) {
        if (!file_ || complete_ || view.seed!=seed_) { throw std::invalid_argument("Checkpoint repeated or seed differs"); }
        const auto bytes=serialize(view);
        if (std::fwrite(bytes.data(),1,bytes.size(),file_)!=bytes.size()) { throw std::runtime_error("Checkpoint partial write retained"); }
        if (std::fflush(file_)!=0) { throw std::runtime_error("Checkpoint flush failed"); }
#ifdef _WIN32
        const auto synced=_commit(_fileno(file_));
#else
        const auto synced=fsync(fileno(file_));
#endif
        const auto closed=std::fclose(file_); file_=nullptr;
        if (synced!=0 || closed!=0) { throw std::runtime_error("Checkpoint durability/close failed"); }
        complete_=true;
    }
private:
    FILE* file_{nullptr};
    std::int64_t seed_;
    bool complete_{false};
};
}  // namespace zhiyu::qwen_edge::checkpoint
