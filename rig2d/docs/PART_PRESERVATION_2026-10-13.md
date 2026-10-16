# 原前景保全与分层核心验证

本轮完成独立纯 JVM 分层核心和可运行的十二翼桌面示例。新增源码在 `src/main/kotlin/rig2d/parts/`，库为 `build/libs/rig2d-0.1.2-research.jar`。知语 Android、现有 v1/golden、v2 schema、聊天和 Key 未修改。

本模块处理**已经提供的前景和部位掩码**，不执行分割模型推理；既不保证自动识别正确，也不宣布 Live2D 或手机通话可用。阶段1现为部分完成，仍需实际分割模型/交互选区与真实 OC 验收。

## 行为与接口

| 文件 | 已完成行为 |
|---|---|
| `PixelMask.kt` | 不可变压位 mask；布尔/编号/有限 confidence 输入；并交差不删小连通分量；最长边1536、最多4MP；尺寸与资源先校验 |
| `PartRaster.kt` | 保存原 straight ARGB 副本，输出也复制；保留半透明像素；hash 包含尺寸与全部ARGB；这是工作像素hash，不能充当原PNG文件SHA |
| `PartLayerAssembler.kt` | 稳定实例ID与现有19类kind；同类可有多个实例，不限定双翼；最多63候选，为静态保留层预留第64层预算 |
| `parts/demo/PartPreservationFixture.kt` | 只接受固定SHA合成源，桌面读取并重组PNG与分层PNG，不是用户媒体导入器或模型适配器 |

入口为 `PartLayerAssembler.assemble(source, subject, protected, proposals, minimumConfidence=.8f)`。有效前景是 `(subject ∪ protected) ∩ 原图非零alpha`。模型漏掉的翼或武器可由明确保留掩码补回；两份输入掩码都没包含的真实部位仍可能遗漏，所以**原图只读保留，真实自动背景分割仍须单独验收**。

语义候选先与有效前景求交。低置信度、未标注前景和多个合格候选重叠的像素进入静态保留层；冲突不随候选顺序改变，也不猜测应归哪层。`recomposeArgb()` 验证各层不重叠且并集完整，然后从原图读取原ARGB；没有生成隐藏像素或猜侧脸。`reviewRequired=false` 只表示此草案没有剩余待分类像素，**不是身份验证或发布批准**；`identityVerified` 固定false。

## 十二翼跨语言示例

另一个代理用 Python 标准库生成固定256×256合成源：12个不同颜色的翼实例、头发/发丝、身体、单武器、1像素alpha=1装饰。模拟subject故意遗漏第12翼和武器尖；语义候选只有11翼，还存在两个冲突像素、部分漏分头发。

根代理的 JVM 实际读入这些二进制ARGB/mask，使用分层核心后输出：

| 指标 | 实际结果 |
|---|---:|
| 原前景 | 18,304像素 |
| 模拟subject遗漏 /补回 | 872 /872 |
| 最终遗漏前景 | **0** |
| 合格非冲突语义层 | 16 |
| 静态保留像素 | 913 |
| 冲突像素（保留层中的子集） | 2 |
| 完整覆盖的原翼mask | **12** |
| 保留的半透明像素 | 163 |
| 重组PNG与原PNG解码ARGB | **逐像素完全一致** |

第12翼没有被假标注成一个已识别实例，而是完整保留在静态层。这证明保全机制和重组正确，不能用来证明模型自动识别了十二翼。示例产物在 `artifacts/parts-host-2026-10-13-r1/`，`result.json` 明示 `SYNTHETIC_NOT_MODEL_NOT_REAL_OC`、identity false、Live2D ready false。源与mask的hash在 `input-shas.properties`；原fixture metadata也记录完整文件SHA。

复演需指定一个尚不存在的新输出目录：

```powershell
./gradlew.bat partsFixture -PpartsOut=artifacts/parts-host-new-run
```

示例类使用桌面 ImageIO，**不包含在0.1.2可移植JAR中**；语音合成driver也排除。真实Android适配应只移植核心，不能拷入fixture或Java桌面类。

## 实际 TDD 与覆盖

实施来源：[本轮方案](PART_PRESERVATION_2026-10-13.plan.md)，源于上一轮人物轮廓候选遗漏的问题。

| 步骤 | 实际证据 |
|---|---|
| 核心stub RED | `parts-red-2026-10-13-r1.log`：15项全部业务失败，编译成功，PARTS_RED |
| 初核心GREEN | `parts-green-2026-10-13-r1.log`：同15项全部通过 |
| 真实二进制集成 | `parts-fixture-green-2026-10-13-r1.log`：15+4项通过；含1536²最大frame边缘alpha=1保留 |
| 列表快照资源边界RED | `parts-resource-red-2026-10-13-r1.log`：18项中1失败；调用方List增长可绕过预快照上限 |
| 资源修复/示例stub RED | `parts-replay-red-2026-10-13-r1.log`：18核心项GREEN、1示例未实现失败；改为有界逐项快照，在读取第64项前拒绝 |
| 实际桌面示例GREEN | `parts-replay-green-2026-10-13-r1.log`：23项分层相关通过，partsFixture成功写出PNG/分层/回执，PNG解码精确对照 |
| 最终统一构建 | `parts-integrated-green-2026-10-13-r1.log`：**155项/15 suites，0失败/错误/跳过**；test、coverage、jar成功 |
| Python夹具 RED→GREEN | `parts-fixture-python-red/green-2026-10-13-r1.log`：8项先1failure+7error，后8通过；原stub保留 |
| 根代理工具独立复跑 | `parts-tools-independent-green-2026-10-13-r1.log`：**26项全部通过**，3.575秒 |

JaCoCo：新分层核心行 **99.16%（118/119）**、分支 **85.16%（155/182）**；整个库行92.74%、分支73.88%。只排除两类纯host合成driver，没有排除核心错误路径。Python夹具作者标准库行追踪94.17%（194/206），子进程CLI不计入该行追踪，但其行为有真实subprocess测试。旧probe Python30项此前通过，未改旧probe或重跑耗时投影/长测。

目录不是Git，因此没有虚构提交或初始化Git；stub、资源修复前快照、逐次RED/GREEN、最终源码/测试/产物SHA保留。独审范围见 `artifacts/independent-parts-review-2026-10-13.md`。

## 后续未完成

需要接实际、许可明确的模型或交互提示，产生可确认的主体/部件mask，并用复杂背景、真人/非真人、线稿、细翼/武器等样本评估。随后才是层序/网格、隐藏部位补全、多视角一致性、动态露缝、真实角色渲染和原子发布。静态PNG分层不等于Live2D。

声学设备adapter、实际ASR/TTS/网络、AEC及手机端打断/时延仍未验收。已有语音核心通过155项完整回归中的既有测试，本轮没有把它迁移到知语或出新APK。
