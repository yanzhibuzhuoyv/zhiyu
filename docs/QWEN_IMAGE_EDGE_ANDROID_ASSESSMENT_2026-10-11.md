# 自蒸馏生图模型与 12 GB 手机可行性核查

2026-10-11。目标是判断 `C:/Users/32202/Desktop/99999/qwen-image-edge` 能否替代现有 Agnes，并在用户的红米 Turbo 3（12 GB RAM）运行。结论是：**文生图具有移植路线，当前文件不能直接接入 Android，也尚无手机出图成功证据。** 本次未将它加入安装包，未移除 Agnes。

模型的主要缩减来自文本编码器：Qwen3-0.6B 学生加对齐适配器替代原大编码器。负责生成图像的扩散主干仍有 7,115,124,736 个参数，VAE 仍有 337,740,404 个参数。不能将学生 GGUF 的大小当作整个生图模型的大小。

## 实际文件与理论大小

由 safetensors 的张量头、shape、dtype、offset 和文件长度交叉核验，并逐文件比对扩散主干 index 的分片、张量名和总 payload；不加载扩散主干的张量内容。模型与蒸馏目录中的 GGUF 清单及 Android 导出文件清单记录在 `artifacts/qwen-image-edge-footprint-r3.json`。

| 组件 | 当前文件字节 | 十进制 GB | 当前格式 |
|---|---:|---:|---|
| 0.6B 学生 | 396,704,544 | 0.397 | Q4_K_M GGUF |
| 对齐适配器 | 167,857,361 | 0.168 | PyTorch，FP32 |
| 扩散主干 DiT | 14,230,284,408 | 14.230 | 两片 BF16 safetensors |
| VAE | 675,508,656 | 0.676 | BF16 safetensors |
| 四项合计 | 15,470,354,969 | 15.470 | 文件账本，不是 Android 安装包 |

这些现有文件合计约 14.408 GiB；辅助 tokenizer 等未计入。GGUF 目前属于学生编码器，没有找到可直接使用的量化 DiT GGUF。检查的 `models`、`distill` 目录中没有 ONNX、MNN、NCNN、TFLite、PTE 或 Android `.so` 导出。

假设把主干所有参数恰好存为 4 bit，保留 BF16 VAE、现有 Q4 学生和 FP32 适配器，权重文件的**理想下界**约 4.798 GB（4.468 GiB）。它不计量化尺度、未量化张量、tokenizer、激活、驱动工作区、App 和其他模型。实际导出与峰值内存必须重新测，交付说明中的“4.41 GB 整包”没有对应实物支持。文件大小也不能直接当作 Android 进程占用。

电脑脚本是在载入原 BF16 主干时通过 BitsAndBytes/NF4 量化并使用 CUDA；这不是已经导出的 Android INT4 文件。NF4、GGUF Q4_K 与 ONNX MatMulNBits 需要不同表示和执行内核。

## 本轮实际执行的测试

使用模型目录原有 Python 环境，在 Windows CPU、4 线程、完全离线模式运行 0.6B BF16 学生和 FP32 适配器。模型仅加载本地 safetensors；checkpoint 使用 `weights_only=True`，禁用远程自定义代码。不运行原交付中使用 `weights_only=False` 的脚本，不加载 DiT、不调用 API、不改原模型。

与原编码脚本一致地取 `[-1,-4,-7,-10,-13,-16]` 六层逐 token 特征，拼成 6144 维，经适配器投影到 4096 维，按原 mask 筛选并删前 14 token。使用 `Qwen3Model` 避免分配无用的语言输出 logits，保留同一隐藏层结构。

| 合成提示 | 输入 token | 输出 shape | 学生前向 | 适配器 | 数值 |
|---|---:|---|---:|---:|---|
| 既有 A01 中文生活照 | 40 | 1×26×4096 | 0.924 s | 0.033 s | 全部有限 |
| 英文水彩背景 | 41 | 1×27×4096 | 0.931 s | 0.012 s | 全部有限 |
| 较长中文日常场景 | 230 | 1×216×4096 | 8.045 s | 0.050 s | 全部有限 |

学生载入 2.415 s。与既有 A01 BF16 条件缓存相比，shape 和 mask 相同，平均逐 token 余弦相似度 **0.99993294**，平均绝对差 **0.0979248**；CPU 与既有数值不逐元素相等。Windows 进程 peak working set 约 2.176 GB。这些值仅说明本次电脑编码链有效，不能推算 Android 内存、速度、画质或身份保持能力；没有测试 Q4 学生等价性，也没有本轮重新出图。

证据：`artifacts/qwen-student-condition-probe.json`、同名 `.log`；脚本 `scripts/probe_qwen_student_condition.py`。拆分函数后 r2 复核仍 3/3 有限，A01 相似度/差值完全相同，peak working set 略有差别。体积审计的腐坏头测试先取得 6 项中 3 项失败（shape 与 payload 不符、bool 维度、未知 dtype），修复输入校验后 **6/6 通过**，见 `qwen-image-audit-red.log` / `qwen-image-audit-green.log`。终审提出缺失主干/分片可能低估体积，新增 4 项测试先因接口未实现出现 4 errors；补齐非空、分片 index 与张量核验后 **10/10 通过**，见 `qwen-image-audit-shards-red.log` / `qwen-image-audit-shards-green.log`。最终 r3 严格审计保持上述实际尺寸和参数数目。

## Android 衔接缺少哪些环节

