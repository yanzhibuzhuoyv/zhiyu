# 分阶段任务地图（交给 codex）

> 2026-10-14 用户确定“自动建议＋手动修正”，不要求完全自动生成作为当前工具完成前提。阶段4新增撤销／重做、原图对照与草稿保护已完成：52Node、12新browser检查及11原流程回归通过。后续重点转向Android一起接入，自动建议质量可继续改善，详见[当前人工修正](MANUAL_CORRECTION_2026-10-14.md)。

> 2026-10-14 新增独立前视角 `front_mesh` 路线：电脑工坊的导入/人工修整/真实网格渲染/持久化下载重开及音频RMS口型完成。220JVM/58Python/37Node与用户17实图及真实browser流程通过。阶段4–6有可操作前视角产物，旧多视角v2的画质/身份/±90°发布门禁仍未通过，不能把两条路线混同；自动定位/复杂分层与手机接入仍待续。详见 [最新工坊报告](LIVE2D_STUDIO_2026-10-13.md)。

> 最新细节研究：RGB种子笔画提取与不连接候选独立保存、JVM静态保护验证，181 JVM/29分割Python GREEN。原raw支持域合成线稿召回84.300%仍漏2338，未解锁完整分割/角色发布；三种追加点方案退化未采用。见[细节报告](DETAIL_REFINEMENT_2026-10-13.md)。

> 最新：阶段1实际SAM2.1 tiny CPU推理与提示适配/JVM桥接完成研究验证，**179 JVM /34工具 /13adapter GREEN**。彩图逐部位IoU95.376%、召回97.003%，仍漏约3%；线稿需单独路径。点框为oracle，未完成自动真实OC/手机推理/Live2D发布。见[真实模型报告](SAM2_PROMPT_ADAPTER_2026-10-13.md)。下面“没有实际模型”等为此前历史，以本段为准。

> 最新继续：阶段1新增不可变前景/部位mask与草案保全核心，当前为部分完成；155 JVM /26工具GREEN，新分层行99.16/分支85.16。固定十二翼示例补回872模型subject遗漏、最终原前景零丢失，未分类/冲突静态保留。没有实际模型推理或真实OC验收；不解锁完整渲染/发布。见[最新分层报告](PART_PRESERVATION_2026-10-13.md)。下方阶段0/P0数字为上轮历史。

> 2026-10-13：v2 契约错误已修，新增独立语音协调核心（阶段7为部分完成）；132 JVM /30 probe Python /18研究工具通过。默认彩图/线稿仍未通过正面空洞硬检查；候选投影通过几何检查，但完整工作图漏掉7,345个头部前景像素，未采用。阶段1–6尚不具备发布条件。见 [新验证记录](REVIEW_AND_VOICE_CORE_2026-10-13.md)、[轮廓复核](CONTOUR_STUDY_2026-10-13.md)；下面的2026-10-12列表保留为历史规划，不能当作新验收。

本文件把实施计划的 8 个阶段映射成**本仓库里的具体文件与验收**，
并标明每一步的前置条件（阻塞项）与不得宣称的事项。

状态图例：`[x] 已完成` · `[ ] 未开始` · `[!] 被阻塞` · `[~] 部分完成`

---

## 当前进度

| 阶段 | 状态 | 产出 |
| --- | --- | --- |
| P0 探针 | `[~]` 真实彩图/线稿复跑，画质门槛未通过 | `probe/`、新轮廓/保全报告 |
| 阶段 0 契约 | `[x]` 已修复，纳入220项当前JVM回归 | `src/main/kotlin/rig2d/domain/`、`rig2d.planar` |
| 阶段 1 部件分割 | `[~]` SAM2实际提示/笔画候选/保全，真实OC质量待续 | `segmentation/`、`rig2d.parts` |
| 阶段 2 3D 代理 | `[ ]` | — |
| 阶段 3 重投影与分级补全 | `[ ]` | 探针已给出算法原型（需重写为 Kotlin） |
| 阶段 4 人工修整 | `[x]` 前视角眼口/主体/多附件编辑；多视角另待续 | `studio/web/editor.mjs` |
| 阶段 5 运行时渲染 | `[~]` 前视角WebGL与JVM数值核心完成；多视角/Android待续 | `studio/web/renderer.mjs`、`rig2d.planar` |
| 阶段 6 固化与发布 | `[~]` 本机数据包原子保存/下载重开；完整自动身份门禁未过 | `studio/storage.py`、`studio/package.py` |
| 阶段 7 全双工 | `[~]` 独立协调核心/合成复演，真实设备待验收 | `rig2d.voice` |

