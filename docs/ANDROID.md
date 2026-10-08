# 知语 Android AI 恋人

原生 Kotlin + Jetpack Compose，应用 ID `com.zhiyu.app`，支持 Android 8.0（API 26）及以上。

## 功能

- 首次进入自动检测服务与加载公司，只需选择AI公司、输入API Key；后端地址由构建配置提供。模型设置可选展开，豆包需自己的模型或接入点ID。
- 服务商 Key 验证会调用一次极短的真实模型输出（后端限制 8 个输出 token），可能产生少量费用。开发后端开启 `demo` 时才提供本地演示服务商；界面明确标记演示，无云端请求。
- 一个持续聊天页面，没有新开话题、会话列表或底部导航；发送、加载、自动滚动、复制与来源展开。
- 选择页显示“恋人仅此唯一，此择不可挽回”。首次确认公司、模型与设定；取消或验证失败不绑定，验证与后端设定接受成功后固定公司和模型。
- 可选“我们调好的恋人”：温柔体贴/俏皮亲昵/成熟稳重，女友/男友/不限定，名字和对用户称呼；首次绑定后整份预设只读，无法换性格、形象、名字或关闭。后端也拒绝变更。
- “自行调教”可写最多2000字恋人设定，之后可调整或清空内容，仍是同一段聊天；启用后不切换成固定预设或关闭模式。不启用设定则在聊天中自行培养，之后可启用自调。
- “更多设置→资料”弹窗使用系统文件选择器导入TXT/MD/PDF/DOCX，单个最大5MiB，查看来源、确认删除。扫描PDF需OCR；文档RAG和聊天记忆独立。
- 消息每批最多100条，点击更早消息切换旧批，返回最近或发送后回到最新；完整聊天数据库主要在后端，不整份下载到手机。最近20完整轮及相关较早原话参与回复，不保证无限历史都能准确召回。

## 连接与部署

0.1.2调试APK默认`http://127.0.0.1:8791`，通过ADB反向端口转发访问电脑Docker测试服务。手机无需与电脑同一Wi-Fi，也无需用户填后端地址。先启动Docker及`zhiyu-docker-test-backend-1`，解锁手机、开启USB调试并允许电脑授权，然后在项目根目录执行：

```powershell
.\scripts\Start-AndroidUsbDebug.ps1 -ApkPath .\artifacts\ZhiYu-0.1.2-usb-debug.apk -RestartApp
```

多台手机时加`-Serial 手机序列号`；只恢复连接时省略`-ApkPath`。覆盖安装使用`adb install -r`，保留数据。USB重新插拔、ADB重启或电脑重启后需重新运行脚本；这是本地调试方式，拔线后不能继续访问电脑后端。模拟器可使用`adb -s emulator-5554 reverse tcp:8791 tcp:8791`，或明确以`-PzhiyuBackendUrl=http://10.0.2.2:8791`构建模拟器专用包。

仅USB debug配置自动将已保存的旧默认`http://10.0.2.2:8791`改成同端口USB入口；Key、会话及完整恋人绑定保留。自定义地址与release不迁移。debug“开发设置”仍可手动改为可达的局域网地址，手动改址清Key并回验证页、保留绑定；局域网方式需要另行配置后端监听和可信网络。

真机上只运行`com.zhiyu.app.data.UsbBackendConnectivityTest`等不访问用户存储的测试；现有`BackendFlowTest`会清空测试安装的凭据与绑定，禁止在用户原安装上运行整套`connectedDebugAndroidTest`。

2026-10-08在红米Turbo 3（Android 16/API 36）复现旧0.1.1入口超时，旧安装包SHA与模拟器版一致。恢复Docker临时socket目录及原数据卷后，覆盖安装0.1.2并建立USB连接；实际手机公司目录正常加载，使用用户授权的原DeepSeek Key验证成功并进入同一聊天页面。手机升级前未保存Key/绑定；既有连接迁移的保留规则另由单元测试验证。81项单元测试及debug/debugAndroidTest构建通过，release HTTP配置实际被拒绝、HTTPS构建配置的USB迁移开关为false。Windows PowerShell5.1/7脚本解析和退出码兼容性通过，独审C/H/M开放0。测试辅助APK首次安装被手机系统`INSTALL_FAILED_USER_RESTRICTED`拒绝，instrumentation未执行，不计通过；旧清凭据E2E未在手机运行。没有替用户发送连通性聊天消息。

