# Qwen 学生版：Redmi Turbo 3 Vulkan 兼容研究

记录日期：2026-10-12（北京时间）。研究设备为 Redmi Turbo 3、12 GB RAM，ADB serial `6659de32`。本记录只覆盖小张量探针、锁定源码和静态 SPIR-V 检查。

**当前结论：保留 CPU 生图路线，Vulkan 尚不可进入完整 Qwen 产图或 App。** Adreno 735 的独立 masked flash attention 已实际计算且通过数值比较；Q4_K 矩阵乘法在默认、关闭 integer dot、再关闭 F16 三种配置均在创建 compute pipeline 时失败。识别 GPU、attention 成功和静态 shader 验证通过，均不能代替完整模型验证。目前没有手机 GPU 生图成功、加速倍数或 GPU 内存预算充足的证据。

## 1. 来源与可复现构建

研究源码位于 `artifacts/qwen-vulkan-study/ggml-src`，GGML 固定提交为 `d25b121ce1d6ae0c7e62ad080460d9fc1e46dd93`。独立 clone 避免修改 `native/qwen-edge` 的生产候选源和已有 CPU 测试结果。源码 commit 由研究 `CMakeLists.txt` 在配置时校验。

公开头文件的下载来源、文件字节数和 SHA-256 完整记录在 `artifacts/qwen-vulkan-study/headers-lock.json`：

| 官方仓库 | 固定提交 | 归档 SHA-256（先前下载记录） |
| --- | --- | --- |
| KhronosGroup/Vulkan-Headers | `8864cdc896bbc2a9b6eb36b3218fc9ef57908d77` | `35824117f649befc664a12622bc801eddbba036ac03d906db400ee1c1e7903e9` |
| KhronosGroup/SPIRV-Headers | `ad9184e76a66b1001c29db9b0a3e87f646c64de0` | `b5b7eba62453eb8c6f6a5fbf7155b71cde693bafe9cd5f03b79ed8c714816afe` |

2026-10-12 对已存在的 133 个头文件/许可证重新核验：缺失 0、长度或 SHA-256 不符 0。此次没有重新下载头文件或模型权重；归档 SHA 是锁定的历史下载记录，文件级核验才是此次操作。`headers-lock.json` 当前 SHA 为 `e1bb5a4ef73ab8ee8dd8700b0ef7d6608458241b2b7e6a276aa65badf3e1bbb1`。

GGML clone 唯一源码修改位于 `src/ggml-vulkan/vulkan-shaders/vulkan-shaders-gen.cpp:343`：将 shader 编译器的内部并发上限从 `16u` 改成 `2u`。外层 `--parallel 2` 不能约束该内部异步编译，所以研究构建也限制内部并发。这一修改没有变更 shader 算法、特性选择、量化计算或运行时调度。

```diff
- uint32_t N = std::max(1u, std::min(16u, std::thread::hardware_concurrency()));
+ uint32_t N = std::max(1u, std::min(2u, std::thread::hardware_concurrency()));
```

原始文件 SHA 为 `a25c7567e8208f1264a2c5e55cc763ff4a15c521e7f8a7d6005bdc862187d28f`，修改后为 `cf712a13831f46377009627f42596a06fa2216418e222c7fdb17645e9afaebb0`。完整差异、源码与 shader SHA 已保存到 `artifacts/qwen-vulkan-static-audit-r1.json`。

构建工具为 Android NDK `30.0.16248370`、CMake `3.31.6`、Ninja 和 MSYS2 UCRT64 `glslc.exe`。以下历史路径必须区分：

| 尝试 | 结果与证据 |
| --- | --- |
| `study/build-arm64-vulkan`、API 26 | 链接失败：`vkGetPhysicalDeviceFeatures2` undefined。`arm64-vulkan-build-r2.log` 保留 RED；该目录缓存仍指向 API 26 loader stub。 |
| `artifacts/qwen-vk-a`、target API 28、仍缓存 API 26 loader stub | 仍链接失败。`arm64-vulkan-build-r3-api28.log` 明确显示 target 28 却使用 `/26/libvulkan.so`。 |
| `artifacts/qwen-vk-a`、API 28、明确 `/28/libvulkan.so` | 成功链接；`arm64-vulkan-config-r5-api28.log`、`arm64-vulkan-build-r5-api28.log` 和 ELF/imports 记录为证。 |

