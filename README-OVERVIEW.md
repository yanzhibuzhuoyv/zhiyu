# 知语 · ZhiYu 总览

> 本文件是**整仓库总览**：项目是什么、两部分怎么配合、构建怎么入手。
> 构建步骤与技术栈细节见 [README.md](README.md)；逐版变更见 [CHANGELOG.md](CHANGELOG.md)。

本仓库包含两个部分：

| 目录 | 内容 |
|------|------|
| `android/` `backend/` `native/` `shared/` `docs/` | **知语主工程**：原生 Android AI 伴侣，Kotlin + Jetpack Compose，本机 RAG 与离线语音 |
| `qwen-image-edge/` | **端侧图像引擎研究工程**：把 Qwen-Image-2.1-Turbo 瘦身到手机可分发体积的剪层 / 蒸馏实验与工具 |

`qwen-image-edge` 是为知语的手机端出图能力准备的。接入层代码在 `native/qwen-edge/`，两者的关系与判断记录见
[docs/QWEN_IMAGE_EDGE_ANDROID_ASSESSMENT_2026-10-11.md](docs/QWEN_IMAGE_EDGE_ANDROID_ASSESSMENT_2026-10-11.md)
与 [docs/QWEN_EDGE_NATIVE_PORT_2026-10-11.md](docs/QWEN_EDGE_NATIVE_PORT_2026-10-11.md)。

---

## 一、知语主工程

一个**手机本地优先**的 AI 伴侣应用：原话、资料、素材都存在本机，App 直接通过 HTTPS 调用使用者自己选的模型服务商，不依赖自建服务器。

### 架构

```
┌─ Android（Kotlin + Compose，当前唯一功能实现）────────────────┐
│  UI 层      ui/        Compose 界面 + ViewModel               │
│  领域层     domain/    检索规则、联系人策略、回复分段           │
│  数据层     data/      SQLite 原话库、本机素材库、会话存储       │
│  端侧推理   onnxruntime（BGE 语义检索 / u2netp 人像分割）       │
│  语音       sherpa-onnx（zipformer ASR + vits TTS）            │
│  图像引擎   native/qwen-edge（JNI + stable-diffusion.cpp）      │
└────────────────┬──────────────────────────────────────────────┘
                 │ HTTPS（使用者自备 API Key）
                 ▼
       所选 AI 服务商（DeepSeek / 通义千问 / 腾讯混元 …）

┌─ Python 后端（FastAPI，legacy 冻结）──────────────────────────┐
│  只修安全、兼容与契约正确性；schema / 迁移 / 排名与手机端不等价   │
└───────────────────────────────────────────────────────────────┘
```

### 检索与记忆

手机端以 **BGE INT8 语义检索**为主路径、**FTS4** 补查；旧后端用 FTS5/BM25。两侧 schema、迁移及排名**不等价**，旧后端 A/B 结果不能当作手机端召回等价的证明。两端共享的行为契约由 `shared/generate_companion_contract.py` 生成，构建时校验。

### 运行

```powershell
.\scripts\Start-Backend.ps1          # 旧后端，默认 127.0.0.1:8790
.\scripts\Start-Backend.ps1 -Demo    # 无 Key 本地演示
```

API 文档 <http://127.0.0.1:8790/docs>，健康检查 <http://127.0.0.1:8790/health>。

Android 端构建环境与命令见 [README.md](README.md) 第二节；后端依赖、测试与 Docker 见第三节。

---

## 二、qwen-image-edge 端侧图像引擎

**目标**：把 Qwen-Image-2.1-Turbo 压到能在手机上分发，只保留文生图（T2I）功能。
**结论**：整包从 16.33 GiB（原文本编码器单文件）压到 4.41 GB（int4 整包），画质与中文图内文字经实测无损。

完整交付清单、体积账本、质量实测与复现命令见 **[qwen-image-edge/交付说明.md](qwen-image-edge/交付说明.md)**。

### 文本编码器剪层

| 方案 | 参数量 | bf16 | 说明 |
|------|--------|------|------|
| 原始 Qwen3-VL-8B | 8.77 B | 16.33 GiB | 基线 |
| 删 lm_head + 视觉塔（L36） | 7.57 B | 15.17 GiB | 保守方案 |
| **剪到 L24 + 删冗余** | **6.02 B** | **10.86 GiB** | **逐元素验证与原模型第 24 层一致** |

