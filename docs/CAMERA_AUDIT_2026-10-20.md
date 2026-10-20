# 通话摄像头审查与回归 · 2026-10-20

本轮审查针对 `aca2b10` 引入的通话摄像头能力，在 `a0b09c8` 基线上修复生命周期、会话切换和语音取消问题。Android 全量 JVM 测试通过，摄像头专项原生回归通过，可以继续制作宣传片。此结论限于下列测试范围，不代表物理手机或模型视觉理解质量已经验收。

当前版本仍为 `0.2.19 / versionCode 25`。本报告不是新版本发布记录，也不是再次运行万轮或五千万字记忆测试。

## 功能边界

用户明确开启摄像头并授予权限后，通话界面显示预览；支持识图的模型可在一句语音最终识别完成时接收当前单帧。该实现不持续向模型发送视频流，不在用户关镜头后自动重新开启。

- 帧采用 JPEG，最长边不超过 768 像素，单帧不超过 384 KiB；新鲜度使用单调时钟，最长接受 10 秒内的帧。
- 帧仅用于当前通话请求，不作为聊天附件写入图库或数据库。历史 OC 图片、历史用户照片不冒充当前摄像头画面。
- 页面暂停、锁屏、关闭镜头、切换镜头或摄像头异常后，旧会话帧失效；已开始的视觉回复及其排队语音被撤销，语音通话仍可继续。
- 同一镜头持续采样时，新帧替换缓存不会撤销已经选中的有效当前帧；它仍须满足原会话代际和新鲜度要求。

## 发现的问题与修复

| 问题 | 实际影响 | 修复 |
| --- | --- | --- |
| 帧租约检查与请求登记分开加锁 | 关闭镜头可能错过尚未登记的视觉请求 | 在同一锁内验证并登记 Job；执行视觉块前检查协程取消状态 |
| 只在 `ON_STOP` 清理会话 | 已暂停、尚未停止的页面仍留下旧帧 | `ON_PAUSE` 即关闭镜头并清理帧；预览不可见和锁屏也通知会话失效 |
| 镜头切换后的回调读取可变 token | 旧镜头的迟到帧可能获得新 token | 以 token 为 Compose 子树 key，回调捕获该代 token，并检查当前代际 |
| 捕获失败只显示错误 | 后续语音仍可能使用最后一帧 | 对应镜头错误立即撤销会话，清掉缓存，保留明确错误提示 |
| 只取消模型生产协程 | 已排队的语音继续播放，界面仍停在说话状态 | 按 `VoiceStamp` 撤销整条回复，停止合成、播放和口型，保留麦克风与通话 |
| 模型已结束后不再持有请求 Job | 关镜头仍无法撤销尚未播完的视觉回复 | 额外观察镜头代际失效，生命周期延续到回复源关闭；模型完成后仍可撤销语音 |
| 正常采样刷新被当作撤销 | 选帧与执行之间到达一张新帧，导致有效语音请求被取消 | 保留选中帧的租约，以代际和年龄判定有效性，不要求仍是缓存中最新对象 |
| 未观察 CameraX 运行中状态错误 | 绑定成功后发生设备错误，预览与帧状态可能不同步 | 观察 `cameraInfo.cameraState`；当前绑定报错时关闭镜头，并在释放时移除观察器 |

CameraX 状态观察器由绑定对象先持有、再注册，避免 LiveData 首次同步回调错误时无法释放观察器。注册状态观察是绑定的最后一步，防止同步关闭后再次注册旋转监听。旧代际和旧绑定对象的错误不会关闭替换后的镜头。当前采用遇到状态错误即关闭并提示用户重开的策略，包括 CameraX 可恢复错误。

## 实际回归结果

