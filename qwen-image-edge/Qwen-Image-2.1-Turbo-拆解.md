# Qwen-Image-2.1-Turbo 全量拆解 与「只留生图 + 端侧部署」可行性

> 数据来源：ModelScope/HF 权重 safetensors 头部实测、diffusers `qwenimage21` 源码、QwenLM/Qwen-Image-2.1 README。
> 标注「估算」的数字是我按 config 推导的，非官方公布。

---

## 一、一句话定位

Qwen-Image-2.1-Turbo = Qwen-Image-2.1 的**同架构加速 checkpoint**：参数量一个没减，只是把 40 步去噪换成 **8 步**，并把「推荐的 8 步采样网格」直接存进了 `model_index.json`。所以它不是「小模型」，是「少步数模型」。

---

## 二、数据流拆解（一条 prompt 的完整旅程）

```
用户文本 "一只在雨中弹吉他的柯基"
   │
   ├─(可选) 官方 prompt rewriter：Qwen-Image-2.1-PE-T2I（Qwen3.5-VL 9B）
   │        固定把任何输入扩写成「约 20 句 / 400-500 词」英文长描述
   │        ← 这是官方推荐用法，也是"令牌大户"
   ▼
[处理器] tokenizer.json (11.4MB) + Qwen3VLProcessor，左 padding，max_seq_len=4096
   ▼
[文本编码器] Qwen3VLForConditionalGeneration = 8.77B
   │  · model.language_model (36 层, hidden 4096, GQA 32/8头, vocab 151936) = 7.568B
   │  · model.visual (ViT) = 0.576B  ← 只做编辑/参考图时才用
   │  · lm_head = 0.622B            ← 只要隐状态，完全不需要
   │  输出：取 hidden_states[-1]（最后一层输出、RMSNorm 之前），逐 token 4096 维
   ▼
[衔接层] txt_in = RMSNorm + Linear(4096→4096) + GELU + Linear(4096→4096)
   ▼
[主干] QwenImage21Transformer2DModel = 7.11B
   │  · img_in: Linear(64 → 4096)（latent 64 通道直接投影，patch_size=1，无 patchify）
   │  · 拼接成单流序列：[文本 token] + [图像 token]，block-causal 注意力
   │      mask = (q_idx >= kv_idx) or same_image_block
   │      文本：逐 token 因果；每张图（含参考图）：块内双向
   │  · 32 × TransformerBlock，每层 218.1M：
   │       Attention  67.1M（q/k/v/o 各 4096×4096，32 头 × 128 维，无 GQA）
   │       SwiGLU MLP 151.0M（4096 → 12288 ×3）
   │       AdaLN 调制（t 经 modulation.1 67.1M 生成 scale/shift/gate，tanh 门控）
   │  · 3D RoPE：axes_dims_rope = (16, 56, 56)（t/h/w），theta=10000
   │  · proj_out: Linear(4096 → 64)
   ▼
[采样器] FlowMatchEulerDiscreteScheduler，dynamic shifting
   │  mu 由「当前图像序列长度」决定（base_image_seq_len 256 / max_image_seq_len 8192）
   │  → 换分辨率会**自动换噪声表**，这点比 SD 系列友好
   ▼
[解码] AutoencoderKLQwenImage21 = 0.338B
   · z_dim=64，空间压缩 16×，时间压缩 8×（是视频 VAE，CausalConv3d 实现）
   · decoder 0.259B（图片只需它）/ encoder 0.079B（编辑才需要）
   · 输出 4 通道 RGBA（原生透明）
```

### 8 步调度表（实测自 `model_index.json`）

```json
"sample_sigmas": [1.0, 0.978453, 0.95418, 0.926626, 0.89508, 0.845148, 0.704534, 0.414568]
```

不是均匀切分：**前 5 步全挤在高噪声区**（1.0→0.845，每步只走 0.02~0.05），**最后 3 步大跨步**（0.845→0.705→0.415→0）。这是蒸馏学出来的网格，端上不要自作主张改。

官方明确：`num_inference_steps` **会被这张表覆盖**，只有 call-time 传 `sigmas` 才能改。默认 `true_cfg_scale=1.0`（**无 CFG，省一半算力**）。

---

## 三、体积账本（safetensors 头部实测，非估算）

