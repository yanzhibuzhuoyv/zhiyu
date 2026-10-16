# 独立语音核心接入知语的准备

本轮不迁移 Android，按用户“后期把这个接入”先完成独立模块。知语仍为0.2.6、HEAD11b17c7；这里的 JAR 不是 APK，真实手机全双工尚未验收。

## 已有可移植接口

`DuplexCallSession` 接收 `CoroutineScope`、`DuplexPortsFactory`、配置及 typed事件/口型回调。每次 start 获取新的五类资源，stop取消任务并等待释放；同一启动不会重复取得资源。session/utterance stamp 区分两次通话和两次用户发言。

| 接口 | 未来 Android 对接 | 必须验证 |
|---|---|---|
| `VoiceCapture.frames()` | `AudioRecord` 单声道PCM流 | 权限、音频焦点、路由、稳定采样率、短帧与后台释放 |
| `VoiceActivityDetector` | 可替换的 VAD | 默认 Energy VAD 仅为 RMS 门限，不是回声消除；噪声/回声/短促声音误触发 |
| `VoiceRecognizer.open(stamp)` | 已有 sherpa streaming Zipformer | 开口前 pre-roll、partials/final、取消与独立 native 会话 |
| `VoiceTokenSource.tokens()` | 已绑定服务商的流式回复 | 真实 HTTP cancel；只播正文，不把 reasoning_content/工具协议/状态流当对白 |
| `VoiceSynthesizer.synthesize()` | 现有 VITS 按句/限长分块 | 继承音色、语速、音高、音量；active/cancel能停止或丢弃过时推理 |
| `VoicePlayer.play()/stop()` | `AudioTrack` | play在真正播完后返回；stop立即pause/flush，拔耳机/焦点丢失释放 |

`LocalVoiceEngine` 当前 ASR/TTS 共用锁；移植前应隔离识别与合成的 native 会话/锁，否则一边合成可能阻塞持续识别。不能只搬“全双工”状态机名字。

设备 AEC 是否可用应查询并实测。Android 的 AEC 绑定 `AudioRecord` session，并提供 availability 检查；并不保证每台设备的扬声器环境都有效。[Android 官方 AEC 接口](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler)

## 状态、取消与口型

采集、ASR actor 与回答流水线独立执行。token→句块→TTS→PCM→播放使用有界队列；用户持续开口达到 start 门槛后，先撤销旧 reply，再取消模型/TTS并停止实际播放器，继续听新句。采集溢出显式停止，不偷偷丢帧。

适配器必须合作取消且快速响应 stop；纯协程不能抢占不响应取消的 native 推理。core在取消及提交处仍检查 epoch，不能仅依赖 Job.cancel。[Kotlin Channel 取消与队列语义](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/)

口型来自实际播放 level，限制0–1，打断后清零。硬件回调移交 UI 时，要在主线程实际更新那一刻重新检查 stamp；不能只在音频线程检查一次，再异步提交旧口型。

v1 headTurn 保持原3.5°装饰性语义；v2视角yaw另行分派。嘴型RMS接口不是音素viseme，也不是已经完成的完整 Live2D 绑定。

## 对接聊天和原文记忆

- ASR partial只更新临时UI；final确认后只写一次用户原话，不把不断变动的候选文本全部写进RAG。
- 沿用当前固定恋人/服务商/模型/性格和一个聊天，不新增会话或允许预设随通话变换。
- 完整模型原文、实际已播放范围与打断状态分别存放；保留完整原文，播到哪作为独立metadata，不通过重写或融合原文伪造“用户听过”。这一步尚未实现，需要在移动端事务接线时补实际播放进度事件。
- 配文/语音继续经过已有拟人提示词和回答约束；天气、日历等只经当前授权业务入口。语音模块不执行隐藏指令，不读取自己的Key或用户库。
- 技术 Fault 转为简短产品提示和恢复入口，不直接对用户输出buffer、数据库、Key等工程词。

## 接入顺序与验收

1. 保留当前APK/源码/语音资产基线，先搬纯 core 和同一组测试，替换设备接口；v1/golden不改。
2. 验证 AudioRecord/AudioTrack 与 echo路由；持续输入和播放真正重叠，不用按键完成一回合。
3. 验证 ASR/TTS独立native锁、分块模型、真实网络SSE与取消、选择声音及调节保持。
4. 验证中途离开、拒绝/撤销权限、开关音频路由、来电、拔耳机、立即start/stop/restart及长时通话。
5. 实测首次开口到partial、结束到首音、用户插话到旧音停止的分布，以及内存/CPU/温度；不把合成trace时间当真实延迟，不提前承诺1.2秒。
6. 接完整角色资产与渲染之前先通过P0/分层/露缝/身份验收；资产不可用时沿用当前头像降级，不能白屏。

已有 sherpa 官方提供 streaming ASR 与 VAD相关Android示例，可用于适配对照；本轮没有下载新模型或移植它们。[sherpa 官方 Android 示例](https://k2-fsa.github.io/sherpa/onnx/android/prebuilt-apk.html)