最终研究二进制为 `artifacts/qwen-vk-a/qwen_tiny_probe`，57,739,832 字节，SHA-256 `a03e969fb1214612b715f1ba6dc682cacc63880d777bf7a8ff7bebae8df8ebeb`。这是 API 28 的研究可执行文件；App 的 min SDK 26 没有更改。只读 capability probe 的 API 26 动态查找入口成功，也不能证明使用直接符号链接的 GGML Vulkan runtime 在 API 26 可用。

已有源码/头文件完整时，下面的 PowerShell 命令可在全新研究目录复现构建；不要复用 API 26 RED 目录或覆盖其日志。本次审计没有再次执行大编译。

```powershell
$taskRoot = 'C:/Users/32202/Desktop/99999/zhiyu'
$taskSdk = 'C:/Users/32202/AppData/Local/Android/Sdk'
$taskCmake = "$taskSdk/cmake/3.31.6/bin/cmake.exe"
$taskBuild = "$taskRoot/artifacts/qwen-vk-repro-api28"
$env:PATH = 'C:/msys64/ucrt64/bin;' + $env:PATH
& $taskCmake -S "$taskRoot/artifacts/qwen-vulkan-study" -B $taskBuild -G Ninja `
  "-DCMAKE_TOOLCHAIN_FILE=$taskSdk/ndk/30.0.16248370/build/cmake/android.toolchain.cmake" `
  '-DANDROID_ABI=arm64-v8a' '-DANDROID_PLATFORM=android-28' '-DANDROID_STL=c++_static' `
  '-DCMAKE_BUILD_TYPE=Release' '-DQWEN_PROBE_VULKAN=ON' `
  '-DCMAKE_MAKE_PROGRAM=C:/Users/32202/AppData/Local/Programs/Python/Python313/Scripts/ninja.exe' `
  '-DVulkan_GLSLC_EXECUTABLE=C:/msys64/ucrt64/bin/glslc.exe'