| 组件 | 参数量 | bf16 | int8 | int4 |
|---|---|---|---|---|
| DiT（32 层） | 7.11B | 14.23 GB | ~7.1 GB | ~3.6 GB |
| 文本编码器 合计 | 8.77B | **16.33 GB** | ~8.2 GB | ~4.1 GB |
| ├ `model.language_model` | 7.568B | 14.10 GB | 7.05 GB | 3.53 GB |
| ├ `lm_head` | 0.622B | 1.16 GB | ← **生图不需要** | |
| └ `model.visual`（ViT） | 0.576B | 1.07 GB | ← **生图不需要** | |
| VAE 合计 | 0.338B | 0.63 GB | ~0.3 GB | |
| ├ `decoder` | 0.259B | 0.48 GB | ← 生图只需要它 | |
| └ `encoder` | 0.079B | 0.15 GB | ← 只有编辑用 | |
| **总计** | **16.2B** | **30.26 GB** | | |

**「只留生图」能白捡的裁剪**：`lm_head` 1.16GB + 视觉塔 1.07GB + VAE 编码器 0.15GB = **2.38 GB，占 7.9%**。
剩下的 92% 必须靠**量化 / 蒸馏 / 换模型**——结构裁剪救不了端侧。

---

## 四、令牌账本：分辨率才是那个开关

`patch_size=1`（源码注释：*"2.1 consumes latents unpatched"*，一个 token 覆盖 16×16 像素），
所以 **图像 token 数 = (边长/16)²**。FLOPs 模型（估算）：`2·N·n + 4·L·d·n²`，N=7.11B、L=32、d=4096、n=token 数。

| 分辨率 | latent | 图像 token | 单步 TFLOP | 8 步 PFLOP | @20TOPS 秒 | 相对 2K |
|---|---|---|---|---|---|---|
| 2048×2048 | 128×128 | 16 384 | 370.6 | 2.96 | ~148 | 1× |
| 1536×1536 | 96×96 | 9 216 | 173.8 | 1.39 | ~70 | 2.1× |
| 1024×1024 | 64×64 | 4 096 | 66.2 | 0.53 | ~26 | 5.6× |
| 768×768 | 48×48 | 2 304 | 35.1 | 0.28 | ~14 | 10.6× |
| 512×512 | 32×32 | 1 024 | 14.9 | 0.12 | ~6 | 24.8× |

> 「@20TOPS」= 假设端侧有效算力 20 TOPS（旗舰 NPU 峰值口径 ~80-100 TOPS，DiT 实际利用率乐观 20-25%）。若利用率只有 5-10%，时间 ×3~4。
> VAE 解码（同样按层结构估算）：512² 约 2.6 TFLOP、2048² 约 57 TFLOP —— 512² 时约占总量 2%，不是瓶颈。

**2048² → 512²：token ÷16，attention 算力 ÷256，总算力 ÷25。**
这是整个端侧方案里唯一"免费"的大杠杆（只改一个参数），但也最容易掉画质。

### 文本 token 到底值多少？

| 项 | 600 token（官方 rewriter 输出量级） | 60 token（短提示词） |
|---|---|---|
| 文本编码器前向 | 10.5 TFLOP | 1.05 TFLOP（÷10） |
| prefix KV cache 显存 | ~315 MB | ~31 MB |
| DiT 512² 单步 attention 占比 | ~2%（joint 序列 1024+600） | ~0.6% |
| DiT 总算力节省 | — | **仅 ~2-3%** |

结论：**砍文本 token 几乎不减 DiT 算力**（权重算力占绝对主导），它省的是文本编码器自身算力和 KV cache 显存。
真正决定算力的是**图像 token = 分辨率**。

---

## 五、端侧账本要分成两笔：**存储体积** ≠ **峰值内存**

**这是最容易算错的地方。** 文本编码器与 DiT 是**严格串行**的：
TE 跑完一次前向 → 产出 `prompt_embeds`（0.4–8MB）→ **整个 TE 可以卸载** → 再加载 DiT 跑 8 步 → 再换 VAE 解码。
所以运行内存的峰值是 `max(各阶段)`，**不是三者之和**。

### 5.1 峰值内存账本（512×512 口径，int4 权重）

