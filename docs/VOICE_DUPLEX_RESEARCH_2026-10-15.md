# 手机全双工接线研究与验收边界

研究起点为 `3733fe6` / 知语 0.2.9；此文是只读源码审查和官方接口核查，不代表后续实现已经完成。没有读取用户 Key、调用收费接口、启动录音或运行新手机测试。用户最新顺序为先完成其他功能，再开始全双工；研究可以提前，生产接线须在非语音 checkpoint 后进行。

## 非语音范围核对

| 项目 | 起点的实际情况 | 本轮处理建议 |
| --- | --- | --- |
| 半身/头像扩图 | `LifePhotoPromptClient` 明确禁止把局部参考扩成全身；没有用户审批后替换完整参考的产品流程 | 这是仍缺的原始要求；独立候选、真实双图审查、用户确认、原图保留与取景选择需要一起接，不能只改 prompt |
| 动态形象草稿 | 0.2.9 的 `PlanarController` 主要通过 Compose 内存保留编辑；交接明确旋转/进程重建丢未保存草稿 | 有界、原子、按聊天和原图绑定的 checkpoint；已保存 active 包保持独立 |
| 人格、原背景动画、候选照片 | 0.2.8/0.2.9 已完成对应产品入口及回归 | 不重做已完成流程；复杂姿势可由用户修正，不能把完美自动 Cubism 重新设为门槛 |
| 旧 parity 文档 | `MOBILE_FEATURE_PARITY.md` 顶部仍为 0.2.3，Agnes/抠图/三种声音等行已被新版替代 | 更新当前总览，历史表注明日期；不能把过时表格当新增缺口 |
| 超大历史检索延迟 | 冻结 50M 报告约 20.66–21.56 秒；当前 `LocalSemanticStore.search` 仍扫描相应 active/cold 向量并逐条算分 | 仍是明确性能限制，未做分区 ANN 和同库 A/B；不能称已融合论文或达到 134ms。本轮不重跑旧长测，后续单独优化 |
| 发布计费/旧电脑历史迁移 | 仍是同签名调试包、无完整电脑历史迁移 | 保持边界；没有用户支付产品规则，不自行引入支付/账号体系 |

已取消的自动抠图、2D/3D 捏脸和默认 5GB 学生生图不恢复。先前实图已显示细节漂移，像素审批也不能证明人物绝对不变。

## 0.2.9 现有声音路径

`VoiceController` 明确声明一轮一轮处理：`startListening` → 麦克风录一轮 → `REVIEWING` 人工发送 → 等完整 assistant `Message` → 分段 TTS → 播放。等待或播放时 `busy` 阻止再次录音。因此当前入口尚非全双工。

- `LocalVoiceEngine` 已有本地流式 Zipformer ASR 和 VITS TTS，174 个底层 speaker，可保留现有声音与语速/音高/音量设置；无需为接线新增大模型。
- ASR 和 TTS 现在共用 `lock`，`synthesize` 在 native 合成期间持有该锁，`LocalVoiceRecognition.accept` 会等待。持续识别必须与 TTS 使用独立 native owner/锁，不能只增加两个协程。
- `LocalVoiceMicrophone` 用 `VOICE_RECOGNITION`，每轮最多 120 秒、100ms 短读；`finishTurn` 停录。需增加通话期间连续 PCM 采集适配器，而不是反复调用原 turn 方法。
- `LocalVoicePlayback` 已有非阻塞短写、真实播放尾等待、取消时 `pause/flush`，口型来自所写 PCM 的 RMS；当前为 `USAGE_MEDIA` 且每段申请焦点。通话应由统一 owner 管理路由与焦点。
- `LocalVoiceTtsCallback` 是 JNI 需要的具名具体类，返回 `0` 表示取消。不可替换成会失去具体 `invoke(float[])` 签名的普通 lambda。
- `DirectProviderClient` 的 HTTPS 传输已有协程取消时 `disconnect()` 和 `Future.cancel(true)`；当前完整 JSON 回答先经过日历/媒体协议处理再入库。