debug允许本地HTTP；release通过`-PzhiyuBackendUrl=https://你的域名`配置，只允许HTTPS。未配置的release仍可编译，但进入时提示服务尚未配置，不能用于正式用户。正式部署需有效证书与自己的签名；客户端不忽略证书错误、不跟随重定向、不记录Key或网络内容。

无需独立账号。Key发往应用配置的受信任后端，由后端调用已绑定服务商；更换不同Key不自动迁移原聊天。

## 本机凭据与恢复

`SecureCredentialStore`用不可导出的AES-256 Keystore Key、随机IV、AES-GCM与分离AAD加密凭据、绑定与最新自调设定；SharedPreferences只存密文，禁止备份和迁移。凭据损坏时清除凭据并明确报错；绑定损坏则拒绝重新选择，不静默解锁。

会话过期或401时用本机Key重新验证，同一请求最多重试一次。“重新验证API Key”只清除凭据，不解除公司/模型/preset绑定；离线也清除本机Key。最新custom自调也加密保存，重新登录优先采用后端最新档案，显式编辑才更新。旧版升级沿用原AI及后端设定，不新建第二段聊天改人格。

绑定只覆盖正常使用中的本次安装；清数据、卸载或换设备不会保留本机身份，没有跨设备永久锁定。后端身份按Key与公司隔离，同Key恢复原历史，不同Key不自动迁移。

## 结构与构建

`domain`为纯Kotlin模型、输入规则与SessionCoordinator；`data`负责网络、JSON映射、加密和限量文件读取；`ui`采用StateFlow/ViewModel、单聊天页和弹窗。单模块保持边界。

