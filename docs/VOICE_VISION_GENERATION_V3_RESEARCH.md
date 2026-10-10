# 原生语音、看图与 OC 生图接入核查

本轮用户要求：不依赖电脑；内置表情包；主动生成图片且人物保持一致；用户能发图；视频通话显示用户自己的 OC；可以同时听说及选择几种声音。本报告负责公开资料核查和可实施接口建议。**它不表示这些功能已全部接入或实测通过。**

## 恢复依据与本轮边界

已读取全局任务索引、项目交接全部 829 行、父 `AGENTS.md` 和 `.claude/SKILL_INDEX.md`，核对 Git HEAD `0043301649d834738dd975f1ea5ff9d214448283`、最近提交和工作树。水彩试用版已有 255 单元测试／14 界面测试证据；大量本地迁移代码此前未提交。旧研究 `NATIVE_CALL_REFERENCES.md`／`OC_PORTRAIT_AND_CALLS.md` 已明确真正 Live2D rig、ASR／TTS 和全双工未完成，不能把立绘、抠图或文档研究改称已完成。

本报告作者未操作 AVD、手机、Gradle、环境变量或私人记忆库，未读取用户给出的 Agnes Key 文件，也未调用收费／凭据 API。公开文档只读获取；未下载大模型或执行网上脚本。接口建议应用 `api-and-interface-design` 的边界验证与显式状态原则，不改变原文独立保存、固定恋人／性格规则。主任务负责产品接入、Key 试调用及真实验证。

## Agnes：已有真实公开接口，适合后台生图

