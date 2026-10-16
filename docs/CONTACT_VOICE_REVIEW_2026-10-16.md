# 主动联系与持久语音独立审查（2026-10-16）

当前结论：root 的最终 `enqueueReplyVoice` 恢复接线已只读复核，先前“正文已归档但派生语音尚未 enqueue”的 P2 已在源码闭环；指定 root 源无剩余 CRITICAL/HIGH，也无该项待修 P2。`device-feedback-final-build-r18.log` 构建成功，JVM XML 合计 123 suites / 823 tests / 0 failure / 0 error / 0 skipped；root 最终 `device-feedback-final-native-r10.log` 整轮 348 项通过，另有全新断网首次进入 1 项通过。以下 RED、等待与 P2 描述保留为过程证据，当前状态以本段及末节为准。

本轮审查限 `notifications/`、`ProactiveController`、`LocalContactPolicy`、新增 `data/voice-messages/` 及对应测试。读取 Repository 的调用位置用于检查接口合同，没有修改 Repository、主聊天数据库、App、VM、录音 Controller、rig2d 或旧后端。

## 已发现的阻断

### 新录音在第一次发送前没有暴露转写校验值

`LocalChatRepository.submitVoice()` 在发送前校验 `clip.sourceSha` 等于待发原文 SHA。原 `VoiceMessageStore.recordDraft()` 只写入独立的 `transcript_sha`，返回的未绑定草稿 `sourceSha` 为 null。因此 PCM 已保存也无法通过首次发送入口。新增 `aRecordedDraftExposesTheTranscriptHashBeforeItsFirstSend` 用真实 SQLite 验证首次发送前、Store 重建后的 SHA 合同，`device-feedback-integration-native-red-green-r6.log` 已真实断言 RED（期望 SHA，实际 null）。修复录音初存时同步保存 `source_sha`，仍保留独立 `transcript_sha` 供绑定再次核对；GREEN 待 root 统一运行。

### 已成功重连仍可能沿用先前六小时连接退避

`ProactiveDelivery` 只有在服务抛出不可重试的 `ApiFailure` 时记 `needs_connection` 和六小时 due；网络错误、408、429、5xx 记 `retry`。普通恢复保留真实 due 是正确行为，但成功 provider 验证也不能只走普通恢复，否则旧连接错误会继续挡住用户。

实际登录/重新验证成功后调用窄接口 `onConnectionValidated()`，只解除当前绑定且已授权聊天的 `needs_connection` 退避；保留原 `checkedAt`、outcome、pending request ID。`not_due`、`sent` 对应的性格间隔、每日上限、暂停等仍由实际服务重新判断。新增连接释放/真实间隔保护两项 Native 测试，r6 中释放用例真实断言 RED，间隔保护 GREEN；不能把仅保存 Key 当成 provider 验证成功。

修复已落地：`ProactiveWorkStore.releaseConnectionBackoff()` 在与 Worker 写入同一个锁内检查 outcome 并更新 due，避免 Worker 刚写入 `sent` 又被旧连接失败快照覆盖。通知调度只在该方法返回 true 后替换当前聊天旧 due 链；普通恢复、其他 outcome 和 pending request 均不清除。实际 provider 验证成功的调用点由 root 接入 Repository。GREEN 待 root 统一运行。

## 源码检查清单

| 范围 | 已核对的约束 |
| --- | --- |
| 主动联系持久许可 | 短时进程队列保存本地 opt-in；取消界面 scope 不取消设置写入；通知拒绝/撤回不安排新任务。 |
| 调度 | 首次检查 30 秒；普通恢复不覆盖真实检查/退避；due 链继续使用同聊天唯一名称，周期修复最小 15 分钟，受网络和系统调度约束。 |
| 联系频率 | 首次间隔锚定真实聊天；后续 personality spacing、每日上限和未回复上限仍存在；不以重复恢复滚动推迟最后互动时间。 |
| 原文与音频 | 用户 PCM 对应转写 SHA；回复音频对应 owner/chat/message/SHA/origin，合成前及发布前核对当前原文修订；不改写聊天原文。 |
| 本地文件 | clip ID 限 32 位小写十六进制；目录和文件检查 canonical path，拒绝软链接/越界；原子写且不覆盖已有文件。 |
| 编解码/容量 | 只存有界 PCM16 单声道 WAV，验证采样率、时长、内容 SHA；包括 ledger 的容量检查，空间满时保留已发送语音。 |
| 生命周期 | 录音属于用户明确前台操作；回复合成由有界 WorkManager 任务运行；取消标记 terminal，不无限重试合成。 |
| 一次尝试 | `claim` 只接受 PENDING 且 attempts==0；已 READY 不再合成，进程中断后的已认领任务不重新生成新音频。 |
| 保护已发送语音 | 绑定草稿不删除；主库已有同 origin 指针时，不因 ledger 绑定尚未结束而删除 PCM。 |