下列结果来自本轮日志和 XML，不沿用旧版本测试总数。

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| Android 全量 JVM | **974 项 / 146 suites，0 failure / error / skipped** | `android/app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| Android Debug 与 AndroidTest 构建、lint | **成功，2 分 35 秒**；lint 0 Error/Fatal，82 Warning，7 Hint | `camera-final-build-r3.log`；`android/app/build/reports/lint-results-debug.xml` |
| 摄像头专项 Native | **23 项全部通过，11.128 秒** | `camera-native-r2.log` |
| rig2d 语音专项重跑 | **26 项 / 2 suites，0 failure / error / skipped** | `rig-voice-r3.log`；`rig2d/build/test-results/camera-audit-2026-10-20/` |
| rig2d 全量运行 | **220 项，217 通过、3 失败**；未称全绿 | `rig-core-r2.log`；`rig2d/build/test-results/latest/` |

rig2d 语音专项由 `DuplexCallSessionTest` 的 16 项和 `VoiceRulesTest` 的 10 项组成。全量运行中另有 `VoiceSessionLifecycleAuditTest` 3 项通过，但不把它们重复计入专项的 26 项。

rig2d 的 3 项失败全部来自原有 `PromptMaskBridgeIntegrationTest`：缺少 `artifacts/sam2-model-eval-2026-10-13-r2/` 下 `colored_oc`、`line_oc`、`astronaut` 三个案例的 `packet.properties`。异常为 `NoSuchFileException`；它们涉及旧抠图评估材料，本次没有制造替代材料、跳过断言或宣称已修复。摄像头与语音专项不依赖这些材料。

Android 使用的 `rig2d-0.2.0-studio.jar` 已随协调核心修改重建，并同步构建校验值：

```text
SHA-256: ed8c5b9eb719a99a6d6fa879814881f271670bbc8b096bbb963b2ac62cde2c0f
```

### 23 项 Native 的组成

| 测试类 | 项数 | 范围 |
| --- | ---: | --- |
| `CallCameraDialogLifecycleNativeTest` | 3 | 暂停而未停止、镜头切换迟到帧、捕获报错后的失效 |
| `CallCameraStateObservationNativeTest` | 5 | 真实 LiveData/Lifecycle 的运行中错误、同步初始错误、旧代际、显式关闭、销毁 |
| `CallCameraGatewayNativeTest` | 6 | 请求网关的视觉传递、当前帧与历史内容隔离等既有契约 |
| `CallCameraDeviceNativeTest` | 1 | 真实 CameraX 绑定模拟前后镜头、JPEG 解码和尺寸/字节限制、关闭后重开 |
| `CallCameraAnalyzerNativeTest` | 2 | ImageProxy 释放、节流/失败、迟到帧拒收 |
| `CallCameraEncoderNativeTest` | 3 | 编码转换的既有回归 |
| `CallCameraPreviewNativeTest` | 3 | 预览生命周期、可见性与资源释放的既有回归 |

`CallCameraDeviceNativeTest` 使用专用 Android 模拟器的模拟前后镜头，不使用真人摄像头。它验证真实 CameraX 与编码链路；其他生命周期场景按测试分别使用合成帧或替代引擎，不能统一描述为物理摄像头测试。

### 保留的红绿证据

本轮私有原始日志在仓库根目录 `artifacts/camera-audit-promo-2026-10-20/`，不随源码提交。以下文件名均相对于该目录。

| 复现阶段 | 红色结果 | 修复后的对应证据 |
| --- | --- | --- |
| 已取消的请求仍进入视觉块 | `regression-red-build.log`：5 项中 1 项失败，`anAlreadyCancelledRequestCannotEnterTheVisualBlock` | 最终 974 项 JVM 全绿 |
| 暂停、切镜头、捕获失败 | `regression-native-red.log`：3 项全部失败，断言均发现仍存在不该存在的帧租约 | `camera-native-green-r1.log`：首批 17 项通过；最终 `camera-native-r2.log` 23 项通过 |
| 关镜头后视觉语音仍在播放 | `camera-green-r1-build.log`：42 项中 1 项失败，`closingCameraWhileSpeakingStopsVisualPlaybackAndKeepsAudioCallUsable` | 最终 JVM 包含该测试并通过；关闭后可接收下一句纯语音，不重新获取麦克风 |
| 正常采样刷新与模型完成后的排队语音 | `camera-red-r2.log`：13 项中 2 项失败，分别为 `aNewSampleDoesNotDiscardTheAlreadySelectedFreshTurn`、`closingCameraAfterModelFinishedStillStopsQueuedVisualSpeech` | `camera-green-r2.log` 定向通过，随后最终 974 项全量通过 |

日志名带 `green` 不代表其中所有检查都成功：例如 `camera-green-r1-build.log` 保留了当时真实的 1 项失败。最终结论以 `camera-final-build-r3.log` 和 `camera-native-r2.log` 为准。

## 审查范围与限制

多智能体分别检查权限/生命周期、帧缓存与请求所有权、视觉回复与语音取消，再由主代理实际构建和回归。独立复审覆盖 `DuplexCallDialog`、`CallCameraPreview`、`CallCameraSession`、`CallReplySource`、`DuplexVoiceController` 及 rig2d 的回复撤销改动；截至本报告，未发现这些改动中可直接证明、尚未处理的严重并发缺陷。该结论不是对仓库全部代码的安全担保。

本轮没有连接物理手机，没有读取真人摄像头，没有使用用户 Key 做真实视觉模型质量比较；未验收红米 Camera HAL、权限在真实系统设置页动态撤销的表现、物理麦克风回声消除、蓝牙路由、真实端到端延迟，也没有宣称连续实时视频理解。宣传片应使用合成演示素材，明确摄像头是“开启后，语音携带当前单帧”，避免表达成持续视频流或已经证明的即时回应。

CameraX 状态处理依据：[CameraState 官方 API](https://developer.android.com/reference/androidx/camera/core/CameraState)；逐帧所有权依据：[CameraX ImageAnalysis 官方文档](https://developer.android.com/media/camera/camerax/analyze)。
