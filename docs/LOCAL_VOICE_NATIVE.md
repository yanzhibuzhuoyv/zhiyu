# 原生离线语音：接口、验证与来源

更新：2026-10-11。本文记录原生轮流语音通话模块；测试是否通过以主任务的原生测试日志为准。

## 行为与接口

手机麦克风通过 `AudioRecord` 采集 16 kHz 单声道 PCM16，直接送入内置中文流式 ASR。按照 Sherpa 官方 `getModelConfig(9)` 使用 INT8 encoder/joiner 与 FP32 decoder，避免为了体积把所有组件一律量化。识别文字先展示给用户修改；点击发送后调用 `sendText(String)`，进入已有的同一段聊天和记忆检索链路。语音入口保留键盘草稿与图片草稿，由主任务接线负责。录音波形只在内存中使用，不保存音频文件，也不上传 ASR 服务。

收到完整的新回复后，内置 AISHELL-3 VITS 模型按不超过 120 UTF-16 单元的小段合成，逐段通过 `AudioTrack` 播放。分段不会改动聊天原文或漏掉 Unicode 代理对，全部语音段拼回原回复；超限或无效 Unicode 明确报错。当前预置声音 ID 为 10、33、0，提供单独试听和本地保存选择；声音展示名采用“声音一/二/三”，不凭猜测标记性别或性格。早期第三声99及中途候选2均迁移为实测更清楚的0，保持第三选择位并持久保存；10/33、Key、恋人绑定、性格和原文均不变。下文保留历史声线与失败记录。

主要接线：

- `rememberVoiceController()`：应用持有的控制器；全局 `ON_STOP` 会取消录音、合成和播放并释放模型，退出组合也会释放。
- `VoiceSettingsDialog(controller, onDismiss)`：选声音、试听、停止试听；每一个关闭回调先停止声音，移出组合再兜底停止。
- `TurnCallDialog(controller, name, lastAssistant, chatBusy, chatError, sendText, onDismiss, character)`：申请麦克风权限、录音、修改/发送识别结果、听回复、结束通话。`character(speaking, mouthLevel)` 是主任务提供的已确认角色渲染插槽，口型幅度来自当前播放波形。
- `LocalVoiceEngine.openRecognition()` / `synthesize(text, voiceId, active)`：模型操作共享锁，释放不能与原生推理并发；引擎关闭前释放全部已打开流。

这版为轮流说话；用户说话、等待模型回复和恋人朗读是不同阶段。全双工和打断模型聊天请求不在此模块范围内。

## 已定位的原生崩溃与修复

`artifacts/native-v4-voice-crash-r1-full.log` 的 JNI `NoSuchMethodError` 指向 `invoke([F)Ljava/lang/Integer;`。Sherpa 在具体回调对象上查找 `invoke(float[]): java.lang.Integer`；Kotlin 2 的 indy lambda 只有 `Object` 桥接签名，导致查找失败后原生代码继续操作并触发进程中止。

现改为公开命名类 `LocalVoiceTtsCallback : (FloatArray) -> Int`，显式覆盖 `invoke(samples: FloatArray): Int`，保证 Kotlin 生成所需的 boxed Integer 方法。回调检查当前操作是否仍活跃，返回 0 时仍可停止合成；没有改用不能取消的 `generate`。JVM 反射测试检查精确参数、返回类型及非桥接方法，原生测试检查取消一次后下一次合成仍可运行。

官方 AAR 的 `proguard.txt` 为空。主任务须保留 JNI 反射用类和方法，规则：

```proguard
-keep class com.zhiyu.app.data.LocalVoiceTtsCallback { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
```

录音、播放和模型合成的取消会分别停止硬件与合成回调。播放请求音频焦点，不修改系统音量；丢失焦点停止播放。录音最长 120 秒，既检查帧数也检查墙钟时间；音频写入非阻塞并限制写入/排空时间。退出后不会自动重新开始录音或试听。

## 验证边界

修复前的全套原生测试在第 18 个测试因 JNI 崩溃中止：前 17 项通过，后 8 项没有执行，不能作为整套通过证据。修复后主任务完成 r5 构建（388 单元测试 / 55 suites 通过）；voice 与 BGE 同一进程 17 项测试中 16 通过，只有单次 TTS→ASR 的关键词断言失败，JNI 取消、三声音、播放和两个生命周期测试通过。单独重跑该识别测试时返回“你好听见的天气真好”，也保留了“你好”与“天气”，表明准确度有波动。合成模型实际输出采样率 8 kHz，识别入口正确重采样为 16 kHz。

