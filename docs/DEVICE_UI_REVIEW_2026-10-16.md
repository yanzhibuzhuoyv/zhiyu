# 队列、动态人物、OC 自认与表情退役独立审查

root 最终运行补记：`device-feedback-final-native-r10.log` 完整 348 项 GREEN，包含本页 Watercolor 4、PhotoSend UI 3、SelfieBank 5、OutgoingCommitRecovery 8、StickerRetirement 10；另 1 项全新断网启动通过。r18 构建成功，JVM 复用未变的 r17 全量 823 项通过结果。独立审查与作者自查边界沿用下文，不把 root 执行写成代理执行。安装包见 [最终报告](DEVICE_FEEDBACK_2026-10-16.md)。

审查只覆盖本轮 11/23/24、13/15、16、18，以及后续补入的 14 自拍备库。没有修改旧后端、rig2d 核心、旧回执，未运行 Gradle/ADB、提交 Git、访问真实 Key 或收费服务。构建与测试由 root 统一执行；以下状态来自实际日志。

## 已发现并修复的问题

- 用户原文已在 IO 落库、返回主调前取消，会丢失提交回调。队列改为跨 attempt 复用稳定 `item.id`，Repository 持久化原文和回复请求标识；已提交无回复只重新发布提交事实并停止，完整 pair 直接重放，不再次请求模型。
- 自动脸部动画原先只固定矩形纹理边界，椭圆剪裁轮廓仍会移动。改为每轴至少 9 个采样点、紧支撑径向包络；实际受影响三角形完全位于脸部轮廓内，原纹理 UV 不变。过小的脸无法达到可见 idle 位移，最低眼距提高到 18 个预览像素，退回手工标注。
- 可选 OC 参考 metadata 丢失、creator manifest 损坏会阻断用户主图。Repository 对可选归属资料降级，取消继续传播；有有效快照时，最终保存仍执行当前 OC CAS，损坏原资料不覆盖。
- 两项旧登录测试改为队列行为合同：原文进入 FAILED 快照、较新 composer 不被回填，聊天、绑定、Key 保留且不自动重发；显示错误不得泄露 provider 原始 payload、Key、内部诊断或电脑后端措辞。

## 验证证据

| 范围 | 实际结果 |
| --- | --- |
| 队列稳定 ID、真实椭圆轮廓 | `device-feedback-queue-id-motion-red-r10.log` 两项真实断言 RED；r12 全 JVM 中对应合同 GREEN |
| LoginStartup 两项行为与隐私断言 | `device-feedback-integration-build-r12.log` GREEN |
| 18px 小脸门 | r12 对应新断言真实 RED，随后源已修复；最终 GREEN 以 root 后续全量日志为准 |
| 原文提交取消恢复、完整 pair 重放、ID 原文绑定、可选 OC 损坏 | `device-feedback-integration-native-r7.log`，`OutgoingCommitRecoveryNativeTest` 5/5 GREEN |
| 表情包退役 | 同 r7，`StickerLibraryRetirementNativeTest` 10/10 GREEN |
| 首轮自拍备库合同 | 同 r7，`SelfieBankNativeTest` 4/4 GREEN；后续独审问题另列，不视为边界已全部验证 |

r12 全 JVM 为 820 项、2 项失败，r7 Native 为 43 项、2 项失败；不能把本范围通过写成整轮全部通过。r7 的两个失败是另组录音取消清理合同。

音色另用本地脚本独立将随包 speakers 顺序连接已下载官方 `spk-info.txt`；174 条恰为 138 女声、36 男声，两个集合及两源 SHA 与 `LocalVoiceCatalog` 完全相符。没有重新排列原 speaker ID。

## 其他已核对边界

- 分段保留完整原文 offsets；URL、代码块、emoji 不按长度硬切，复制仍复制整条原文。
- 队列 attempt/epoch 门阻止旧回调污染重试；失败暂停后续消息，已提交失败不能重新 POST 用户原话。
- 未识别、侧脸、多人图保留原图并提供手工入口；自动临时动作没有编造眼嘴地标，不移动整幅背景。
- OC 参考与导入原图 SHA 来源绑定当前快照；可选参考加主图总计仍受 5 MiB 限制。
- 表情退役扫描所有 owner 的历史 pins，完整验证 metadata 后写原子 intent；已发图片和共享 SHA 保留，未知孤儿不删。包过滤仅移除 20 张 sticker，保留两张旧 image。

真实云模型识图、照片审核准确率、物理麦克风、红米帧率及后台到达率不由这些隔离合同证明。