用户给出的 [campaign](https://agnes-ai.com/campaign) 是宣传入口，正文为动态页面；**实际请求应走文档发布的 API 主机**。官方 [文档索引](https://wiki.agnes-ai.com/llms.txt) 与 [AgnesAI-Labs 模型仓库](https://github.com/AgnesAI-Labs/AgnesAI-Models) 能对应到文本／图像模型，不能凭宣传页推测不存在的端点。

最新 [Image 2.5 Flash 官方 Markdown](https://wiki.agnes-ai.com/en/docs/agnes-image-25-flash.md) 已通过公开 HTTPS 只读获取成功。`web` 抓取该页失败不代表文档不存在。官方约定：`POST https://apihub.agnes-ai.com/v1/images/generations`、Bearer 鉴权、模型 `agnes-image-2.5-flash`，必填 `prompt`／`size`，`ratio` 可选。编辑／合成示例用 `extra_body.image` 数组，支持 Data URI 和公开图片 URL；用 `extra_body.response_format=b64_json` 接收 Base64，避免额外公开上传 OC。顶层 `response_format` 明确不受支持。相同的 [Image 2.1 完整示例](https://agnes-ai.com/en/docs/agnes-image-21-flash) 可交叉核对，但文档表格把 `image` 列成顶层参数与具体示例矛盾，本轮优先采用完整示例并由主任务实测。

可核对的最小参考编辑请求，不含任何真实凭据：

```json
{
  "model": "agnes-image-2.5-flash",
  "prompt": "更换场景，保留参考角色外观和全部附属物",
  "size": "1K",
  "ratio": "3:4",
  "extra_body": {
    "image": ["data:image/png;base64,REFERENCE_BYTES"],
    "response_format": "b64_json"
  }
}
```

官方响应可包含 `data[].b64_json`／`url`，本轮原生实现仅接收一张 Base64 图片，不跟随结果 URL；必须验证响应类型、解码、尺寸和资源上限。原始 Key 只送 `apihub.agnes-ai.com`，不转发到结果 URL 或文档站；生成超时／网络中断状态未知时不自动重复扣费请求。官方描述生成通常数秒至几十秒，因此后台预生成比前台每条消息现做更合理。官方当前公开促销价格不能作为永久免费承诺。[价格说明](https://wiki.agnes-ai.com/en/docs/pricing.md)

### 人物不能变：确定性合成与参考重绘必须区分

文档中的“尽量保留构图”不是人物身份、翼数或武器绝对不变的保证。**硬要求可先实现为：生成无人背景，叠加用户确认的透明 OC 主体**。原图／主体不覆盖；合成只变化位置、等比缩放、背景及独立遮挡层，主体内容不重新生成人脸或肢体。不要在这条路径自动做镜像，否则单翼／左右道具可能反转。风景、宠物、餐桌、窗景等可自行生成并进入本机独立图库，不需要用户先上传这些素材。

此路径会限制新姿势与自拍角度：原图没有的侧脸、身体角度需要重绘，不能同时保证逐像素相同。Agnes 参考编辑可作为后续**待确认候选**，先按当前 OC 的原图 SHA／主体 SHA／visualVersion 隔离；经用户确认后才发布。不能仅用 face embedding 相似分数就认定多翼、武器、二次元／非人角色完全一致。

建议后台任务状态：`queued → generating → candidate → approved`，失败进入 `failed`，响应未知进入 `unknown`。任务记录 `requestId`、conversationId、visualId/version、referenceSha、subjectSha、dayId、sceneId、参数哈希和产物 SHA；不存 Key。提交产物前再校验 OC 版本与日期，过期结果隔离，避免更换 OC 或跨零点后旧图误发。聊天只从已批准且身份一致的库存选图。表情包继续内置授权素材，和人物库分离；此前未授权第三方 QQ 图不直接放进分发包。

## 用户发图：两个运行能力，而非把 SKILL.md 当视觉模型

Codex 的 skill 是开发代理操作指引；给文本模型附一份 `SKILL.md` 不会让它读取像素。移动端实际需要能运行的图片解码、OCR 和视觉描述能力。保持用户绑定的 AI：多模态模型直接收原图；纯文本模型接收视觉工具产物，但工具内容应标为**可能有错的图片观察**，不写成用户亲口说过的独立原文。

| 能力 | 可落地方案／授权来源 | 明确限制 |
| --- | --- | --- |
| 截图、照片文字读取 | [ML Kit Text Recognition v2 Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)，选 `com.google.mlkit:text-recognition-chinese` 的 bundled 模式，模型随 APK，首次无需动态下载 | OCR 只读文字，不会完整理解人物、宠物、空间关系。需测试无 GMS 的中国版 Redmi；识别错误和文字顺序仍可能存在 |
| 图片内容描述／问答 | [Agnes 2.5 Flash](https://agnes-ai.com/en/docs/agnes-25-flash) 明确支持 `messages[].content[]` 中 `text` 与 `image_url`，走 `POST /v1/chat/completions` | 文档明确公开 URL，Base64 图片对文本端点是否可用需要主任务真实测试；独立 Agnes 凭据，不假定 DeepSeek Key 可用 |

这两个能力可由 Android `ImageOcrSkill`／`ImageDescriptionSkill` 统一运行接口包装；本报告只是接口设计，未安装可运行“两个新 skill”。OCR 的开源备用是 [RapidOCR](https://github.com/RapidAI/RapidOCR) 与 [RapidOcrAndroidOnnx](https://github.com/RapidAI/RapidOcrAndroidOnnx)，其官方声明代码和指定 PaddleOCR 转换权重遵循 Apache-2.0，并要求保留权重来源。Android 旧样例存在迁移／版本差异，不能复制旧 Gradle 或签名材料当已兼容现工程。

完全离线描述候选：[Hugging Face SmolVLM-256M-Instruct](https://huggingface.co/HuggingFaceTB/SmolVLM-256M-Instruct) 发布 Apache-2.0 权重，能做 caption／VQA，模型卡语言为英语；不能据“256M”就保证中文可靠、速度快或可生图。 [llama.cpp Android 文档](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) 和 [multimodal Android 示例](https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal/MobileVLM.md) 提供 JNI／NDK／视觉投影路径；实际应同时带投影模型、tokenizer／模板并核 hash，资源与中文质量未在本机测过。先用小型 OCR＋已获授权的视觉 API 更容易做出真实可用版本。

仅需基础物体标签时， [ML Kit Image Labeling](https://developers.google.com/ml-kit/vision/image-labeling/android) 可作为本地补充；`cat`／`food` 等标签不是完整视觉语义描述，不应把它包装成已经理解用户整张照片。

### 图片和 RAG 的存储建议

用户图片作为附件保存原始 SHA／规范化 SHA／messageId、来源角色和时间；原用户文字原样保持。OCR／caption 各自用派生记录：`engineId/version`、sourceImageSha、status、输出和 observedAt，可重建但不覆盖原图。送给 LLM 的工具观察放低信任段落，图中文字不得当系统指令执行。检索摘要不能冒称用户原话；长对话检索先找相关图片观察，再按需要回取附件，避免每轮把全部历史图片 Base64 塞进上下文。

## GitHub 语音：先用 Android 原生离线识别与多音色 TTS

[sherpa-onnx 原始仓库](https://github.com/k2-fsa/sherpa-onnx) 提供 Android／Kotlin／arm64，支持离线 ASR、流式 ASR、TTS 和 VAD，引擎 Apache-2.0。官方 [Android 构建](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html) 和 [APK 目录](https://k2-fsa.github.io/sherpa/onnx/android/apk-cn.html) 具有中文／中英模型。14M 中文小 Zipformer 官方自己提示准确率不如大模型，适合先测耗时和内存，再和更大中文流式模型对照；不把预训练仓库的“流式”当本机通话通过证据。

| TTS 候选 | 官方事实与最小实施建议 |
| --- | --- |
| VITS AISHELL-3 | [Sherpa VITS 文档](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html) 列出约 29 MB ONNX、174 speaker，样例有 sid 10／33／99。原 [AISHELL-3 数据作者入口](https://www.openslr.org/93/) 列 Apache-2.0。可在 Android 随包同一模型，实际试听挑 3–4 speakerId，记录完整权重来源／SHA／许可证；目前没有选定温柔、低沉等主观标签或手机实物模型 |
| Kokoro | [82M 官方模型卡](https://huggingface.co/hexgrad/Kokoro-82M) 权重 Apache-2.0。 [官方音色表](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md) 有中文女声 `zf_xiaobei`、`zf_xiaoni`、`zf_xiaoxiao`、`zf_xiaoyi` 及男声 `zm_yunjian`、`zm_yunxi`、`zm_yunxia`、`zm_yunyang`；作者的中文评级 D，不先称高品质。 [Sherpa Kokoro](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html) 有中文＋英语和 INT8 版本，需 model／voices／lexicon 等完整依赖，比小 VITS 更大 |
| VoxCPM2 | [OpenBMB 原始项目](https://github.com/OpenBMB/VoxCPM) 提供 2B TTS、语音设计／克隆；[包依赖](https://github.com/OpenBMB/VoxCPM/blob/main/pyproject.toml) 使用 PyTorch 等，不是可直接导入 Kotlin 的小库。可作为之后高质量云 TTS 或离线量化研究，未证明手机原生兼容和实时速度 |

声音选择持久化 `engineId/modelSha/speakerId/language`，试听停止后再换音色，通话中的当前输出仍绑定原 voiceVersion。声音选择不改变固定人格；主观“轻声、低沉”等标签必须根据实际听见的音色决定，不能从数字 sid 猜性别与性格。

官方 [Kotlin Tts.kt](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/Tts.kt) 已核 `numSpeakers()`、`generate(text, sid, speed)` 和 `generateWithCallback`。它叫 OfflineTts；有音频 callback 不等于任意模型已经做到 token 级 TTS。可先在 LLM 有完整句段后并行合成与播放，实际测首音频等待；完整未播音频不能在记忆里被当成“恋人已经说完”。

## 全双工：双向音频工程与模型轮次解耦

可实施链路：持续 `AudioRecord` 收音 → VAD／流式 ASR → 已绑定 AI 与同一 RAG → 句段 TTS／`AudioTrack`，收音和播放可以并行。用户有效插话时立即停旧播放、取消旧生成；使用 sessionId／generationId 门控，晚到音频或回答不得重播。不是在每次播放时强制闭麦，也不是把每个“嗯”都当新话题。

[Android AcousticEchoCanceler](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler) 明确要求检查 `isAvailable()`、以录音 audioSessionId `create()` 并核是否创建成功。支持状态不代表扬声器实际回声已经消除；耳机、外放和蓝牙路由分别验证。没有设备 AEC 时需可靠软件 AEC 或明确降级，不用简单“文本像刚才说的话”代替回声模型。 [系统 SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) 不适合持续识别，反复启动它不能算真正持续流式 ASR。

建议纯内部状态：`listening / userSpeech / responding / speaking / interrupted / reconnecting`，麦克风状态独立于回复状态；用户只看到自然的通话状态，不泄漏 RAG／token／模型推理细节。测量停顿→首个可听音频、插话→停止播放、误回声次数、恢复是否重播、30 分钟音频队列／内存和耗电。通话原文和实际播出的音频区间关联保存，ASR 暂定文本不写成用户最终原话。

之前 [VoiceMem 作者代码](https://github.com/xzf-thu/VoiceMem) 的 playback 进度、打断取消、ASR 与 TTS 解耦思想仍有价值；其 Python／Web 依赖不直接搬到手机，不能因论文延迟数字就改变本机 RAG 或许诺相同 134 ms。现有原文独立记忆库仍保留，不做记忆融合。

## Live2D：用户照片不能直接变成完整 Cubism 文件

[Cubism Java Framework](https://github.com/Live2D/CubismJavaFramework) 是加载 Editor 输出模型的框架；Core 不在该仓库，需要官方 SDK。 [官方 PSD 导入](https://docs.live2d.com/en/cubism-editor-manual/psd-import/) 依赖独立图层；抠图只得到一个 alpha 主体，没有眼睛／嘴／发丝／翅膀／武器拆层、遮挡后区域补画和网格参数。PNG 整体摆动或嘴巴贴片可以叫“立绘动画预览”，不能说已生成用户自己的完整 Live2D rig。

真正产物状态从 `awaitingLayers`、`awaitingRig`、`validating` 到 `ready`，ready 要同时存在可加载的 `.model3.json`、`.moc3`、纹理及正确参数／附件绑定，并绑定 originalSha／subjectSha／visualVersion。部件是该 OC 实际构造，可一只、很多翅膀或非人器官，不使用固定翼数模板。

若优先做纯手机体验，可先展示已确认 OC 的实时立绘，嘴型按实际播放样本包络驱动，明确为预览；完整 rig 的自动生成需要另做拆层／补画／制模工具链。 [Live2D 发布许可](https://www.live2d.com/en/sdk/license/) 特别提到 AI／聊天界面和可扩展应用分类，不能按一般开源 Apache／MIT 分发规则理解 Core，也不能凭“小企业免费”一句断言本应用免授权。

## 主任务接入顺序与验收边界

1. 发图＋本地 OCR／视觉描述路由：验证原图安全解码、Key 分域、原文字与观察分离、文本／多模态能力明确失败，不让错误识图污染用户原文。
2. 自带授权表情与无人背景库存＋OC 原主体合成；单次 Agnes 参考生成只用合成素材验证，真图输出经审核后按当前 OC 版本提交，前台不等生图。
3. 固定实际模型的 Sherpa ASR／TTS 和几个真实 speaker 试听，完成降级、权限、播放取消、真实装置速度后再开放语音。
4. 持续收音／播放／AEC／插话专项；照片输入到 Live2D 真正拆层 rig 单独完成和验证，不能用“有通话页面”代替这两项。

本轮资料核实结果不等于已下载模型、试听音色、生成全部素材、构建通话 APK 或实测全双工。后续产品真实结果以主任务统一构建、隔离测试和脱敏 API 报告为准。

## 本轮新增原生 Agnes 模块与主任务实测

主任务已用其保管的 Key 做两次官方 Image 2.5 参考图调用：均返回 HTTP 200，约 14／16 秒，PNG 864×1152。视觉核对发现四只翅膀和武器保留，但脸部、伤疤和身体比例漂移。因此参考重绘仍只能作为待确认候选，不直接进入固定人物的已批准库存。主任务另测 Agnes 文本视觉端点接收 Data URI：HTTP 200，约 5.778 秒、1,478 tokens；描述把四只翅膀误认成四对八只，**不能拿 caption 自动批准 OC 一致性或判定附属物数量**。这些是主任务的实际调用证据，本报告作者没有接触凭据或自行收费调用。

新增文件范围为独立协议、严格 JSON、可注入客户端、固定官方 HTTPS 传输和独立 Keystore 凭据库；不改已有聊天 Key／绑定存储。客户端接口 `generate(key, prompt, reference?, size, ratio)` 返回不可变图片字节副本及 MIME，由调用方负责图库、OC 确认与次数记账。请求固定 `agnes-image-2.5-flash`，参考图仅放 `extra_body.image` Data URI，顶层 `return_base64=true` 与 `extra_body.response_format=b64_json`。

连接等待 15 秒，总操作截止 6 分钟；使用 OkHttp 持久取消的 Call，取消处理在 enqueue 前注册，避免旧 HTTPS 实现在连接尚未建立时 disconnect 后仍可能发出请求。最多四个未完成请求、两个并发，关闭重定向及自动重试；官方 5.4.0 AAR 已核验 minCompileSdk=36，5.5.0 要求37故未采用。响应流最多 8 MiB（足够容纳一张5 MiB图片的Base64与JSON，降低手机峰值内存），解码 PNG／JPEG 最多 5 MiB，并以 Android `decodeBounds` 检查最大 2,000 万像素；拒绝远程结果 URL、多个结果、非法 Unicode、重复 JSON 字段和无效图片。错误消息固定，不回显服务商响应或凭据。超时／连接中断标为 `image_request_unknown`，不得自动重试或假设服务商没有扣费。独立 `AgnesCredentialStore` 只读／写／清除图像服务 Key，AES-256-GCM 与聊天凭据使用不同 preferences、alias 和 AAD，损坏时明确报错并保留已有密文。

测试先行新增 25 个纯 JVM 用例（协议9、客户端7、传输9）及 3 个 Android 用例：涵盖协议与输入边界、取消／截止时间、可控 Call 请求／错误／资源释放、图片解码与 UUID 隔离凭据存储。主任务首轮构建已捕获生产协议尚不存在的编译 RED，日志 `artifacts/native-experience-v3-build-r1.log`；这是缺失模块的 RED，不是业务断言失败。最终 r9 统一构建、328 个单元测试和 lint 已通过；3 个 Agnes 原生用例已在隔离 AVD 实际通过，最终统一结果见 [本轮验证](NATIVE_EXPERIENCE_V3_VALIDATION_2026-10-10.md)。可控 Call 验证不等于真实网络取消后服务商零扣费。真实语音模型、完整 Live2D rig 和全双工仍未接入。