## 证据与边界

`device-feedback-integration-native-red-green-r6.log` 实际 24 项、9 项失败，不能称整轮 GREEN。本范围：主动联系完整服务链和前台跳过、Activity 取消保存三项 GREEN；原恢复/未授权/通知拒绝三项 GREEN，真实间隔保护一项 GREEN，重连解除一项 RED；Store 原五项 GREEN、新草稿 SHA 一项 RED；Preparation 三项 GREEN，包含真实内置 TTS 生成非零 WAV、冻结音高音量、原文修订后不发布、已准备回复不重复合成。

服务链使用真实 Repository、SQLite 和 Android `NotificationManager`，仅远端 transport 为合成响应；不能称实际云模型已经调用或真实手机已经收到通知。此前 Store、origin 与规则的真实 RED 仍留在原日志，本轮两个新增阻断修复待下一轮验证。

本审查没有运行 Gradle、ADB、收费模型或读取真实 API Key。模拟器的 WorkManager/SQLite/通知 API 通过，不等于红米后台省电、自启动、Home/滑卡和整夜到达率验收；系统强行停止后也不承诺自动运行。

## 追加：录音 Controller 只读独审与 Native 合同测试

后续获准只读审查 root 的 `ChatVoiceController`、`ChatVoiceMessages`、`LocalVoiceMicrophone` 增量，并新增独立 `ChatVoiceRecordingNativeTest`，未修改这三个生产源。

发现两个确定取消窗口，已先补测试待 root 统一 RED：

1. `created = withContext(IO) { store.recordDraft(...) }` 在 IO 已写完 WAV/ledger、返回 Main 前被取消时，会被协程的 prompt cancellation 丢弃返回值；外层 `created` 仍为空，`finally` 无法清理未发送录音。
2. `cancel()` 把草稿清理任务放在原 screen scope。该 scope 已被销毁时，新的 `launch` 立即取消，草稿 UI 虽清空，实际文件仍留存。清理只能处理未发送草稿，不应误删已移交队列的录音。

Native 新类共七项：注入真实 PCM 并立即复用/清空 recorder 缓冲区以验证 snapshot；保存 WAV/转写 SHA/时长与重读校验；队列拒绝保草稿、接受才移交且后续 cancel 不删除；PREPARING finish 无迟到 start；录音中取消无迟到草稿；capture 出错无可播放半成品；后台 pause 保 READY；screen scope 销毁和已提交 WAV 返回边界的两类清理测试。项目另有 Store/Preparation 的真实内置 TTS 验证，此处不重复合成。

本组使用真实 Main dispatcher、隔离 SQLite 和 WAV，麦克风输入由注入 recorder 产出；试听只核对实际会消费的 PCM，不假称已经播放 AudioTrack、验证物理麦克风或红米回声。`ON_STOP` 的动态合同覆盖生产 `pause()` 入口；ZhiYuApp 的真实 LifecycleEventObserver 绑定为只读源码核对，没有把测试自己注册的 observer 冒称产品整页 E2E。

## 最终只读复审：root 录音、队列与通话增量

本节为独立复审：`ChatVoiceController`、`UnsentVoiceCleanup`、`ChatVoiceMessages`、`LocalVoiceMicrophone` 和 Repository 语音/queued replay/通话接线由 root 实现，本代理本轮仅阅读与记录，没有修改生产源或测试。上文主动联系 17、Store 哈希与原子重连释放包含本代理自己的实现，属于作者自查，不能将本节独立复审结论扩展到自己实现的代码或全项目。

两个录音清理窗口已闭合：IO 内把已提交草稿写入 `AtomicReference`，返回 Main 被取消时，`finally` 仍能取到并清理；正常已接管草稿会清空该临时句柄。销屏后的显式放弃交给容量 32 的单消费者本地清理队列，不依赖已取消的 screen scope。Store 的 owner/主库 origin 保护继续阻止误删已发送录音。

`device-feedback-recording-green-selfie-corrupt-red-native-r8.log` 实际 12 项、1 项失败，失败属于另一图片测试；本组七项录音测试全部 GREEN，包含之前两个真实取消 RED。不能将整份 r8 日志称为全绿。已核对源码中录音取消/finish、前台 pause、PCM 快照和队列 Boolean 接管合同；没有读真实 Key、调用真实麦克风或重跑构建。