## 自拍备库追加独审

已完成本轮增量只读检查，没有发现新增 C/H；发现两个具体 P2，均已通知 root，由作者补合同和修复。本审查没有修改自拍生产源。

1. 请求方向判定：`LocalSelfieRules.requested()` 把 `给你看张自拍`、`教我怎么自拍`、`我想拍张自拍` 当成索取恋人自拍，会强制附图、消耗备库并触发补库。最低复现是对三串调用该规则，预期 false；正向 `给我发张自拍`、`想看你的自拍` 应继续 true。
2. 损坏可选 creator 的降级只包到 prepare：`selfiePlan` 捕获损坏 manifest 后返回自然待备图方案，但 `commit → withSelfieIdentity → characters.withCurrentSnapshot` 在内部 fallback try 之前再次读取相同坏文件，抛出 JSON 异常，实际聊天失败。最低 Native 复现为导入 OC、向 `oc_identity_v1/<chat>.json` 写入坏内容，再调用 `prepare(...,"给我发张自拍")` 和 `commit(plan,...)`；应返回无附件自然等待文字并保留坏文件，实际在 commit 抛出。

其余本轮检查：三 lane/generation 入收费 ID，Key/模型变更不会给同机会换 ID；未知或中断收费结果只保留，不自动重发；满 128 消费 SHA 时不给新 targets；每个 worker 至多一次新生成，候选容量门在 reserve 前；原图 SHA、当前参考 SHA、已消费 SHA 不作为新自拍；发布和消费都在当前 media/profile → OC → 持久日轨迹短锁边界内复核，最终发布还核对实际 candidate 审核、收费 ledger、联系版本与当前取景。自拍素材不进入普通随机媒体选择；清理私有候选不删除已发布媒体、聊天附件或收费记录。这里核对的是代码合同，不证明云端实际审图准确率。

## 最终整轮后的两组 UI 测试合同修复

`artifacts/device-feedback-final-native-r9.log` 已实际结束，349 项中 343 项通过、6 项失败，原日志保留。本次只修改 `WatercolorUiTest` 和 `PhotoSendUiNativeTest`，生产源不变；其中五项失败来自旧 UI 断言或 Proxy fixture，另一个全新安装前提由 root 使用独立空 AVD 验证，不清除原模拟器已有凭证，也不放松启动 guard。

- Watercolor 两项旧断言仍寻找含换行的整条 assistant 文本，而产品已经按 `ResponseSegments` 分段、逐段 trim 后绘制。测试现在逐段核对气泡和可见文字，重组仍等于完整原文，并实际点击该 assistant 自己的复制按钮，读取 clipboard 验证换行等原文完整性；浅暗主题参数、可读输入/发送控件、自定义背景替代默认花卉及截图非空断言均保留。
- PhotoSend 三项 Proxy 原先只处理 `sendPhotoMessage`，新队列实际调用 `sendQueuedMessage`，因此没有进入模拟提交回调。fixture 现按 `id/content/requestId/image/voiceDraftId/onSubmitted/Continuation` 七参数接线，使用真实传入的文字和附件，记录稳定请求 ID 与调用次数；图片实际渲染像素断言继续保留。草稿清理调用隔离存储的真实 `clearDraftIf(oldId)`，确保上次发送结束不会清除后来选择并持久化的新附件。
- 已提交后回复失败继续要求原图片可见、只有一条用户原文、草稿不恢复，队列为 `SUBMITTED_NO_REPLY` 且绑定原 user ID；不显示 retry 按钮，直接调用 retry 也不能再次发送。显示错误与队列说明不得包含合成 provider payload、合成 Key 或内部失败正文，队列说明应明确无需再发；不再要求原样展示 provider 错误。

复读已核对上述测试与 `ChatMessageBubble`、`ChatRepository.sendQueuedMessage`、`OutgoingMessageQueueController`、`ChatScreen`、`cleanupSentPhotoDraft → photos.clearDraftIf` 的实际生产接线一致，本次范围没有残余 C/H。本轮没有修改生产或其他测试文件，没有运行 Gradle/ADB 或提交。

`artifacts/device-feedback-final-build-r18.log` 确认测试 APK 编译成功，这只证明编译。root 正在执行 Native r10 的 68 类整轮与独立空 AVD 的启动用例；实测结果以 root 最终日志为准，尚未把本次修复写成已运行 GREEN。浅/暗主题完整覆盖仍取决于实际 instrumentation 参数，不能仅由一次默认参数运行推定两种主题都已实测。