## 最小可交付架构

保留同一聊天、同一 RAG、同一人格与 Key。全双工第一版可用完整回答，再分句合成：持续收音、播音及插话不依赖 LLM SSE。流式正文降低首音等待是后续独立优化；先播未经完整媒体/日历解析的增量有协议泄漏和先播后检查的问题。

1. 通话 owner 取得麦克风、通信路由、焦点、AEC 和独立 ASR/TTS owner；获取中途失败须回滚。
2. 独立 IO 协程连续采集 16kHz mono PCM。每帧约 20–40ms，固定小缓冲；不能因等待网络/TTS而暂停采集，也不能无界积压。
3. VAD/端点 actor 持有约 200ms pre-roll，持续识别新语句，partial 只更新临时 UI。端点接受后才保存一次 final 原话；不把每次 partial 都写入 RAG。
4. 新开口先令旧 utterance stamp 失效，立即停止实际播放并取消旧网络/TTS，再继续识别。新回复必须在旧 native 工作合作退出后取得 TTS/player lease，ASR 不等待该 lease。
5. 单条 final 调用可取消的 suspend `callTurn`，复用现有 repository 串行发送、完整回答解析及原文保存。完成的正文分句 → 有界 TTS 队列 → 有界 PCM 队列 → AudioTrack。
6. 每个异步结果同时检查 session 和 utterance。主线程实际写 UI/口型时也检查，不能只在音频线程检查后再排队一个无保护的 UI 更新。
7. 挂断、离开前台、权限撤回、焦点丢失结束当前 owner；停播放器/录音、取消网络、等待 native 退出再释放，不允许旧 callback 启动新通话。

`rig2d` 已有 `VoicePorts.kt`、`DuplexCallSession.kt`、`VoiceReplyPipeline.kt`、`VadEndpointDetector.kt` 和 `SentenceChunker.kt`，手机已有的 portable JAR 包含这些协调类。可复用 stamp、pre-roll、有界队列、取消和 release 思路。其 Energy VAD 只是 RMS 门限，合成 fixture 证明不了 AEC。默认 240ms 端点对中文犹豫和思考停顿可能过短，需要真实语音调参，不应直接当最终产品值。

## 取消、持久化与竞争

当前 `LocalChatRepository.send` 先保存 user，之后调用 provider，再 `ensureActive`，然后在媒体提交中保存 assistant。取消后 user 已经入库是正确行为，不能为保持界面整齐而删除原话。

建议 `callTurn` 归属于一条 utterance 的子 Job，插话立即撤销其播放资格和 Job；新 final 的发送等待旧操作合作结束，并继续用同一串行 repository。UI 的 `busy`、`pending` 和历史刷新须有自己操作 token，旧 finally 不能清新任务状态。

关键线性化点在 assistant 实际 append：

- append 前取消：最终提交锁内、紧邻 append 检查 Job、call stamp 和当前关系绑定，拒绝旧结果。仅 provider 返回后的那一次 `ensureActive` 不足以覆盖后续 IO 切换/媒体锁等待。
- append 已完成才取消：保留已提交完整 assistant 原文，停止继续播放；不得删原文制造没说过的历史。完整文本和“播完/打断”是不同状态，后者需要独立 metadata，不能重写正文。
- provider 不合作地晚返回：本地 stamp 仍禁止发布/播放；取消 HTTP 不能保证上游没有计费。
- 发送异常/取消：在有界 `NonCancellable` 收尾中读取同 chat 历史，显示已经存下的 user/assistant；仍检查当前 chat，避免旧关系结果污染当前界面。

## Android 音频约束