---

## P0 探针 — 剩余工作

- [ ] **用一张真实角色图复跑**（计划 §1.1 P0.1）。
  命令：`python probe/run_probe.py --image <path> --out artifacts/rig-probe-real-<date>`。
  图**不得取自私聊 / 用户媒体库**；先看 `proxy.json.head_box_px` 是否落在头上。
- [ ] 记录真实图的门禁读数与目视结论；与合成夹具的读数并排留档。
- [ ] （可选）切换 `backend="mediapipe"` 复跑，验证许可干净路径的几何质量差异。
  需要 `pip install mediapipe`；**探针默认不依赖它**，切换前先补 LICENSE 核查记录。

**不得宣称**：真实图跑通之前，不得把合成夹具的 `GATE_PASS_C_VIABLE` 当成产品结论。

---

## 阶段 1 — 部件分割接入

- [x] RGB种子笔画候选、unseeded候选独立保存/始终review、原ARGB静态保护跨语言验证；仅研究工具，未进入Android。
- [ ] 支持域不足、浅色/同色背景细节与真人/真实非真人OC评估；本次自动误差点策略退化，保持旧默认。

- [x] SAM2.1 tiny固定许可/源码/权重SHA、实际桌面CPU推理与有界提示适配器、JVM工作像素来源桥接及实际输出重组测试。无需采用CelebAMask-HQ研究权重；SAM2模型未进手机assets。
- [ ] 逐部位提示细化/漏翼细丝保护、线稿墨迹路径与真实OC数据；当前oracle合成质量不能解锁渲染/发布。

- [x] 独立纯JVM原前景保全核心、语义候选输入/unknown与conflict留存、不可变像素hash、12翼跨语言合成复演。见 `rig2d.parts`；这是mask处理核心，不是自动分割模型或绑定完成。

**前置阻塞（计划 §3.1）**：CelebAMask-HQ 权重条款核实结论。
在结论出来之前**不得**把权重写进随包 assets（见 [LICENSING.md](LICENSING.md)）。

- [ ] 许可核实：BiSeNet face-parsing 权重（训练于 CelebAMask-HQ）能否商用。
      不可接受 → 自训/微调（延期）或换商用品。
- [ ] 新增 `data/LocalPartSegmentation.kt`：仿 `LocalPortraitProcessor.kt` 的结构
      （固定 SHA 校验、CPU 会话、按次释放）。
- [ ] `face_mask ∩ subject_mask`（u2netp 输出）求交，避免背景误判成脸。
- [ ] 改 `android/app/build.gradle.kts` 新增 `prepareParseAssets`（核 SHA 后复制）。
- [ ] `src/main/assets/` 新增 `parse-model-NOTICE.txt` + 许可全文。
- [ ] 测试：`androidTest/.../LocalPartSegmentationNativeTest.kt`（空图、常量 mask、
      几乎全图 mask、形状不符、非有限数 → 明确报错不伪装成功）；
      `test/.../PartLayerMappingTest.kt`（BiSeNet 类别 → 绑定层类别的映射；未知类别拒绝）。

**验收**：真实角色图产出分层 mask 目视检查；峰值内存实测（≤1536 最长边、≤4MP 纪律）；
许可文件随包且 SHA 固定。
**不得宣称**：不得把「19 类 mask」称为「绑定完成」——mask 只是层，层序与网格在阶段 2。

**可复用**：本仓库 `OcRigAssetRules.layerKinds`（19 个层类别）就是映射目标集合，
且「未知类别拒绝」已在校验里实现并有测试。

