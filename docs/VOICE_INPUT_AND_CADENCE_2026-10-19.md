# 语音谐音理解与轻微语调 · 2026-10-19

本轮对应用户反馈 42、45。原始录音、自动转写、逐句聊天与 RAG 原文不改写，不新增第二次在线纠错请求，也不更换音色模型。

## 已定位的链路

聊天录音由 `ChatVoiceController` 调用本地 ASR，`VoiceMessageStore` 保存 WAV 和原始转写 SHA。发送时 `LocalChatRepository.submitVoice` 校验录音状态、原文 SHA、聊天归属和音频文件，再提交 `voice_user` 原话。

当前文字模型收到的是转写文本，没有收到该 WAV。此前提示中只有“可以发语音”等输出能力，没有说明当前用户输入来自 ASR，也没有约束按高置信谐音理解。因此模型可能把识别错字当作用户刻意表达的词，并沿着错字继续聊。这是输入来源与解释约束的缺口；不能把它描述为已接通原生音频理解。

TTS 使用打包的 VITS AISHELL3。模型的随机声学/时长噪声设为 0，以保留既有同文同音色的确定性。原先只有按标点插入的静音，以及用户选择的整段语速、音高和音量，没有句末音高轮廓。官方 [Sherpa VITS 配置](https://k2-fsa.github.io/sherpa/onnx/c-api/html/structSherpaOnnxOfflineTtsVitsModelConfig.html) 并不提供本项目可直接使用的情绪标签接口，不能把增加随机噪声等同于情感表达。

## 最小修复

`VoiceInputRuntime` 与 `VoiceInputInterpretation` 提供本次请求的输入来源：普通键入为 `text`，本地语音转写为 `asr_transcript`。原音频是否仍可用与是否实际发送给模型是两个字段；现有模型路径的 `audio_supplied_to_model` 固定为 `false`。这些状态不从用户正文关键词或旧消息推断，也不把原话、音频路径嵌入可信状态块。

ASR 规则要求按本句话及相关前文理解高置信同音、近音偏差，自然回应含义，不把纠错作为新话题。不能擅自改人名、地名、数值、日期、否定、意愿和关键承诺；不确定时只确认必要含义。猜测不写回原文或记忆，也不声称听到了未提供的音色、哭声、笑声或背景声。该方案仍由模型执行解释，不能保证所有谐音、口音、噪声都能正确理解。

`VoicePcmProsody` 在 `LocalVoiceEngine.synthesize` 的共同出口处理音频派生。试听、语音消息及通话共用该出口。它按句末标点加入确定性的轻微升降轮廓和能量包络，保留输出帧数，采样映射首尾锚定，避免切掉末字。局部采样变化限制在约正负 2.5% 内；峰值限制在合法 PCM 范围，每 4096 帧检查取消。输入数组与文本均不修改，没有额外模型、网络或随机声纹噪声。用户音高和音量仍在既有播放层叠加。

这是有限的韵律改善，不是情绪神经 TTS，也不能单靠波形变化证明听感自然。原声与处理后音频需要人工试听；如果效果仍机械，后续应另行比较小型可控韵律 TTS，而不是加大 DSP 幅度扭曲中文声调。

## 验证记录

- Root 首轮 `feedback4146-first-red-build-r1.log`：新 11 项 JVM 测试真实运行，8 项失败，3 项保护控制通过。失败包括 ASR 来源/理解边界，以及 PCM 轮廓、输入验证、取消。
- 实现后 root 统一运行相关 18 项 JVM（7 项集成状态、11 项语音规则与 PCM）全部 GREEN；完整回归与发布结果另由 root 汇总，不能把这些单元测试当作自然语音准确率或听感已通过。
- `VoiceProsodySampleNativeTest` 为显式研究 probe，从普通全量中排除。用 `-e voice_prosody_sample true` 单独运行；可传 `-e voice_prosody_speaker 0`（女）或 `10`（男）。它先生成原 VITS 基线，再走真实引擎，验证实际新输出逐帧等于处理后的基线、帧数不丢，并导出三组固定合成句的 before/after WAV，供听感比较。它不读取用户录音或私有 Key。

`VoiceInputRuntimeNativeTest` 新增两项真实队列发送回归，使用隔离 SQLite、合法 READY WAV 和替代 HTTPS transport：一项核对 ASR 状态实际进入序列化 system，同时校验转写原文、SHA、绑定和 PCM 不变；一项先发语音再键入包含“语音转写/错别字”的普通文字，验证本轮回到 TEXT，不能继承历史 ASR 状态。修复后已在 `feedback4146-native-green-r1.log` 的 38/38 定向原生测试中通过。

实际内置模型样例 `feedback4146-real-tts-prosody-sample-r2.log` 为 1/1 通过、1.98 秒，speaker 0，三组共六个 WAV；原帧数和逐帧派生关系均核对通过，导出 SHA 在 `artifacts/feedback4146-voice-samples/manifest.json`。首次 r1 因夹具缓存目录的 canonical 路径校验失败，修正夹具后重跑；未掩盖失败或把未合成算作通过。

Root 集成点为 `ConversationRuntime.voiceInput` 与本次 `send` 的实际 `call` / `voiceDraftId` 来源。最终整轮与 APK 见 [本轮报告](FEEDBACK_41_46_2026-10-19.md)；语义规则和波形验证不能代替用户真实语音准确率与人工听感验收。