| 阶段 | 常驻权重 | 工作集 | 阶段峰值 |
|---|---|---|---|
| ① 文本编码（TE int4） | 3.53 GiB | KV 极小（650 token 前向，transient） | **~3.6 GiB** |
| ② 卸载 TE → 加载 DiT（int4）+ 8 步采样 | 3.31 GiB | prefix KV cache 0.33 GiB(650 token) / 0.005(9 token)；单层激活 8 MB；attn 分数 transient 0.1 GiB | **~3.8 GiB** |
| ③ VAE 解码（Tiny VAE） | 0.03 GiB | 小 | **~0.2 GiB** |
| ③' VAE 解码（**原版 3D VAE**） | 0.48 GiB | **feat_cache 在 512² 需 ~4.3 GB**（MNN 版原文自述，曾致测试机显存耗尽重启） | **~4.8 GiB** ❌ |
| **全局峰值（串行 + Tiny VAE）** | | | **~3.8 GiB ≈ 4.1 GB** ✅ |
| 全局峰值（若 TE 与 DiT 同时常驻） | 3.53+3.31 | | ~7.5 GiB ❌ 这才是"受不了"的那种算法 |

**推论 1**：串行加载后，峰值 ≈ 4.1 GB，**在 Google Play 2027 的 5GB 前台线以内** → 内存不是死结了。
**推论 2**：**原版 3D VAE 的 4.3GB feat_cache 才是真正的内存杀手**，换 Tiny VAE（30MB）后消失 → Tiny VAE 从"可选优化"升级为**过关必需品**。
**推论 3**：1024² 时 attn 分数矩阵 transient 峰值约 1.24 GB（32 头 × 4096×4746），总峰值约 5.2 GiB → **过不了 5GB 线**，除非用从不物化全矩阵的 flash-attention 类内核。**512² 是 5GB 线内的安全分辨率。**

### 5.2 存储 / 下载体积账本（这笔没变小）

串行只解决内存，**不解决下载与安装体积**——权重还是得全部存在闪存里：

| 方案 | DiT | TE | VAE | 存储合计 |
|---|---|---|---|---|
| int4 全本地（MNN 现状） | 4.5 | 5.4 | 0.03 | **9.9 GB** |
| 免费手术（删 lm_head + vision + 缩词表） | 4.5 | ~4.9 | 0.03 | **9.4 GB** |
| **换 0.8B 编码器（Zen 路线）** | 3.91 | ~0.5 | 0.03 | **4.44 GB** |
| **服务端出 embedding** | 3.91 | 0 | 0.03 | **3.94 GB** ✅ |

**关键洞察**：`prompt_embeds` 是 diffusers 管线的一等输入（源码：*"Pre-generated text embeddings, which skip prompt encoding"*），
「服务端出 embedding、端上只做采样」**架构原生支持**，不需要改模型；payload 在 650 token 时仅 4.9MB（fp8 约 2.5MB）。
**在串行口径下，这条路对峰值内存毫无影响（TE 本来就不常驻），它纯粹是在省存储与下载。**

---

## 六、工程坑清单（移植前必须知道）

1. **许可证**：Qwen Research License，原文 *"Non-Commercial shall mean for research or evaluation purposes only"*、*"You shall not use the Materials for any commercial purpose without obtaining a separate commercial license"*（商用需联系 model-business@notice.qwencloud.com）。**要做上架 App，这是第一道关**。注意 Qwen-Image 1.0 是 Apache-2.0，2.1 系列已收紧。
2. **注意力结构**：DiT 是 block-causal（文本因果 + 图块内双向）+ prefix KV cache。端侧若实现成普通全注意力，会同时错结果/白算。要么实现等价 mask，要么按 prefix 分段做注意力。
3. **VAE 是视频 VAE**：`CausalConv3d` + `scale_factor_temporal=8`。移动端 NPU 普遍**没有 3D 卷积算子**；好在单图时 T=1，可数学等价地改写成 2D 卷积（省算子、省算力）。VAE 自带 tiling（256 块）可省解码显存。
4. **依赖**：要吃 `sample_sigmas` 必须用 diffusers 源码版（PR #14950 之后）。移植时把调度表**硬编码**进端上代码，别依赖版本。
5. **分辨率约束**：宽高必须能被 32 整除。1024² 有官方示例（vLLM/SGLang 文档），**512² 没有官方验证**，低分辨率画质是未知数。
6. **不要动步数**：8 步是学出来的非均匀网格，`num_inference_steps` 无效；CFG 默认关闭（1.0），开着会翻倍算力且不符合该 checkpoint 的训练分布。
7. **HuggingFace 直连不通**（本机实测超时），权重走 ModelScope 或 `HF_ENDPOINT=https://hf-mirror.com`。