复审其余接线：queuedId 回放先核验 owner/chat 下的原话、附件与 voice origin，已有 user 但无 assistant 时终止，不重新收费生成；已有完整 pair 返回原始 pair。通话使用独立 call origin，主文字 history 排除 call_user/call_reply/call_interrupted，VM 通话返回也不把 pair 插入文字列表。`recentOnly` 仅对长度不超过 16 的明确短语白名单跳过 RAG；事实询问仍走原检索，通话近期正文预算 12000、端点静音 480ms 均为源码配置，不等于真机首音性能。路由优先耳机，再 Speaker、再听筒，并保留实际设备路由验证。

初次复审未发现 CRITICAL/HIGH，曾保留一项 P2：Repository 已提交 `voice_reply` assistant 后、`VoiceMessageScheduler.enqueue()` 前若取消或进程退出，正文和 origin 已存在，但 ledger/Worker 可能尚未创建；原 `queuedReplay()` 不修复派生音频，`MessageVoiceContent` 也只轮询。该项随后由 root 修复，最终复审结论见末节。正文在此窗口始终保留，没有重复收费。

清理的 `withTimeout(5000)` 是协作式超时；同步文件/SQLite 调用不承诺五秒硬中断。队列容量和语音总容量有界，进程突然结束时未发送草稿清理仍不具持久保证。这是已知本地清理边界，不应写成持续保活服务或硬实时保证。

### P2 闭环追加：已 claim 的中断任务

root 决定在主 archive 分页读取及 queued pair 回放时，幂等补齐已归档 voice_reply 的本地任务。可直接复用现有 `VoiceMessageScheduler.enqueue(context, owner, chatId, message)`：它核对主库原文 SHA/origin/assistant 身份，`prepareReply` 对已有相同条目幂等，READY/FAILED 不再 enqueue，PENDING 使用 clip 唯一 WorkManager 名称和 KEEP，不重发 LLM。

专门核对“已 claim、进程退出后是否永久 PENDING”：现实现没有该缺陷。Worker 的完整 preparation 在共享 serial Mutex 内；`VoiceReplyPreparation` 先跳过非 PENDING，PENDING 的 `claim` 返回 false 时立即 `fail(interrupted)`。进程退出后的再次执行会安全终止，不重合成；同进程在途 unique work 由 KEEP 保留。为此只补 `anInterruptedClaimBecomesTerminalWithoutSynthesizingASecondTime` 保护测试，预期现行为直接 GREEN，没有伪造 RED 或改动 scheduler/preparation 生产代码；最终完整 Native 结果待 root 汇总。

### 最终恢复接线复核

已实际读取最终 `LocalChatRepository`，确认以下三个入口调用同一 `enqueueReplyVoice`：发送后归档成功、`history()` 当前页内每条消息、`queuedReplay()` 已完整归档的原 pair。它只处理主 archive、assistant、合法 `voice_reply:<clip>`，不会给通话或用户语音安排回复任务。

helper 仅查询本地 ledger 并调用本地 `VoiceMessageScheduler.enqueue()`；不存在 provider.complete/stream/validate、重新发送用户原话或重新生成 LLM 正文的调用。queued replay 在此后直接返回已归档 pair，原缺失 assistant 的 409 分支继续终止，不会再次收费。缺少 ledger 的已归档语音会建立一次 PENDING；已有 PENDING 使用唯一 WorkManager KEEP，READY/FAILED 直接返回；已 claim 但中断的 PENDING 后续只进入 interrupted 终态，不重复 TTS。

`enqueueReplyVoice` 在每条消息内隔离空间不足、PENDING 队列满、文件或 SQLite 等普通异常，只留下用户可理解的语音暂不可用提示，`history()` 仍返回原正文；协程取消继续抛出。主聊天正文、原文来源和既有 WAV 均不改写。由此确认原 P2 在源码上已闭环，指定 root 增量没有剩余 C/H 或该项待修 P2；不将此源码结论冒称断电/杀进程的真机实验。

本次只读读取 r18 构建日志和 JVM XML，确认构建成功及 823 项零失败；没有运行 Gradle/ADB，也没有修改生产源、测试、提交或付费调用。`device-feedback-final-native-r10.log` 仍在进行中，最终 Native 总数及结论由 root 收尾回填。
# 最终运行补记

root 的 `device-feedback-final-native-r10.log` 完整 348 项 GREEN，其中真实主动服务链 3、重连/调度恢复 5、语音 Store 6、Preparation 4、录音 Controller 7、提交恢复 8 项全绿；另 1 项全新断网启动通过。823 JVM 全绿，0.2.12 已打包。下文 RED/待执行文字保留为过程，最终以 [汇总报告](DEVICE_FEEDBACK_2026-10-16.md) 为准。独立复审范围与作者自查仍分别注明，没有新增物理麦克风、真实云模型或红米后台结论。
