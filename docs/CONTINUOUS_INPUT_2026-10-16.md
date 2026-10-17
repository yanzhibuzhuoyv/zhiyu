# 连续普通文字统一回复 · 用户反馈 26

本轮承接已冻结的 0.2.12，旧报告、APK、receipt 不改。范围仅是短时间连续发送的普通文字合成一次回答；每条用户原文仍单独保存、分别进入 RAG，assistant 继续按既有展示规则分段。最新一条改换话题时，应优先回应最新意图；不会因此清除历史。时间/话题提示由 root 集成。

## 接口合同

- `QueuedTextInput(requestId, content)`：每条用户确认发送的独立原文及稳定 ID，按输入顺序排列，不拼成新用户消息。
- `QueuedTextSubmission(requestId, message)`：明确回传每条原文的持久 user 记录。
- `QueuedTextBatchResult(submitted, assistant)`：提交列表必须与输入一一对应、user ID 各不相同，整批只有一个统一 assistant。
- `ChatRepository.sendQueuedTextBatch(chatId, inputs, onSubmitted)`：先原子保存整批 user，再按输入顺序回调 `(requestId, Message)`。有任何已提交但无完整统一回复时不再调用收费模型；已有完整 pair/batch 则直接返回。相同原文 ID 不能换文字或换批次成员顺序。
- 默认合同只允许单输入委托既有 `sendQueuedMessage`；不支持多输入的 repository 在产生任何提交前明确拒绝，不能通过默认循环给每条各生成一次。
- 一批至多 32 条、合计 32,000 字符，每条仍不超过 16,000 字符。验证有界、非空和唯一请求 ID，保留原文与合法 Unicode。

## 队列行为

Queue 已使用 750ms 安静窗口，首条开始后合并窗口最多延续 2,000ms；此前请求排队、断连和网络时间不包含在这 2 秒内。在入队时确定普通文字的相邻分组，而不是等前一次 HTTP 结束后再把所有排队文字重新合并；已经过了不同安静窗口的输入不会因早先 HTTP 慢而误并。两个有界 coroutine timer 关闭窗口，不依赖真实时钟推进，因此 virtual-time 合同能精确检验边界。

只合并相同 chat、连接、epoch 的相邻普通文字。图片、带附件文字、语音各自保持原请求，并关闭此前普通文字窗口。已经启动的 HTTP 不取消、不为了合入新文字重发。两条及以上走 batch API，单条走原稳定 ID 接口，保留现有图片/语音行为。

每条保留独立 id/attempt/epoch 和 immutable draft。未提交失败只显式重试原批次、原 ID；任何已提交事实都阻止整批重新 POST。队列容量、失败暂停、迟到回调门、较新 composer 与后选附件保护仍在。

合并窗口内及其他未提交条目仍属于 ViewModel 内的待发送队列，沿用 0.2.12 的边界，不承诺进程被杀后恢复。开始 Repository 提交后，原文与批次幂等事实由 SQLite 保存；这两个状态不能混称已经持久发送。

## 当前 TDD 状态

已新增 domain/API 合同和 `ContinuousInputQueueTest` 12 项，覆盖安静窗、2 秒上限、已启动请求不取消、图片/语音边界、排队期间不同窗口、原组稳定 ID 重试、已提交失败不重发、连接切换取消、旧 attempt 回调、32 条容量、32k 上限及默认接口不循环发送。

`artifacts/feedback25-batch-capture-red-build-r2.log`：新 12 项实际执行，10 项失败，其中 9 项为旧 Queue 立即发送造成的真实 Assertion RED；容量用例在观察到多次请求后直接调用 `single()` 导致一项测试形状异常，该异常不算业务 RED，已补先断言只有一次请求，再检查 32 条原文。

RED 后已实现 Queue 与 `OutgoingDraft.textBatchId`：每条独立 ID/attempt/epoch，关闭窗口才冻结同组发送；整组未提交失败显式恢复同组，各条 attempt 一并推进；有任一提交回调时，同组均不重新 POST。已同步旧 `OutgoingChatQueueTest` 的 750ms 推进，fake batch 仍存每条 user 并只生成一个 assistant。图片/录音接口和即时提交合同保留。

`artifacts/feedback25-runtime-batch-green-borrow-red-r3.log` 已实际执行：`ContinuousInputQueueTest` 12 项和 `OutgoingChatQueueTest` 11 项全部 GREEN。同次共 51 项、4 项失败均为自拍借图新增 RED，不是整轮 GREEN。Repository batch 原子存储与有序 fingerprint 后由 root 实施并独立验证，不能用 Queue fake 通过替代真实 SQLite/付费调用边界证据。

复审 Queue 的窗口/epoch/attempt/单条 callback：连接失效取消未关闭窗口，保留快照；已开始 HTTP 不因新输入取消；原组重试不吸收较新组，旧 attempt 回调不发布；单条、图片和录音继续校验原内容、附件/voice origin。Queue 范围没有剩余 C/H；Repository 独审发现、RED 与修复证据分列如下。

## Repository / SQLite 独审及新增 Native 合同

只读复审 root 本轮 `LocalChatRepository.sendQueuedTextBatch`、`LocalChatDatabase.appendQueuedTextBatch` 与 `QueuedBatchOriginals`：每条原文使用独立稳定 alias；批次 fingerprint 按有序 request ID 与原文 SHA 计算，验证每条 member alias 均指向同一批末条 user。原文、FTS、user alias、成员关系和批次标记位于同一外层 SQLite 事务，通知回调在外层 commit 返回后执行。完整批次重放复用统一 assistant；已有 user、没有 answer 时拒绝第二次 LLM，单条接口也拒绝拿批次 member 脱组重放。不能用这些只读观察替代真实事务、取消及 replay 测试。