---

## 阶段 2 — 3D 代理、层序与网格

**前置**：阶段 1（层）+ P0.2（几何路径已探明）。

- [ ] `data/local/rig/HeadProxyBuilder.kt` —— 几何估计（许可干净路径）。
      可移植 `probe/headproxy.py` 的 `build_proxy` / `grid_directions` / `fit_head_box` 逻辑。
- [ ] `data/local/rig/LayerOrderRules.kt` —— 深度 → 层序。
      契约侧已就位：`OcLayerAsset.z` 唯一性与 `depth ∈ -1..1` 已在校验里；
      本文件负责**推断**（谁盖谁），并须在 z 与 depth 矛盾时拒绝。
- [ ] 变形网格：复用 `OcRigVerticesV1` 的网格生成路径（本仓库已冻结副本，
      移植后替换为真实的 `OcRigRules.vertices`），把位移来源换成代理驱动的 yaw 投影。
- [ ] 测试：`test/.../LayerOrderRulesTest.kt`（深度冲突、同深度并列、层序唯一性、
      z 与深度矛盾时拒绝）；`androidTest/.../HeadProxyNativeTest.kt`（对称平面稳定性、深度单调性）。

**验收**：同一输入重复运行的代理参数**逐位一致**（确定性）；不同图之间不出现对称平面翻转。
**范围边界（如实标注）**：代理覆盖脸；头发是壳层；身体不在代理内。
**不得宣称**：不得把代理称为「3D 建模已恢复」——2D/3D 建模入口仍是取消状态。

---

## 阶段 3 — 5 视角重投影与分级补全（投入最大）

**前置**：P0 门禁通过。

- [ ] `data/local/rig/ViewReprojector.kt` —— 5 视角投影。
      可移植 `probe/project.py`：`camera_basis`（**注意环绕方向与屏幕基，这里有两次真实翻车**）、
      `raycast_ellipsoid`（**注意射线参数化必须让 s>0 唯一**）、`render_view`。
- [ ] `data/local/rig/SymmetryFill.kt` —— A 级。可移植 `symmetry_mirror`。
- [ ] `data/local/rig/PatchMatchFill.kt` —— B 级。可移植 `fill.py`，
      **必须保留 `max_chained_distance`**（否则块匹配会退化成涂抹，见探针缺陷日志第 6 条）。
- [ ] `data/local/rig/InpaintClient.kt` —— C 级（MI-GAN 端侧 / LaMa 桌面兜底）。
- [ ] `data/local/rig/LayerComposer.kt` —— A/B/C 分级调度。
- [ ] `margin` 写回：用 `OcViewBlendRules.requiredMargins` 计算并写入 `OcLayerAsset.margin`；
      不足即拒绝（契约已有 `adequacy` 实现与测试）。
- [ ] 测试：`MarginAdequacyTest`（**已在本仓库完成**，可直接移植）、
      `SymmetryFillNativeTest`、`PatchMatchFillNativeTest`（输出像素必来自本图）、
      `ViewConsistencyNativeTest`（5 视角部件类别与相对位置）、
      `SeamScanNativeTest`（**yaw 全程逐角度检查层间露底，最关键**）。

**验收**：露缝扫描 yaw 从 -90 到 +90 逐角度无露底；一致性校验通过；目视 5 视角身份一致；
导入期耗时与峰值内存记录在案。
**不得宣称**：不得把合成结果称为「原图逐像素相等」；不得因一路视角通过而声明身份验收通过；
不得把单角度通过当作全程通过。

---

## 阶段 4 — 人工修整

- [ ] 复用现有笔刷（`LocalPortraitProcessor.refine` 的保留/擦除），扩展到「按层修」。
- [ ] `SubjectRefineDialog` 的底图从原图换为该视角的投影底图（保证笔刷坐标有参照）。
- [ ] 修完后重算 `bounds` / `margin` 并写回（用 `requiredMargins`）。

