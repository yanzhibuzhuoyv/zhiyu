# 三条后台链的同一网络验证修复与系统派发夹具收尾

2026-10-17。本批在 0.2.13 候选上继续，处理用户要求的“三条一起修”和最后一次 Native 整轮暴露的测试夹具竞态。原文 RAG、绑定、生图账本、图片审批与收费语义均未改变；本批没有新的付费调用。

## 起点

主动联系的修复已经证明：WorkManager 2.12 的 `CONNECTED` 在受测环境要求网络带 `VALIDATED`，而用户所用国内 API 在具备 `INTERNET` 时仍可能不可验证，导致任务登记、时间已到、后台与额度正常却不创建 Worker。主动联系改用只要求 `INTERNET` 的自定义请求后，仍有三条后台链保留旧约束：

| 链 | 文件 | 用途 |
|---|---|---|
| 日常分享 | `notifications/LocalDailyShareScheduler.kt` | 带图分享的周期、due 与图片就绪任务 |
| 轨迹备图 | `data/LocalSceneStockWorker.kt` / `LocalSceneStockScheduler` | 生活照库存预生成 |
| 记忆识别 | `data/LocalMemoryRecognitionWorker.kt` / `LocalMemoryRecognitionScheduler` | 后台语义标注 |

`feedback25-proactive-final-native-r9.log` 另暴露一个问题：系统派发测试在 `ProactiveSystemDispatchNativeTest` 的第二个用例期间进程崩溃，栈为 `SerialExecutorImpl.scheduleNext` 向正在 `Shutting down` 的 `ThreadPoolExecutor` 提交后续任务，被 `AbortPolicy` 拒绝。根因在测试夹具 `close()` 的 `shutdownNow()` 与 WorkManager 异步清理的竞态，不是用户 App 生产崩溃。

## 实现

### 共享策略

- `ProactiveNetwork` 继续作为唯一实现：`constraints(existing)` 复制四个布尔约束、URI 触发与触发延迟，API 28+ 提交只含 `INTERNET` 的自定义 `NetworkRequest`；API 30+ 用 `clearCapabilities()`，28/29 移除默认能力；26/27 使用 `NOT_REQUIRED`，由运行时门判断。
- 新增 `isLegacy(constraints)`，只把“旧 `CONNECTED` 且没有自定义请求”的未完成任务视为迁移对象；已带自定义请求或已完成的任务不动。
- 新增 `BackgroundNetworkRepair`：迁移在进程级 IO 作用域执行，不阻塞 UI，也不在 Worker 线程内同步等待 WorkManager 的串行执行器。
- `available(context)` 保留为真实默认网络门；三个 Worker 在读取 Key / 调用服务之前检查，离线返回 `retry`。

### 日常分享

- 周期、due、图片就绪三类新任务全部改用共享约束；周期任务继续用 `ExistingPeriodicWorkPolicy.UPDATE`，保留同一 UUID 并更新约束。
- 旧 due / 图片就绪任务按固定输入契约就地迁移：同一 UUID、原 `CHAT_INPUT` / `CHAIN_INPUT` / `picture_ready` 输入、原初始延迟、原标签和其他约束；只替换网络请求。
- 迁移由 `next()` 与 `pictureAvailable()` 触发，因此主动联系恢复、日常分享自身续链和图片到达路径都会修复旧行。

### 轨迹备图

- 新入队请求使用共享约束。
- `zhiyu_stock_${chat}_${version}` 下未完成且等待中的旧 `CONNECTED` 行，按调用方当前的 `chat/day/version` 就地更新，保留 UUID、初始延迟、run attempt、backoff 和其他约束。
- **RUNNING 行不迁移**：正在执行的远程生图请求保持原输入与原约束完成，避免中途改变已付费请求；本批也不取消、不重建任何旧请求。
- 等待中的旧行若来自过期日期，会被重定向到调用方当前窗口；Worker 的 `day` 窗口校验和 `hasStock` 去重继续生效，不会据此新增付费请求。

### 记忆识别

- `ONCE` 与 `PERIODIC` 新建都使用共享约束；周期任务由 `KEEP` 改为 `UPDATE`，保留周期 UUID 并更新网络请求。
- `ONCE` 没有输入契约，旧行通过 `updateWork` 就地更新 UUID、初始延迟、backoff、标签与其他约束；迁移异步执行。
- Worker 在读取 API Key 之前先检查默认网络，离线只 `retry`，不构造识别器、不发起云端分类。

### 系统派发夹具

`ProactiveSystemDispatchNativeTest.Fixture.close()` 改为：

1. `cancelAllWork` 后等待 `Processor.hasWork()` 变为 false；
2. 向 WorkManager 串行执行器提交 FIFO barrier，确认此前排队的任务已执行，并留出 200 ms 静默窗；
3. `manager.close()` 后再做一次同样的收敛检查；
4. 两个测试执行器改为 `shutdown()` + `awaitTermination`，不再 `shutdownNow()`。

没有吞异常、没有跳过系统测试、没有降低断言、没有把真实系统派发改成 Runner 直调。

## 验证

### 新三链

`artifacts/background-network-migration-native-red-r1.log`：在修改生产代码前，5 项真实失败，原因是旧约束仍为 `CONNECTED`、旧任务未被迁移。

`artifacts/background-network-migration-native-green-r2.log`、`-r3.log`：同一 5 项全通过，覆盖：

- 日常分享 due / 图片就绪 / 周期三类旧任务就地迁移且 UUID、输入、延迟、尝试次数、backoff 不变；
- 轨迹备图旧等待任务就地迁移且其他约束保留；
- 记忆识别 ONCE / PERIODIC 就地迁移；
- 全新日常分享、记忆识别与备图任务都提交 `INTERNET`-only 请求且不要求 `VALIDATED`；
- 重复调用不清空已有任务或改变其身份。

### 系统派发

`artifacts/proactive-system-fixture-fix-native-green-r1.log` 至 `-r4.log`：同一测试类连续 4 轮 2/2 通过（每轮含真实 30 秒 JobScheduler 延迟、生产 Worker、合成 HTTP、SQLite 与通知），没有再次出现 `RejectedExecutionException`。

### 全量

- JVM：127 suites / 857 项，0 failure / error / skip。
- lint：0 Error / Fatal，79 Warning，7 Hint。
- 完整 Native：`artifacts/feedback25-proactive-final-native-r10.log`，84 类 / 414 项，563.299 秒，`OK (414 tests)`，无 `FAILURES!!!`。
- 本批没有手机、物理麦克风或新的付费生图/识别调用。

## 边界

- API 26/27 没有平台自定义网络请求，只由 Worker 的运行时门兜底；真实离线仍可能让任务留在 `ENQUEUED` 等待网络，这是预期。
- 备图的 RUNNING 旧行不会被迁移；等待中的旧行会被重定向到当前窗口，但不会取消或重建已登记请求，也不会改变收费账本、候选像素或已发布素材。
- 没有真机，不能据此宣称红米锁屏整夜、OEM 省电、Doze 或真实国内网络下的到达率通过；模拟器只能证明受测环境中的调度与迁移。
- 日常分享与记忆识别的旧周期任务由 `UPDATE` 更新；若系统已取消或已完成，不会重新创建。
- 本轮不是新的记忆排序算法，也没有重跑万轮/五千万长测。
