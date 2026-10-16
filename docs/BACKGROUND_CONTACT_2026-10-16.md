# 后台联系排查与修复（2026-10-16）

最终状态：0.2.12/code18 已打包。`device-feedback-final-native-r10.log` 完整 348 项全绿，其中 `ProactiveEndToEndNativeTest` 3 项、`ContactRecoveryNativeTest` 5 项、`CompanionBackgroundAccessNativeTest` 2 项均 GREEN；另 1 项新空环境断网启动通过。通知授权后销屏保存和已验证重连解除旧六小时退避均已闭环；823 JVM 全绿。下方 RED/待验证文字保留为排查过程，以本节及 [最终报告](DEVICE_FEEDBACK_2026-10-16.md) 为准。

本报告对应用户反馈“退出后台就不能运行、没有主动消息”。验证结果由本轮最终构建与 Native 回归回填；源码检查不能证明红米的省电设置或整夜通知已经通过。

## 已定位的应用问题

1. `ProactiveScheduler.recover()` 原先每次打开或离开页面都写入 `not_due / now+30秒`，覆盖真实的上次检查结果、预约时间与连接失败退避。修复调度本身不应被当成已执行联系检查。
2. `ProactiveScheduler.schedule()` 原先即使没有持久主动联系许可，也会留下周期任务；`next()` 检查持久许可但遗漏通知权限。Worker 内仍有权限门，因此旧实现不是绕过权限发消息，不过会遗留不应安排的后台任务。
3. `MainActivity` 原先把离开页面后的调度恢复放在 `lifecycleScope`，Activity 销毁可能取消这次恢复。已持久化的 WorkManager 任务本身不会因为这个 scope 结束而自动取消。不能把这个局部窗口误说成整个后台原本没有实现。

新增后台接收状态卡放在现有日记/关心权限页面，显示系统通知、后台限制和电池限制的实际状态，提供标准系统设置入口与重新检查。它不显示聊天正文、记忆内容或 API Key，不自动更改系统限制，不使用私有厂商 Activity、不申请直接省电豁免，也不启动持续保活服务或后台麦克风。

## 三种退出方式

| 操作 | 应用处理与系统边界 |
| --- | --- |
| 按 Home / 切换其他 App | 已持久化的联系和日常任务由 WorkManager 调度。省电、网络、待机配额可延迟执行，不承诺精确秒到达。 |
| 从最近任务中划掉 | AOSP 的移除任务不等于强行停止；部分厂商会额外限制。红米需实际验证自启动/无限制省电，不能用模拟器代替这项结论。 |
| 系统设置“强行停止” | 系统停止状态应持续至用户再次打开或明确交互。不能承诺自动复活；再次打开时恢复已有许可的安排。 |

主动联系与日常分享仍有各自独立的 due 链和 15 分钟周期修复，不将周期任务当成持续连接，也不添加 AlarmManager 无限唤醒。人格间隔、每日上限、最近聊天后的间隔和用户要求暂停等仍然适用。

## 官方依据

- [WorkManager：定义工作请求](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)：周期最小 15 分钟，执行受约束和系统优化影响；初始 delay 是最小等待而非精确闹钟。
- [Doze 与 App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)：网络、任务可能推迟；用户可进入标准电池优化设置。豁免不等于所有系统限制全部消失。
- [Android 15 停止状态变更](https://developer.android.com/about/versions/15/behavior-changes-all#stopped-state)：停止状态只应由用户直接或间接交互解除。
- [ActivityManager.isBackgroundRestricted](https://developer.android.com/reference/android/app/ActivityManager#isBackgroundRestricted())：用户限制后台运行后，jobs/alarms 等会受到系统限制。

## 自动验证

`ContactRecoveryNativeTest` 使用 UUID 隔离的 SharedPreferences 和 SQLite，仅保存合成绑定；不保存/读取 API Key，不调用模型。覆盖未许可误排周期任务、通知拒绝误排 due 链、恢复覆盖真实检查记录三项故障。`CompanionBackgroundAccessNativeTest` 检查设置 Intent、未绑定状态不捏造已排任务。

`artifacts/device-feedback-slices-native-red-r1.log` 已实际运行，以上三项均为断言失败：6 小时 `needs_connection` 被恢复改为 30 秒 `not_due`、未 opt-in 遗留周期任务、通知拒绝遗留 due 链。测试初始化和类型编译成功，故不是把编译错误当故障复现。

对应生产修复已落地：恢复保留原检查记录，安排任务时同时检查持久许可与通知权限；Activity 仅请求进程拥有的短修复任务，并等待 WorkManager enqueue 操作落地。无系统服务保活，这个进程 scope 也不能抵抗系统结束进程。重复恢复共享在途任务，串行恢复避免重复修复。状态卡已挂入原日记页面，返回设置时刷新；不新增导航项。

`artifacts/device-feedback-avatar-red-background-green-r2.log` 中，后台 5 项 Native 全部通过（三个原故障与两个状态/Intent 检查）。该文件整轮 7 项还有另一个模块的 2 个未实现占位失败，因此不能把该文件或本轮全部功能称为全绿；这里只确认后台这 5 项 GREEN。

Home/滑卡/强行停止的真实 OEM 行为与整夜等待未在此报告中冒称通过，仍需安装包在红米实测。标准权限门和 WorkManager 记录恢复已经由真实 Android API 测试，未以静态推断替代。

## 追加：主动联系不能只验调度记录

用户再次指出一直没有收到主动联系后，追加完整服务链验证。当前仅后台 5 项通过不能代表主动联系实际生成成功，故新增 `ProactiveEndToEndNativeTest`：通知授权后的 Controller 保存 → due 检查 → `LocalProactiveService` → `RepositoryProactiveGateway` → 真实 `LocalChatRepository`（仅远程 transport 使用合成返回）→ 独立 SQLite 原文 → Android `NotificationManager.activeNotifications`。测试不直接调用 `post()` 假装生成成功；前台跳过另测。默认 Worker 调用同一 `ProactiveWorkRunner`，没有生产全局测试钩子。

另外追加通知授权后、保存途中 Activity scope 被取消的用例；该场景原实现可能丢失初次持久 opt-in，而不是系统“没有保活”。尚待真实 Native RED/GREEN，不提前声称已修好。

初联系规则也有可解释的过长等待：原先所有人格最近互动后一律等待 45 分钟。新增三项 JVM 规则已在 `device-feedback-slices-privacy-green-queue-voice-contact-red-r4.log` 真实断言 RED。拟将首次安静间隔按人格锚定实际最后互动：焦虑 5 分钟、活泼 10 分钟、温暖 15 分钟、温柔/自定义 20 分钟、沉稳 60 分钟、疏离 120 分钟；后续人格自身频率、每日上限和用户暂停仍保留。初次许可可 30 秒开始检查是否已到期，不再额外等一刻钟；并不等于每 30 秒向用户发消息。
