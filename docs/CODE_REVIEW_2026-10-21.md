# 知语整体代码审查 · 2026-10-21

> 后续修复：R1–R6 已进入 0.2.20，结果与边界见 [修复报告](REVIEW_FIXES_2026-10-21.md)。以下保留审查时的原始发现和失败证据，定位行号属于审查基线。

审查基线：`8e61cd584239467002fd565deb2a8e352ca37b28`，App `0.2.19 / code 25`。

**确认 6 组需要处理的问题：1 项 P1、5 项 P2。其中 5 项是产品行为问题，1 项是测试接口失配。最优先处理 R1：当前记忆裁剪可能永久删除仍含独有内容的原话。**

本轮完成代码审查、缺陷复现和证据归档，未修业务代码、未发布 APK、未提交或推送。下文区分新增发现、已有设计限制和验证缺口；通过的历史测试不能抵消本轮新增反例。

## 确认的问题

### R1 · P1：用第一分块的相似度决定删除整条原话，可能丢失独有记忆

- 定位：[LocalMemoryPruneService.kt](../android/app/src/main/java/com/zhiyu/app/data/LocalMemoryPruneService.kt#L39)，第 39–52、59 行。
- 关联：[LocalSemanticStore.kt](../android/app/src/main/java/com/zhiyu/app/data/LocalSemanticStore.kt#L120)，第 120–125 行；[LocalChatDatabase.kt](../android/app/src/main/java/com/zhiyu/app/data/LocalChatDatabase.kt#L403)，第 403–424 行。

裁剪只取旧消息的第一个向量，找到更新、同角色、权重不低且相似度达到 0.95 的来源后，就将旧消息整条加入删除列表。它没有验证后续分块，也没有证明新消息包含旧消息的全部事实。数据库随后永久删除原文及向量、FTS、标注等派生数据。

**实际复现：**旧消息开头是重复的日常描述，结尾另有“周五去松林路接姐姐”的独有约定；更新消息只有相同开头。建立 40 条符合年龄条件的旧候选，并保留 100 条近期消息后，真实 SQLite 服务返回 `scanned=1, confident=1, deleted=1`，旧原文从 1 条变成 0 条。2.5% 数量上限只限制损失数量，不能保证被删内容冗余。

该测试使用人工构造的二维向量，验证“第一块匹配即删除整条”的控制流，不代表真实 embedding 模型的误删率。即使相似度为 1.0，未比较的尾部仍无法据此判为重复。

**建议：**先停止基于该条件的不可逆原文删除，保留原文并压缩或移出活跃索引。若保留去重删除，只允许已经证明整条原文重复且来源关系安全的情况；删除时在事务内重新验证。不要把提高余弦阈值当作完整性修复。

证据：`RepoReviewPruneNativeTest.sharedFirstChunkMustNotDeleteUniqueTail`，见下方证据目录。现有记忆裁剪文档明确选择永久删除，本项指出的是判重依据不足，并非将“所有删除”一概认定为实现错误。

### R2 · P2：已点发送、尚未提交的消息只在内存中，重建后丢失

- 定位：[OutgoingMessageQueueController.kt](../android/app/src/main/java/com/zhiyu/app/ui/OutgoingMessageQueueController.kt#L59)，第 59–65 行。
- 关联：[ChatViewModel.kt](../android/app/src/main/java/com/zhiyu/app/ui/ChatViewModel.kt)、[AppState.kt](../android/app/src/main/java/com/zhiyu/app/ui/AppState.kt)。

点击发送后，代码把消息加入 `pendingOutgoing` 并清空输入框，但没有先写入持久发件箱。第一条仍在等待模型回复时，后续消息可能长时间停留在这个内存队列。ViewModel 被销毁、重新创建后，既不会恢复队列，数据库也尚无这些消息。

**实际复现：**第一条请求挂起，第二条点击发送后可见于队列且输入框为空；保留同一个模拟仓库，清理并重新创建 ViewModel，第二条在待发列表和历史消息中均不存在。新增 JVM 断言失败。

这是已经在旧交接中披露的设计限制，本轮补充了可执行反例。测试模拟 ViewModel 销毁重建，不是实际 Android 进程杀死实验；普通旋转如保留原 ViewModel，不一定触发。

**建议：**接受发送前先持久保存原文、附件引用、requestId、目标对话及提交状态，再清空输入框。恢复时区分“从未提交”和“已提交但结果未知”，避免恢复队列时重复调用付费模型。

证据：`RepoReviewQueueTest.acceptedUnsubmittedMessagesSurviveViewModelRecreation`。

### R3 · P2：照片先记为已发送，聊天保存失败后却无法再次发送

- 定位：[LocalReplyMedia.kt](../android/app/src/main/java/com/zhiyu/app/data/LocalReplyMedia.kt#L89)，第 89–102、249–253 行。

生活照分支在调用聊天保存回调之前，已将图片 SHA 写入持久“已交付”集合。若之后的消息写入失败，两处存储没有共同事务或恢复协议：用户未收到图片，但后续选择会永远排除该图。

**实际复现：**从正常图库选择猫咪图片，在 `commit` 的保存回调中注入写入前异常；重新创建 `LocalReplyMedia` 后再次索图，原本有效的图片不再被选中，`requiredAttachmentId` 变成 `null`。这是故障注入，未模拟物理磁盘损坏。

旧交接已记录类似“先消耗、后保存”的取舍，本轮验证生活照路径同样受影响。它能减少重复发送，却可能让实际未交付的素材永久失去机会。

**建议：**引入可恢复的预留、消息落盘、确认交付状态，或将两者放入同一数据库事务。不能只把两行简单调换：消息保存后、标记前崩溃又会带来重复发送，需要按稳定消息 ID 恢复和核对。

证据：`RepoReviewPhotoNativeTest.failedChatSaveMustNotConsumeAnUnsentPhoto`。

### R4 · P2：裁剪每轮只看最早 400 条，后面的候选可能永久得不到检查

- 定位：[LocalMemoryPruneService.kt](../android/app/src/main/java/com/zhiyu/app/data/LocalMemoryPruneService.kt#L39)，第 39、69–75 行。
- 关联：[LocalChatDatabase.kt](../android/app/src/main/java/com/zhiyu/app/data/LocalChatDatabase.kt#L387)，第 387 行按 `seq ASC` 排序。

`old.take(MAX_SCAN)` 每轮都从最早的 400 条开始，没有续扫游标。当前 400 条一直找不到可替代的新来源时，它们不会被删，下一轮仍占满扫描额度。即使后面的消息已经完整重复，也永远没有机会进入扫描。这与“分多天逐步处理”不同。

**实际复现：**440 条旧候选中，前 400 条无更新代表，后 40 条有更新副本；连续两次直接执行裁剪，均为 `scanned=400, confident=0, deleted=0`。定时入口另会记录本轮时间，重复等一天也不能改变这个固定前缀。

**建议：**持久保存扫描游标，每轮无论是否删除都推进；到末尾后回绕，重新评估旧候选。测试至少覆盖首批零删除、后批可删除、重启恢复游标，以及新增消息。

证据：`RepoReviewPruneNativeTest.scanningMustEventuallyReachEligibleRowsPastFirst400`。

### R5 · P2：生活照交付记录达到 4096 条后，所有新生活照都被拒绝

- 定位：[LocalReplyMedia.kt](../android/app/src/main/java/com/zhiyu/app/data/LocalReplyMedia.kt#L204)，第 204、241–252 行。

`used.size < 4096` 被放在所有候选的过滤条件中；`consumePhoto` 同样拒绝满额集合。该集合按对话持久累加，没有日切、分页或迁移机制。因此达到 4096 个不同图片 SHA 后，即使有新的合格素材、正确 Key 和新轨迹，显式索要生活照也无法再选中图片。

**实际复现：**写入 4096 个与当前素材不重复的合成 SHA，放入一张真实可读取的新猫咪图；准备发送仍返回 `requiredAttachmentId=null`。这验证容量边界，不是实际累计发送 4096 张的长期测试。影响范围是这里的生活照交付路径，不能据此说所有自拍分支也在同一上限停止。

**建议：**将已交付 SHA 移到有唯一索引的持久表，按当前候选查询是否已发，不要把整个聊天寿命绑定到 SharedPreferences 集合上限。迁移时保留历史去重语义，不能简单清空集合让旧图重复发送。

证据：`RepoReviewPhotoNativeTest.oldDeliveryLedgerMustNotDisableAllFutureNewPhotos`。

### R6 · P2：探针测试没有同步新函数签名，三项安全清理回归失效

- 定位：[test_native_emulator_cloud_probe.py](../scripts/tests/test_native_emulator_cloud_probe.py#L77)，第 77、102、119 行。
- 关联：[native_emulator_cloud_probe.py](../scripts/native_emulator_cloud_probe.py#L62)，第 62 行。

`execute_probe` 新增必填参数 `resume_fifty_million`，测试还按旧位置调用。第 77 行的停止回调被绑定成了 resume 参数；第 102、119 行缺参，直接抛 `TypeError`，尚未验证预期的超时和设备身份保护。

**实际结果：**脚本整套 799 项通过、3 项失败，另有 56 个子测试通过。三个失败均定位到上述调用。

**建议：**改用明确的关键字参数，并增加正常、50M、新建和恢复模式的选择断言；保留中断、超时、设备身份变化时的清理保护。当前命令行入口传参正确，本项不代表真实探针已经泄露 Key 或误停其他设备。

## 本轮验证结果

| 范围 | 本轮实际结果 | 说明 |
| --- | --- | --- |
| Android JVM | 975 项：974 通过、1 失败；147 suites | 失败是新增 R2 反例；原有 974 项全部通过 |
| Android 定向设备测试 | 15 项：11 通过、4 失败；15.553 秒 | 11 个既有对照通过；4 个新增反例对应 R1、R3、R4、R5，使用隔离模拟器数据 |
| Python 后端 | 956 + 30 = 986 项通过 | 核心与人物处理分别运行，非声称一次整轮；核心报告 1 warning |
| 脚本 | 799 通过、3 失败；56 个子测试通过 | 失败见 R6 |
| rig2d 核心 | 220 项：217 通过、3 失败；24 suites | 三项缺少 SAM2 旧测试素材，见下方限制 |
| Native C++ 合约程序 | 4 个程序、58 个检查通过 | JNI 输入 10、latent 18、condition 19、VAE 11；未运行实际生图模型 |
| Remotion 宣传片源码 | TypeScript 检查通过 | `npm run check`；未重新渲染影片 |

Android 主程序与测试包构建成功。中间一次模拟器恢复旧快照后产生主程序/测试包不匹配，日志 `photo-repro-and-controls-r2.txt` **不计入结论**；重新安装配套包后的 `native-repros-final.txt` 才是上表依据。

首次 Python 收集分别缺少 NumPy、psutil；补装到既有本地 `.venv` 后按上表完成测试。未因此修改依赖声明或锁文件，也不把首次环境缺包当作业务缺陷。

## 覆盖范围与验证限制

本轮以全仓目录清单、重点调用链阅读、现有测试和新增故障反例相结合。基线共有 1,522 个受 Git 管理文件，识别到 1,178 个源码/脚本文件、约 125,333 行；统计包括测试和研究代码，**不是逐行审完所有文件或达到 80% 覆盖率的声明**。

| 模块 | 重点检查内容 |
| --- | --- |
| Android | 聊天队列、原文存储/迁移、语义与词法检索、裁剪、主动联系调度、时间天气、日历、图片/语音发送、OC 素材、摄像头生命周期、Live2D 包与绘制接口 |
| 历史 Python 后端 | 会话鉴权、owner 范围、请求大小、聊天与 RAG、文件解析、媒体导入、图片处理、部署配置 |
| rig2d / native | 通话会话与取消、音频队列、模型包解析、JNI 参数和尺寸校验、推理合约 |
| scripts / shared / qwen-image-edge / promo | 脚本入口、探针清理、共享契约、模型导入边界、研究代码结构与宣传片类型检查；研究模型未重新训练或推理 |

安全检查涉及凭据加密、网络目标限制、文件/ZIP 路径与尺寸、导出组件和敏感日志。受控文本模式扫描未发现匹配的真实 Key 或私钥头，但模式扫描不能证明仓库完全没有秘密；未读取用户 Key 文件，未做外部渗透、最新依赖漏洞数据库扫描或发布合规审计。

其他需要明确保留的边界：

- rig2d 三个失败来自 `PromptMaskBridgeIntegrationTest` 缺少 `artifacts/sam2-model-eval-2026-10-13-r2/{astronaut,colored_oc,line_oc}/packet.properties`。它们属于既有测试资源缺口，不能当作本轮证明“Live2D 在手机上不动”。建议补可分发夹具，或将外部素材集成测试显式分组并报告前置条件，保留其原有校验。
- `LocalSemanticStore.search` 会遍历匹配分区内的向量；裁剪每个候选又分别搜索 active/cold，最大 400 个候选会放大工作量。这是代码可见的规模风险；本轮未测十万向量的手机耗时，不据此给出毫秒或耗电结论。后续应单独测前台检索与后台裁剪竞争，再选择候选缩小或索引方案。
- 本轮未连接物理手机，没有真实声学全双工、蓝牙/AEC、通宵通知到达率、真实定位天气、云识图准确率或人物生图一致性验收。未调用付费模型，未重跑万轮/五千万长测。
- 已有相机/语音测试通过不能证明任意用户设备与素材都正确；上述反例也不用于推断用户已经实际丢失了数据。

## 证据与后续修复顺序

本机证据目录：`artifacts/repo-review-2026-10-21/`。此目录被 Git 忽略，不随公开仓库分发。

- `validation-summary.json`：基线、重算测试数量、失败名称与验证限制。
- `android-jvm-xml/`、`rig2d-xml/`：本轮 XML 冻结副本。
- `native-repros-final.txt`：15 项定向原生测试的最终有效结果。
- `backend-core-tests.log`、`backend-portrait-tests.log`、`script-tests-r2.log`、`promo-check.log` 与四份 C++ 日志。
- `RepoReviewPruneNativeTest.kt`、`RepoReviewPhotoNativeTest.kt`、`RepoReviewQueueTest.kt`：三份复现源，含 5 个预期安全行为断言；在当前产品代码下失败。审查结束后移出正常测试源目录，保留于此，避免把审查专用失败用例误当业务补丁交付。

修复时可将对应反例恢复到原测试包，先确认仍失败，再针对性修复并保留回归：

1. **R1 先做**：保证不因局部相似而永久删除独有原文；补长消息多块、冲突事实、同开头不同结尾、来源并发变化测试。
2. **R2、R3**：补齐发送与交付状态持久化，测试写入失败、重建恢复和结果未知，维持不重复付费、不重复交付。
3. **R4、R5**：修复长期增长下的扫描推进与交付容量边界。
4. **R6 与素材前置**：恢复测试入口可信度，再做与修复范围相称的回归。

这份报告的完成状态是“审查完成，问题待修复”，不是“整个项目已无问题”。
