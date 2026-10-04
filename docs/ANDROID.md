# 知语 Android 客户端

原生 Kotlin + Jetpack Compose，应用 ID `com.zhiyu.app`，支持 Android 8.0（API 26）及以上。

## 功能

- 连接页：输入后端地址，真实检测 `/health` 后获取 `/api/providers`；选择国内服务商、模型或自定义接入点，输入 API Key 登录。
- 服务商 Key 验证会调用一次极短的真实模型输出（后端限制 8 个输出 token），可能产生少量费用。开发后端开启 `demo` 时才提供本地演示服务商；界面明确标记演示，无云端请求。
- 会话列表、新对话、永久删除；发送 AI 消息、加载提示、自动滚动、复制消息、展示实际检索来源。
- 知识库：系统文件选择器导入 TXT、Markdown、PDF、DOCX，单个文件限 5 MB，列出文件与片段数，确认后删除。扫描 PDF 请先 OCR。
- 对话级知识检索开关；设置页查看连接、更换服务商/模型/后端、退出并清除本机 Key。

## 连接与部署

调试 APK 默认地址 `http://10.0.2.2:8790`，这是 Android 模拟器访问宿主电脑的地址。真机请输入电脑的局域网 IP，例如 `http://192.168.1.20:8790`，后端需监听局域网接口且防火墙允许端口。

调试版允许 HTTP，界面显示传输未加密提示；正式版默认地址为空，并由输入校验与 Android Manifest 双重限制仅使用 HTTPS。远程部署必须配置有效 HTTPS 证书。客户端不忽略证书错误，不跟随 HTTP 重定向，不记录 Key、请求或响应日志。

用户不需要独立账户。Key 会发送至用户指定的后端，由后端调用所选服务商。该后端是受信任的凭据接收方。

## 本机凭据与恢复

`SecureCredentialStore` 使用 Android Keystore 中不可导出的 AES-256 Key，以随机 IV、AES-GCM 与 AAD 加密 API Key、后端地址和 Bearer 会话令牌。SharedPreferences 中仅保存密文；禁止云备份与设备迁移。加密信息损坏时明确提示并清除，要求重新连接。

会话将要到期，或请求返回 401（包括后端重启丢失会话）时，会使用本机 Key 重新登录，同一请求最多重试一次。模型更换沿用后端 Key 身份；服务商或 Key 更换由后端隔离数据。更换连接必须先退出，退出会清除本机凭据；即使后端离线，本机 Key 仍会删除。

## 结构与构建

`domain`：纯 Kotlin 模型、Repository 接口、输入验证和 SessionCoordinator。`data`：系统 HttpURLConnection、JSON 显式映射、加密存储、限量文件读取。`ui`：StateFlow + ViewModel，Compose 四个页面。单模块保持边界，避免过度拆分。

依赖固定为本机已有并核对官方资料的 AGP 9.3.0、Gradle 9.5.0、Compose BOM 2026.06.01、Compose compiler plugin 2.3.21。AGP 9 内置 Kotlin，无需重复应用 `org.jetbrains.kotlin.android`。源码使用 JDK 17 字节码，本机通过 JDK 23 构建。

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-23'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK 输出 `android/app/build/outputs/apk/debug/app-debug.apk`。正式版需部署 HTTPS 后端，并配置自己的签名后构建 release。

Gradle wrapper 脚本与 JAR 复用本机 WakeDeck 项目的 Gradle 9.5.0 标准 wrapper，保留脚本内原有 Apache 2.0 版权；分发来源为 [Gradle 官方](https://services.gradle.org/distributions/gradle-9.5.0-bin.zip)。没有复用 WakeDeck 应用源码或凭据。

官方依据：[AGP 9.3.0 兼容表](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[Kotlin 版本记录](https://kotlinlang.org/docs/releases.html)、[Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

## 验证记录

最终 domain 单元测试 16 项通过，覆盖地址/HTTPS/消息/文档限制、会话到期刷新、401 重试、失败登录不覆盖旧连接和凭据字符串脱敏。模拟器 instrumentation 3 项通过，包括真实 Keystore 随机 IV 加密往返与损坏处理，以及真实后端的 Compose 登录、聊天、引用展开、知识库列表和退出。系统文件选择器导入与删除、后端重启后的自动重连另经实际操作确认。debug 与 release 构建成功，详见 [VALIDATION.md](VALIDATION.md)。
