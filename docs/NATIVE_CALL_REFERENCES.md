# 原生 OC 通话与流式记忆：参考项目核查

核查日期：2026-10-09。本文是研究与移植方案，不代表实时通话、自动 Live2D 建模或论文速度已经在知语实现。只下载、阅读官方源码与论文，没有安装或执行上游程序，没有接触用户 Key、私聊数据或手机。

## 1. 项目身份与版本

| 用户所说名称 | 查到的一手资料 | 本次结论 |
| --- | --- | --- |
| pinch-portal-ar | [rison114514/pinch-portal-ar](https://github.com/rison114514/pinch-portal-ar) | 名称完全吻合。实现是手势 AR 与人物前景特效，不是 Live2D 自动建模器。 |
| VoiceMen / “论文里记忆检索 134 ms” | [VoiceMem 官方仓库](https://github.com/xzf-thu/VoiceMem)、[作者项目主页](https://xzf-thu.github.io/VoiceMem/)、[arXiv:2608.26005v1](https://arxiv.org/abs/2608.26005v1) | VoiceMem 与数字、研究主题高度吻合，但用户尚未明确确认项目拼写。以候选资料研究，不能把 VoiceMen 自动改名并视为已确认。 |

源码固定到以下提交，所有下载文本按 GitHub tree 的 blob SHA-1 校验，并记录 SHA-256；没有跟随可变的 main 内容作实现依据。

| 项目 | 固定提交 | 本地研究副本 |
| --- | --- | --- |
| pinch-portal-ar | `5761224c96d4d1011e07842d5bd91511aff77590`，提交时间 2026-08-04 | `artifacts/reference-projects/pinch-portal-ar-source/`，5 个文本文件 |
| VoiceMem | `6cacb3c1e7fca2679f3bf95451f6b480efd9a783`，提交时间 2026-09-14 | `artifacts/reference-projects/VoiceMem-source/`，136 个文本文件 |

各副本的 `research_manifest.json` 保存来源、提交与文件哈希。论文 PDF 来自 arXiv；PDF 第 6、10 页已渲染并目视检查，分别对应 Figure 3 流式时间线、Figure 5 与 §5.4 的时间/成本/准确率分析。副本与图片均在 Git 忽略的研究目录内。

## 2. 134 ms 到底测了什么

论文 §3.3 的原句是：

> the dense dual-brain retrieval itself costs only 134 ms

此句说的是记忆检索。§5.4 / Figure 5 的对应实验是 LoCoMo，K=5，报告 430 个记忆 token 与 91.2 的准确率；不是模型首字、完整答案、声音或视频的端到端耗时。论文把 query embedding 放在预备阶段，把后端搜索放在后续阶段。没有披露该数字对应的 CPU/GPU 型号、完整索引条数或均值/中位数口径，不能承诺红米同样达到 134 ms。[论文 §3.3 / §5.4](https://arxiv.org/html/2608.26005v1)

| 要核对的口径 | 一手证据与边界 |
| --- | --- |
| 是否包含建库与写记忆 | 当前评测代码先灌完整对话；搜索单独计时。建库耗时另计。见 [evaluation/run.py:87–110](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/evaluation/run.py#L87-L110)。 |
| 是否包含 LLM 回答、裁判与 TTS | 该计时截止于 `vm.search` 返回；构建上下文、调用回答模型与评分在计时后。未测 TTS。见 [run.py:107–124](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/evaluation/run.py#L107-L124)。 |
| 是否包含 embedding / 分类 | 当前 harness 计整个 `vm.search`，底层 Mem0 search 内可能执行 query embedding；但是它不传 slots/entities，也没有先调用 classify。流式预取路径会先 classify 再 search，两者不是同一计时口径。不能仅凭论文时间线断言所有 embedding 开销已排除。见 [core.py:142–146](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/core.py#L142-L146)、[embedding adapter:132–143](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/leftbrain/mem0_backend_store.py#L132-L143)。 |
| 均值还是中位数 | 当前代码汇总 `median_search_ms`，用排好序的中间元素；偶数样本取上中位数。它不能反证论文 134 ms 必定由此提交与此统计生成。见 [run.py:137–156](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/evaluation/run.py#L137-L156)。 |
| 430 token 如何复测 | 当前代码用 `len(memory)//2` 估算，并非模型 tokenizer 精确计数。知语自己的测试应记录 tokenizer/估算口径。见 [run.py:123](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/evaluation/run.py#L123)。 |
| 手机、万轮是否得到验证 | 未验证。上游环境记录包括 Python、OS、包版本、提交，但没有设备 CPU/GPU 与功耗记录。见 [run.py:43–70](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/evaluation/run.py#L43-L70)。 |

当前评测入口没有 slots/entities 时，`SearchCogGraph` 没有域候选，`Rank` 会走全库回退；不能仅运行这个入口就声称复现论文有上层路由的 134 ms 条件。见 [brain.py:357–366](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/leftbrain/brain.py#L357-L366)、[全库回退:543–545](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/leftbrain/brain.py#L543-L545)。应借鉴“用户还在说话时做准备，停顿期间完成必要检索”的设计，再对知语的完整原文库单独测试。本文不把其性能指标移作本项目测试结果。

## 3. pinch-portal-ar 能参考的部分

源码读取摄像头画面，以 MediaPipe HandLandmarker 追踪两只手，ImageSegmenter 使用 `selfie_segmenter_landscape.tflite` 分割人物前景，然后用 Canvas 2D 画实时效果。每帧检测手势，人物分割约每 110 ms 一次；任务初始化优先 GPU，失败回退。见 [page.tsx:550–620](https://github.com/rison114514/pinch-portal-ar/blob/5761224c96d4d1011e07842d5bd91511aff77590/app/page.tsx#L550-L620)、[初始化:700–745](https://github.com/rison114514/pinch-portal-ar/blob/5761224c96d4d1011e07842d5bd91511aff77590/app/page.tsx#L700-L745)。

它没有角色图层、骨架、Cubism 参数、moc3 或说话人物视频生成流程。可借鉴的只是实时渲染与检测分别限频、画面缩放、模型失败回退等思路。人物前景 mask 不等于 OC 的完整语义部件；更不能由普通人像分割推断单翼、十二只翼、武器与背景一定能正确区分。

该仓库明确没有项目级 LICENSE，原创代码保留全部权利。不能把第三方 MediaPipe 的 Apache-2.0 当成应用自身授权而复制原创效果代码入 APK。可从官方 Android MediaPipe 文档独立实现合理思路；它的 segmentation 是预定义类别，仍需合适模型及用户修正入口。见 [原创授权声明](https://github.com/rison114514/pinch-portal-ar/blob/5761224c96d4d1011e07842d5bd91511aff77590/THIRD_PARTY_NOTICES.md#L58-L60)、[官方 Android 分割指南](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter/android)。

## 4. VoiceMem 候选：可以移植哪些原理

### 4.1 候选池、实体与多条原文

源码根据语义领域和实体查关联 memory ID，并作一跳邻居扩展；默认实体池与领域池取并集，可选 strict 交集并留不足时回退。可借鉴“先找相关实体/时间/承诺，再并列给多条证据”的思路。见 [brain.py:434–483](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/leftbrain/brain.py#L434-L483)。

但固定版本的 Mem0 适配器，在有候选 ID 时设置 `fetch_k=10000`，先全库向量查询，再在客户端筛 memory ID。我们自己的 SQLite/本地向量层必须把候选 ID 筛选放到计算与排序之前，候选上限、分块与补召回可测；直接照搬会削弱随库增长的速度收益。见 [mem0_backend_store.py:388–421](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/leftbrain/mem0_backend_store.py#L388-L421)。

它另有词面、日期 overlap 与时间查询补召回，说明语义向量与精确信息可以组合；当前排序仍区分原始余弦结果与救回的条目。见 [搜索与补召回:410–452](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/leftbrain/mem0_backend_store.py#L410-L452)。知语既有 A/B 顺序实验不应因这篇资料直接改掉。

### 4.2 完整原文、不能融合的适配边界

不能把 VoiceMem 整库替换成知语的原文库。它有原子事实抽取、slot 综合摘要与冲突决策。当前源码 UPDATE 默认追加新事实，另有环境变量可开启覆盖；DELETE 仍可实际删事实。因此“它现在都融合/覆盖”也不准确。见 [voice_input.py:598–663](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/utils/common/voice_input.py#L598-L663)、[slot 摘要:332–371](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/leftbrain/memory_repository_v2.py#L332-L371)。

知语的建议约束：

1. 用户与恋人的完整原话先原样落本地库，分别保留作者、消息 ID、时间、原文 hash；向量/实体/情绪只是派生索引。
2. 后台只更新指向原文的标签、权重、热冷状态和事件关联；标签不是新的“共同原话”。冷原文可以无损压缩，检索回来的内容仍是原句。
3. 同一偏好重复表达提高关联与层级，不把三句话改写成一条。矛盾内容用不同来源与日期并列，允许最新状态标签，旧原文不被覆盖。
4. 多条原文相关时并列输出给模型，禁止模型把两个事件的时间、作者或承诺对象串错。检索不足时以自然语言表示没印象，不能断言用户从未讲过。
5. 用户特征/情绪观察与 OC 的已绑定性格分开。上游右脑的用户人格/情绪节点可以作为研究方向，不能拿它自动修改恋人既定人设。

上游 `Rank` 默认排除 assistant 记忆，只有问助手过去说什么时才放入。知语保留双方每句原话，并需要回忆恋人的承诺；移植时必须按作者和意图设计，不能把相关 AI 承诺一律滤掉。见 [brain.py:508–554](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/leftbrain/brain.py#L508-L554)。

### 4.3 边说边预取，而不等用户结束才查

当前 `VoiceStream` 默认 200 ms 预备、300 ms 确认、至少 6 个字符触发；后台 classify 与 search，并在文本改变时取消旧任务。见 [stream.py:241–243](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/stream.py#L241-L243)、[预取:295–335](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/stream.py#L295-L335)。

可迁移为 Kotlin coroutine 的独立预取槽，控制线程与 UI 不等待索引工作。需要补充两项保障：

- 每次 partial/final transcript 带 turn ID 与版本；新版本结果才准提交。取消协程不等于取消它委托到工作线程的所有计算，需要代数与数据库取消检查。
- 上游 `_confirm` 在 final 与 partial 不同时可能沿用原检索。知语遇到否定、日期、主体或事件变化必须重新核对；“我想吃苹果……不是苹果，是梨”不能直接使用苹果证据。日常分享仍保留既有按需检索路由。

上游本地 embedding 用 multilingual-e5-small，query/passage 前缀不同，归一化后计算；Python SentenceTransformer 运行不是 Android 已可用的模型部署。需另行取得合适量化模型、tokenizer、转换产物并记录向量版本，严禁把不同模型向量混算。见 [local_e5_embedder.py:23–70](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/leftbrain/local_e5_embedder.py#L23-L70)。

## 5. 语音链路、打断与本机部署

VoiceMem 仓库是 Python 记忆库与浏览器 demo；依赖 Torch/FunASR/Sherpa 等，不能直接作为纯 Kotlin Android 库导入。见 [pyproject.toml:27–50](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/pyproject.toml#L27-L50)。

demo 同时提供 `llm_tts` 与默认 `realtime` 路线。前者可以接自定义回答模型，文本增量分段交 TTS 并行合成；后者连接 OpenAI realtime。为保持用户选定的 DeepSeek，不应静默切到 OpenAI 的对话模型。适合本产品的是 **本地/独立 ASR → 手机直连已绑定 AI → 独立 TTS → 本地角色动画**；并行收音、播放和打断是工程双向音频，不能等同于 DeepSeek 模型已经原生全双工思考。见 [模式:run.py:18–24](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/web/run.py#L18-L24)、[自定义 reply](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/core.py#L37-L47)、[流式 TTS:400–440](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/voicemem/tts.py#L400-L440)。

| 环节 | 应借鉴与原生实现要求 |
| --- | --- |
| 同时听说 | 浏览器 AEC/noiseSuppression，默认可不闭麦。Android 用 AudioRecord/AudioTrack 与 VOICE_COMMUNICATION 音频用途；检查 AEC 可用性，耳机/扬声器分别测。不能只靠原文字符串相似认回声。参考 [voicemem.html:1777–1793](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/web/voicemem.html#L1777-L1793)、[Android AEC](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler)。 |
| 真实播放进度 | 发送/生成完成不等于用户听完。记录输出 ID 与实际播放 samples，插话立即取消生成、清播放队列，存用户实际听见的片段及中断标记；不把未播完承诺视作已说。上游已有这一思路，见 [run.py:2127–2191](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/web/run.py#L2127-L2191)。 |
| 插话与附和 | VAD 先标疑似人声，回声/“嗯”等附和与实质新内容分开；持续音频与最终 ASR 共同确认。强制停止随时响应，不能等长回答完。见 [run.py:1939–1979](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/web/run.py#L1939-L1979)。 |
| 手机 ASR | Sherpa 官方有本地 Android 实时识别路径；引擎 Apache-2.0，不代表所有模型/声音同许可。系统 SpeechRecognizer 官方说明不用于连续识别，可做能力存在时的初步体验，但不能将其重启循环包装成已完成持续全双工。参考 [Sherpa Android](https://k2-fsa.github.io/sherpa/onnx/android/index.html)、[引擎 LICENSE](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE)、[Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)。 |
| Key 与资源 | DeepSeek Key 继续只给绑定的对话 API。云 ASR/TTS 若选用，需要对应供应商授权与独立凭据；本地 ASR/TTS 则需要实际模型文件、声音授权与设备性能验证，不能假设 DeepSeek Key 兼容它们。 |
| 可观测性 | 只记录分阶段毫秒/字节/结果状态，不照搬上游输出私聊前 24 字与记忆片段的调试日志。真实资料不进入开发性能日志。 |

上游 Apache-2.0 允许在条款下复制、修改、分发，需随分发保留许可证与相关声明，并标注修改；下载的模型、音色及依赖各自核对。[VoiceMem LICENSE](https://github.com/xzf-thu/VoiceMem/blob/6cacb3c1e7fca2679f3bf95451f6b480efd9a783/LICENSE)

## 6. 每个用户 OC 图片生成自己的模型

### 6.1 真正 Cubism 的资产边界

Cubism 官方明确要求把素材拆成眼睫、眼球、轮廓等部件，并补画运动后露出的部分。Editor 导出 `.moc3`、`.model3.json` 和纹理；SDK 读取这些既有模型并更新参数。官方 Java 样例可 Android 运行，Core 库需另从官网下载，不在 GitHub 仓库内。参考 [分层与补画](https://docs.live2d.com/en/cubism-editor-manual/divide-the-material/)、[Editor 导出](https://docs.live2d.com/en/cubism-editor-manual/export-moc3-motion3-files/)、[SDK 模型载入](https://docs.live2d.com/en/cubism-sdk-manual/model/)、[Java 官方样例](https://github.com/Live2D/CubismJavaSamples)。

本次核查没有找到 runtime SDK 从任意 PNG 自动产生 `.moc3` 的公开能力。`CubismMoc::Create` 创建的是已加载 moc 字节对应的运行对象，不是图像建模。Editor External API 是与运行中的 Editor 通信、读写参数和订阅事件；`NotifyMocFileExported` 是导出通知，不能据此宣称它能无 Editor 自动建模/编译。[External API 列表](https://docs.live2d.com/en/cubism-editor-manual/external-application-integration-api-list/)

如果坚持真正 Cubism，应一次在受授权的外部制作流程得到完整 rig 和 Editor 输出的模型包，再下载到手机本地渲染；其制作服务/工具链尚未接入。手机不需要依赖用户电脑常开，这也不意味着重度模型制作已经能在任意手机完全离线执行。

### 6.2 可自主实现的分层 2D 路线

可以设计每个用户 OC 独有的透明分层 PNG、网格与参数配置，手机用 Canvas/OpenGL 做网格形变、眼口切换、呼吸与头发/翼部位轻微运动。Android 自身提供 `drawBitmapMesh` / `drawVertices` 渲染基础，但 API 不提供角色理解、遮挡补画或 rig 自动生成；这些仍是我们需实现和验证的工作。此路线应称“分层 2D 动态 OC”，不能把摆动整张 PNG 叫已完成 Cubism Live2D。[Android Canvas](https://developer.android.com/reference/android/graphics/Canvas)

两条路线都以**该用户原始 OC**作为身份依据，不能换成通用女孩/男孩模板。建议资产协议与流程如下，属于本项目设计建议：

1. 本地保留原始图、`reference_sha256` 与不可变 OC 绑定 ID；选择图片不改 Key、性格或已有聊天。
2. 用户标注需保留的额外部件；`components[]` 用开放的部件 ID、角色关联、层级、遮罩和父节点，不写死“两只翅膀”。允许一只、十二只翼、尾巴、角、武器、衣饰；有歧义时预览确认。多角色时明确主 OC。
3. 去背景仅产生完整 OC 前景遮罩；随后才拆分脸/眼/嘴/头发/身体和自定义部件。翅膀/武器被遮挡或出框时，不凭空宣称重建正确。遮挡补画必须核对原始身份及部件数。
4. 生成任务绑定原图 SHA 与任务 ID；可取消/重试，记录来源与版本。一次外部生成只在用户授权导入制作时传图到已选 HTTPS 服务；不需要自托管电脑后端。未配置服务时明确说明缺少制作能力。
5. 模型包导入验证路径穿越、文件类型、解压总量/文件数、图像像素总量、浮点有限值、参数边界、层级环与部件数量。包内 manifest 必须匹配当前 OC 的原图 SHA；失败保留原图，不静默套别人模型。
6. 本地眨眼与口型由**实际播放音频**驱动；模型首帧、音频播放时钟一致。暂停/取消/挂断同步停止嘴部动作。说话生成快于播放时仍只显示当前声音对应口型。

SDK 样例/Framework、Core 与示例角色各有不同授权。官方 AI/Chatbot 发布页面还要求判断是否属于 Expandable Applications；用户可导入多种模型的应用不能未经分类就假设享受个人/小企业豁免。开发试验与正式发行条件应分别核对，当前没有为本项目申请或达成发行许可。[样例 LICENSE](https://github.com/Live2D/CubismJavaSamples/blob/develop/LICENSE.md)、[官方 SDK 发布许可](https://www.live2d.com/en/sdk/license/)

## 7. 建议实施顺序与验收

| 阶段 | 可交付内容 | 完成证据 |
| --- | --- | --- |
| A. 保持单手机可用 | 文字聊天、原文 RAG、天气可选上下文仍独立运行；绑定模型不变 | 无 USB、无电脑服务下手机实际登录与聊天；网络失败具体到供应商/权限/请求层 |
| B. 先跑真声音 | 选定并授权的 ASR/TTS；流式首句、录音/播放、结束与恢复 | 真 PCM / 真音频播放，拒绝麦克风仍可文字聊；错误不假装成功 |
| C. 双向音频 | 持续 ASR/VAD/AEC、插话停止、输出进度与原文入库 | 扬声器与耳机、附和/回声/抢话测试；用户听见范围正确保存 |
| D. 每用户 OC 动态模型 | 原图绑定、自有部件、分层/补画/rig、预览修正、本地渲染 | 从用户上传图生成的独有资产；翼/武器数量和身份不丢；无模板换人 |
| E. 语音前预取记忆 | partial 版本化、候选前置筛选、最终语义核对与按需路由 | 对文字与语音分别 A/B；默认方案由速度和原文召回质量共同确定 |

测试不应只记录一个 search 毫秒数。对合成且互不融合的 1,000 / 10,000 轮长短混合原文库，记录 query 解析、embedding、候选筛选、精确/语义搜索、冷原文读取、模型首字、TTS 首音、实际可听首音的 p50/p95/p99；注明手机型号、ABI、温度、冷热缓存与所用模型。质量指标包括多事件原文 recall、作者/日期错配、否定修正后错召回、无记忆时错误断言，以及引用未实际播完内容。照片测试应覆盖背景杂乱、单翼/多翼、细长武器、遮挡、多人、出框与透明原图。

本轮已完成：固定版本来源核验、源码阅读、论文图表目视检查与本文。未完成/缺资源：用户所指项目拼写最终确认，实际音频供应商或许可模型/音色，自动分层补画与 rig 制作服务、真实 Cubism Core 与用户模型包，红米断 USB 的通话/功耗/速度实测。本次研究没有调用用户 API Key、没有给手机装包，也没有替换生产记忆系统。