根据 [官方 Kotlin 配置](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/OnlineRecognizer.kt)恢复 FP32 decoder；当前 SHA256 `5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78`，7,509,745 字节。本地现有官方 tar 与模型仓库的同名文件一致。修复准确度时没有删除原关键词断言，也没有只检查“识别非空”。新增三声音合计十轮探针，输出每次 actual/汉字编辑距离；要求至少 8/10 轮保留“你好”或“天气”、平均字符错误率不超过 35%、每种声音至少一轮保留关键词。

r6 原生 voice/stock 测试 19 项中 18 通过，只有十轮探针失败（7/10 保留关键词）；第三个 speaker 99 的三轮全部失败，音量峰值约 0.047，而 10/33 约 0.2，其输出质量不合格。结果位于 `artifacts/native-v4-voice-stock-r3.log`。随后主任务显式运行候选 0/1/2/8/20/60 各三轮（`artifacts/native-v4-voice-candidates-photo-green-r1.log`）；speaker 2 最好，三轮均保留关键词，CER 分别 0 / 22.2% / 22.2%，0/1 次之，8/20/60 较差。现据实测把第三声 99 替换成 2，不降低正式十轮验收标准。

`artifacts/native-v4-voice-derived-stock-retention-r5.log` 中三个新声音的正式十轮曾达到 10/10 关键词、平均 CER 27.78%，Voice 5、Prefs 2、Lifecycle 2 和 BGE 11 同进程通过；整批 35 项另有 8 项 retention journal 失败，不能算整批通过。随后 `artifacts/native-v4-final-experience-voice-r13.log` 的 39 项中 38 项通过，十轮语音仍失败：9/10 关键词，平均 CER 36.67%，超过原 35% 门槛。两批都是重复同一句合成语音，不能换算成自然用户、方言、噪声或自由对话识别准确率；较早的通过不能覆盖较晚的失败，识别文字纠正步骤继续保留。

### 合成随机性定位与零噪声修复

修复前生产代码未指定 VITS 噪声参数，采用固定 [v1.13.8 Kotlin API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/Tts.kt) 的默认 `noiseScale=0.667`、`noiseScaleW=0.8`、`lengthScale=1`。本地固定 AAR 通过 javap 核查拥有相同字段；[同版本 C++ 推理](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-tts-vits-model.cc) 将两个噪声值传入 ONNX 输入，[配置验证](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-tts-vits-model-config.cc) 允许零值。

r13 同文本、同声线的帧数和 float 波形 SHA 多次不同，与默认随机合成一致；峰值约 0.09–0.262，没有显示削波或接近无声。实际输出均为 8 kHz，识别入口正确插值到 16 kHz，结束已有 0.5 秒补零。此次先不修改增益、重采样、语速或静音时长，而测试显式关闭两个噪声项是否使输出稳定并保留可辨识内容。

新增 `LocalVoiceSynthesisStabilityNativeTest` 使用生产引擎，对同一句、三声线各连续合成三次，汇报 rate/frames/peak/SHA，并要求每声线重复结果一致、三声线仍不同。`artifacts/native-v4-voice-stability-red-care.log` 已真实复现旧默认失败，三个声线的重复帧数与波形 SHA 不稳定。该批另外一个错误是 CareCancellation 测试类的运行包名配置错误，不属于语音失败；天气六项通过。

取得真实 RED 后，`LocalVoiceEngine` 首先只在 VITS 配置中显式设为 `noiseScale=0`、`noiseScaleW=0`、`lengthScale=1`。该阶段声音仍为10/33/2，权重、采样、增益、结束补零和可取消回调不变；关闭噪声可能减少韵律变化，仍需真人试听判断自然度。

r15实际40项中39通过，稳定性已GREEN，但第三声2在零噪声下稳定识别为“您好今天的天星戏真好”，十轮仅7/10关键词，不能据此结案。随后候选0/1/2每声三次短句，CER为11.11%/33.33%/33.33%；独立三句日常话各三次平均CER为13.535%/22.626%/38.182%（`native-v4-zero-noise-candidates-r1.log`、`native-v4-zero-noise-varied-r1.log`）。varied句集不使用“你好/天气”的关键词指标，最终诊断将该字段置null。

依据两组诊断将第三声换为0，99/2选择均持久迁移，未知ID仍拒绝；8项Rules先取得3项RED。最终r14构建422单元/57suites通过；`native-v4-final-experience-voice-r16.log` 的40项全部通过、63.248秒，三声线各三次波形/时长稳定且不同声仍不同，正式十轮10/10关键词、平均CER10%，旧声线迁移落盘与重复读取通过。至少8/10、平均CER≤35%、每声有关键词三个原断言保持，未用候选诊断代替正式验收。重复合成回识别不等于自然用户、噪声或自由对话准确率；详见 [完整v4验证](NATIVE_V4_VALIDATION_2026-10-11.md)。

