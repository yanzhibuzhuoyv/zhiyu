# 录音启动、首段保留与本地韵律（反馈 33 / 27）

当前初版修复已获 Native GREEN，交叉审发现的短包上限 HIGH 也已保存真实 RED 并修复，等待 root 新一轮统一 GREEN。0.2.12 旧交付、报告及 receipt 保持冻结。root 统一 Gradle/ADB；本代理不运行构建或设备、不读取 Key、不调用付费服务、不下载新模型。

## 已定位

原 `AndroidChatVoiceRecorder` 先同步 `engine.openRecognition()`，完成模型加载后才进入 `LocalVoiceMicrophone.capture()` 创建并启动 AudioRecord。模型加载期间尚无音频采集，用户首句不是 ASR 难以识别，而是该时段根本没有 PCM。仅预热或把 UI 提早显示“录音中”不能解决此缺口。

为复现先做保持旧模型优先顺序的薄层抽取：`VoiceCapturePorts`、`AndroidVoiceCaptureInput` 与 `LocalVoiceMicrophone.capture(openRecognition, ...)`。Android recorder 使用同一 capture coordinator；真实 AudioRecord 创建/权限/16 kHz 单声道转换行为保持。该模型优先版本已实际断言失败后，才启用并行采集修复，没有把重构本身称为修复。

## 真实 RED 与新增保护

- `VoiceCaptureStartupNativeTest` 四项：冷加载期间已启动真实采集端口；模型未就绪时首段已读并保序、用户结束后仍回放给 ASR；取消冷加载释放已开启采集所有权；8 秒启动预缓冲满时明确停录失败，不滚动丢弃句首。
- `VoiceReplyPreparationNativeTest.punctuationAddsOnlyBoundedNaturalPausesWithoutChangingTheArchivedOriginal`：实际 SQLite/WAV 派生路径，原文“嗯，我在。晚安！”按标点分段，逗号后 100ms、句号后 220ms、末尾无额外静音；正文与 source SHA 不变，总音频仍受 180 秒约束。

捕获测试走实际生产 coordinator、注入合成 PCM 和可控模型加载门，不访问物理麦克风，不假称红米启动延迟或 ASR 准确率已验收。韵律测试使用确定合成 PCM 来核对 WAV 静音边界；真实内置 TTS 的声音自然度需另验，不把数组通过当主观效果通过。

`artifacts/feedback25-context-ui-capture-native-red-r1.log` 实际 15 项、11 项失败：本范围四项 capture 和一项新韵律均为明确业务断言失败，原 Preparation 四项通过；其余失败属于 root 的 context/UI 新功能。`feedback25-batch-capture-red-build-r2.log` 已成功产出主/测试 APK，整轮退出失败因 JVM 中另有预期 RED，不是用编译失败代替上述复现。

实现后再加入一项真实内置 TTS→延迟 ASR factory→完整 PCM 预缓冲→内置 ASR final 的保护测试；只要求合成固定中文保留可识别问候/天气和完整采样数，不能称不同噪声/口音/真实手机 CER 通过。当前 capture 类五项，新的保护尚待统一运行。预缓冲耗尽用例同时要求明确 `ApiFailure/voice_prebuffer_full`，防止 await 超时被误当成正确停止。

## 已实现，待统一 GREEN

1. 权限确认后先打开 AudioRecord，只有真实 startRecording 成功才发送 started 回调/UI“录音中”。开麦始终属于显式用户操作，预热不自动录音。
2. 并行初始化 ASR 与读取固定 PCM 帧。启动队列最多 80 × 1600 个 Float（8 秒、约 512 KiB）；满时显式停止并保留失败事实，不能偷偷丢开头、无限扩容或只识别后半句。
3. 模型就绪后保序消费预缓冲并继续流式 partial；手动 finish 先停采集，再排空已采集帧并执行现有 final/padding；cancel/后台先关闭输入和平台效果，再安全回收尚在初始化的 native session。
4. 检查平台 `NoiseSuppressor.isAvailable()`，仅在当前 AudioRecord session 上实际创建、启用成功时记录可用；缺失则保真 16 kHz 单声道继续，不能宣称普遍降噪或准确率提升。控制参数不引入新模型或未经证实的强滤波。
5. 本地回复韵律只对音频派生加入有界标点停顿，不重写聊天正文、不随机漂移音色/音高。先限定持久 voice-message 合成；通话流式首句不为此等待整篇。

采集端口在模型 factory 开始前实际 start，ChatVoiceController 的 RECORDING 仍只跟随成功 started 回调。除新录音按钮外，已获 root 窄授权把旧 VoiceController.startListening 的单个调用点切到 factory 路径，phase/token/权限和其他控制不变。没有改 Duplex 核心、Repository、Models 或消息渲染。

model factory 的返回 session 在 native 构造完成的同一执行体写入 AtomicReference，防止返回时 prompt cancellation 丢失 native 资源。结束先停/关 AudioRecord 和 NS，再等待同步模型初始化安全结束并关闭识别 session；这不保证 native 初始化可被硬中断，也不把协作取消说成五秒硬实时。

ASR 已有 0.5 秒尾部 padding、inputFinished 和 decode drain。先验证完整首段路径及 partial/final 差异；没有足够证据时不盲目更换解码器/扩大模型或宣称中文准确率已提升。

官方依据：[Android NoiseSuppressor](https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor) 说明 NS 绑定 AudioRecord 的 audioSessionId，create 可返回 null；[Sherpa Android 示例](https://github.com/k2-fsa/sherpa-onnx/tree/master/android) 为现有本机引擎入口。可用性检查和资源释放不等于物理降噪测试。

## 交叉审追加：80 包不等于 8 秒

`selfie_bank` 只读交叉审发现真实 HIGH：`READ_NON_BLOCKING` 可以返回不足 1600 的短包，初版直接每次 read 放入一个 Channel 槽；80 个 160/320 帧包实际上只有 0.8/1.6 秒，模型冷加载仍会提前溢出。此前五个 capture GREEN 只覆盖固定满帧和整句采样，未覆盖这种实际端口行为；不能把初版“80×1600=8秒”当成已保证。

新 `shortHardwareReadsMustNotExhaustTheEightSecondPrebufferAfterOnlyOneSecond` 使用 160×101=16160 帧（1.01 秒），模型继续阻塞，要求首段已全部采入；结束后逐采样核对顺序和最后不足 1600 的 160 帧。`feedback25-short-pcm-mixed-native-red-r6.log` 实际17项/2失败，本项在1.01秒还未采满时真实 Assertion RED；其余原capture5仍GREEN，另一失败属于混合照片范围。

RED后已修：先按实际帧数复制到1600帧工作块，再将完整块放入80槽队列；下次read最多请求工作块剩余空间。finish将最后不足一块的尾帧独立copy入队，取消清空工作块和排队帧。排队上限128000帧加一个读取工作块，不滚动丢首段；`onSamples`仍每次真实read提供独立快照和原始总帧统计，不修改VoiceRecordingBuffer。该修复待root r9及Native统一GREEN，不能把已落源码当通过。

root 已报告 `feedback25-integration-native-green-contact-topic-red-r3.log` 中此前 capture5/Preparation5 GREEN，整份文件还有其他范围 RED。不把原绿错误扩大成真实麦克风启动/噪声准确率验收。