& $taskCmake --build $taskBuild --target qwen_tiny_probe --parallel 2
```

## 2. 四次手机 GPU 实算

探针明确选择 CPU 或 Vulkan，并拒绝无 Vulkan 构建、未知 case、后端不支持、错误数值和计算失败。Vulkan 失败没有静默改用 CPU。每次 `--vulkan` 的 CPU 行是对照图计算，不是成功的 GPU 输出。

Q4_K 用 `256×16` 权重与 F32 输入，输出 256 个值，张量 buffer 19,712 字节；masked attention 用 F32 Q、F16 K/V、head dimension 128、16 tokens、2 heads，输出 4,096 个值，张量 buffer 51,200 字节。单个测试张量 buffer 被限制为 1 MiB；这不是对驱动内部 shader 编译内存的上限。它们均不加载模型或用户数据。

| GPU 实算配置 | 实际结果 | 数值/失败位置 | 原始文件 |
| --- | --- | --- | --- |
| 默认 `--vulkan --case all` | exit 1；先在 matmul 失败，未进入 attention | `matmul_q4_k_q8_1_0`，`createComputePipeline: ErrorUnknown` | `artifacts/qwen-vulkan-phone-tiny-r1.json`、`qwen-vulkan-phone-tiny-r1-vulkan.log` |
| 默认 `--vulkan --case attention` | exit 0；显式 Vulkan 算完 | GPU vs CPU max abs `0.000268642`、RMSE `0.0000213475`；GPU vs naive max abs `0.000261333`；阈值 `0.01` | `artifacts/qwen-vulkan-phone-attention-r1.log` |
| `GGML_VK_DISABLE_INTEGER_DOT_PRODUCT=1`，matmul | exit 1；日志确认 int dot 0、fp16 1 | `matmul_quant_f32_aligned_0`，同一 ErrorUnknown | `artifacts/qwen-vulkan-phone-matmul-no-intdot-r1.log` |
| 再加 `GGML_VK_DISABLE_F16=1`，matmul | exit 1；日志确认 int dot 0、fp16 0 | `matmul_quant_f32_aligned_0`，同一 ErrorUnknown | `artifacts/qwen-vulkan-phone-matmul-no-intdot-fp32-r1.log` |

后三次 exit 来自 root 实际执行后的 ADB shell wait / PowerShell `$LASTEXITCODE`；它们没有独立的 guardian/native 双回执。前一次 JSON 也只记录外层 shell exit，不把这些数据提升为生图阶段守护器的退出证明。

attention 首次 GPU `forward_ms=371.347`，CPU 对照 `0.957709`。计时包含该小图首次图计算/管线准备等成本，不能据此推出完整 Qwen 推理耗时或 GPU 加速收益。默认 matmul 的 0.336 秒外层耗时止于失败，不能作为成功推理时间。

已部署探针的精确命令如下，仅用于授权后的独立小图复测。现有目录为研究资产，复测结果应另存新日志，不能覆盖 RED 或用户 App 文件。命令有 90 秒 deadline 和 5 秒 kill 宽限；这一 `timeout` 不等于生图阶段专用的 thermal/memory guardian。

```sh
setsid -w timeout -k 5 90 /data/local/tmp/zhiyu_qwen_research_20261011_vk_tiny_r1/qwen_tiny_probe --vulkan --case all
setsid -w timeout -k 5 90 /data/local/tmp/zhiyu_qwen_research_20261011_vk_tiny_r1/qwen_tiny_probe --vulkan --case attention
GGML_VK_DISABLE_INTEGER_DOT_PRODUCT=1 setsid -w timeout -k 5 90 /data/local/tmp/zhiyu_qwen_research_20261011_vk_tiny_r1/qwen_tiny_probe --vulkan --case matmul
GGML_VK_DISABLE_INTEGER_DOT_PRODUCT=1 GGML_VK_DISABLE_F16=1 setsid -w timeout -k 5 90 /data/local/tmp/zhiyu_qwen_research_20261011_vk_tiny_r1/qwen_tiny_probe --vulkan --case matmul
```

手机 CPU 基线 `--cpu --case all` exit 0：Q4_K vs naive max abs `0.000678778`；attention vs naive `0.0000421181`。2026-10-12 主机 CPU CTest 又通过 5/5，用例包括两种数值对照以及拒绝缺少 backend、未知 case、无 Vulkan 构建请求 Vulkan。新日志为 `artifacts/qwen-vulkan-static-host-ctest-r1.log`，0.21 秒。

## 3. 设备能力与静态 shader 差异

`artifacts/qwen-vulkan-device-capability-r3.log`、`qwen-vulkan-device-capability-r4.log` 是 root 重新连接设备后实际执行的只读能力查询，设备为 Adreno(TM) 735，Vulkan `1.3.128`，vendor `5143`，device ID `43030b00`。r4 补查了 8-bit storage / integer dot；查询本身没有模型或 GPU 图计算。

| 能力 | 查询结果与边界 |
| --- | --- |
| `storageBuffer16BitAccess`、`shaderFloat16`、`shaderInt8` | 全部 1 |
| `shaderInt16`、`shaderInt64` | 全部 1；因此不能把缺少 shaderInt16 当成已知问题 |
| `storageBuffer8BitAccess`、`shaderIntegerDotProduct` | r4 全部 1；因此不能把缺少这两项当成量化失败根因 |
| `uniformAndStorageBuffer8BitAccess`、`storagePushConstant8` | r4 全部 0；被检验的五个 SPIR-V 没有声明对应 capability |
| compute subgroup | size 64；shuffle、vote、ballot、arithmetic 为 1；clustered 为 0 |
| workgroup 上限 | invocation 1,024；各轴上限 1,024；共享内存 32,768 字节 |
| `maxStorageBufferRange` | 134,217,728 字节，即 128 MiB；完整模型张量能否分配/分块仍需单独验证 |
| memory heaps | heap 0 = 11,819,589,632 字节；heap 1 = 4,294,963,200 字节；共享/UMA 的报告值不能相加当成可用显存 |
| 扩展 | 已报告 float16/int8、16bit_storage、integer_dot_product；能力日志仍未提供运行时可用显存预算 |

对此次 API 28 构建中的 5 个实际 `.spv` 用 `spirv-dis` 读取，并以 `spirv-val --target-env vulkan1.2` 检查，5/5 返回 0。它们均为 SPIR-V 1.5；量化四个产物分别为 23,408、23,304、46,192、44,968 字节，attention 候选为 82,408 字节。完整 capability、扩展、长度、SHA、validator 输出记录在 `artifacts/qwen-vulkan-static-audit-r1.json`。

静态检查和主机 CPU 复测可独立复现，不需要手机、权重或网络：

```powershell
$taskRoot = 'C:/Users/32202/Desktop/99999/zhiyu'
$taskSpv = "$taskRoot/artifacts/qwen-vk-a/ggml-build/src/ggml-vulkan/vulkan-shaders.spv"
$taskShaders = @('matmul_q4_k_q8_1.spv', 'matmul_q4_k_q8_1_fp32.spv',
  'matmul_quant_f32.spv', 'matmul_quant_f32_fp32.spv', 'flash_attn_f32_f16.spv')
