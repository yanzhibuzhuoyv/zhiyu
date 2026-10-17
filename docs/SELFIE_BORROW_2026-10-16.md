# 自拍提前借图 · 用户反馈 32

本轮以 0.2.12 / `ddb60b7` 为冻结基线，不刷新旧报告或回执。Root 统一执行 Gradle、ADB、真实收费请求与最终交付；本代理不读取私 Key、不提交 Git、不自行发起带图请求。

## 调查与边界

- `LifePhotoPromptClient.review` 以当前主聊天 provider/model 的已验证原生视觉能力为门。`deepseek-flash`、`deepseek-v4-flash` 和 `deepseek-v4-flash-vision-exp` 在现白名单内，Pro 不在；不能把本例未经核实地推定为纯文字模型。图像 Key 本身只负责图片编辑，不等于当前主聊天模型能做双像素审核。
- 现首次预取先准备三条当前自拍 lane，再准备后续日常，每 Worker 最多一张新图并在 30 秒后续链。未知收费机会不能重发，当前 day/slot 与自拍用途门也会排除已生成的明日普通人像。
- Root 的只读目录 `artifacts/feedback25-model-catalog-r1.json` 仅列 `gpt-image-2`、`gpt-image-2.5-flare`、`gpt-image-2.5-sunburst`，不含可验证的视觉聊天模型。不擅自添加 gpt-4o 等别名，不把主聊天 Key 发送到图像中转。
- 可读的 [OpenAI Images 接口参考](https://developers.openai.com/api/reference/resources/images)描述编辑/生成图片及图片结果，不提供本链需要的结构化 OC 审批证明。此资料也不能证明第三方代理别名的审核能力；OOAPI 公开站未提供可读审核协议，docs 域本轮不可达。因此本轮不把“图片服务能编辑”当作“能可靠审核”，不伪审。

## 本轮方案

当当前自拍不足，优先借用明日已生成、已做真实像素审批的人像照片。只借正常 portrait 日常图，不借 POV、风景、占位、未知或未审候选，也不借已经补过的位置。通过原生成账本、私候选、原 OC/取景与真实明日轨迹验证来源；不改原图、原候选或原日期。回复使用中性照片语境，不声称明日活动已经发生。

借用记录与原位置的补图票据在同一持久事务内登记，原图不再参与明日分享。补图仍关联同一真实 scene/slot，单独记该 slot 内另一时点的 `capture_time`（通常后一分，短 slot 则后一秒），不能伪造 scene 绕门。补图经过原图参考编辑、真实双像素审核、稳定收费 ID 和最终 CAS；未知结果不重发。每个原位置只借一次，其补图留给原位置，并且分享时必须已到新的拍照时点。

已知当前模型不具备审核能力时，新预付机会前明确停为 `review_unavailable`；Flash 正常走原双图审核。App 诊断使用 `review_model_unavailable` 区别“图片 Key 未配”和“审核模型不支持”，恋人的回复保持自然待备句。没有证据已经生成并审核好的图时不声称存在可发照片。

## TDD 索引（先 RED 后实现）

| 保证 | 测试 | 证据 |
| --- | --- | --- |
| 真实轨迹/slot 不变，补图改另一时点且可重建 | `LocalPhotoBorrowRulesTest` | r3 实际 Assertion RED 后实现 |
| 已知无像素审核能力时不产生新预付机会 | `RemoteScenePhotosTest.knownUnavailablePixelReviewerStopsBeforeAnyNewPaidImageOpportunity` | r3 实际 Assertion RED 后实现 |
| 补图收费 ID 区别原图并稳定 | `RemoteScenePhotosTest.reshootAfterBorrowHasANewStablePaidIdWithoutChangingTheActualTrajectory` | r3 实际 Assertion RED 后实现 |
| Prompt 明确另一 `capture_time`，保真实原 slot/trajectory | `LifePhotoPromptClientTest.borrowedSlotReplacementDescribesAnotherPhotoMomentWithoutChangingItsStoredTrajectory` | r3 实际 Assertion RED 后实现 |
| 明日已审人像可借、无未来已经发生的声称、不改来源日期 | `SelfieBorrowNativeTest` | r2 实际 Assertion RED 后实现 |
| 借用持久且不重复，未来原图退役、补图另时点 | `SelfieBorrowNativeTest` | r2 实际 Assertion RED 后实现 |
| OC 变更后不提交也不消耗 | `SelfieBorrowNativeTest` | r2 实际 Assertion RED 后实现 |
| 未审/未知不借 | `SelfieBorrowNativeTest` | r2 原拒绝行为通过，继续保留 |
| 未到补图时点不分享、未知补图换 Key 不重付、明日轨迹变化不借 | 追加纯 JVM 与 Native 用例 | 实现后防回归，待最新统一结果 |
| 无对象直接索要照片默认恋人照；发自己的照片/教学/风景/否定不消费 | `LocalSelfieRulesTest.directUnqualifiedPhotoRequestsMeanCompanionPhotosWithoutConsumingForOffersOrScenery` | r6 实际 Assertion RED 后实现，待统一 GREEN |
| 混合批次索图待备不能吞掉同批最新求助；独立索图继续快捷 | `PhotoBatchReplyNativeTest` | r6 Native 实际 Assertion RED 后实现，待统一 GREEN |
| 混合批次借图时保完整生成正文与真附图 | `SelfieBorrowNativeTest.aMixedReplyKeepsItsGeneratedAdviceWhenTheBorrowedPhotoStillPassesCommit` | 晚于 r6 编译新增，未声称已取得 RED，待最终 Borrow 6 项 |

Root 的 `artifacts/feedback25-runtime-batch-green-borrow-red-r3.log` 记录 51 项 JVM、4 项本轮新增 Assertion 失败；`artifacts/feedback25-borrow-native-red-r2.log` 记录 4 项 Native、3 项实际 Assertion 失败。确认 RED 后才实现。

Root 的 `artifacts/feedback25-photo-request-red-contact-build-r6.log` 记录照片请求规则 5 项、仅新增直接照片用例 1 项实际 Assertion 失败。随后在明确的收件人/直接祈使句内加入“照片”，保留方向、教程、风景排除，并为普通照片否定句保留不消费约束。

Root 的 `artifacts/feedback25-short-pcm-mixed-native-red-r6.log` 记录同轮 17 项 Native、2 项失败：本轮 `PhotoBatchReplyNativeTest` 已确认主 provider 被调用，但生成的考试求助回答仍被待备句覆盖，属于实际业务 RED；另一项是交叉审发现的录音短包问题。原借图 5 项及 Contact 5 项通过。借图第 6 项混合正文用例在该轮编译之后加入，尚未运行；不将其写成 RED 或已通过。

Root 的 `artifacts/feedback25-integrated-build-r5.log` 所属一轮已完成全 JVM 和主/测试 APK 构建；lint 发现借图库 `SQLiteOpenHelper.use` 在 minSdk 26 不兼容，已改显式 `try/finally close`，未提高 minSdk 或压制规则。最终 lint、最新 Native 与新增直接照片用例结果尚待 Root 统一确认；本轮尚未交付最终 APK，不能沿用旧 0.2.12 回执当本轮通过。

## 接口与持久化

- `LocalReplyMedia.prepare(..., userText = "")` 保持默认兼容。优先当前自拍，随后通过 `LocalPhotoBorrower` 验证明日普通已审人像；`LocalMediaPlan.borrowed` 携带来源证明，`requiredAttachmentId` 强制附图。独立借图固定使用“这张我挺喜欢的，先给你看看。”，不把原场景或未来活动讲成已发生。
- `LocalMediaPlan.preserveGeneratedText` 默认 `false`；Root 对多条连续输入设为 `true` 并跳过待备快捷路，生成正文须回应同批其他问题。`parse`、借图复验和最终提交都保留正文；有图曾准备但最终失效时仍自然无图待备。无图遇明确的独立自述已发送句时保守待备；引用、否定与将来句保持正文。该小型保护不宣称具有通用语义验证能力。
- `LocalReplyMedia.commit` 在当前 visual、OC、今日场景门下重验来源、真实明日轨迹和候选，再原子登记借用与补图；失败自然待备。保存回调只调用一次，数据库或保存异常不能被当成可以重试保存的失败。原来源像素、候选、账本与日期不改，展示副本才用当前聊天日期。
- `LocalPhotoBorrowStore` 独立 `photo_borrows_v1.sqlite3` 以原 request、chat/sha 和 chat/day/trajectory/slot 唯一约束防重复；`adjusted` 重建原 slot 的补图票据，`available` 同时阻止原图再进入聊天与明日主动分享。
- `RemoteSceneRules.opportunityId` 不依赖 Key；仅补图追加来源 ID、SHA 与新拍照时点，新稳定 ID 不覆盖原收费记录。`PhoneRemoteSceneEnvironment` 与 `ScenePhotoManager` 使用同一补图票据，所有参考编辑与真实双像素审核门继续生效。
- `LocalSceneStock` 仍先准备三条自拍 lane，再准备真实日常；`reviewConfigured` 只按已验证主模型判断，已知无审核能力在新收费机会之前停下。诊断与恋人待备文案分离。

本轮没有实付图像/审核请求，不声称已验证第三方线上模型或新图真实生成效果。SQLite 收据会先于消息保存消耗：消息保存失败时宁可该图暂时不可再借，也不重复发送或重复计费。
