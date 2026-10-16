# 许可边界核查

对应实施计划 §1.3（几何估计的许可陷阱）、§3.1（分割模型许可）、§5.6 / 设计文档 §6.4（修复模型许可）。

**结论先行**：本仓库当前**不含任何需要许可核查的第三方权重**，
因此阶段 0 与 P0 探针的结论不受许可阻塞。
但阶段 1 与阶段 3 一旦接入模型，下列条款必须**先有结论再动手**。

---

## 1. 当前状态（已核）

| 组件 | 许可状态 | 证据 |
| --- | --- | --- |
| 契约层（Kotlin） | 本仓库自研，无第三方代码 | 只依赖 `junit:junit:4.13.2`（EPL-1.0，测试期） |
| P0 探针（Python） | 本仓库自研 | 只依赖 `numpy`（BSD-3）、`Pillow`（MIT-CMU） |
| 探针几何 | **参数化椭球，自研**，不下载任何权重 | `probe/headproxy.py` 的 `license_report()`；`backend="parametric"` 是默认值 |
| 合成夹具 | 自研合成，非真实角色、非模型产物 | `probe/synthetic.py`，标注 `SYNTHETIC_FIXTURE` / `NOT_MODEL` |

`probe.json.licensing` 会随回执写入上述结论，便于审计。
探针**不访问网络**（`network_access: false`）。

---

## 2. 几何估计的许可陷阱（计划 §1.3，必须一起查）

| 方案 | 依赖 | 许可风险 | 本仓库的处理 |
| --- | --- | --- | --- |
| DECA / EMOCA | **FLAME 模型** | **非商业研究许可**，不可随商用品分发 | **不使用**。探针默认几何是自研椭球，因此不引入该风险 |
| 经典 3DMM | **BFM (Basel Face Model)** | 同为**非商业** | **不使用** |
| **MediaPipe Face Mesh** | 自带规范脸模型 | **Apache-2.0**，无上述问题 | 预留为可选后端（`backend="mediapipe"`，当前未安装、未使用）。**真实角色图的最终验收应切到该后端复跑** |
| Depth Anything V2 / MiDaS | 深度估计 | 代码宽松；**权重条款需单独核** | 未使用 |

**纪律的来源**：设计文档 §1.3 明确要求「不要先用 DECA 出漂亮结果，再在阶段 5 发现不能用」。
本仓库因此把「许可干净的路径」做成默认路径，把有风险的路径做成需要显式切换的选项。

---

## 3. 待核查（阶段 1 之前必须出结论）

### 3.1 部件分割（计划 §3.1，可阻塞）

| 候选 | 输出 | 许可状态 | 结论 |
| --- | --- | --- | --- |
| **BiSeNet face-parsing**（face-parsing.PyTorch） | 19 类头部分区 | 代码 MIT；**权重训练于 CelebAMask-HQ** | ⚠️ **待核**：CelebAMask-HQ 源自 CelebA，条款通常**限制商业使用**。这是决策 5 已接受的风险点，但仍须在接入前给出结论 |
| SegFormer-B0 human parsing | 18 类全身 | 需核实 | 待核 |
| MODNet / RVM | 时序 matting | Apache-2.0 系 | 只给 matte 不给部件，**不能替代** BiSeNet |

三条处置（计划 §3.1）：① 条款可接受 → 直接用；② 不可接受 → 自训/微调（延期）或换商用品；
③ **在结论之前不得写进随包 assets**。

**硬性执行点**：`android/app/src/main/assets/` 新增 `parse-model-NOTICE.txt` + 许可全文，
与现有 8 个许可文件并列。**权重不入 Git**（`.gitignore` 已排除 `*.onnx` / `models/` / `model-packs/`）。

### 3.2 修复模型（设计文档 §6.4）

| 模型 | 参数 | 体积（估） | 许可 | 结论 |
| --- | --- | --- | --- | --- |
| **MI-GAN** | 为移动端设计 | ~20–50MB int8 | Apache-2.0 | ⚠️ **待核**：体积与许可需实际核对后写入 NOTICE |
| LaMa (big-lama) | ~51M | ~200MB fp32 / ~50MB int8 | Apache-2.0 | 桌面兜底，同上 |

---

## 4. 现有 8 个许可文件的边界（不得借证据）

`android/app/src/main/assets/` 已有：

```
portrait-model-APACHE-2.0.txt    portrait-model-NOTICE.txt    portrait-runtime-MIT.txt
semantic-model-MIT.txt           semantic-model-NOTICE.txt
voice-APACHE-2.0.txt             voice-model-NOTICE.txt       voice-ONNXRUNTIME-MIT.txt
```

**这 8 个文件覆盖的是既有的抠图/语义/语音模型，不覆盖新的分割与修复模型。**
「既有模型的许可已核」不能当作「新模型许可已核」——这正是计划 §10.4
「不借证据：不以旧阶段或其他模块的通过充当前阶段的验收」的具体适用。

---

## 5. 不得宣称

- 不得因为探针「不含权重」就声明整条流水线许可干净——阶段 1 的模型尚未核实。
- 不得用代码的 MIT/Apache 许可替代**权重**的许可核查（两者的授权主体不同）。
- 不得在许可结论出来之前把权重写进随包 assets，也不得提交进 Git。