$env:PATH = 'C:/msys64/ucrt64/bin;' + $env:PATH
foreach ($taskShader in $taskShaders) {
  & 'C:/msys64/ucrt64/bin/spirv-dis.exe' "$taskSpv/$taskShader" |
    Select-String 'OpCapability|OpExtension|OpExecutionMode|Version:'
  & 'C:/msys64/ucrt64/bin/spirv-val.exe' --target-env vulkan1.2 "$taskSpv/$taskShader"
  if ($LASTEXITCODE -ne 0) { throw "SPIR-V validation failed: $taskShader" }
}
& 'C:/Users/32202/AppData/Local/Android/Sdk/cmake/3.31.6/bin/ctest.exe' `
  --test-dir "$taskRoot/artifacts/qwen-vulkan-study/build-host-cpu" --output-on-failure -V
```

| 产物 | 关键 `OpCapability`（省略 Shader） |
| --- | --- |
| `matmul_q4_k_q8_1.spv` | Float16、Int8、StorageBuffer16BitAccess、StorageBuffer8BitAccess、DotProductInput4x8BitPacked、DotProduct |
| `matmul_q4_k_q8_1_fp32.spv` | 同上，但没有 Float16 |
| `matmul_quant_f32.spv` | Float16、Int8、StorageBuffer16BitAccess、StorageBuffer8BitAccess；没有 dot |
| `matmul_quant_f32_fp32.spv` | Int8、StorageBuffer16BitAccess、StorageBuffer8BitAccess；没有 Float16/dot |
| `flash_attn_f32_f16.spv` | Float16、Int8、StorageBuffer16BitAccess、GroupNonUniform/Vote/Shuffle；没有 StorageBuffer8BitAccess |

这些量化产物没有 `Int16`、clustered subgroup capability；r3/r4 的 clustered 0 不足以解释此组失败。关闭 F16 并没有去掉 Int8 与 StorageBuffer8BitAccess。静态审计曾发现失败量化产物均有 8-bit buffer storage capability，而成功 attention 的候选没有，于是 root 执行 r4 补查：`storageBuffer8BitAccess=1`、`shaderIntegerDotProduct=1`，排除了这两项物理设备特性缺失的解释。r4 为 0 的 uniform/storage 8-bit、push-constant 8-bit feature，不是这五个产物声明的 capability。日志尚未记录实际运行所选 attention SPIR-V 的 SHA/全部 specialization，不能把候选产物直接等同于完整 runtime 捕获。

Khronos 规范分别映射 Int8 与 8-bit buffer storage 到不同 feature；shader capability 需要实际在 device 上支持并启用。扩展字符串、物理设备 API 版本或 glslc 能编译，不可代替全部 feature/启用链核验。固定 GGML 源码查询 `VkPhysicalDeviceFeatures2` 后使用其 feature 链创建 device，下一步仍应核验实际查询和启用结果，而不是仅凭头文件推定。依据：[Vulkan 的 SPIR-V capability 要求](https://docs.vulkan.org/spec/latest/appendices/spirvenv.html)。

独立静态校验也没有覆盖 device specialization 后的共享内存/工作组预算。按固定源码的小 tile 公式计算，Q4_K intdot FP16 为 7,168 字节、intdot FP32 为 8,192 字节；generic quant FP16 为 4,224 字节、FP32 为 8,448 字节，均小于 32 KiB。这可反对“显然因小 tile 共享内存超额”的猜测，不能排除其他 specialization、寄存器、驱动内部资源或输入组合问题。

## 4. 已排除的捷径与下一步

`ErrorUnknown` 不等于 OOM、驱动编译器缺陷或特定硬件不支持所有 Vulkan 运算；官方定义允许应用输入问题或实现失败，建议先检查 validation，再判断实现问题。此次没有 validation-layer capture、driver fault dump 或实际所选 shader/specialization 完整捕获，因此根因仍待确定。依据：[Khronos VkResult](https://docs.vulkan.org/refpages/latest/refpages/source/VkResult.html)。

当前在此结束 GPU 实验，继续已经启动的 CPU 采样/独立解码路线。若以后恢复 GPU 研究，需要保存实际 shader、pipeline specialization 和启用链，完成失败量化最小图的 validation 和数值核验；然后才考虑固定模型图的 tensor range、分配预算、thermal/memory guard、取消和真实端到端手机产图。此前不加入完整 GPU runtime、不开启 GPU 产品选项，也不通过 silent CPU fallback 掩盖量化失败。

本次审计仅新增此文档与两份静态/CPU 研究记录；没有 ADB 操作、模型运行、模型/权重下载、付费 API、Key/聊天读取、生产 native 编辑、Git 提交或交接文件变更。
