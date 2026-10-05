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

0.1.1调试APK内置`http://10.0.2.2:8791`，模拟器访问Docker测试服务。普通用户无需填地址；开发人员在debug“开发设置”改为直接后端8790或真机局域网IP。地址变更清除Key并回验证页，保留公司、模型及人格绑定；真机需后端监听局域网接口。

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

APK输出`android/app/build/outputs/apk/debug/app-debug.apk`，最终副本`artifacts/ZhiYu-0.1.1-debug.apk`；正式分发仍需HTTPS与发布签名。

Gradle wrapper 脚本与 JAR 复用本机 WakeDeck 项目的 Gradle 9.5.0 标准 wrapper，保留脚本内原有 Apache 2.0 版权；分发来源为 [Gradle 官方](https://services.gradle.org/distributions/gradle-9.5.0-bin.zip)。没有复用 WakeDeck 应用源码或凭据。

官方依据：[AGP 9.3.0 兼容表](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[Kotlin 版本记录](https://kotlinlang.org/docs/releases.html)、[Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

## 验证记录

2026-10-05新版39项unit通过，debug/debugAndroidTest/release R8构建通过；真实API36模拟器连接Docker8791，8项Keystore和5项UI用例通过。UI测试覆盖首选取消/确认、单页聊天引用资料、退出重验保持绑定、preset只读、custom修改取消与保存后退出/Activity重建恢复。首轮13项中一项密码显示断言错误，修为显示Key后断言，受影响UI5项全部重测绿色。未用真实云Key、真机或公网HTTPS。[完整验证](VALIDATION.md) · [记忆与设定](CONVERSATION_MEMORY.md) · [空间优化](STORAGE.md)。
