# 静默日历年度重复、提醒与安排变更

日期：2026-10-10。前置授权后，恋人仍在同一次自然回复里悄悄记下明确事项和自己的日记，不增加逐条确认、成功写入通知或“查记录”话术。用户原文和日历派生事项保持独立。

## 已接入手机的行为

- 明确公历生日/纪念日写为每年重复：`FREQ=YEARLY;BYMONTH=M;BYMONTHDAY=D`。重复事件使用 `DURATION=P1D`，不同时写 `DTEND`。提前一天的 `METHOD_ALERT` 提醒与事件在同一个 CalendarProvider 批次内写入。2月29日不擅自改成2月28日；农历、阴历、闰月不猜公历日期。
- 已确定约定仍是全日日期事件，提醒提前量为60分钟。当前没有精确时分字段，不能把“明晚”自动猜成18点；这个提前量不是“已支持真实约定时刻”的证明。恋人自己的日记没有提醒，保持一天最多一条。
- 当前用户明确取消或改期时，可撤销或更新应用自己创建的安排。需要完整当下原文同时支持原日期、事情和明确变更；改期还要有明确的新日期。活动匹配使用去除明确修饰词后的完整内容，不把“一起看”的共同前缀当成同一活动；看星星、看日落不能撤销看电影。假设、疑问、否定取消、裁去条件的节选、未知目标或多个可匹配候选均不执行变更。
- 取消保留原事件身份并标记 `STATUS_CANCELED`，同时清除它的提醒。保留取消身份能防止重放早期助手回答把安排再次创建。改期更新同一事件ID、日期及相应提醒，不先删除再新建。

不读取或修改其他日历。即使用户在知语自己的日历内手工新增事件，也只有具有本App包名、规范 `zhiyu://companion-calendar/{sourceSHA}` 和已知事件前缀的事项可成为变更候选；没有这些身份的事件保持原样。

## 协议与调用口

`CompanionCalendarProtocol.prepare()` 默认三个参数仍兼容；Store在已授权且当下有明确取消/改期词时，补充最多32个应用事项快照，最多读取128行候选，不在普通日常分享时查询事件。快照标题是低信任数据，JSON转义 `<`、`>`、`&` 以阻止伪造协议边界。

当前随机nonce绑定最后一行标记。创建沿用5个字符串字段；取消/改期增加 `target`，只能选择当前快照内唯一匹配的source身份。模型不能提交任意CalendarProvider事件ID。派生动作还必须通过客户端独立原文/日期校验，不能单靠模型自称“已确定”。

`LocalChatRepository` 先完整保存自然助手原话，再在IO线程调用：

```kotlin
calendar.commit(message.id, calendarReply)
```

旧三参数 `commit(messageId, draft, kind)` 仅保留兼容入口。Parser不丢自然文字或图片/表情标记。没有有效日历动作时返回 `saved=false`，不影响聊天。

变更时再次核对应用日历、事件ID、source身份、日期、标题、正文、重复规则、时区和状态快照；Provider update还带这些字段的精确CAS条件和 `expectedCount=1`。用户在生成过程中编辑了事件、已改期后的旧快照或已取消快照都不能覆盖当前事项。事件变更和提醒替换在单个Provider批次内原子执行。

原话SQLite提交和系统日历提交仍是两个事务。手机在它们之间中断可能漏写日历，本轮没有引入跨存储分布式事务，也没有声称跨消息的生日语义去重或精确时分调度已实现。拒绝、撤销权限或系统Provider失败时聊天保留；不对用户其他事件执行补偿删除。

## 验证证据

| 验证 | 结果 | 日志 |
| --- | --- | --- |
| 真实年度重复RED | 原实现生日没有RRULE，1/1断言失败，不是编译失败 | `artifacts/native-v4-calendar-red-r1.log` |
| 新动作接口编译RED | 新候选/变更/年度策略尚未存在；该编译失败与业务RED分开记录 | `artifacts/calendar-v4-lifecycle-red-compile.log` |
| 活动匹配真实RED | 生命周期11项中1项失败；“看星星”共享“起看”二字片段，旧实现误撤销电影 | `artifacts/calendar-v4-specific-activity-red-r1-test.log` |
| 日历纯逻辑 | **41/41通过，0.386秒**；包含旧协议24项、writer6项、生命周期11项 | `artifacts/calendar-v4-specific-activity-green-r2-test.log` |
| 原生Provider | `CompanionCalendarProviderTest`2项及 `CompanionCalendarMutationProviderTest`3项均通过；所在OC/竞态联合批次 **20/20，12.693秒** | `artifacts/native-v4-oc-calendar-green-r1.log` |

原生测试检查实际Provider中的年度规则、全天DURATION、1440分钟生日提醒、改期后同一事件及60分钟提醒、旧快照被拒绝、撤销后无提醒且重放不复活、同一日历内手工事件不变，以及生成途中用户编辑不会被覆盖。测试只使用root管理的隔离AVD及本次UUID事件，清理按事件ID/包名/source身份绑定，不清除用户手机数据。

中间独立runner曾因误列不存在的LocalMediaPlan路径缺依赖而未收集测试，修正后GREEN日志保留；没有将该脚本错误当业务RED。整包单元测试/lint、设备权限恢复与最终APK由主集成单独确认，本文的Provider通过不等同红米实测或系统实际弹提醒验证。

日历重复/提醒字段依据 Android 官方 [Calendar Provider](https://developer.android.com/identity/providers/calendar-provider) 及 [RemindersColumns](https://developer.android.com/reference/android/provider/CalendarContract.RemindersColumns)。
