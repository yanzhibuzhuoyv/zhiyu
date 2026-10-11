# VAE-only 定位工具

`qwen-edge-vae-probe VAE full|tile256 zero|sine NEW_PNG`

此 research CLI 直接使用锁定 sd.cpp 内部的 `ModelLoader`、`ModelManager` 和 `WAN::WanVAERunner`，不实例化 DiT、文本编码器或云服务。明确使用 **VERSION_QWEN_IMAGE_2_1**，decode-only，64 latent channel、16 倍空间缩放、4 RGBA channel；不以 Wan 2.2 版本替代 Qwen 2.1。构建继承锁定 runtime，不改变公共 API、external-condition patch 或其 SHA。真实文件 238 个张量，其中 decoder 132 + post_quant_conv 2 = **134 个注册参数**；graph 实际使用/载入的 128 个不能替代 catalog 参数总数。

初版曾将 128 硬编码为 builder 参数数，手机 r1 在 metadata 阶段即返回 native exit 1，没有执行 decode。这是工具计数混淆，已在 r2 修正：分别输出 SOURCE_ALL / SOURCE_DECODER / BUILDER_PARAMS、DECODER_REGISTERED、DECODER_VALIDATED，缺名/注册/形状分开报错。仅检查实际 builder 要求的名称及真实 source 形状，不强加图裁剪后的数量。

输入是固定 `{32,32,64,1}` 的归一化 diffusion latent，`zero` 全零，`sine` 为幅度不超过 0.01 的确定性正弦。解码前调用原 `diffusion_to_vae_latents`，沿用真实 64 维均值/标准差。合成 latent 仅用于判断 decoder 执行情况，不能验收人物质量或一致性。

`full` 不启用空间切块；`tile256` 使用 256×256 图像像素（16×16 latent）、25% overlap、无 temporal tiling。两模式都要求完整 512×512 RGBA；不会失败后切换模式，也不会减少分辨率。切块结果可与 full 不同，不承诺逐像素等价。

PNG 在读权重之前通过 `wbx` 独占创建，不截断已有文件。失败时保留空/不完整输出用于诊断，**只有 `PNG_COMPLETE true` 加独立 PNG 完整解码验收才算成功**。每次测试选择新输出。`DECODE_BEGIN` / `DECODE_COMPLETE` 标记包含阶段耗时；`PEAK_RSS_BYTES` 为 OS 进程高水位，无法获取时明确 `unknown`。loader callback 的值称 `CALLBACK_VALUE`，不当作 VAE 秒数。

本 CLI 没有设备温度/电量自主 watchdog。手机运行必须由 root 使用身份匹配的 owned guard 包住。现有 `qwen_phone_image_smoke.py` 的 PID 匹配硬编码 `image_smoke`，**直接另名启动 probe 不能沿用原 guard 的成功计数**；应严格增加 probe 身份白名单，或在新的独占研究目录将 probe 命名 `image_smoke` 后运行 observer。USB 不可达只可记远端状态 `unconfirmed`。

## 构建及已验证范围

2026-10-11 最终版：host 与 Android arm64-v8a/android-26 都已真实编译；本代理没有运行真实 VAE、ADB、手机或 GPU。probe 11 合同用例、1 个小合成 metadata 用例 host 全部通过，CTest 3/3（原 condition 19、新 probe 11、metadata 1）。metadata 测试仅构造合成 tensor storage/CPU metadata，实测 builder=134，读取权重 0、运行图 0。8 个初始用例先因缺失实现真实编译 RED 后 GREEN；两条 catalog 回归也先真实缺函数 RED 后 GREEN。另 12 个 CLI 参数/非有限输入/独占输出回归全过，没有加载任何真实模型。ARM64 合同测试仅编译，没有手机执行。

```powershell
$project = 'C:/Users/32202/Desktop/99999/zhiyu'
$cmake = 'C:/Users/32202/AppData/Local/Android/Sdk/cmake/3.31.6/bin/cmake.exe'
& $cmake --build "$project/artifacts/qwen-native-runtime/diffusion-build-host" --target qwen-edge-smoke qwen-edge-vae-probe qwen-edge-vae-probe-test qwen-edge-vae-structure-test -j 4
& $cmake --build "$project/artifacts/qwen-native-runtime/diffusion-build-arm64" --target qwen-edge-smoke qwen-edge-vae-probe qwen-edge-vae-probe-test qwen-edge-vae-structure-test -j 4
```

host 二进制 `artifacts/qwen-native-runtime/diffusion-build-host/qwen-edge-vae-probe.exe`；ARM64 二进制对应 `diffusion-build-arm64/qwen-edge-vae-probe`。实际运行交由 root 统一调度，固定真实 `artifacts/model-packs/qwen-edge/vae-bf16.safetensors`。顺序先 full/zero 再 tile256/zero，各自新文件；需要时再两种 sine 对照。一次只运行一个大任务。

最终证据：`artifacts/qwen-vae-probe-contract-green-r2.log`、`qwen-vae-probe-synthetic-metadata-r2.log`、`qwen-vae-probe-ctest-r2.log`、`qwen-vae-probe-cli-preflight-r2.json`、`qwen-vae-probe-source-metadata-r2.json`（只读 27,840 B header）、`qwen-vae-probe-host-build-r4.log`、`qwen-vae-probe-arm64-build-r3.log`。各初始 RED/后续修复日志保留。host 初次集成编译发现 fork 的 `GGML_MAX_NAME` ABI 必须为 160，已使 research target 与库一致后编译通过。ARM64 有上游 missing-override warnings，无编译错误。

按 AGENTS 将 probe/smoke 的 main、load、register 和 generate 拆为小于 50 行的辅助函数，测试 main 也已缩短；完整采样参数、所有权和输出行为保持原样，只验证构建与不启动模型的回归。

此工具脱离 DiT 后显著减少驻留权重，因此单独成功不排除完整采样之后内存或进程生命周期问题。原手机 ADB 255 的根因仍要依据独立远端 exit/signal 与 USB 状态判断，不可仅从该工具结果倒推。

## Root 后续真手机验证

上述“本代理未运行”是作者的审查边界。root 后来在红米 Turbo 3/12GB 执行了真实 ARM64 合同11项与 VAE `full/sine`：native直接wait0，完整512² RGBA，解码196.118s、HWM3,041,890,304B。输入只是合成 latent，不能当 prompt 图或完整采样链通过。

首轮 `tile256/sine` 在5/9块遭遇USB中断，ADB255且没有native回执，失败记录保留。新增 [设备守护](DEVICE_VAE_RUNNER.md) 只负责研究VAE，不是App或完整sampler的watchdog。修复启动时取消信号被monitor重置的问题后，最终脚本 `4913f5afcd0ae1b49495727debaf5a7faf68c3a688a321faf62ac60123dd4116` 的真手机短验证全部通过：正常exit0、错误exit17、2秒超时timeout124/native143、向精确身份的自有guardian正PID发TERM后native143/guardian_signal/cleanupconfirmed。这些是合成生命周期夹具，不是出图或实际拔USB/热阈值测试。

该脚本用设备当前HAL温度、framework等级及可用内存守护；未知读数拒绝继续。状态、独立worker wait及外层timeout回执保存在研究目录，USB通信失败不能被解析为native成功。最终17个host合成合同通过。实际tile重试的部署版本与最终取消修复版本分别记录，完整结果见 [原生移植报告](../../../docs/QWEN_EDGE_NATIVE_PORT_2026-10-11.md)。