通话输入使用 `VOICE_COMMUNICATION`，输出使用 `USAGE_VOICE_COMMUNICATION` 与 speech content；通话 owner 使用 `MODE_IN_COMMUNICATION`，不使用电话特权的 `MODE_IN_CALL`。需要 `MODIFY_AUDIO_SETTINGS` 声明及运行时 `RECORD_AUDIO`。[Android 音源接口](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource)、[音频属性](https://developer.android.com/reference/android/media/AudioAttributes#USAGE_VOICE_COMMUNICATION)。

API 31+ 从可用通信设备选择输出 sink，输入由平台匹配；结束清除本应用请求。API 26–30 保留兼容路径并验证实际路由。不要在每句 TTS 间反复恢复音频模式，也不要修改系统主音量。[AudioManager 官方接口](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))。

AEC 绑定 `AudioRecord.audioSessionId`；先检查 availability，再处理 `create` 返回 null、控制权和 enable 状态，最终释放 effect。平台能力不等于当前房间、扬声器音量和手机距离下的效果。软件 RMS 门限、播放时屏蔽识别、识别文本与自己回答比较，都不能替代 AEC。[Android AEC](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler?authuser=303)。

焦点申请失败不得继续播放，损失焦点需停当前声音。target 35+ 在新系统上要求前台应用或前台服务；本轮前台通话可沿用 `ON_STOP` 结束，不为它擅自新增后台麦克风服务。[音频焦点官方指南](https://developer.android.com/media/optimize/audio-focus)。

系统可能令录音继续但输入静默，也可能改路由/预处理；API 支持的录音状态回调可区分系统 silenced 与用户沉默，不能让静默长循环被当作设备正常。[Android 音频输入共享规则](https://developer.android.com/media/platform/sharing-audio-input)。

本地 ASR 能力和 stream/reset/endpoint/release 接口以项目使用版本为准，不能照抄 master 新参数。官方已有本机流式 Android 示例与 Kotlin API；本轮不下载额外权重。[官方示例](https://k2-fsa.github.io/sherpa/onnx/android/apk.html)、[v1.13.8 OnlineRecognizer](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/OnlineRecognizer.kt)。

## 必须分别记录的验证

| 层次 | 可以证明什么 | 不能外推什么 |
| --- | --- | --- |
| JVM 虚拟时钟/合成 PCM | 同时存在 capture/play、pre-roll/端点/溢出、插话先失效、旧 callback拒绝、严格释放顺序 | 真实麦克风、AEC、手机延迟 |
| 隔离 Native fake transport | 同 chat 仅一次 user/assistant、取消前后提交边界、媒体/日历不泄漏、HTTP取消、旧 finally 不改新 UI | 真实 Key 服务延迟、云模型质量 |
| AVD 真实模型/音轨 | 实际 JNI ASR/TTS、独立锁并发、AudioRecord/AudioTrack 构造、路由能力分支、生命周期释放 | Redmi 物理回声、蓝牙行为、扬声器可用性 |
| 真机听测及 trace | 耳机/扬声器同时讲话、回声误触发、低声与噪声、真实首音/打断分布、热/内存 | 未测设备的统一性能保证 |

最小 RED/GREEN 用例需覆盖：立即 start/stop、获取资源时取消、TTS callback取消、正在合成/播放/等回复时插话、非合作晚回调、重复 final、空识别、连续两句、队列满显式失败、权限撤回、音频焦点和路由丢失、挂断不自动重播。

真实设备验收至少记录首次开口→partial、用户结束→首音、插话→实际旧音停止的分布，分别注明模型冷启动/热启动。耳机路线、扬声器不同音量/距离、仅自己 TTS 无用户说话、双人同时说话分别统计错误中断和漏中断。AVD 或合成 PCM 不足以填写这些数值；真实麦克风采集不等于 AEC 已通过。

本文没有执行新测试，也没有宣布全双工已验收。后续实施报告应独立记录实际结果和仍需真机验证的项目，不改写 rig2d 历史 synthetic 回执。
