# 主动联系整夜未触发：调度与权限修复

用户反馈：21:30 至次日 09:30 没收到主动联系，聊天模型还回答自己不能主动联系。手机目前未连接，不能从电脑日志断言那一晚具体在哪一步停止，也不能将原因直接归给红米省电。

## 真实系统测试发现的阻断

修复前任务已真实登记到 Android JobScheduler，实际等待 90 秒后 Worker 创建数仍为 0。系统快照显示最早执行时间已经过去、应用处于 ACTIVE、有后台运行资格且不在 Doze，阻断项是网络：当时默认网络具备 `INTERNET` 与 `PARTIAL_CONNECTIVITY`，没有平台的 `VALIDATED` 标记，原 `CONNECTED` 约束仍要求它。

这不是测试数据猜测，也没有修改模拟器的网络验证状态来使测试通过。两次失败与实际任务证据分别在：

- `artifacts/proactive-system-repair-native-green-r5.log`：名字带 green，但实际五项有一项系统派发失败，不能按文件名当通过。
- `artifacts/proactive-system-constraints-native-red-r6.log`：再次实际等待 90 秒，Worker 仍为 0。
- `artifacts/proactive-system-active-job-constraints-r2.log`、`proactive-system-after-delay-constraints-r3.log`：自己的任务已登记、执行时间已到，网络验证约束未满足。

当前项目所用 [AndroidX WorkManager 2.12.0 官方源码](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0-sources.jar) 的 `NetworkTypeCompat.kt` 会为非 `NOT_REQUIRED` 的网络类型构造同时包含 `INTERNET` 与 `VALIDATED` 的请求；API 26/27 的旧网络控制器也要求验证。平台验证结果与用户正在使用的国内 API 是否可访问并非同一件事。这个阻断已在受测环境复现，但没有读取用户手机那一晚的系统状态，不能说已证明手机当时也是相同网络标记。

最终方案只改普通主动联系这条调度链：API 28 及以上经公开 `setRequiredNetworkRequest` 提交仅包含 `INTERNET` 的请求，保留联网恢复唤醒，又不要求平台验证、默认可信标记或特定传输类型。API 30+ 使用 `clearCapabilities`，28/29 使用公开 `removeCapability` 移除默认标记，避免调用低版本不存在的方法；26/27 使用无平台网络约束，由 Runner 检查实际默认网络。Runner 的真实 `activeNetwork` 与 `INTERNET` 门同时保留；确实离线时返回带退避的 Retry，不分配新请求 ID、不构造默认生成服务、不读取 Key、不调用模型。已有未来 due、通知权限、联系暂停、前台与启用状态仍优先。

旧的 due/resume 任务使用 KEEP，单改新建请求不会修复已排队任务。本轮对这两个已知自有名称下未结束、原 `CONNECTED` 且没有自定义网络请求的任务调用公开 `updateWork`：同一 UUID、原固定输入契约、相同初始延迟、标签和退避；其他约束继续保留。AndroidX 的更新实现保留入队时间、尝试数与链状态，运行中的工作只更新后续配置，不取消已有 HTTP。周期任务通过原有 UPDATE 策略更新；其他后台任务不在本次改动范围。

## 已复现的程序问题

原实现有两个独立的 `enabled`：`local_proactive` 保存服务偏好，`proactive_delivery` 保存 Worker 开关。通知授权后的设置依次写入两处；中途停止会留下只有服务开启、Worker 未开启的状态。反向只开启 Worker 时，服务仍按关闭处理，后续检查可推迟一天。屏幕恢复以两者交集显示状态，只能依赖另一段权限 effect 恰好再次成功保存来恢复。

`ProactiveOptInConsistencyNativeTest` 的五种状态在修改生产实现前均真实失败；证据位于 `artifacts/proactive-overnight-optin-runtime-native-red-r1.log`。该轮另三项是能力提示词测试，不能把八项都写成调度测试。

另一个生命周期缺口在自动通知权限入口：原回调只触发下一次 Compose effect，没有立即保存联系意图。用户点允许后立即离开，下一次前台 effect 可能根本没有机会开始；“保存工作不随页面取消”的旧修复覆盖不到尚未开始的保存。本轮改为在授权回调里立即启动已有的进程级本地保存任务，常规恢复 effect 只协调状态。

## 当前实现

- `LocalProactivePreferencesStore` 作为唯一持久来源。默认服务与 Worker 读取同一条 `proactive_delivery` 记录；服务保存启用值和偏好元数据为一个事务，不再要求后续另写 Worker 开关才能有效。
- 保留旧文件用于迁移和核对。一处已有明确值、另一处缺失时恢复已保存的意图；两处冲突时保留关闭并记录枚举 `legacy_conflict`，不猜测用户意图。新的明确授权可以更新设置；聊天中的暂停状态始终独立保留。
- 构造器中显式指定的测试存储名保持原来的隔离语义，不会写生产命名空间。
- 系统权限与持久偏好分离：已有系统权限不无条件覆盖明确关闭；首次授权且没有旧选择可以启用；本次系统弹窗明确允许也可以启用。撤销通知权限只停止调度，不把保存的意图抹掉，包含保存过程中撤权的竞态；聊天暂停不会因此清除。
- 后台状态卡提供实际最后检查时间、通用结果和下一次安排。`sent` 只显示“已生成主动消息”，不冒称通知已送达。通知已允许但联系关闭时，有明确的“恢复主动联系”修复入口；点击才是新授权，自动恢复不会替用户猜测开关。
- 只有明确的关闭到开启操作，才释放旧关闭状态可能遗留的一天退避，并另排一个 30 秒复查。保留原 checkedAt、结果和 pending 请求 ID；普通恢复或 true→true 保存不刷新 due。复查仍按原文、人格间隔、上限和暂停状态决定是否联系，不强发消息。新增入口在 IO 线程读取绑定和 SQLite，短本地保存由进程级作用域持有，销屏不取消。

