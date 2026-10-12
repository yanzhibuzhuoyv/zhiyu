# Research latent checkpoint（Qwen Image 2.1）

这是明确 opt-in 的研究桥，用于分开定位真实八步采样与 VAE 解码。它不改 APK，不代表 OC 参考编辑或手机完整生图已通过。现有 `qwen-edge-smoke DIT VAE CONDITIONS TOKENS SEED NEW_PNG` 六参数行为保留；新功能要求使用 fork revision 2 重新编译的二进制。

## CLI

```text
qwen-edge-smoke DIT VAE CONDITIONS TOKENS SEED NEW_PNG --checkpoint NEW_CHECKPOINT
qwen-edge-smoke DIT VAE CONDITIONS TOKENS SEED UNUSED_PNG --sample-only NEW_CHECKPOINT
qwen-edge-latent-decode --inspect CHECKPOINT
qwen-edge-latent-decode --verify CHECKPOINT
qwen-edge-latent-decode VAE full|tile256 CHECKPOINT NEW_PNG
```

`--checkpoint` 完成真实八步后写检查点，再走原来的 VAE 与 PNG；`--sample-only` 在完整检查点之后停止，返回零张图片，不创建占位 PNG。占位参数仍需是不存在的独立输出路径。检查点由回调原地借用真实采样张量、同步复制并持久写入，不是重算或合成值。CLI 校验观察到的采样进度为 8/8；取消、回调缺失、回调失败或写入失败均不是成功。

原参数固定为 512×512、Euler 八步、CFG 1、四线程、CPU、真实 2.1 VAE。九个完整 sigma 边界为 `[1,.978453,.95418,.926626,.89508,.845148,.704534,.414568,0]`；八个正值对应八次采样。条件输入为 1..498 token，每 token 4096 个有限 F32，文件字节数精确为 `tokens*16384`，已由外部编码器处理前缀。native seed 是非负 int64；设备研究守护采用更窄的 0..2147483647。

独立解码器仅加载 2.1 VAE 的 ModelManager 和 WanVAERunner，不创建 DiT context。它读取 `diffusion_normalized` 阶段数据，执行与原 `decode_first_stage` 相同的 `diffusion_to_vae_latents` **一次**，然后直接 `decode`。`full` 和 `tile256` 是明确两种模式，不自动回退；tile256 是 256 像素瓦片与 25% 目标重叠。`--inspect` / `--verify` 只使用同一严格 reader，不加载模型。

## 输出与源保护

源文件须是绝对路径的常规文件，父目录须为实际 canonical 路径。输出父目录须已存在，禁止 `.` / `..`、已有文件、链接及原始 `qwen-image-edge` 目录。显式模型、VAE、条件、输入检查点的**父目录及其子树**不可作为新输出位置；可执行文件的父目录不作为数据源树保护范围。Windows canonical 解析会拒绝改写实际路径的 junction / symlink；Windows 测试中的真实 symlink 分支受创建权限限制。

检查点在加载模型之前通过 `wbx` 独占占位。PNG 和检查点不得同名；错误保留空文件或部分文件，后续检查会拒绝它们，程序不覆盖旧失败历史。检查点必须 fwrite 全量、fflush、fsync / Windows `_commit`、fclose 均成功才算完成。CRC 是损坏检测，不是模型来源签名；条件和模型 SHA、完整运行回执由外层记录。即使取消前已经写好检查点，取消的执行也不能作为完整生图成功证据。

## 二进制格式（version 1）

文件固定 **262288 字节**：128 字节头 + 262144 字节 F32LE + 16 字节完成 footer。空间顺序是 width 最快的 `[32,32,64,1]`，65536 个值，全有限。各数值均 little endian。

| 偏移 | 内容 |
| --- | --- |
| 0..7 | ASCII `ZHQWLAT1` |
| 8 / 12 | u32 format version 1 / header bytes 128 |
| 16 / 20 | u32 model 210（QwenImage2.1）/ stage 1（diffusion_normalized） |
| 24 / 28 | u32 dtype 1（F32LE）/ layout 1（width fastest） |
| 32 / 36 / 40 / 44 | u32 width32 / height32 / channels64 / batch1 |
| 48 / 56 | u64 value count65536 / nonnegative int64 seed |
| 64 | u32 sampling steps8 |
| 68..127 | 必须为零 |
| 128..262271 | 原始有限 F32LE 张量 |
| 262272..262279 | ASCII `ZHQWEND1` |
| 262280..262283 | u32 CRC32 IEEE（头与 payload，不含 footer） |
| 262284..262287 | 必须为零 |

严格 reader 拒绝大小不符、未知字段、错误 stage/model/shape/steps、缺失 footer、CRC 不符，以及 CRC 有效但 payload 为 NaN/Inf 的文件。

`--inspect` JSON 的稳定字段包含 `scope:"research_checkpoint_inspection"`, `version:1`, `width:32`, `height:32`, `channels:64`, `batch:1`, `steps:8`, `seed`, `crc_verified:true`，另给出 format_version/model/stage/shape/value_count/dtype/layout/sampling_steps/crc32/bytes/finite/model_loaded:false。它验证内容，不能单凭文件字段证明真实采样来源。

## 固定源码与证据

upstream `2988060a4338a5dcd94721c964a8e87003c1ac8b`；ggml `d25b121ce1d6ae0c7e62ad080460d9fc1e46dd93`；fork revision 2；patch SHA256 `9bf82bfd37dd1ffd5df28fd32e0a3948ae10ab8f086c886496c01203f300cc95`。`qwen_diffusion_prepare.py` 将四个 API/验证 header 与 patch 一起核对。新研究 checkout 是 `artifacts/qwen-native-runtime/sd-checkpoint-src`，旧 `sd-src` 不被覆盖，旧 fork 的已知历史不得与新构建混淆。

2026-10-11：实际 host 四项 CTest 通过（condition19、probe11、checkpoint18、无模型 metadata1）；prepare9项通过；实际 host CLI 11项合成合同通过，覆盖严格 inspect、截断、超长、CRC 损坏、禁止覆盖源树/已有输出、非法参数。日志见 `artifacts/qwen-latent-ctest-host-r2.log`、`qwen-latent-prepare-green-r1.log`、`qwen-latent-cli-contract-r2.json`。合成检查点明确标注 `NOT_MODEL NOT_SAMPLING`，不是出图证据。

Host 与 Android arm64-v8a/API26/NDK30/r30 Release 构建成功；ARM OpenMP 关闭，ELF 仅依赖 libc/libm/libdl。尚需 root 的真实同条件/seed 对比原 PNG 与检查点独立解码 PNG、独立手机回执及图像验证。本文写入时该真实 host 实验正在进行，不能提前声明通过。
