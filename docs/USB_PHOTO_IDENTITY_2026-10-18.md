# USB 反馈：OC 外貌与索图修复（2026-10-18）

本文只覆盖 0.2.14 实机反馈里的“固定形象后仍变化”和“有图像 Key 仍不给照片”。时钟、连续输入、Live2D 由主任务另行验证。未读取或复制手机 Key，也未在本子任务发起付费请求。

## 实机证据与根因

- `artifacts/usb-feedback41-real-device-diagnostics-r3.log`：绑定的聊天模型为 `deepseek-flash`，支持原生识图；聊天和独立图像 Key 均可读，图像模型为 `gpt-image-2.5-flare`，外貌导入版本 2，原图引用存在。因此不能把问题归因于“用户没配 Key”或“聊天模型一律不支持识图”。
- `artifacts/usb-feedback41-photo-review-summary-r1.json`：13 个候选中 6 个通过、5 个拒绝、2 个未知。部分风景照因“没有 OC 人物”被拒绝，另有画风不符的拒绝。这些状态不等同于已送达用户，也不能统一改为通过。
- `LocalPhotoPromptHistory.build` 原先在最近 12 条消息没有用户图片时直接返回纯文字，连当前已确认 OC 快照都不返回。用户固定的外貌在普通文字轮次失去视觉依据。
- `LifePhotoPromptClient.create` 原先让模型仅凭轨迹 metadata 撰写图像提示词，没有提供实际 OC 原图。随后全局人物身份约束又应用到纯风景请求，与风景分支冲突。
- `LocalSelfieRules` 漏掉“发张照片给我”“拍张照给我看看”等直接请求。
- `LocalReplyMedia.generateSelfieNow` 原先只传第一个自拍库存 lane。该机会一旦处于 UNKNOWN/FAILED，后两个现有机会也不会检查；底层队列本身已经支持跳过未知机会且不重付。

## 改动

1. 普通文字聊天保留独立的当前 OC 外貌锚。原生识图模型每轮可接收一份最多 512 KiB 的原图传输副本；校验原图 SHA，不更改原文件。它明确标为应用提供的设定依据，不写入用户历史、RAG 或新用户消息。超过消息预算、模型无视觉或引用损坏时不伪造像素或未见外貌；取消操作继续向上传递。
2. 生图提示词作者在支持识图时接收真实原图。人物视角保留身份、服装、附件数量、可见身体范围；纯风景采用专门约束，要求保持参考画风并排除人物。审图明确纯风景的身份检查意味着符合画风和无人物，不要求添加 OC；仍检查场景、道具、画风及实际像素，不绕过审图。
3. 扩展直接索图措辞，同时排除用户发自己的照片、教程、引用、否定以及后续明确取消。不把单纯“你能发照片吗”能力询问当成付费生图请求。
4. 即时自拍把全部三个既有持久化机会交给原队列。一次调用仍最多新生成一张，UNKNOWN 原付费请求不重放、不清账，不自动批准未知审图结果。

当前图像能力状态（Key、OC、审图是否可用）的注入由主任务独立实现，未靠替换所有模型拒绝文本来假称照片已经送出。

## 测试

主任务先构建并实跑了本子任务的回归测试：

- `artifacts/usb-feedback41-photo-red-r1.log`：16 个 JVM 测试，新增的三个行为测试真实失败，分别为后置收件人请求漏检、提示词作者无实际原图、风景仍受全局 OC 人物约束。失败包括断言和缺少图片导致的 `NoSuchElementException`，不是编译失败。
- `artifacts/usb-feedback41-photo-clock-native-red-r1.log`：新增的两个 OC 历史测试实际失败，都是文字轮次返回的 OC 快照为 null；日志亦含主任务的时钟测试，最终统计由主任务记录。
- 已添加边界回归：无视觉、损坏引用、取消、100 条历史时不追加像素消息；旧上传图退出最近 12 条后原图引用仍存在、历史文字不变；写图像提示词仅使用绑定聊天 Key 且不把图像 Key 拼进提示词；首 lane UNKNOWN 后第二 lane 仅新增一次生成并保留原 UNKNOWN。
- GREEN、完整构建、手机照片实际发送与模型质量结果由主任务统一运行并记录。子任务没有运行 Gradle、ADB，没有安装或提交。

## 验证边界

这些修复恢复外貌依据并消除确定的分支错误，不能保证模型每次绘图都绝对一致。新候选仍须看实际像素、通过审图并满足当前 OC/轨迹归属后才发布。已有 UNKNOWN、旧拒绝图片及费用记录全部保留；不会为让测试通过而自动重付或把未知审图伪装为成功。

手机实际是否按请求收到照片，以及现有资料下原图一致性的生成效果，需要主任务的真实验证；本文不把离线单元测试算作云模型效果证明。