1. **学生六层特征。** 已有 Q4 文件还没有“Q4 六层 → 适配器 → 条件”验证。普通句向量或末层 embedding 不能替代这六层逐 token 结果。llama.cpp 有 Android 路线和 graph callback，需实现对应层捕获，并与当前 BF16 基线比较。[Android 示例](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)、[接口](https://github.com/ggml-org/llama.cpp/blob/master/include/llama.h)、[Qwen3 图](https://github.com/ggml-org/llama.cpp/blob/master/src/models/qwen3.cpp)
2. **扩散主干量化与条件桥接。** stable-diffusion.cpp 当前明确支持 Qwen Image 2.1、Android 和量化权重，是优先候选。但公开 C API 没有直接输入这套 `prompt_embeds` / mask 的参数；需要接入自蒸馏条件。不能只把标准大编码器路径改成小 GGUF。Turbo 的 8 步 sigma 网格也必须保持。[运行时](https://github.com/leejet/stable-diffusion.cpp)、[2.1 文档](https://github.com/leejet/stable-diffusion.cpp/blob/master/docs/qwen_image_2.1.md)、[C API](https://github.com/leejet/stable-diffusion.cpp/blob/master/include/stable-diffusion.h)、[Turbo 模型卡](https://huggingface.co/Qwen/Qwen-Image-2.1-Turbo)
3. **VAE 与内存。** 2.1 VAE 是 64 latent channels、16×空间缩放、RGBA，旧 Qwen/Wan VAE 不能直接替用。专门的 TAEQI2.1 可做优化候选，本地尚无权重，也未验证它与当前方案的画质差别。[2.1 VAE](https://github.com/leejet/stable-diffusion.cpp/blob/master/docs/qwen_image_2.1.md)、[TAEQI2.1](https://github.com/leejet/stable-diffusion.cpp/blob/master/docs/taesd.md)
4. **真实设备验证。** 当前没有手机连接，也没有跑 Android 生图。12 GB 总 RAM 不代表应用可以长期独占；原 BF16 主干仅权重就超过该容量。量化后应从 512×512、单任务、文本编码/扩散/VAE 分阶段卸载开始，测完整进程内存、冷/热耗时、连续生成、取消、温升和后台恢复。不能把 RTX 4060 的出图速度当手机承诺。

现有 App 的 ONNX Runtime 不直接读取 GGUF、`.pt` 或 safetensors。ORT 的 INT4 主要针对部分矩阵算子；NNAPI/XNNPACK 的 Conv 支持限于 2D，原 VAE 的 3D 卷积不能假定全图加速。QNN 又需单独构建与图转换。因此本轮没有添加一个名义上的 ONNX 依赖来宣称已完成本机生图。[ORT 量化](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html)、[NNAPI](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)、[XNNPACK](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)、[QNN](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)

交付中的“5 GB 前台上限”也不是普通 12 GB App 的统一硬限制。当前 Android vitals 的 12 GB 普通 App 前台指标为 3.25 GB，统计匿名 RSS 与 swap，与映射权重文件大小不是同一口径。具体红米系统预算仍需真机检查。[Android 官方指标](https://developer.android.com/games/optimize/vitals)

## 能替代 Agnes 的哪些用途

| 能力 | 本次判断 |
|---|---|
| 文字生成生活背景 | 可以作为候选，但需先完成上述 Android 原型 |
| 聊天照片识图、描述 | 当前学生没有视觉输入，不能替代 |
| 导入照片与离线抠图 | 保留 App 已有独立路径；不依赖本次模型 |
| 固定 OC 与不同背景合成 | 可以生成背景后合成已确认的主体原图；主体像素由原资产保留 |
| 同一 OC 换动作、换角度、保持面貌 | 文字特征蒸馏没有证明具备参考图编辑能力，需要单独建立与验收 |

完整官方 2.1 支持参考图编辑，并不代表这套只训练文字特征的学生方案保留了视觉能力。现有 `AgnesVisionClient` 和带参考图片的 `AgnesImageClient` 用途不同；生图切换不能顺带删除识图。[官方 2.1 介绍](https://github.com/QwenLM/Qwen-Image-2.1)

建议先让本机模型承担后台生活背景生成，图片进入现有缓存后聊天即时使用。模型应作为独立可选下载包，先按设备内存检测与单任务调度验收，避免让整个 App 常驻生图权重。换姿势的 OC 图与识图保留独立能力；这不恢复已经取消的 2D/3D 建模。

## 收费发行与交付状态

本地和当前官方 2.1/Turbo 文件使用 2026-09-20 版 Qwen Research License，研究/评估授权与商业使用分开。用户计划靠更新版本收费，因此在分发仍使用这些主干或衍生模型的收费产品前，需要核实已有商业授权或另行取得授权。自有蒸馏适配器与学生许可不能自动解除主干的条件。本次仅研究评估，没有把该权重分发到 APK。[当前官方许可](https://github.com/QwenLM/Qwen-Image-2.1/blob/main/LICENSE)

独立运行时调查见 `artifacts/qwen-mobile-runtime-review-2026-10-11.md`；本报告结合文件审计与 root 实测，区分官方架构支持、当前 BF16 编码成功、Q4 文件存在和手机完整出图成功，后三者不互相替代。

本轮另已交付 0.2.3：修复图片形象进入视频页、照片先显示原消息再等待回复，并移除 2D/3D 建模。该 APK **不含 qwen-image-edge**，详细验证见 [修复报告](CALL_CHARACTER_FIX_2026-10-11.md)。桌面 `C:/Users/32202/Desktop/知语-0.2.3-手机独立版.apk`，188,789,732 B，SHA256 `bda9f380ea4bb82464abc566179dc448da37ff23978bdfe093991050984c7e5f`。已有 431 JVM 用例、原生主批 56/56 与补测 4/4（重复一项，59 个不同用例）通过；尚未在用户红米实测。本次模型调查未改变 APK、RAG、Key 或聊天数据。