依赖固定为本机已有并核对官方资料的 AGP 9.3.0、Gradle 9.5.0、Compose BOM 2026.06.01、Compose compiler plugin 2.3.21。AGP 9 内置 Kotlin，无需重复应用 `org.jetbrains.kotlin.android`。源码使用 JDK 17 字节码，本机通过 JDK 23 构建。

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-23'
.\gradlew.bat --no-daemon --offline --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8' :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
```

APK输出`android/app/build/outputs/apk/debug/app-debug.apk`，当前记忆版副本`artifacts/ZhiYu-0.1.1-memory-debug.apk`，旧`artifacts/ZhiYu-0.1.1-debug.apk`保留；正式分发仍需HTTPS与发布签名。

Gradle wrapper 脚本与 JAR 复用本机 WakeDeck 项目的 Gradle 9.5.0 标准 wrapper，保留脚本内原有 Apache 2.0 版权；分发来源为 [Gradle 官方](https://services.gradle.org/distributions/gradle-9.5.0-bin.zip)。没有复用 WakeDeck 应用源码或凭据。

官方依据：[AGP 9.3.0 兼容表](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[Kotlin 版本记录](https://kotlinlang.org/docs/releases.html)、[Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

## 验证记录

2026-10-05新版39项unit通过，debug/debugAndroidTest/release R8构建通过；真实API36模拟器连接Docker8791，8项Keystore和5项UI用例通过。UI测试覆盖首选取消/确认、单页聊天引用资料、退出重验保持绑定、preset只读、custom修改取消与保存后退出/Activity重建恢复。首轮13项中一项密码显示断言错误，修为显示Key后断言，受影响UI5项全部重测绿色。未用真实云Key、真机或公网HTTPS。[完整验证](VALIDATION.md) · [记忆与设定](CONVERSATION_MEMORY.md) · [空间优化](STORAGE.md)。

## 2026-10-06 记忆管理客户端

聊天仍为同一个页面。“更多设置→记忆”打开弹窗，每批最多50条原话节选；长按消息或点消息旁的书签可精确查看该句的来源作者、保存时间、层级和归档状态。原话逐句独立保留，不通过这些操作编辑、删除或合并。消息复制按钮继续可用。

可手动指定中期、长期、永久，或恢复自动判断；自动识别是否开启由后端响应标记。归档显示“已归档，原话完整保留”，可恢复活跃状态。长期、永久及受保护的约定在界面禁用归档，后端仍负责最终校验。“确认这是一次独立表达”只在后端确认这句用户自述具备条件时显示，不由客户端猜测意图。

每次更新携带读取时的版本；409明确显示原因并重新读取当前状态，不自动覆盖或重试修改。切服务、会话身份变化、401和重新认证会清空原记忆弹窗数据，保留既有公司、模型与人格绑定规则。客户端只取最多768码点节选，不下载全部长聊天数据库。

列表与详细页区分“语义识别”“规则识别”和“未识别”，依据后端实际识别结果展示，失败或未调用模型时不显示语义成功。失败、不可用或过长跳过时提醒“原话已保留，层级可手动确认”。弹窗说明启用语义标注将使用所选AI额外识别一次，可能产生额外费用，判断仍可手动调整。旧响应缺识别字段时兼容为`none/unavailable`；未知枚举、null或非字符串拒绝处理，不猜测分类。

63项单位测试通过（新增24项），覆盖Unicode/NUL原文、分页边界、版本携带、冲突刷新、归档/恢复、身份隔离和识别状态兼容；debug、debugAndroidTest、release R8构建通过。2026-10-06 21:48–21:49，在实际API36模拟器`medium_phone / emulator-5554`连接升级后的Docker8791，28项instrumentation首轮全部通过（35.561秒）：13项真实Android JSON映射、2项新记忆UI、5项既有绑定/资料/设定UI、8项Keystore。日志`artifacts/android-memory-instrumentation.log`明确`OK (28 tests)`，运行记录`artifacts/android-memory-instrumentation-runtime.json`。

记忆UI联测覆盖长按、永久层级归档保护、恢复自动、归档/恢复、原话不变、768码点Unicode节选以及关闭应用重新启动后保留归档状态。测试只使用隔离的合成demo Key；后端语义模式下demo无法调用真实模型，界面明确显示“未识别”和原话保留提示。真实云端语义质量、真实Key、真机和公网HTTPS仍未验证。本阶段没有失败后的重试，测试期间新旧APK哈希均未变化。

新调试副本为 `artifacts/ZhiYu-0.1.1-memory-debug.apk`（18,798,982字节，SHA256 `D2175EFDB97C80BF84F90C0DE9580F7BC6EC89953B4FCA4EE0D860247102806D`）；旧 `ZhiYu-0.1.1-debug.apk` 保留。此构建增加记忆界面但沿用0.1.1版本号，正式分发仍需HTTPS和发布签名。

## 2026-10-07 后台整理记忆状态

客户端接受后台识别的`pending`、`running`和`waiting_auth`状态。列表和详细页对前两种状态显示“正在整理记忆，聊天可以继续”，等待凭据时显示“重新连接后继续整理记忆”。这些状态保持原话、来源与版本，不显示语义识别成功或失败提示，也不把整理状态设为聊天忙碌。设备加密Key、公司/模型绑定和固定预设继续沿用现有实现。

先运行新增后台状态用例，旧校验真实拒绝`pending`，1项预期红色失败已保存为`artifacts/android-background-memory-tdd-red.log`及XML。放行三种已确认状态后，65项单位测试通过，debug/debugAndroidTest构建通过（`artifacts/android-background-memory-build.log`）。

2026-10-07 10:07，重新核验SDK、adb与服务易失状态，在后台启动的API36模拟器`medium_phone / emulator-5554`安装上述已编译的两份APK，连接实际新Docker8791镜像`sha256:2ab853fb3244d3ceb7f604d9a7cdfbb09cd2df343215d79f7846454712c071ad`。19项instrumentation首轮全部通过，运行15.128秒：14项真实Android JSON映射、3项后台状态Compose UI、2项实际后端记忆管理流程。证据`artifacts/android-background-memory-instrumentation.log`明确`OK (19 tests)`，运行及哈希记录在`artifacts/android-background-memory-instrumentation-runtime.json`。上一轮28项属于旧语义版，未重复计入本轮结果。

新增映射与UI覆盖`pending/running/waiting_auth`、原话Unicode与来源/版本保留、“聊天可以继续”与等待凭据的提示，不误报识别成功或失败。实际demo联测再次验证长按原话、永久层级归档保护、恢复自动、归档/恢复、768码点节选、Activity重新启动后归档与绑定不变。只使用合成demo凭据，本轮无云端请求；模拟器状态测试不能代表实际云端语义判断准确率、真机或公网HTTPS。两份本轮APK与两份历史APK的SHA在测试前后均未改变，服务测试后仍healthy。

本轮包单独保存为`artifacts/ZhiYu-0.1.1-background-memory-debug.apk`（18,798,982字节，SHA256 `882AEAECEE9633FD426DC82C3CE708A99C6A739755A8C27C33DC60EFD58242FC`），测试包`artifacts/ZhiYu-0.1.1-background-memory-androidTest.apk`（SHA256 `EF3800291ED46CC3AC4CF029457DC2E79C868D7E347D5EA2F9E807B62D86897E`）。上一轮已联测的`memory-debug`包与原始`debug`包均保留且哈希未变。
