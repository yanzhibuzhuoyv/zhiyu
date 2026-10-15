# 0.2.9 照片候选和恢复

承接0134450/0.2.8已验证650JVM+173AVD。研究已有RemoteScenePhotos/私候选AtomicFile/付费ledger/WorkManager/图库发布边界；复用，不改RAG、原素材、人物背景或全双工。

1. prepare遍历跳过单slot未知/无视觉/过时/已清候选，使其他slot可准备；新HTTP授权/余额失败立即停止本批，不重发原请求。每worker仍最多1新图。
2. 手机更多→照片候选：分页查看状态和单张缩略图、重新审图、明确失败后一次用户确认重生成、清理候选像素。私候选不通过审核不能发，清理保ledger/标注/原图/已发送图片；结果未知不能重生成。
3. ledger增加独立scene retry lineage，v1→v2迁移保持所有原行，CAS只有failed可一次reserveRetry，重点击/重启无重复授权；ID绑定parent，不随Key/model重抽。不删除/重置原请求。
4. 重新审图只调用当前聊天Key，真实双像素；原图/角色/轨迹结构和SHA保持，不复用旧审核；允许Key更新后重新审核的新binding证明，最终仍按current snapshots发布。生图与审核不确定仍private。
5. candidate清理先durable tombstone再删自己bin/backup；不复活已清pixels，不删原媒体或metadata。分页有界，坏行不暴露其他chat，preview采样避免大图堆满heap。
6. 根统一真实RED→GREEN/Gradle/AVD；三代理按ledger+candidate、queue+review、UI独立所有权并互审。使用合成Key和传输，不增加真实付费请求；旧失败/最终receipt冻结保留。交付新APK、总结、交接和白名单conventional commit。

自动全身扩图和全双工仍后续；当前局部取景限制保持。
