# SAM2 实际提示分割与跨语言验证

2026-10-13，独立 rig2d，版本 **0.1.3-research**。本轮接入真实 SAM2.1 tiny 桌面 CPU 推理与提示掩码桥接。彩色十二翼合成样例按部位选择优于整个人物选择，但仍漏约3%前景，线稿需要不同的处理路径。尚未进入 Android 或角色发布。

## 实现与边界

`segmentation/sam2_adapter.py` 接收有界 RGB 工作图和人工点/框声明，全部验证后一次编码、逐部位解码。最多63部位、每部位64点、最长边1536、最多4MP；正负点为原图XY像素坐标。串行锁隔离 predictor，finally释放缓存，取消防止提交而不能抢占Torch内核。输出保存不可变二值掩码、全部候选分数、选中索引及拒绝原因。空mask、接近整帧、正负点不满足或低质量明确拒绝。

`accepted` 只代表结构、提示和预测质量条件通过。预测IoU分数不是实测IoU、语义识别或身份验证，高分仍可能带入背景。SAM2为提示分割，部位类别由调用方声明。

`rig2d.parts.PromptMaskBridge` 纯JVM验证ARGB工作像素SHA、长度、二值、有限质量、稳定ID、既有19类声明与63项预算；被拒绝记录也验证，逐项复制以防数组改写和列表增长。接受项进入已有分层保全核心，未知/冲突继续留静态层。没有模型、文件导入或Android依赖。

RGB SHA为 `RigRgb-v1\0 + 大端宽高 + RGB`；JVM SHA为 `OcPartRaster-v1\0 + 大端宽高 + 大端ARGB`。两者都不同于PNG文件SHA。实际实验同时输出两者，JVM集成验证ARGB来源。精确重组证明选中前景无损，不能证明漏分的真实前景已保留。

## 模型与许可