## 真机后续：三个自拍机会均为已确认失败

主任务追加的 `artifacts/usb-feedback41-real-photo-inventory-r1.log` 显示：当前 OC 的三个自拍 lane（generation 0）原任务均为 `failed`，无候选及重试 child；图像配置、绑定及当前性均有效。`usb-feedback41-real-selfie-r2.log` 真实保存了无图的 `inventory_pending` 回复。因此“检查全部 lane”仍不足以恢复这个已失败库存。

追加修复只在用户明确索要自拍时启用 `prepare(..., allowFailedRetry = true)`。后台预备默认 false；并且 flag 只对带真实自拍 ticket 的机会生效，普通生活照不能借此重试。锁内私有 helper 与既有公开 `retryFailed` 复用相同校验及持久化唯一 child CAS，不在已有 Mutex 内递归调用公开入口。每次新建 child 后立即结束本轮，最多新增一次生成；原任务及费用记录不改写。UNKNOWN、reserved、running、stale、其他聊天的任务及已有 child 不重新付费，也不重试 child 的 child。

实机的“给我拍张自拍”虽已被 requested 识别，但 standaloneRequest 未包含“拍”，又被当成混合问题交给模型，得到永久否认能力的回答。纯请求现在复用同一个带全句锚点的匹配器；包含额外问题、转述或否定仍保留原回答路径，不吞掉复合问题。

追加测试先由主任务实跑：`usb-feedback41-explicit-failed-retry-red-r1.log` 为 25 项、1 个真实失败；`...-red-r2.log` 为 33 项、2 个真实失败（已失败机会不创建 child、全句索图不被认作 standalone）。新增四个恢复契约及一个全句/复合问题契约，修复后的 GREEN 和真机生成由主任务统一验收。

首次 USB selfie 测试出现长时间无输出，后续前台恢复后 watchdog 才能运行；当前不能据此认定生产代码有锁死。主任务保留该独立测试异常证据，不能把它计作照片链路成功或确定死锁。

## 真机后续：保留已确认失败的安全原因

`usb-feedback41-real-selfie-r3.log` 的显式重试在 20.453 秒内真实完成，但未附照片，原先仍仅给出 `inventory_pending`。此前即时自拍函数只返回图片/null，丢弃了队列返回的失败原因；这会掩盖授权或余额问题，不能把该次完成误称为生图成功，也不能在看到安全原因之前断定一定是哪家服务的问题。

即时自拍现在返回“图片 + 安全失败码”的局部结果，并把仅四种精确代码传入 `LocalMediaPlan.selfieStatus`：`image_auth_failed`、`image_balance_insufficient`、`provider_auth_failed`、`provider_balance_insufficient`。角色正文继续自然说明照片还没准备好；主任务在应用提示中说明对应服务的授权/余额问题。原始上游正文、拼接了其他内容的代码、Key、未知超时及审图不确定都不会通过此接口传播。超时仍为待备，不编造确定失败、不额外重试。

主任务先实跑 `usb-feedback41-photo-terminal-reason-red-r1.log`：2 个测试、1 个真实断言失败。新测试检查四个允许码原样保留，未知、超时、stale、rejected、附加秘密文本等均返回 null。安全原因贯穿后的 GREEN 及手机下一次明确自拍取证由主任务统一执行。

## 真机后续：已附图时不能继续说没弄好

充值后的 `usb-feedback41-real-selfie-r5.log` 在 64.776 秒内通过，真实保存并附上 1 张新 OC 图。但 UI `usb-feedback41-real-selfie-visible-r1.png` 中正文仍说照片没弄好并责怪催促：最终聊天模型沿用了旧对话里的待备状态。照片成功不能掩盖这项文字/附件冲突。

使用独立字段 `LocalMediaPlan.readyCaption` 表示已备好图片的配文，不复用 `unavailableMessage`。仅明确索自拍、必选候选为有效图片且无需保留复合问题全文时，仓库直接采用简短的人格配文，跳过最后一次聊天模型请求。parse 也只有实际选中候选时才采用此配文。借图沿用真实来源校验及消费收据，并在校验后采用 readyCaption；最终 `media.commit` 仍检查当前 OC、像素、轨迹、日期和唯一消费，失败时不会发布“有图”假状态。用户同时提问或连续输入的混合回答仍调用模型、保留完整正文；其提示词明确旧待备状态不能覆盖本轮已有图片。

主任务先实跑 `usb-feedback41-ready-caption-red-r1.log`：14 项 Native、1 个真实失败。失败测试在真实本地已审库存及旧否认 history 下，用“provider 一经调用就抛错”证明就绪纯索图仍错误调用聊天模型；其余 13 项控制通过，包含复合问题及借图来源验证。新增 JVM 边界覆盖人格短配文、缺失/无效/重复候选、非自拍与混合正文保留；修复后 GREEN 及实机就绪库存验收由主任务统一执行。