剪层工具：`qwen-image-edge/tools/pack_te.py`；打包后必须用 `tools/fix_packed_config.py` 改 `config.json` 的 `num_hidden_layers`，否则会补随机层。

### 蒸馏学生

| 学生 | 参数量 | 适配器 | val_cos |
|------|--------|--------|---------|
| Qwen3-1.7B + adapter | 1.79 B | 256 MB | 0.9870 |
| Qwen3-0.6B + adapter | 0.67 B | 160 MB | 0.9849 |

三方对照出图 11 条提示词：1.7B 与 0.6B 学生均 **10/11 正确**，中文图内文字「深夜咖啡」同样正确。

### 复现

```powershell
# ① 打包文本编码器（裁层 + 删冗余）
python tools\pack_te.py --layers 24 --keep-vision 1 --name L24
python tools\fix_packed_config.py
python tools\verify_packed_te.py --packed models\te_packed\L24 --expect-layer 24

# ② 蒸馏（语料 → 教师 → 学生 → 适配器）
python tools\gen_corpus_v3.py -n 12000
python tools\gen_teacher.py --corpus distill\corpus_v3.jsonl --limit 12000 --out distill\v3_teacher
python tools\gen_student_feats.py --corpus distill\corpus_v3.jsonl --limit 12000 --out distill\v3_student
python tools\train_adapter.py --teacher distill\v3_teacher --student distill\v3_student --epochs 40 --out distill\v3_adapter
```

依赖：`torch 2.11.0+cu128` / `transformers 5.19.0` / `safetensors 0.8.0` / `bitsandbytes 0.50.2`。

### 权重下载

**模型权重不进入 Git**（单文件超 2 GiB 无法作为 Release 附件）。权重发布在本仓库的 Release 中：

- `L24` 剪层文本编码器（bf16，10.86 GiB，6 分片，逐元素无损）
- `L36` 全层保守版（bf16，15.17 GiB）
- 蒸馏学生 `Qwen3-0.6B-Base` / `Qwen3-1.7B-Base` + 对齐适配器
- GGUF 量化件（Qwen3-0.6B/1.7B 的 Q4_K_M、Q5_K_M、q8_0）

各文件的 SHA256 校验值与体积见 Release 说明。放置路径需与 `交付说明.md` 的复现路径一致。

---

## 三、许可证与来源声明

本项目为个人学习与自用项目，**未用于任何商业用途**。

### Qwen-Image-2.1-Turbo（Qwen Research License）

`qwen-image-edge/` 中的剪层文本编码器、DiT、VAE 等权重均为 **Qwen-Image-2.1-Turbo 的衍生物**，遵循 **Qwen Research License**：

- 该许可将 "Non-Commercial" 定义为 *"for research or evaluation purposes only"*，并要求商业用途另行取得授权
- 许可第 24 条要求分发时附带许可证副本 —— 见 [qwen-image-edge/LICENSE-Qwen-Image-2.1-Turbo](qwen-image-edge/LICENSE-Qwen-Image-2.1-Turbo)
- 许可第 31 条允许使用其输出训练其他模型，需标注 **"Built with Qwen"** / **"Improved using Qwen"**

本仓库中的实现与权重即按此标注：**Built with Qwen. Improved using Qwen.**

### Qwen3-0.6B / 1.7B Base（Apache-2.0）

蒸馏所用的学生底模为 **Apache-2.0**，Copyright 2024 Alibaba Cloud。再分发时附许可证副本（见各模型目录 `LICENSE`）。

### 其他

- 知语主工程代码中不含任何 API Key；密钥由使用者在应用内输入，凭据经 `EncryptedSharedPreferences` 加密存储
- 本仓库不含签名密钥、`local.properties`、用户聊天数据库与媒体文件

---

## 四、免责声明

- 本项目为个人学习用途，**非任何学校或公司的官方产品**。
- 模型权重按上述许可证分发；使用者应自行确认其使用场景符合许可条款。
- 接口与模型结构可能随上游更新而失效，使用本仓库内容产生的后果由使用者自行承担。