固定代码commit `2b90b9f5ceec907a1c18123530e92e794ad901a4`，配置 `configs/sam2.1/sam2.1_hiera_t.yaml`。官方代码与checkpoint为Apache2.0，可选cc_torch有独立BSD条款，本轮未构建。依据：[官方许可](https://github.com/facebookresearch/sam2#license)、[固定predictor](https://github.com/facebookresearch/sam2/blob/2b90b9f5ceec907a1c18123530e92e794ad901a4/sam2/sam2_image_predictor.py)、[官方模型镜像](https://huggingface.co/facebook/sam2.1-hiera-tiny)。

- checkpoint156,008,466B，SHA `7402e0d864fa82708a20fbd15bc84245c2f26dff0eb43a4b5b93452deb34be69`，已下载且与官方LFS SHA一致。
- 固定源码ZIP55,952,852B，SHA `fe93082a71a885a427894b1eab76341768781b6b58a298a0717e03862097d137`。
- 初次解包拒绝4个旧配置symlink，失败保留。`source-r2`仅提取所需普通文件、跳过别名；不是将失败改称成功。
- 项目venv复用已有Torch2.12.0+cpu，仅在项目内增加Hydra/iopath/psutil；没有升级全局Torch。权重未进入JAR或手机assets。

## 样本与评分

三张工作图均512²。NASA astronaut为已安装scikit-image公共领域样本，没有像素GT，只作定性观察：[来源](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut)。另两张为自有十二翼/武器/发丝合成样例叠加复杂背景，不是真实OC数据集。

彩图GT是73,216个alpha非零前景像素；线稿GT是14,892个可见墨迹像素，不是轮廓内填充区域。模型填充物体和墨迹GT口径不同，不能用线稿低IoU冒充真实人物分割准确率，但当前输出不适合直接墨迹提层。

点/框由GT推导以模拟理想人工提示（oracle），GT掩码只评分，未传给模型。这不是自动定位评估。quick为2/4/4提示，full为2/18/18。

## 真实CPU结果

Python3.13.5、TorchCPU、计算2线程/inter-op1线程。耗时含每张图编码和全部提示解码，不含模型加载和写盘；每轮只测一次，不是手机/GPU性能或稳定延迟分位数。

| 样本 | quick接受数 / 耗时 | full接受数 / 耗时 |
| --- | --- | --- |
| astronaut | 2/2，1.900s | 2/2，1.905s |
| 彩色十二翼 | 4/4，1.948s | 16/18，2.575s |
| 线稿十二翼 | 2/4，1.888s | 4/18，2.554s |

quick全轮6.841s、加载0.663s、进程峰值WorkingSet1,139,462,144B；full全轮9.102s、加载0.466s、峰值1,148,817,408B。初次因缺psutil在模型执行前失败，补项目依赖后两轮实际exit0。失败日志仍保留。

使用已保存full掩码进行并集A/B，未重复推理：

| GT / 策略 | 召回 | 精确率 | IoU | 漏前景 / 混入背景 |
| --- | --- | --- | --- | --- |
| 彩图：含粗主体 | 99.608% | 38.566% | 38.507% | 287 / 116,174 |
| 彩图：仅部位 | **97.003%** | **98.272%** | **95.376%** | **2,194 / 1,249** |
| 线稿墨迹：含粗主体 | 99.423% | 7.229% | 7.226% | 86 / 190,011 |
| 线稿墨迹：仅部位 | 15.666% | 18.748% | 9.331% | 12,559 / 10,111 |

彩图逐部位有价值，但两个翼实例02/08未过质量门槛，仍漏细节，不能声称十二翼自动完整分层。quick翼12实测IoU87.599%、脸97.941%、武器40.361%；预测高分不能代替实际质量。没有降低原门槛，粗主体与线稿当前都不作为默认发布方案。

## 测试与审查

- adapter stub11方法真实RED→GREEN。独审发现regions类型P2，新增真实13方法RED（2errors）→类型门禁→13GREEN。最终有效stdlib trace包括导入/执行，140/150可执行行（93.333%），无分支覆盖声明。r1收集器None sentinel失败、r2遗漏导入导致低估，均保留，最终只采用r3覆盖。
- bridge stub编译成功后21/21业务RED→同21GREEN；3个集成读取实际模型输出，来源ARGB及选中像素重组一致。
- **统一179 JVM /17suites，0failure/error/skip**；新桥接39/39行、70/70分支。全库1023/1100行（93.000%）、1007/1337分支（75.318%），未达到全库分支80%。两个桌面demo排除在覆盖与JAR外。
- 根代理全部tools **34/34**，adapter独审 **13/13**；cases generator独立有效行216/231（93.506%）。已有probe30源未改、沿用历史验证；未重跑旧长测。
- 独立审查未发现未关闭CRITICAL/HIGH；重跑13adapter测试，并读取根代理JVM XML、重算6实际案例及4并集A/B。没有代跑模型/Gradle。见 `artifacts/independent-sam2-review-2026-10-13.md`。

JAR `build/libs/rig2d-0.1.3-research.jar`，256,326B，SHA `2e8c885dbf3d493aef86329f42d0791a2d0422b95f8a50ccbaea8553c2f6a9a6`。旧0.1.2路径在本轮bridge Gradle测试时已重生成，原SHA仅为历史，不称旧路径仍为原二进制。v1三源/golden未改。

证据目录：`artifacts/sam2-model-eval-2026-10-13-r2/`、`sam2-model-eval-2026-10-13-r3-full/`、`sam2-cases-2026-10-13-r1/`；A/B为 `sam2-union-ab-2026-10-13-r1.json`。`sam2-model-eval-2026-10-13-r2-script.py`是固定科学实验驱动，不是交付的任意资源包导入器。源、证据SHA与覆盖归档在最新run回执。

## 第一未完成项

逐部位提示细化、漏翼/细丝保护、线稿墨迹路径及许可明确的真实OC评估，随后才是层序/网格/隐藏内容、多视角身份、动态露缝和渲染发布。完整Live2D未完成。

语音核心仍需真实设备、ASR/TTS/AEC、连续打断和手机延迟验证。本轮没有USB、真实录音、付费API或私人素材操作；知语Android/APK0.2.6、HEAD11b17c7只读，RAG未改。
