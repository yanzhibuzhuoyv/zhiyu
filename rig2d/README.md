# rig2d — 参数化 2D 通话形象：契约与引擎

## 2026-10-14 手动修正已完善

用户确定采用“自动建议＋用户修正”。电脑工坊新增撤销／重做、原图对照、附件删除恢复、保存锁定与失败保留；未完成整体轮廓会提示处理后再保存。52 Node与12项新浏览器检查、11项原流程回归通过；详见[使用与验证](docs/MANUAL_CORRECTION_2026-10-14.md)。自动准确率沿用此前实测，不以完全自动生成作为当前电脑工具完成门槛。Android接入和真实全双工仍待后续。

## 2026-10-13 最新状态（优先于下方历史）

**2026-10-14 /0.2.0-studio：可操作的前视角动态角色工坊已完成验证。** 双击根目录 `启动角色工坊.cmd`，本机 http://127.0.0.1:8808/ 可导入/人工修正眼口及附件、真实WebGL动画/本地音频口型、保存下载及重开。用户15张ZIP图+2附件均完成资产闭环；中性0像素差、4352组极限姿态无翻转。220 JVM/58 Studio Python/37 Node及真实浏览器流程通过。自动定位和复杂图分割仍未准确（14比例回退、误检、浅线/飘带漏分），初次导入保持原图待确认；不称自动OC/侧脸/手机通话已验收。最新 [实图与工坊报告](docs/LIVE2D_STUDIO_2026-10-13.md) 优先于下方研究历史。新JAR `build/libs/rig2d-0.2.0-studio.jar` 只含可移植核心，不包含Python/Web/图像模型。

细节研究最新：新增RGB种子笔画提取与分离的不确定小片段，**181 JVM /29分割Python GREEN**。合成线稿在原raw模型范围内召回84.300%、FP0；整帧92.452%只是诊断，浅色/范围外细节仍漏。纠正点三方案实测退化，未采用、门槛不降。所有输出仍需检查，不当自动OC身份或完整Live2D。见[细节与线稿报告](docs/DETAIL_REFINEMENT_2026-10-13.md)。JVM主库/JAR仍0.1.3，Python工具另在segmentation；下面179项是上轮历史。

当前版本 **0.1.3-research**：SAM2.1 tiny 已在电脑CPU实际推理，新增提示适配器与纯JVM来源桥接；**179 JVM /34工具 /13适配器测试通过**。彩色十二翼合成逐部位IoU95.376%、召回97.003%，仍漏2,194像素；粗主体易带背景，线稿墨迹路径未达要求。点框由GT推导，不能当自动OC识别或Live2D发布验收。当前JAR为 `build/libs/rig2d-0.1.3-research.jar`，详见[真实模型报告](docs/SAM2_PROMPT_ADAPTER_2026-10-13.md)。以下0.1.2及更早为历史；旧0.1.2路径已在本轮构建重生成，原SHA只留历史回执。

本轮继续完成原前景保全与分层核心，版本 **0.1.2-research**：**155 JVM /26工具 Python通过**，已有30 probe Python不重跑。十二翼合成实例实际重组后，18,304原前景像素全部保留，补回872模拟subject遗漏；半透明ARGB逐像素一致。未知/冲突留静态层，不伪装成语义识别。新分层核心行99.16%、分支85.16%。[当前分层验证](docs/PART_PRESERVATION_2026-10-13.md)优先于以下上轮132项记录；库不含桌面fixture drivers，手机通话/完整Live2D仍待后续。

本轮独立审查修复 v2 契约、投影/补全探针错误，并新增 `rig2d.voice` 可移植语音协调核心。**132 项 JVM、30 项 probe Python、18 项研究工具测试通过**；核心 JaCoCo 行覆盖率 91.93%，分支覆盖率 71.98%。v1 冻结源码与 golden 保留，知语 Android 没有修改。

全双工核心可运行合成复演：持续采集、VAD 自动端点、流式 token 按句分块合成与播放、用户开口打断、旧回调隔离、停止释放。**不是已经接好真实麦克风/ASR/TTS/AEC 的手机通话**。

