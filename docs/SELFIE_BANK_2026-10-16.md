# 自拍备库 · 用户反馈 14

最终状态：`device-feedback-final-native-r10.log` 中本组 5 项全部 GREEN，包含坏 creator 降级和保存恰一次；该整轮 348 项全绿。r17 全量 JVM 823 项通过，r18 仅更新 UI 测试后主/测试 APK 与 lint 成功。0.2.12 已打包，详见 [最终报告](DEVICE_FEEDBACK_2026-10-16.md)。下方 RED/待统一执行文字为过程记录，不代表当前仍未通过；没有新增收费生图或真实云审图结论。

来源：[手机反馈计划](USER_DEVICE_FEEDBACK_2026-10-16.plan.md)。本页记录新增实现与验证边界，旧 APK 和历史回执不改。

## 目标与接口

用户直接索要自拍时，应优先取得一张当前 OC 的已审新图。固定三条持久库存位置作为准备目标，消费后只推进被消费位置的代际；未知生成结果保留原付费机会，不自动重发。没有可用图时自然说“这会儿还没准备好合适的自拍，等我弄好了再给你看。”配置、库存、审核术语不进入恋人的回复；App 可通过诊断码区别原因。不能把原 OC 图片当作新自拍。

自拍使用真实持久日轨迹及其真实 slot，额外记录用途、库存位置和代际，不创建虚构 scene 来绕过发布校验。仍经过现有原图参考编辑、当前聊天 Key 编写 prompt、原图和候选双像素审核、持久收费账本、私有候选和最终发布 CAS。原有日常请求 ID 计算保持；自拍额外绑定用途、位置和代际，Key/模型变化不创建另一个付费机会。

库存状态文件采用原子写入，文件损坏时保留记录并停止新生成。每个当前范围保存最多 128 条已消费 SHA；不会在三个位置之间把相同像素当成不同新自拍。128 次达到上限则等待下一日轨迹，不额外付费。消费先落地再调用聊天保存；聊天保存失败可能少一张库存，但不会因此二发同张图。库存范围绑定原 OC/取景参考、形象版本、人物包和真实日轨迹；最终发送再次校验。普通聊天和主动日常不会随机消耗专用自拍库存。

`LocalReplyMedia.prepare(chat, profile, recent, userText = "")` 的最后参数仅由普通用户发送传入；通话及主动联系沿用空值。后续库存触发由普通消息保存后复用 `initializeDailyPlan`，仅在主档案且 `plan.selfieRequested` 时执行。`LocalMediaPlan` 新增字段均有默认值；`selfieRequested` 表示直接索要，`requiredAttachmentId` 固定待附自拍，`unavailableMessage` 是自然待备句，`selfieStatus` 为 App 诊断码（`oc_missing`、`image_service_unconfigured`、`inventory_pending`、`inventory_invalid`）。

请求判定要求“给我/发我”的接收方向、“你的自拍”等明确对象或直接“发/来张自拍”命令。用户给恋人看自拍、提问自拍教程或说自己想拍自拍不消耗库存。可选 creator 档案损坏时，自拍提交降级为无附件自然待备；普通聊天不读该可选档案。只有进入保存回调之前的 creator 读取失败可降级，数据库/保存回调异常仍原样抛出，不会二次保存。

## TDD 证据索引

| 保证 | 测试 | 当前证据 |
| --- | --- | --- |
| 直接自拍请求、否定及用户自拍区分 | `LocalSelfieRulesTest` | r9 两项真实断言 RED，一项原行为通过；r13 原 3 项 GREEN；r14 方向追加项真 RED；r16 最终 4 项 GREEN |
| 三位置持久化、单位置消费补充、原图不变 | `SelfieBankNativeTest` | Native r6 真实断言 RED；r7/r8 原方法 GREEN |
| 无库存不声称已发送；不依赖模型标记；同机会不能二发 | `SelfieBankNativeTest` | Native r6 真实断言 RED；r7/r8 原方法 GREEN |
| 自拍位置/代际付费 ID 独立；原图不冒充新自拍 | `RemoteScenePhotosTest` 新增方法 | r9 两项真实断言 RED；r13/r16 全 13 项 GREEN |
| 明确自拍用途覆盖旧 index 风景取景，保留实际轨迹和裁切门 | `LifePhotoPromptClientTest` 新增方法 | r9 一项真实断言 RED；r12 否定句中 scenery-only 词匹配失败，源改为明确可见 OC 人像；r13/r16 全 7 项 GREEN |
| 坏 creator 自然待备、保存一次、原坏文件保留、普通聊天可用 | `SelfieBankNativeTest.corruptOptionalCreatorKeepsNaturalPendingReplyAndPreservesTheBrokenManifest` | Native r8 真 RED；修复及保存恰一次断言已写，最终 GREEN 待统一执行 |

RED 日志：`artifacts/device-feedback-integration-build-r9.log`、`artifacts/device-feedback-integration-native-red-green-r6.log`，由 root 编译并执行。Native 自拍四项均实际运行并断言失败，没有用接口编译错误替代业务失败。

后续日志：`artifacts/device-feedback-integration-build-r13.log` 及该次 XML、`artifacts/device-feedback-integration-native-r7.log`、`artifacts/device-feedback-recording-green-selfie-corrupt-red-native-r8.log`、`artifacts/device-feedback-selfie-direction-red-build-r14.log`、`artifacts/device-feedback-final-build-r16.log`。r7 整批 43/2 失败发生在其他项，不能称整批全绿；r8 整批 12/1 失败是本页新增坏 creator 闭环。r16 主/测试 APK、JVM、lint 构建成功；本代理只读检查上述自拍三个 XML 共 24 项全部通过，未冒称自己执行 Gradle。坏 creator 最终 Native 与 APK 由 root 继续统一报告。

本代理不运行 Gradle/ADB、不读取真实 Key、不调用收费服务、不提交 Git。root 统一运行并保留 RED/GREEN、整轮与 APK 证据。未测真实云端画质、人物身份准确率或真机性能；不声称全项目 80% 覆盖率。