---

## 七、给你的三档路线（待你选）

| 路线 | 做法 | 端上体积 | 512² 耗时估 | 代价 |
|---|---|---|---|---|
| **① 混合最小改动** | 服务端算 prompt_embeds，端上 int4 DiT + fp16 VAE，512-768px | ~3.9-4.1 GB | 6-25 s | 需联网；仍需自研端侧 DiT int4 推理内核 |
| **② 全本地** | 先蒸馏文本编码器（→1-2B）并量化，再全 int4 | ~5 GB | 6-25 s | 训练工作量最大，且要先有 GPU 做对齐 |
| **③ 换模型** | 端侧优先架构（32× 压缩 VAE / 1-2B 主干） | 0.5-2 GB | 1-10 s | 放弃 Qwen-Image 的中文文字渲染优势 |

**我的判断**：你的「缩减令牌」思路方向对，但杠杆点在**图像 token（分辨率）**，不在文本 token；
而端上真正的墙是**参数量**（文本编码器 8.77B 占整包 54%），令牌缩减一个字节都省不下来。
所以「只留生图 + 缩减令牌」本身不足以把 30GB 变成手机能装的东西，必须先回答一个问题：
**文本编码器是放端上、放服务端，还是换掉？** 这个决定路径分叉，其余都是工程细节。

---

# 附：外部调研补充（2026-10）

## A. 已经有人把安卓版做出来了 —— 可行性不再是问题，体验才是

| 项目 | 内容 |
|---|---|
| Android 真机方案 | `scsonic/libQwenImage21` + `evankuo/Qwen-Image-2.1-MNN`（MNN + OpenCL） |
| 整包体积 | 默认下载 **~9.9 GB**（TE 5.4 + DiT 4.5 + tiny VAE 30MB）；非 Turbo int4 约 14.39 GB |
| 实机性能 | 骁龙 8 Gen 2：基座 int4 **19.1 s/步**（512²/20 步 = 451 s）；**Turbo 6 步 216–235 s** |
| 门槛 | arm64 / Android 8+ / OpenCL / **建议 12GB+ RAM / ~11GB 存储** |
| 换 2026 旗舰 GPU 推算 | 8 步 512² 端到端 **约 90–180 s** |

**结论：手机上「能跑」已经被证明，但今天的时间量级是 3.5–8 分钟/张，不是秒级。**
瓶颈不是带宽（roofline 只要 46ms/步），也不是 TOPS，而是**算子效率（差约 90 倍）**。

## B. 平台级硬墙（比算力更致命）

- **Google Play：2027-02 起，16GB 机型前台内存上限 5GB / 后台 2GB。**
- iOS jetsam 实测存在 ~2048MB 的 ActiveHard 限制。
- 而「DiT int4 3.5GB + 文本编码器 int4 3.5–5.4GB」已必然超线 → 上架型 App 必须先解决体积/内存，否则只能做侧载或"混合"。

## C. 硬件路线：别碰 NPU

- LiteRT 同一测点：**NPU 在 11 张图里只赢 1 张，GPU 赢 10 张**；NPU 加载快但算得慢，且**文本编码器在 NPU 上直接编译失败**。
- 高通 QNN：不支持动态 shape；HTP 只吃 uint8/uint16，`--weights_bitwidth 4` 与 8 **产出字节完全相同** → **没有真正的 4-bit NPU 路径**。
- LiteRT 侧量化实测：**int8 = PSNR 40.4dB（可用），int4 = PSNR 18（图像全废）**。
- MNN 是目前**唯一**官方 DiT 示例齐备的端侧框架（Sana / SD3.5 / Wan2.1），支持 int2/int4/int8 + `--transformerFuse`。

## D. 现成量化权重清单（实测字节）

| 组件 | 格式 | 体积 |
|---|---|---|
| DiT | GGUF Q4_K_M | **3.911 GB** |
| DiT | GGUF Q3_K_M / Q2_K | 2.951 / **2.297 GB**（int2 实测：10 条提示 2 条画错主体，加步数修不回） |
| DiT | int8_convrot（Comfy-Org） | 6.758 GB |
| DiT | 社区 nunchaku W4A4 | 4.336 GB（官方 nunchaku 不支持 2.1） |
| 文本编码器 | int8 / **W4A8** / DHQ IQ4_XS | 8.709 / **5.879** / 4.56 GB |
| VAE | **Tiny VAE `taeqi2_1`** | **≈30 MB，解码快 ~217×**（已有人设为默认） |