```powershell
./gradlew.bat test jacocoTestReport jar
./gradlew.bat duplexFixture
python -m unittest discover -s probe/tests -v
python -m unittest discover -s tools/tests -v
```

默认彩图、线稿的完整新探针均被正面空洞硬检查拦截。轮廓候选的完整投影改善到0.3242%，通过几何研究检查，但固定原头框的完整工作图前景保留只有94.9687%，漏掉7,345像素，已否决为默认人物方案。线稿还缺笔画拓扑判据，**不能进入完整头像发布或据此承诺 ±90° / OC 身份稳定**。详见 [轮廓复核](docs/CONTOUR_STUDY_2026-10-13.md)。

[此前修复、测试与剩余问题](docs/REVIEW_AND_VOICE_CORE_2026-10-13.md)，[后续安卓接入说明](docs/VOICE_PORTING_2026-10-13.md)。历史库 `build/libs/rig2d-0.1.2-research.jar`；当前产物以上方0.1.3为准。依赖coroutines1.10.2，不包含语音模型或设备适配器。

**以下为 2026-10-12 历史说明，当前结论以本节及新回执为准。**

本仓库是 [ON_DEVICE_2D_CALL_AVATAR_PLAN_2026-10-12.md](../../ON_DEVICE_2D_CALL_AVATAR_PLAN_2026-10-12.md)
（实施计划）与 [ON_DEVICE_2D_CALL_AVATAR_2026-10-12.md](../../ON_DEVICE_2D_CALL_AVATAR_2026-10-12.md)
（设计文档）的**独立实现仓库**。

它是**独立成立的**：`android/` 里的 知语 项目**只读**，本仓库不改它任何文件。
后续由 codex 按 [docs/PORTING.md](docs/PORTING.md) 把这里的文件搬进去。

---

## 这是什么、不是什么

| 是 | 不是 |
| --- | --- |
| schema v2 契约（分层多视角资产）的**可编译、可测试**实现 | 产品功能；没有用户可见入口 |
| v1 逐字节回归的**宿主端**门禁（纯 JVM，无需 Android） | 真机像素级回归（那需要 androidTest，见 PORTING） |
| 视角混合、margin 充足性的**可判定**规则 | 「支持 ±90°」的证明（那是 P0 探针的门禁结论） |
| P0 探针的 Python 测量管线 + 回执 | 修复模型（C 级）的质量评估 |

**本仓库当前状态**：阶段 0 完成（95 个 JVM 测试全绿）+ P0 探针管线跑通（取景缺陷已修）。

> ## ⚠️ 当前回执状态（2026-10-12 更新）
>
> **`artifacts/` 下现有的两份探针回执（合成、真实线稿）均已作废**，目录内有 `INVALID_RECEIPT.txt`。
> 原因：探针存在一处**几何与纹理坐标系错位**（D1）与一处**轴序互换**（D2），
> 使 `hole_ratio` 的分子分母双双落在错误区域，比值无意义。诊断见
> [docs/FRAMING_DEFECT_2026-10-12.md](docs/FRAMING_DEFECT_2026-10-12.md)，修复回执见
> [docs/FRAMING_FIX_RECEIPT_2026-10-12.md](docs/FRAMING_FIX_RECEIPT_2026-10-12.md)。
>
> - D1/D2 **已修**，并新增 4 项取景硬自检（修复过程中它们又抓出 2 个新缺陷）。
> - 两份旧回执保留为 RED 证据（纪律要求）。
> - **尚未做**：真实彩图复跑、真实线稿复跑、gate 按 profile 分流（线稿 ±45°）、
>   `front_hole_near_zero` 判定口径重定。**在完成前不得声称「双 profile 测量管线已就绪」。**
> - **不得**用任何旧回执的数字支撑「(c) 可行」「进入阶段 1」或任何门禁结论。

---

## 快速开始

```bash
# 契约与规则：95 个 JVM 测试
./gradlew test

# P0 探针：合成夹具（零许可/隐私暴露），约 70 秒
python probe/run_probe.py --out artifacts/rig-probe-2026-10-12 \
    --resolution 512 --grid 128 --texture 768 --supersample 2

# P0 探针：真实角色图（不得取自私聊/用户媒体库）
python probe/run_probe.py --image path/to/character.png --out artifacts/rig-probe-real-<date>
```