**验收**：一次完整人工修整流程可走通；坐标参照正确（笔刷落点与底图对齐）。
**注意**：自动补全质量不足以无人值守；所有实际出货的 VTuber 资产都是「自动打底 + 人工修正」。
`OcRigAsset.approvedAt` 就是为这个环节准备的（0 = 未人工确认）。

---

## 阶段 5 — 运行时渲染与降级

- [ ] `data/OcRigRenderer.kt`：多纹理四边形批渲染；扩展 `AvatarGlRenderer.kt`。
      资产在导入期已解码为 GPU 纹理并缓存，切视角不触发 IO。
- [ ] 视差：把 `OcFlatRenderer.kt` 的 `headTurn * 3.5f` + 13px 平移
      换成真实 yaw + 按代理深度做 billboard Z 偏移
      （可移植 `OcViewBlendRules.layerParallaxOffset`）。
- [ ] **每视角独立 rig**（`OcViewLayers.rig`）：±90° 侧脸眨眼 ≠ 正面眨眼。
- [ ] **降级路径（硬要求）**：资产缺失或校验失败 → 退回 `OcFlatRenderer` 程序化形象。
      **不得因新资产缺失而让通话白屏。**
- [ ] 测试：`CallRigRenderNativeTest`、`RigFallbackNativeTest`、`MotionSemanticsTest`
      （**已有**：`MotionSemantics` 与其测试可直接移植）。

**验收**：真机 60fps（或实测帧率如实记录）；降级不崩；断 USB 可用。
**不得宣称**：不得把「透明 PNG 分层」称为 Live2D。

---

## 阶段 6 — 固化与发布

- [ ] `data/local/rig/RigAssetInstaller.kt`：分层资产写入私有 `filesDir/rigs/.staging/<operationId>`，
      输入只读；每文件 flush/fsync + 清单 + 完成标志后**原子 rename** 为 `rigs/<assetSha>`；
      最后原子更新 active 指针。
- [ ] 失败/重启留下的 staging 不是 ready；只清理本 operation 残留，旧 active 仍可用。
- [ ] 发布前短提交锁内做最终 CAS：校验 `sourceSha`、`assetSha`、外观版本。
- [ ] 资产目录**排除聊天媒体清理**。
- [ ] 测试：`RigAssetInstallNativeTest`（取消、低存储、错 SHA、源变化、链接冒充、
      重启恢复、原子 active、版本 lease）。

**验收**：中断/失败不污染旧 active；重启后状态正确；真机导入一次完整资产。
**可复用**：`OcPackageSha.v2`（哈希）、`OcRigAsset.approvedAt`（人工确认）、
`OcAssetRefV2.visualVersion`（版本 lease）。

---

## 阶段 7 — 全双工（独立验收，可与 1–6 并行）

与本仓库低耦合，**不需要**在这里实现。要点（计划 §9）：

- 现状：流式 ASR 已随包；TTS 非流式整句合成；回合状态机是按键式。
- 四项差距：回合制→连续（VAD 端点检测）、打断（barge-in）、非流式 TTS（分块合成）、
  LLM 首 token（云侧，本机不可控）。
- 延迟预算：端到端 **< 1.2s**，**不承诺 800ms**。
- 与形象方案的接口：从音频取 RMS / 音素时间戳 → 映射 `blink` / `mouthOpen` / `headTurn`
  → 驱动阶段 5 渲染。**纯参数传递，无推理。**
- **不得宣称**：未实测前不得写「全双工已实现」。

---

## 全局纪律（每个阶段都要遵守，计划 §10）

1. **TDD**：先写测试，保留 RED 日志，修到 GREEN，不降低生产门槛来通过测试。
2. **回执与哈希**：产物写入 `artifacts/<name>-<date>-r<n>/`，
   含 `run.json`、原始日志、输入输出 SHA256；合成/测试数据显式标注 `NOT_*`。
3. **独立审查**：每阶段完成后由独立 agent 审查，范围明确写出；未关 CRITICAL/HIGH 不得进入下一阶段。
4. **不借证据**：不以旧阶段或其他模块的通过充当前阶段的验收。
5. **不夸大**：单组通过不扩大为身份验收；未实测的性能不写数字。