## 真正的系统派发测试

旧 `ProactiveEndToEndNativeTest` 覆盖真实服务、Repository、SQLite、NotificationManager，但它直接调用 Runner，不能证明 WorkManager 实际会唤起 Worker。

新增 `ProactiveSystemDispatchNativeTest`：使用 UUID 隔离的偏好、数据库和文件；创建 WorkManager 2.12.0 实例并关闭 GreedyScheduler，只保留 Android SystemJobScheduler。经生产 `ProactiveScheduler.next` 排入真实 30 秒延迟任务，检查真实 JobScheduler 项目，等待系统 JobService 创建生产 Worker，再验证生成、原文落库、系统活动通知、Worker 成功以及后续检查链。第二个系统派发 Worker 重读保存的未来 due，验证不会多调用模型或重复发通知。

同类另一个测试预先建立旧 `CONNECTED` 请求，迁移后核对同 UUID、相同 input/enqueue time/attempt/backoff/storage 约束以及原 lastRun/pending，再等待这个旧任务经真实系统入口执行。`ProactiveOfflineGateNativeTest` 四项覆盖真实离线的防御门、保留未来 due、暂停优先与前台/关闭优先；这些新增分支只报告最终 GREEN，不伪称先有独立 RED。

没有 `TestDriver.setInitialDelayMet`、手动 `startWork`、系统时间跳变或把 Runner 直调包装成系统调度；只有远端 HTTP 是合成响应。测试恢复原 WorkManager delegate、清理隔离数据和自己的系统任务，不修改用户 Key、聊天或人格。

此测试证明受测 Android 模拟器上的实际 JobScheduler 链，不能代替用户红米手机锁屏 12 小时、Doze、厂商任务清理和网络波动的到达率验收。

接口和隔离方案已核对当前使用的 AndroidX 2.12.0 源码；常规测试驱动与真实系统时延的区别可参见 [Android 官方 WorkManager 集成测试文档](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing)。

## 验证状态

当前证据：单源五项真实 RED 已保存，之后在 `proactive-guidance-reconciliation-native-red-r3.log` 五项均 GREEN；同轮权限协调四项有两项真实 RED（既有授权覆盖明确关闭、撤权抹掉意图），两项原本已 GREEN，后在 r4 四项全部 GREEN。诊断新增一项在 r4 真实 RED，后在 r5 三项全 GREEN；修复入口 r5 的初版一项 GREEN 尚未包含后来加强的旧冲突和退避断言。

系统派发初期有两次测试观察错误，均保留：首轮 Job ID 区间宽度 999 小于最小 1000，尚未注册任务；下一轮读取默认 JobScheduler namespace，遗漏 WorkManager 在 API 34+ 自己的 namespace。修正观察方式后，才发现上述任务实际登记但受网络验证约束阻断的两次真实 90 秒 RED。不能混为同一种失败，更不能按早期日志名宣称系统派发已经通过。

`artifacts/proactive-system-network-migration-native-green-r7.log`：10/10 GREEN，106.443 秒，包含真实系统两项（新建任务与旧 CONNECTED 同 ID 迁移）、离线门四项、强化恢复入口一项、后台诊断三项。原网络仍为 PARTIAL 且没有 VALIDATED，未修改设备网络。新建任务真实等到 30 秒后由系统创建生产 Worker，后续第二 Worker 不再生成或重复通知；旧 CONNECTED 请求保留身份、输入、入队时间、尝试数和其他约束后也真实执行。

随后的全量构建 lint 检出两处兼容问题：`clearCapabilities` 需要 API 30，复制 Constraints 的构造器属于限制 API；均已改为上述公开兼容路径，并保留四个布尔约束、URI 触发和触发延迟。r9 构建成功，857 JVM 全绿、lint 0 error / 79 warning / 7 hint；但完整 Native r9 在系统派发测试期间因测试执行器关闭时的 `RejectedExecutionException` 进程崩溃。测试夹具清理竞态尚未修复，83 类 409 项没有完整通过结果，0.2.13 未正式交付。r7 的通过不能代替修改后整轮检查，更没有声称用户红米整夜通过。

用户随后要求先写交接，当前只整理文档并关闭本任务模拟器，开发与测试停止。另确认 `LocalDailyShareScheduler`、`LocalSceneStockWorker`、`LocalMemoryRecognitionWorker` 仍使用旧 CONNECTED 条件；仅发现相同风险，尚未各自复现或修改，不应把普通主动联系修复外推为这三条后台链也已修好。恢复步骤、工作区保护与证据见项目根目录 `交接文档.md`。

## 2026-10-17 晚：三条链一并修复

用户随后明确要求“都要修”。本轮已把同一 `INTERNET`-only 策略扩展到日常分享、轨迹备图和记忆识别，修复系统派发测试夹具退出竞态，并重新完成针对性验证与整轮 Native：84 类 / 414 项、563.299 秒、`OK (414 tests)`。实现、真实 RED/GREEN 与边界见 [同源网络修复](BACKGROUND_NETWORK_2026-10-17.md)。仍无真机整夜到达率结论。
