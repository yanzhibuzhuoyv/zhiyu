# 知语 · 原生 Android AI 伴侣 + 端侧图像引擎

> 本文件是**整仓库总览**。知语主工程的完整功能、版本变更、测试与部署细节见 [README.md](README.md)。

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

当前开发版本为 **0.2.11 手机独立版**：本机保存独立原话、资料与素材，直接通过 HTTPS 调用选择的 AI 公司，无需电脑后端、Docker 或 USB。云模型仍需联网。安装与升级边界见 [Android 本地版](docs/ANDROID.md)。

Kotlin Android 是当前手机产品的唯一功能实现；Python 后端明确冻结为 legacy，只修安全、兼容和契约正确性。手机使用 BGE INT8 语义主路径与 FTS4 补查，旧后端使用 FTS5/BM25，两侧 schema、迁移及排名不等价。

### 本机启动（Python 后端）

已安装 Python 3.11+（SQLite >= 3.43 并支持 FTS5）与 [uv](https://docs.astral.sh/uv/) 后，在项目根目录执行：

```powershell
.\scripts\Start-Backend.ps1
```

首次启动按 `backend/uv.lock` 安装依赖。API 文档：<http://127.0.0.1:8790/docs>；健康检查：<http://127.0.0.1:8790/health>。

没有 Key 时可以显式开启本地演示：

```powershell
.\scripts\Start-Backend.ps1 -Demo
```

### 测试与部署

```powershell
cd backend
uv sync --frozen --extra dev
uv run --no-sync pytest --cov=app --cov-report=term-missing
uv run --no-sync ruff check app tests
```

Docker 部署：`docker compose up --build -d --wait`。独立容器测试：`.\scripts\Test-Docker.ps1`。

详见 [README.md](README.md) 的「测试与部署」「安卓联调」「知识库」「服务商与模型」各节。

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