## E. 「缩小词表」到底值多少（手机 4-bit 口径）

文本编码器 8.77B 的构成：**主干 6.95B（79%）+ 词嵌入 0.622B + lm_head 0.622B + 视觉塔 0.576B**。

| 操作 | 省参数 | int4 省 | 代价 |
|---|---|---|---|
| 删 `lm_head` | 0.622B | ~0.31 GB | **零**（T2I 不产生 logits） |
| 删视觉塔 | 0.576B | ~0.29 GB | **零**（纯文生图不用图输入） |
| 词表 151936 → 32768 | 0.488B | **~0.24 GB** | 需重映射 tokenizer；**伤中文/图内文字渲染** |
| 词表 → 16384 | 0.555B | ~0.28 GB | 更伤中文 |
| 主干 6.95B | — | 3.5 GB | 只能靠量化 / 剪层 / 蒸馏 |

**残酷结论：词表从 151936 砍到 32768，在 4-bit 手机上只省约 0.24GB（整包的 3%）。**
因为词表只占文本编码器的 14%，而主干占 79%——**「缩小刻度」动的是最小的一块**。

> 单位说明：本文「GB」为磁盘十进制 GB；权重总字节 = 32.44 GB（= 30.21 GiB）。

## F. 编码器替换：唯一能把「3.5GB」变成「0.5GB」的路（重要）

| 事实 | 内容 |
|---|---|
| 接口 | DiT 吃的是 **token 级 `hidden_states[-1]`（末层、final RMSNorm 之前）+ mask**，不是 pooled 向量 |
| 离线可行性 | `prompt_embeds` / `prompt_embeds_mask` 是 `__call__` 公开入参；1000 token = **8.2MB bf16**，45 token = **0.37MB** |
| 已存在的先例 | **Zen Image Edit（AiArtLab/zen-image-edit）**：把 Qwen3-VL-8B 换成 **Qwen3.5-0.8B（1.7GB fp16）+ 158M text-fusion adapter**（取 6 层 hidden 拼 6144→4096）；对原编码器余弦 **0.95（文本）/0.97（视觉位置）** |
| 它的局限 | 只报余弦相似度，**无 FID / GenEval / 独立复现**，卡上注明 AI 辅助 |
| lm_head 删除 | unsloth PR#12669 证实：跳过未用的 lm_head **图像 bit 级一致**（真·免费） |
| 已有成品可白嫖 | Core AI 端口直接发布 **text-path-only 编码器 15.14GB**（= 17.53 − 1.15 vision − 1.24 lm_head）；MNN 版拆成 llm + embeddings_int4 + visual 三个文件 |

**三条路线的端上体积终局**：

| 路线 | DiT | 文本编码器 | VAE | 端上合计 | 离线能力 |
|---|---|---|---|---|---|
| 现状（MNN 安卓版） | int4 4.5 | int4 5.4 | 0.03 | **~9.9 GB** | ✅ 全离线 |
| 免费手术（删 lm_head+vision+缩词表） | int4 4.5 | int4 ~4.9 | 0.03 | **~9.4 GB** | ✅（省得有限） |
| **换小编码器（Zen 路线）** | int4 4.5 | **0.8B+adapter ≈0.5** | 0.03 | **~5.0 GB** | ✅ 需自己训 adapter |
| **服务端出 embedding（路线 A）** | int4 4.5 | **0** | 0.03 | **~4.5 GB** | ❌ 需联网（payload 仅 0.4–8MB） |

## G. 「缩减令牌（缩短提示词）」的实测代价 —— 别走这条

- **端上实测**：骁龙 8 Gen 2 MNN int4，文本编码器只占 **~15s / 216–235s = 3–7%**；缩短提示词省的就是这 3–7%。
- **质量消融（同机同种子）**：短提示 `"two clocks"` **只画了 1 个钟**；`"purple wine glass and black apple"` **丢了苹果**；改写成官方长描述后两例均通过。
- 官方 README 强制要求用 rewriter 扩写，不是可选项 —— **短提示词是"协议"问题，不是"能力"问题**。

**所以：令牌缩减在时延上只值 3–7%，在质量上是断崖。你的「缩小刻度」应该落在词表与编码器上，不要落在提示词长度上。**