候选诊断需指定该测试 class，并传 instrumentation 参数 `voice_quality_probe=true`；可传 `voice_candidates=0,1,2,8,20,60` 改变候选。没有显式参数时通过 Assume 跳过模型诊断，普通 release 的指定 class 列表排除此类。诊断只在测试中直接调用官方模型，未开放生产 UI 对任意 speaker ID 的访问，也不降低正式测试断言。

新增或保留的针对性测试：

- `LocalVoiceTtsCallbackTest`：精确 JNI 方法签名、活跃返回 1 / 停止返回 0、不改波形。
- `LocalVoiceRulesTest`：3 种声音固定集合、长文本不漏字、代理对边界、不合法输入、重采样和音频边界。
- `LocalVoiceNativeTest`：3 种模型真实发声、TTS 音频进入真实 ASR、引擎/流释放、静音 AudioTrack 播放、取消合成后继续工作、三声音十轮识别准确度探针。仅测试生成的音频保存在测试 cache，便于主任务提取试听；正式录音不保留波形。
- `LocalVoiceSynthesisStabilityNativeTest`：同文本、同声线三次的帧数和 float 波形 SHA 稳定，三种声音仍互不相同；只合成固定测试句，不接触麦克风或用户文本。
- `VoiceLifecycleNativeTest`：没有通话弹窗时全局进入后台停止试听；父组件暂不移除设置弹窗时关闭回调也立即停止。

原生测试通过 `ComponentActivity` 测试宿主进行，不打开正式聊天页面、不读取 API Key。AudioTrack 测试仅把该 Track 音量设为 0，不改变手机系统音量。测试需要随 APK 打包模型与支持 ABI；模型真实运行和麦克风物理输入在不同设备上仍需要主任务/用户实测。

## 官方模型与许可证据

官方 AAR 与模型原始下载 URL、大小、SHA256 位于 `artifacts/native-voice/manifest.json`，下载工具是 `scripts/prepare_native_voice.py`。应用启动、ASR 和 TTS 均不需要再次下载模型。

| 组件 | 官方来源与版本 | 已核验许可与文件身份 |
| --- | --- | --- |
| Sherpa Android AAR | [k2-fsa/sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE)；AAR SHA256 `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` 与官方 release digest 对照 |
| 中文流式 ASR | [csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23)，revision `204ad334e2e683fd295359930cc16fc0432a23ac` | 作者模型卡 Apache-2.0；HF API `blobs=true` 中 INT8 encoder/joiner 与 FP32 decoder 的 LFS SHA256 与本地完全一致 |
| 中文多声音 TTS | [csukuangfj/icefall-tts-aishell3-vits-low-2024-04-06](https://huggingface.co/csukuangfj/icefall-tts-aishell3-vits-low-2024-04-06)，revision `57345c004e13ed640e408c4c29ab56187b18f065` | 作者模型卡 Apache-2.0；本地 `model.onnx` 30,482,262 字节，SHA256 `5511d651b7840c0a93a6bbfd4afd070a2c7f39ca1ec3ff2ecd73191519bbb852` 与仓库 `exp/vits-epoch-960.onnx` 完全一致 |
| AISHELL-3 来源说明 | [OpenSLR 93](https://www.openslr.org/93/) | 托管页列 Apache License v2.0；应用不分发训练语料，此项不替代具体模型作者的许可声明 |
| 静态 ONNX Runtime | [Sherpa Android arm64 静态构建定义](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/cmake/onnxruntime-android-aarch64-static.cmake)选择 1.28.2 | [Microsoft ONNX Runtime MIT](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE) |

随 APK 打包 `voice-model-NOTICE.txt`、`voice-APACHE-2.0.txt` 和 `voice-ONNXRUNTIME-MIT.txt`。Apache 全文直接从 [Apache 官方原文](https://www.apache.org/licenses/LICENSE-2.0.txt)下载，11,358 字节，SHA256 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`；MIT 正文从固定的 ONNX Runtime v1.28.2 源码取得，1,073 字节，SHA256 `2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c`。

权重保持官方字节不变。应用新增 Android 音频 IO、取消、声音选择与 UI，没有克隆用户或第三方的声音；上述来源确认的是当前分发权重和所查模型卡的身份，不把所有预构建静态依赖的许可自动视为相同。
