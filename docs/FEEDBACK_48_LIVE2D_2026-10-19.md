# 反馈 48：预览和通话人物都不动

基线为 `5d4b489`、0.2.16 / code22，修复进入 0.2.17 候选。用户已明确：动画预览与通话里都看不到人物动作，原图显示正常。本轮没有手机连接；不把模拟器、数学网格或 GPU 非零变化等同于用户手机验收。

## 实际入口与证据

- `ZhiYuApp` 的“动态形象”打开 `PlanarWorkshopDialog`；“动画预览”调用 `PlanarWorkshopController.buildPreview()`，用当前草稿构建临时包。正式保存另走 `save()`、`PlanarPackageStore`，通话用 `forBinding()` 返回与当前原图绑定的正式包。
- 工坊预览与 `CallCharacterPreview` 共用 `PlanarPreview`、`PlanarSurfaceView`、`PlanarGlRenderer`。两个实际调用均开启 idle。Surface 可见且生命周期 resumed 时每 33ms 推进时间；暂停/隐藏停止。现有生命周期测试与已有帧回调只能证明调度发生。
- 共用 `PlanarAnimation` 的头部 idle 输入只有 `headX=sin(t*.67)*.08`、`headY=sin(t*.43)*.05`。0.2.16 的手绘头部网格还将运动场 `amount` 封顶 20，水平 basis 为 `amount*.55`，所以实际水平位移最多 **0.88 个原图像素**。1224 像素高原图放入 260dp 工坊预览，水平幅度上限约 **0.187dp**，往返也只有约 0.374dp。网格非零不能说明肉眼可见。
- 旧 GPU 回归用 128px 棋盘纹理和少量颜色变化作为通过条件；高频棋盘能显示亚像素采样差异，不能代替大原图在真实页面尺寸下的局部位移。
- 只读检查历史 `artifacts/usb-live2d/active-after-usb-correction.planar`（SHA `26d89408011790f2ba68076bfe639d537c2b393f156c5b16febe2a0cb5d109ac`）：1220×1224，1 个 face 建议层、11 条 subject 笔迹、口型关闭；旧 face `headX` basis 最大12，套用旧 idle 后水平幅度最多0.96原图像素，工坊约0.204dp。该包是上次真机留档，**不是本轮手机上的最新包**。本轮未改它、原图、笔迹或用户数据。

## 本轮回归

新增 `PlanarVisibleMotionNativeTest` 真实页面测试，由 root 统一运行：

1. 1220×1224 合成人像、手画 head 活动层，点击真实“动画预览”，对真实 260dp Surface 执行 PixelCopy；4 秒内鼻部标记的颜色加权重心横移至少 1.2dp，原背景中独立绿色标记的二维重心变化各不超过 0.05dp。只测像素颜色阈值会放大亚像素误差，因此使用连续权重重心；独立背景标记也防止把整图平移误判成局部动作。
2. 点击真实“保存并用于通话”，控制器断开后重连、重新读盘，进入实际 `CallCharacterPreview`，执行同样的可见位移与背景断言。加载源函数禁止调用，确保已保存手工包优先。

首轮 `feedback4749-first-native-red-r1.log` 中 Live2D 两项均失败，但**不是可见位移断言失败**：工坊首次 PixelCopy 尚无 Surface buffer（status3），通话项等待保存 active 超时。不能将这轮称为幅度根因的真实 RED。当时仅修夹具：有界等真实首帧、保存完成后直接断言 error/active、先移除编辑页面再重连读盘，生产未动后重跑。

第二轮 `feedback4749-second-native-red-r1.log` 共 11 项 / 8 失败，其中 Live2D 两项已真正进入 PixelCopy 可见位移断言：工坊只有 **0.069068723dp**，保存重开后的通话只有 **0.291428841dp**，均低于 1.2dp。该轮才是本项业务 RED；不把其它 lane 的 6 项失败算作 Live2D 缺陷。

## 最小修复与保护控制

共享 Android 渲染入口对含 face 层的文档使用 `PlanarPhoneIdleMotion`：头部水平 idle 输入为有界 `sin(t*1.25)*.75`，约 5 秒往返；垂直为 `sin(t*.91)*.35`。保持原网格、纹理、strength、活动范围、固定 base、眼口/呼吸/附件通道和暂停条件。不修改 portable `rig2d` 默认，也不重建或写回旧角色包。新旧正式包在实际预览/通话共享渲染入口直接使用该驱动。

新增控制包括：180×235 小头部在实际 260dp 工坊中的默认强度（`PlanarPart`/头部建议默认 **1.0**）、通过附件改成头部时保留的 **0.5** 强度、**0.0** 完全静止；使用旧 `PlanarLocalHeadMotion` 的 9×9 ellipse 网格保存后重新加载并直接通话，渲染前后保存包 SHA 必须相等。它们均使用合成原图，未把用户历史包放入测试 APK。颜色重心扫描限制在有留白的两个标记区域，每轮要求至少 8 个真实像素样本，背景控制先于位移断言。

JVM 四项补充验证首 4 秒双向动作、非头部通道不变、idle 关闭/无头部包仍用 portable 原驱动、自定义契约范围与不可变性，以及长时间通话跨 60 秒时头部动作连续。自审发现放大后沿用 portable 的 `%60` 会放大非整周期截断；`feedback4749-boundary-jvm-red-r1.log` 真正跑出 **4 项 / 1 失败**，恰为 60 秒连续性。之后仅将新增头部驱动改成各通道按 `2π/frequency` 的完整周期取模，portable 未改。

`feedback4749-integration-native-green-r1.log` 真实 **39 / 39** 通过、222.785 秒，其中本项六个实际像素页面测试全部通过：原预览/保存重开通话、180×235 默认头部、0.5 强度、0 强度、旧 9×9 网格包直接通话及包 SHA 不变。可见位移阈值仍为 1.2dp，独立背景标记的变化阈值仍为 0.05dp。它是本次集成回归，最终版本仍待 root 完整冻结回归，不冒称完整发布验收。

首次统一 GREEN 构建的 lint 发现 `UseSdkSuppress` 一项错误：测试类用 `@RequiresApi(29)` 标记 API29 的窗口检查接口。尝试使用正确包名的 `@SdkSuppress` 后，现有编译 classpath 缺少 runner 注解而失败；未新增依赖。最终保留 `@RequiresApi(29)`，对此测试类精确抑制 `UseSdkSuppress`，并用 `assumeTrue` 明确 API29 运行前提。没有改生产 API 范围、降低像素断言或跳过当前受支持模拟器上的这六项。

已有非零头部动作包可直接使用新版驱动，**不必仅为可见幅度再次保存**。但没有头部层、动作场全为零、strength0 或很窄的活动区域不会因此凭空生出动作，需要在工坊人工修正、预览并正式保存；不会擅自撤销用户的这些限制。

所有 Gradle / ADB 由 root 统一执行；本项未调用 DSH、网络模型或收费 API。没有自动完整 Cubism、补背面或 2D/3D 建模承诺；原图、原背景和人工活动区域继续保留。真实用户手机上的当前角色包和主观动作质量仍未在本轮验收。

## 最终0.2.17验收

最终`feedback4749-final-build-r4.log`成功，948JVM/141suite、lint0Error；`feedback4749-final-native-r3.log`完整94类481/481通过（607.828秒），包含本专项全部用例。相关修复8项定向通过后才重跑最终整轮；不以历史“等待root”阶段或39项集成结果替代。最终包SHA及已知边界见[总报告](FEEDBACK_47_49_2026-10-19.md)。
