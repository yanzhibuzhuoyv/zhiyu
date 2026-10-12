#include "qwen_edge_latent_checkpoint.hpp"
#include "qwen_edge_latent_boundary.hpp"
#include <chrono>
#include <functional>
#include <fstream>
#include <iostream>
#include <limits>

namespace {
using namespace zhiyu::qwen_edge::checkpoint;
void check(bool value, const char* text) { if (!value) { throw std::runtime_error(text); } }
template<class F> void rejects(F action) {
    try { action(); } catch (const std::exception&) { return; }
    throw std::runtime_error("Expected rejection");
}
struct fixture {
    std::filesystem::path directory = std::filesystem::temp_directory_path() /
        ("zhiyu-checkpoint-contract-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    std::filesystem::path file = directory / "new.latent";
    std::vector<float> values = std::vector<float>(32*32*64, .25F);
    fixture() { std::filesystem::create_directory(directory); }
    ~fixture() {
        // Only these explicitly created files, no recursive deletion.
        for (const auto& name : {"new.latent", "input", "other", "link"}) {
            std::error_code error; std::filesystem::remove(directory/name, error);
        }
        std::error_code error; std::filesystem::remove(directory, error);
    }
    qwen_edge_latent_view_t view() const {
        return {values.data(), values.size(), 32, 32, 64, 1, 210, 1, 8, 42};
    }
    void write() { checkpoint_writer output(file, 42); output.write(view()); }
};
std::vector<unsigned char> bytes(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}
void replace(const std::filesystem::path& path, const std::vector<unsigned char>& value) {
    std::ofstream output(path, std::ios::binary|std::ios::trunc);
    output.write(reinterpret_cast<const char*>(value.data()), static_cast<std::streamsize>(value.size()));
}
bool capture(const qwen_edge_latent_view_t* view, void* data) {
    ++*static_cast<int*>(data);
    return view->stage == QWEN_EDGE_DIFFUSION_NORMALIZED;
}
bool reject_callback(const qwen_edge_latent_view_t*, void*) { return false; }
const std::vector<std::pair<std::string, std::function<void()>>> tests{
    {"finite_f32_roundtrip_seed_geometry_stage", [] {
        fixture f; f.values.front()=1.F; f.values.back()=-1.125F; f.write();
        const auto result=read_checkpoint(f.file);
        check(result.seed==42 && result.values==f.values, "Lossy F32 checkpoint");
        check(std::filesystem::file_size(f.file)==file_bytes, "Wrong checkpoint size");
    }},
    {"f32_is_little_endian_not_host_dependent", [] {
        fixture f; f.values[0]=1.F; f.write(); const auto data=bytes(f.file);
        check(data[header_bytes]==0 && data[header_bytes+1]==0 && data[header_bytes+2]==128 && data[header_bytes+3]==63, "Wrong F32 encoding");
    }},
    {"crc32_IEEE_known_vector", [] {
        const unsigned char text[]="123456789";
        check(crc32(text,9)==0xcbf43926U,"Wrong standard CRC32");
    }},
    {"payload_corruption_is_rejected", [] {
        fixture f; f.write(); auto data=bytes(f.file); data[header_bytes+1]^=1; replace(f.file,data);
        rejects([&]{read_checkpoint(f.file);});
    }},
    {"nonfinite_payload_with_valid_CRC_is_rejected", [] {
        fixture f; f.write(); auto data=bytes(f.file);
        put32(data,header_bytes,0x7fc00000U);
        put32(data,header_bytes+payload_bytes+8,crc32(data.data(),header_bytes+payload_bytes));
        replace(f.file,data); rejects([&]{read_checkpoint(f.file);});
    }},
    {"completion_footer_and_unknown_extension_are_rejected", [] {
        fixture f; f.write(); const auto original=bytes(f.file);
        auto data=original; data[header_bytes+payload_bytes]^=1;
        replace(f.file,data); rejects([&]{read_checkpoint(f.file);});
        data=original; data[127]=1;
        put32(data,header_bytes+payload_bytes+8,crc32(data.data(),header_bytes+payload_bytes));
        replace(f.file,data); rejects([&]{read_checkpoint(f.file);});
    }},
    {"truncated_partial_footer_and_trailing_bytes_rejected", [] {
        fixture f; f.write(); const auto original=bytes(f.file);
        for (const auto size : {std::size_t(0), header_bytes, file_bytes-1, file_bytes+1}) {
            auto changed=original; changed.resize(size); replace(f.file,changed);
            rejects([&]{read_checkpoint(f.file);});
        }
    }},
    {"wrong_version_model_shape_stage_and_steps_rejected", [] {
        fixture f; f.write(); const auto original=bytes(f.file);
        for (const auto offset : {8U,16U,20U,32U,40U,64U}) {
            auto changed=original; changed[offset]^=1; replace(f.file,changed);
            rejects([&]{read_checkpoint(f.file);});
        }
    }},
    {"writer_rejects_nonfinite_before_completion", [] {
        fixture f; f.values[10]=std::numeric_limits<float>::infinity();
        checkpoint_writer output(f.file,42); rejects([&]{output.write(f.view());});
        check(!output.complete(),"Invalid checkpoint marked complete");
    }},
    {"writer_geometry_null_seed_stage_and_reentry_rejected", [] {
        fixture f; checkpoint_writer output(f.file,42); auto view=f.view();
        view.channels=16; rejects([&]{output.write(view);}); view=f.view();
        view.stage=2; rejects([&]{output.write(view);}); view=f.view();
        view.seed=43; rejects([&]{output.write(view);}); view=f.view();
        view.values=nullptr; rejects([&]{output.write(view);});
        output.write(f.view()); rejects([&]{output.write(f.view());});
    }},
    {"existing_file_is_never_truncated", [] {
        fixture f; f.write(); const auto original=bytes(f.file);
        rejects([&]{checkpoint_writer duplicate(f.file,42);});
        check(bytes(f.file)==original,"Owned checkpoint changed");
    }},
    {"source_output_collision_and_relative_paths_rejected", [] {
        fixture f; std::ofstream(f.directory/"input")<<"source";
        rejects([&]{checkpoint_writer output(f.directory/"input",42,{f.directory/"input"});});
        rejects([&]{checkpoint_writer output("relative.latent",42);});
        check(bytes(f.directory/"input")==std::vector<unsigned char>({'s','o','u','r','c','e'}),"Source changed");
    }},
    {"output_cannot_enter_a_protected_input_directory", [] {
        fixture f; std::ofstream(f.directory/"input")<<"source";
        rejects([&]{checkpoint_writer output(f.file,42,{f.directory/"input"});});
        check(!std::filesystem::exists(f.file),"Protected source directory was written");
    }},
    {"empty_and_dangling_symlink_input_rejected", [] {
        fixture f; std::ofstream(f.directory/"input"); rejects([&]{read_checkpoint(f.directory/"input");});
        std::error_code error; std::filesystem::create_symlink(f.directory/"absent",f.directory/"link",error);
        if (!error) { rejects([&]{checkpoint_writer output(f.directory/"link",42);}); }
    }},
    {"boundary_copies_no_borrowed_data_after_callback", [] {
        fixture f; int called=0; qwen_edge_latent_checkpoint_t request{capture,&called,true};
        dispatch_checkpoint(request,f.view(),false); check(called==1,"Callback missing");
    }},
    {"cancelled_or_invalid_boundary_never_calls_callback", [] {
        fixture f; int called=0; qwen_edge_latent_checkpoint_t request{capture,&called,true};
        rejects([&]{dispatch_checkpoint(request,f.view(),true);});
        auto view=f.view(); view.sampling_steps=7; rejects([&]{dispatch_checkpoint(request,view,false);});
        view=f.view(); view.values=nullptr; rejects([&]{dispatch_checkpoint(request,view,false);});
        check(called==0,"Invalid boundary invoked callback");
    }},
    {"missing_or_failed_callback_is_generation_failure", [] {
        fixture f; qwen_edge_latent_checkpoint_t missing{nullptr,nullptr,true};
        rejects([&]{dispatch_checkpoint(missing,f.view(),false);});
        qwen_edge_latent_checkpoint_t failed{reject_callback,nullptr,true};
        rejects([&]{dispatch_checkpoint(failed,f.view(),false);});
    }},
    {"partial_or_other_progress_cannot_commit", [] {
        require_eight_progress(8,8);
        for (const auto step : {0,1,7,9}) { rejects([&]{require_eight_progress(step,8);}); }
        rejects([]{require_eight_progress(8,9);});
    }},
};
}
int main(int argc, char** argv) {
    if (argc==3 && std::string(argv[1])=="--write-fixture") {
        const std::vector<float> values(value_count,.25F);
        checkpoint_writer output(argv[2],42);
        output.write({values.data(),values.size(),32,32,64,1,210,1,8,42});
        std::cout << "SYNTHETIC_CHECKPOINT_FIXTURE NOT_MODEL NOT_SAMPLING\n";
        return 0;
    }
    int failed=0;
    for (const auto& test:tests) {
        try { test.second(); std::cout<<"PASS "<<test.first<<'\n'; }
        catch (const std::exception& error) { ++failed; std::cout<<"FAIL "<<test.first<<": "<<error.what()<<'\n'; }
    }
    std::cout<<"tests="<<tests.size()<<" failures="<<failed<<'\n';
    return failed ? 1 : 0;
}
