# 本机日历与天气关心

本版功能全部由手机完成，不要求电脑后端。2026-10-10 更新：首次进入已绑定聊天前置申请能力权限，天气和日历由系统授权启用，不修改已绑定的 AI 公司、模型或性格。

## 日历

用户可在“日历”菜单写日志，通过 `ACTION_INSERT` 打开系统编辑器，自行选择提醒和重复方式；打开页面仍不等于已保存。另一条独立链路是恋人自行记下：前置 `READ_CALENDAR` / `WRITE_CALENDAR` 授权后，在同一次回复中生成隐含日历动作，不要求额外写入指令、不逐项询问，不弹成功通知或在聊天谈内部记录。

此自动链路已由用户明确要求，并取代此前“每项确认保存”的范围。应用只查询或写入账户名为本包、类型为 LOCAL 的知语专用日历。日记最多一条/天；明确生日/纪念日与已定约定保留连续原话证据，日期需匹配原句支持的日期表达。模型负责语义候选，客户端保守拦截条件、否定、候选、疑问、错误日期及不匹配证据；不宣称覆盖所有自然语言。日历是派生行动，不能替代、融合或改写 RAG 原文。随机 nonce 的末行控制格式与自然示例、缩进代码严格区分，先解析日历再解析媒体；事件按原消息身份幂等，系统失败不回滚聊天。[Android 日历官方文档](https://developer.android.com/identity/providers/calendar-provider)

目前自动动作是一日全天事件或准备事项，未接精确提醒、年度循环、实际礼物购买或线下行动。权限允许记录与准备，不意味着模型可以虚构已经做过的事情。没有新云分类请求，也没有读取用户其他日程。

## 天气

首次进入聊天直接申请 `ACCESS_COARSE_LOCATION`，授权后启用天气关心；拒绝不会阻止登录或聊天。每次获取是一次前台粗定位，最多等待 10 秒，应用进入后台会取消尚未完成的定位，撤权会清除天气快照。回到前台时，已授权用户最多每 15 分钟刷新一次；没有后台定位、持续轨迹、精确定位或位置写入 RAG。[Android 定位权限说明](https://developer.android.com/develop/sensors-and-location/location/permissions)、[Android 一次定位 API](https://developer.android.com/reference/android/location/LocationManager#getCurrentLocation(java.lang.String,android.os.CancellationSignal,java.util.concurrent.Executor,java.util.function.Consumer))

位置在发出网络请求前再取整到 0.1 度，手机直连固定 HTTPS `api.open-meteo.com/v1/forecast` 查询天气估计。请求不携带 AI Key、聊天内容或其他身份字段，不允许重定向，不打印请求地址或坐标；响应限制 64 KiB，每段网络等待最多 8 秒、整个天气请求最多 12 秒，取消会断开连接。校验当前天气温度、体感温度、天气代码、降水、风速、UTC 时间和单位，未知代码、空值、字符串冒充数值、过期数据等一律拒绝。天气服务失败时明确提示失败，不编造天气或用户行踪。

坐标只在这次定位和天气请求的内存中存在；本机独立 `weather_care` SharedPreferences 仅保存开关和最近的天气事实，不保存坐标。`NativeCareContext.prompt()` 只在开关开启、仍有权限、数据不超过 2 小时时提供天气事实；关闭、撤销权限、过期或损坏时返回空上下文。这段上下文不会进入聊天原文索引，也不会改变固定性格或要求每条回复都提天气。它限制模型不能把天气估计当成用户自述，也不能声称正在监视或跟踪用户。

当前天气来自模型估计，并非手机传感器实测。界面标明 Open-Meteo 来源；当前公共接口适合本地非商业验证，商业发行需按照其套餐及条款配置客户资源或替换为已授权的天气服务，不能直接把公共测试额度当作商业资源。[Open-Meteo 天气文档](https://open-meteo.com/en/docs)、[服务条款](https://open-meteo.com/en/terms)

## 接入契约

- 菜单层创建 `val care = rememberCareController()`，显示 `CareSettingsDialog(care, close)`。
- 本机聊天与主动消息在原有性格提示词后追加 `NativeCareContext(applicationContext).prompt()`；没有有效天气时自动为空。
- 无后端 HTTP 路由，无服务端天气数据库；不需要接入旧 Docker 后端。
- 自动恋人动作目前是一日全天日志或准备事项；系统日历编辑器中的提醒/周期仅属于用户手动入口，自动链路尚未设置提醒或年度重复。

## 验证记录

`CareRulesTest` 先写测试，在缺少模块时出现预期的编译错误（`artifacts/care-rules-red.log`）；加入纯领域模块后独立 Kotlin 编译和 JUnit 运行 7 项全通过（`artifacts/care-rules-green.log`），覆盖坐标边界/非有限值/取整、固定 HTTPS 端点、天气时效和撤权、未知或无效天气、人格约束、日历真实日期和全天 UTC 边界。

`CareFeaturesTest` 仪器测试覆盖真实 Android JSON 解析、单位/类型/时间异常、本机存储关闭和损坏，以及系统日历 Intent。该测试仅使用测试 APK 独立偏好，不读取应用的 Key、绑定或聊天。最终 Android 构建和模拟器执行结果由主任务统一记录，不能把本节的纯领域测试当作所有 Android 测试已运行。

`CareCancellationTest` 使用可控的定位/天气替身验证拒绝权限不调用定位、关闭后晚到的网络结果不能重新开启、进入后台取消待完成请求，以及撤销权限后清除已接受天气。只操作测试 APK 独立偏好。

公开城市固定坐标的联网冒烟测试成功，接口返回正确单位、UTC 时间和数值字段，耗时 1.627 秒、响应 503 字节（`artifacts/care-weather-public-smoke.json`）。这是电脑到公开天气接口的可达性验证，不代表已经完成红米手机定位和公网网络验证。