## 真机后续：失败机会耗尽后的显式补池

R5 消费 lane 2 后，其下一 generation 在 R6 成为 UNKNOWN；另外两个 lane 的 normal 和唯一 retry child 均已在充值前确认 failed。新请求因此没有可支付机会，不能通过重投 UNKNOWN、删除账本或重试 child 的 child 来恢复。

只对新明确索自拍开放补池回调。正式生成前在原队列的同一 Mutex 内调用共用只读 probe，复用实际队列的普通生成、未用纠正、可用 retry、已审候选恢复判断；probe 在 reserve、generate、publish 边界之前返回。有任何可工作机会或绑定不明确时，不补池。全部无法工作时，才挑选一个 normal 及正确唯一 child 都已确认 failed 的 lane；UNKNOWN/进行中 lane 保留自己的 ticket 和全部原账本，不妨碍另一个双 failed lane 补池。随后仍只执行一次正常队列，不能先生成失败再在同一请求补池多付一次。

`LocalSelfieBank.refillFailedLane` 在既有 OC/日轨迹锁内比较全部当前票据，CAS 成功时只为指定 lane 增加一代，取本轮真实日轨迹的当前 slot，保留 usedSha 及其他 lane。每 OC/轨迹最多持久记录 128 次此类显式补池，保留既有 generation 上限；旧 schema 1 档案缺少计数时按 0 迁移，不清除原记录。后台不传补池回调，不能自动推进。

主任务先实跑 `usb-feedback41-stock-refill-red-build-r1.log`：27 个 JVM、1 个真实失败；`usb-feedback41-stock-refill-native-red-r1.log`：1 个 Native、1 个真实失败。新增契约覆盖“双 failed + UNKNOWN”只补一个且旧账不变、可工作机会/后台/未知与进行中/首次新失败均不误补，以及完整票据 CAS、真实 slot、持久 128 上限和已消费像素去重保留。GREEN 由主任务统一执行。

为定位后续 UNKNOWN，DEBUG 包只记录 `prompt/image/review` 阶段、异常类型及固定允许的安全错误码；不记录异常正文、Key、任务 ID、提示词或图片。外部 reason 与账本状态保持原约定，不能据阶段日志把未知结果认作成功。

补充审查发现仅检查 attempt 0 的双 failed 会漏掉“首图已拒绝、允许一次纠正，但纠正 attempt 1 与其唯一 child 都 failed”的耗尽分支。现由实际 `prepareOne → retryKnownFailed → recoverRetry` 的只读 probe 返回耗尽标志，不再额外枚举另一份 attempt 判断；attempt 1 仍须先通过首图 success、当前拒绝审图及合法纠正的既有分支。UNKNOWN 纠正不会产生耗尽标志，也不会推进票据。主任务先实跑 `usb-feedback41-correction-refill-red-r1.log`：28 个 JVM、1 个真实失败，其余 27 通过；新回归同时验证 UNKNOWN 纠正零费用，以及双 failed 纠正补一图后所有旧账保留。修复后的最终验证由主任务执行，不再为此重复付费生图。

## 主任务最终实际结果

- `usb-feedback41-stock-refill-native-green-r1.log`：SelfieBank 8 项、Borrow 7 项，15/15 通过，4.859 秒；不是把 RED 的选择数量当成功数量。
- `usb-feedback41-real-selfie-r7.log`：手机普通聊天仓库真实索图，1/1 通过，56.538 秒，实际 1 张附件、9 字就绪配文。随后普通 App 页面已显示“这张给你，慢慢看。”与新图，证据 `usb-feedback41-real-selfie-r7-visible.png`。不再重复使用 R5 旧否认文字作为成功结论。
- `usb-feedback41-real-photo-inventory-r3.log`：最终安装后只读检查，lane 2 generation 1 仍是 UNKNOWN，未改为 failed、未清除或重投；lane 0 可正常继续准备新照片。该检查 1/1 通过，1.82 秒。
- `usb-feedback41-final-build-r8.log`：完整 899 JVM / 132 suites、0 失败/错误/跳过，lint 0 Error、79 Warning、7 Hint。RemoteSceneRecovery 28 项与就绪配文测试均通过；`usb-feedback41-final-native-r3.log` 85 类 / 428 项、441.338 秒完整通过。
- 非 Live2D 独立审查发现的两处补池/队列 P2 已真实 RED 后修复并复核，无剩余 C/H/P2。子任务作者自查与主任务实跑、独立审查分开记录。

图像仍依赖真实接口与审图。一次成功约 56.5 秒不代表瞬时生成；当前图保留原参考主要外貌，但不能保证任何 OC 的五官、服饰、复杂附件或动作永远不变。拒绝和 UNKNOWN 继续保留，不能为速度或通过率伪造成功。