环境：JDK 17+（本机为 23，`jvmTarget` 固定 17）、Python 3.11+ 与 `numpy`、`Pillow`。
**不需要 Android SDK、不需要 NDK、不需要真机、不需要下载任何模型权重。**

---

## 目录结构

```
rig2d/
├── build.gradle.kts                  Kotlin/JVM，jvmTarget 17，仅依赖 JUnit4（测试）
├── src/main/kotlin/rig2d/domain/     契约与规则（阶段 0 的全部产出）
│   ├── OcIdentityV1.kt               v1 契约**冻结副本** + canonical 序列化（硬门禁参照物）
│   ├── OcRigVerticesV1.kt            v1 网格生成冻结副本（阶段 2 要复用的那条路径）
│   ├── OcFlatRendererV1.kt           v1 渲染变换的宿主端等价移植（逐字节回归的测量仪器）
│   ├── OcRigAssetTypes.kt            v2 数据结构：OcRigAsset / OcViewLayers / OcLayerAsset / OcProxy
│   ├── OcRigAsset.kt                 v2 校验规则 + 视角带常量 + 网格契约
│   ├── OcViewBlendRules.kt           视角混合权重、margin 充足性（§5.3 硬门禁）、视差
│   ├── OcIdentityV2.kt               schema 分派、route 扩展、packageSha v1/v2 前缀隔离
│   └── MotionSemantics.kt            headTurn 的 v1/v2 解释分界
├── src/test/kotlin/rig2d/domain/     95 个测试，见下表
├── src/test/resources/golden/        v1 逐字节回归的期望值（含 canonical 明文）
├── probe/                            P0 探针（Python）
│   ├── headproxy.py                  代理几何 + 许可核查回执
│   ├── project.py                    纹理反投影 + 多视角渲染 + 相机基
│   ├── fill.py                       B 级块匹配（含链式距离限制）
│   ├── synthetic.py                  合成夹具（默认输入，零许可暴露）
│   └── run_probe.py                  入口 + 门禁判定 + 自检
├── artifacts/                        回执（RED 日志、probe.json、图册）
└── docs/                             契约说明 / 移植指南 / 许可边界 / 探针说明
```

---

## 测试矩阵（95 个，全绿）

> **文件归属更正**（T0，来自 [NEXT_DUAL_INPUT](docs/NEXT_DUAL_INPUT_2026-10-12.md) §0.3-问题7）：
> `OcLegacyRigV1Test` 与 `OcPackageShaV2Test` 在**同一个文件** `OcLegacyRigV1Test.kt` 里
> （该文件有 18 个 `@Test` = 10 + 8）。下表按**测试类**分行，不是按文件分行。

| 测试类 | 用例 | 所在文件 | 覆盖 | 计划条款 |
| --- | --- | --- | --- | --- |
| `OcRigAssetRulesTest` | 35 | `OcRigAssetRulesTest.kt` | 越界 yaw、缺层、重复 viewId、z 冲突、bounds 越界、层数/视角数上限、网格顶点契约、对称平面归一化 → 全部拒绝 | §2.3 |
| `OcViewBlendRulesTest` | 17 | `OcViewBlendRulesTest.kt` | 0/±45/±90 退化单视角、交叉点权重和=1、单调性、连续性、带宽外拒绝 | §2.3 |
| `MarginAdequacyTest` | 16 | `MarginAdequacyTest.kt` | margin 充足性判定、视差折算、写回修复、边界值 | §5.6 / §5.3 |
| `OcLegacyRigV1Test` | 10 | `OcLegacyRigV1Test.kt` | **v1 逐字节回归**（canonical / packageSha / 渲染变换 / 顶点缓冲） | §2.4 硬门禁 |
| `OcPackageShaV2Test` | 8 | **同上文件**第二个类 | v1/v2 前缀隔离、等价重排不变、任一字段改动必变哈希 | §2.3 |
| `BlendAnchorRegressionTest` | 6 | `BlendAnchorRegressionTest.kt` | 交叉点与端点的定点回归（RED 暴露的角度） | §2.3 |
| `RedEvidenceTest` | 2 | `RedEvidenceTest.kt` | 初版缺陷的可复跑物证 | §10.1 |
| `GoldenGeneratorTest` | 1 | `GoldenGeneratorTest.kt` | golden 值生成器（人工运行，不从新代码采样） | §10.2 |