独审先发现两项并报 root，生产实现由 root 修复；以下保留 RED 时的原因与最小复现：

- **HIGH：旧批次重放重新执行主动联系命令。** RED 时在验证持久批次 membership / 已有答案之前执行每条 `contactAware`。最小复现是先完成“先别主动联系我”与“可以主动联系我了”批次，再由用户单独暂停主动联系，然后重放旧批次；旧 resume 会覆盖较新的暂停。应先验证持久批次，再让合法 replay 直接返回，不能重新执行命令；新的合法批次仍按原始顺序解释命令。
- **P2：后条明确取消并转题，前条自拍仍被执行。** RED 时 runtime 已参考末条意图，但 media 入口仍扫描整批拼接文本。最小复现为“给我发张自拍”紧接“不用发了，换个话题，聊聊今天的新杯子”；没有库存时直接给待备图回复，跳过新话题，有库存时可能消费旧请求。应在明确转题或取消点后裁取行为解释窗口，原文与对话上下文仍分别完整保存；普通补充“发自拍”加“在家那种”必须继续合起来理解，不能一律只看最后一条。

新增 `ContinuousBatchRepositoryNativeTest` 3 项，使用独立真实 SQLite / contact 状态与 synthetic provider，不读取真实凭证、不联网：

1. 旧批次重放不能撤销之后的明确暂停，且不新增 assistant / provider 请求。
2. 后条显式取消转题时不执行旧自拍，两条独立 user 与 RAG 原文仍完整，统一回答新话题。
3. 观察到事务内第一条真实 insert 后由 commit guard 取消，要求整组原文、FTS、request/member/batch alias 与 updatedAt 全部 rollback，既有历史保留。

`artifacts/feedback25-integration-native-green-contact-topic-red-r3.log` 已实际执行：同轮 24 项、2 项失败，恰为前述两个业务 Assertion RED。取消自拍转题用例要求 1 次 provider，实际 0 次；旧批次重放用例的新暂停为 revision 3 / paused=true，重放后实际 revision 5 / paused=false。事务内首条 insert 后取消并整组 rollback 的第 3 项已 GREEN。该轮同时的 CurrentContext 4 项等已通过，不能称为整轮 GREEN。

root 随后将 batch 联系命令移到 `send` 的 serial 锁内，仅当 incoming outgoing-ID 全未提交时解释；已提交或重放的批次不再重新 apply。`RuntimeDialogueContext.actionText` 为明确转题 / 取消裁取动作输入窗口，media、calendar、voice 与 retrieval 已全部接入；provider messages 仍是逐条完整原文。补充“发自拍”加“在家那种”或“对了，穿红裙”仍合并，单独“对了”不裁动作窗口。以上是源码复审状态，尚待后续 Native GREEN。

修复复审另报两处同源边界，并补测试实跑后修复：

- 单条 `sendQueuedMessage` 当时仍在 serial 外 `contactAware`，取已完成 batch 的 RESUME member 走单条接口时，虽然最终被 `queuedReplay` 拒绝，仍会先覆盖用户后来 PAUSE；完整旧单条 replay 也应跳过旧命令。必须在所有 queued 入口验证已提交 / member 状态后才允许新命令副作用。
- `actionText` 当时按整条 original 的位置截取；后条若为“发张自拍，算了，换个话题，聊杯子”，保留整条仍让自拍分句被 `any` 命中。行为窗口需覆盖同条里的明确取消 / 转题边界，不能只裁前一条；历史与 RAG 继续保留所有原话。

为第一处新增两项 Native 合同，当前该类共 5 项：拒绝已提交 batch member 的单条重试时，仍须没有 callback / 新 provider / 新历史行，并保留较新暂停；完整旧单条 replay 返回同 pair、不二 provider，亦不得重新 apply 联系命令。`artifacts/feedback25-single-replay-contact-native-red-r5.log` 已实际 5 项 / 2 fail，恰为新增两项状态 Assertion RED：被拒 member 把暂停 revision 3 改成恢复 revision 4；完整旧单条把暂停 revision 2 改成恢复 revision 3。该轮原有 3 项 GREEN。普通发送均使用 queued 入口；此次没有扩大修改非 queued 的旧 API。

同条取消边界的 pure 用例在 `artifacts/feedback25-inline-cancel-red-build-r7.log` 实际运行 Runtime 6 项 / 1 fail，`cancellationInTheSameOriginalAlsoStopsAnEarlierPhotoAction` 为真实 Comparison RED；编译成功但测试任务失败，不冒称整次 build GREEN。root 随后从最后一个明确取消 / 转题标记的字符位置起裁取动作文本；没有此边界时保留所有输入及相关 modifier。

最终源码复审：`sendQueuedMessage` 已不在外围调用 `contactAware`，所有 queued 单条与 batch 统一在 `send` 的 serial 锁内先查询每个 incoming 的 outgoing / member alias，只有全未提交时才按原始顺序执行新联系命令。合法旧 replay、已提交无 answer、成员 / 内容不符的拒绝均跳过旧命令副作用；membership / 有序 SHA 校验和不二 provider 分支保留。`actionText` 已裁同条字符边界，media、calendar、voice 与 retrieval 均使用该窗口；DB / RAG / provider history 继续使用逐条完整原文。本次指定 Queue / Repository 联系重放 / 动作窗口范围未发现剩余 C/H，源码修复的最终 GREEN 仍以 root 后续统一日志为准。其他代理负责的待备图混合求助与 PCM 包容量不计入本报告审查范围。

代理不运行 Gradle/ADB、不读取真实 Key、不调用收费服务、不提交。不能把源代码中存在测试视为测试已经执行或通过；全量回归和真实 Repository batch 结果以 root 后续日志为准。