合计 **95** 个用例、**8** 个 suite（JUnit 按类计 suite）。

---

## 回执与纪律

产物写入 `artifacts/<name>-<date>-r<n>/`，含 `run.json`／`probe.json`、原始日志、输入输出 SHA256。
合成/测试数据显式标注 `NOT_*`：

| 文件 | 内容 |
| --- | --- |
| `artifacts/stage0-red-2026-10-12.log` | 阶段 0 的 RED 日志：3 个真实缺陷 + 3 处测试自身错误 + golden 校准 |
| `artifacts/stage0-green-2026-10-12.log` | 阶段 0 的 GREEN 运行日志（含 `RedEvidenceTest` 的对照输出） |
| `artifacts/probe-red-and-fixes-2026-10-12.log` | **P0 探针自身**被修掉的 6 个缺陷（含两次会产生假结论的错误） |
| `artifacts/rig-probe-2026-10-12/` | 探针回执：`probe.json`、五视角图、空洞掩码、目视图册、`NO_PRODUCT_CLAIM.txt` |

**不得宣称**（计划 §2.5、§5.7、§7.5、§9.5 已立的界，本仓库一并遵守）：

- 契约就位 ≠「支持 ±90°」。契约只是让后续阶段有可能。
- 合成结果 ≠「原图逐像素相等」。
- 透明 PNG 分层 ≠ Live2D。
- 单组视角通过 ≠ OC 身份验收。
- 未实测的性能不写数字；探针结论不得写成产品能力（`NO_PRODUCT_CLAIM`）。

---

## 下一步

**当前优先级是「先让测量可信」，不是往前推进度**（见 [NEXT_DUAL_INPUT](docs/NEXT_DUAL_INPUT_2026-10-12.md) §0.3 与 §6 的 T 编号）。

| # | 任务 | 状态 |
| --- | --- | --- |
| T0 | 修文档过期与断链 | **已完成**：链接实际可解析（`NEXT_DUAL_INPUT` §0.3-问题6 已被其 §0.2 更正）；测试矩阵文件归属已更正；`run.json` 已更新为实测状态 |
| T1 | 查清并修取景 + 加取景自检 | **已完成**（4 项 framing 自检，见 [FRAMING_FIX_RECEIPT](docs/FRAMING_FIX_RECEIPT_2026-10-12.md)） |
| T1d/T1e | 用形状判据替换尺寸上限 | **已完成**（见 [VERIFY_RESPONSE](docs/VERIFY_RESPONSE_2026-10-12.md) §1） |
| T1f | 解释合成 front 0.0769 的成因 | **已完成**：量化诊断为 `GEOMETRIC_FIT`（99.8% 空洞对应背景像素），见 VERIFY_RESPONSE §4 |
| T2 | 重标 `front_hole_near_zero` 等阈值 | **未定**，等决策（方案甲/乙/丙） |
| T3 | 真实**彩图**复跑（走自动头框） | **未做**——需要一张正面彩色角色图 |
| T4 | 新增合成**线稿**夹具 + 线稿判据 | **未做**（当前线稿无可信对照物） |
| T5 | 真实线稿复跑 | 已跑（人工头框），但 T4 未做前缺对照物 |
| T7 | 线稿分割方案调研 | 可立即并行 |
| T8 | 彩图分割许可核实（CelebAMask-HQ） | 可立即并行，**当前最长前置项**（可能触发自训，周级） |
| T9 | 启动阶段 1 | 需 T6 + T7 + T8 全部有结论 |

移植顺序与环境准备见 [docs/PORTING.md](docs/PORTING.md)，
分阶段任务地图见 [docs/TODO_STAGED.md](docs/TODO_STAGED.md)。
