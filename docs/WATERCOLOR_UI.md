# 安卓水彩界面（2026-10-09）

本轮按用户花卉参考图及“还有这种画画风格”的要求，更新原生 Kotlin / Jetpack Compose 手机界面。奶油纸底、玫瑰粉和灰绿来自画作；花卉保留水彩晕染、半透明叠色及不齐的边缘。登录页展示完整画作，聊天页只在底部留淡花影，正文保持清楚。

## 保留的交互

- 公司 + API Key 进入；Key 默认遮蔽，不需要填写电脑后端地址。
- 唯一公司／模型绑定、固定预设、自定义设定的既有规则；确认详情可滚动。
- 单一聊天、完全透明的描边气泡、完整原文复制、双方头像和背景选择。
- 用户背景优先；有自选图片时不叠加默认花卉背景。没有新增记忆入口。
- 浅色／深色随系统切换；纸张纹理仅为轻微画布颗粒。短窗口空聊天可滚动到建议按钮。

## 插画来源与加载

使用内置 `image_gen.imagegen`，以用户提供的花卉图为风格参考，生成原创水彩花瓶插画；真实透明背景，无文字。工具未承诺具体 image2 / image2.5 型号，不能把本轮资产描述成已经接入用户的中转生图服务。

原工具文件：`C:/Users/32202/.codex/generated_images/01a105f8-faaf-7981-b77a-b886992e7c59/exec-6a42fb93-6c7c-47a1-beaa-aa5e9477e31c.png`。

随包资源：`android/app/src/main/res/drawable-nodpi/watercolor_bouquet_v1.png`，1,865,636 字节，1665 × 944。保留工具生成原文件，未另外修改画作。主题只加载一次 painter，背景、预览和列表中的默认头像共享它，避免每条新消息重复解码约 6 MiB 的位图。

生成指令要点：

> Native romantic Android illustration; use reference for style only. Original loose hand-painted watercolor floral still life, blush/coral roses, ivory blooms, sage/olive foliage, peach stems, warm gray earthenware vase with dusty red rim. Watery blooms, translucent overlaps, uneven brush marks and lost edges. Airy wide arrangement with margin, actual transparent background. No ink outlines, photography, flat vector, glossy gradients, 3D, text, UI, hearts or watermark.

## 本轮实测

- `watercolor-build-r1.log`：编译／255 项单元通过，但 lint 因两个旧图片解码函数的 API 版本标注缺失失败。调用方已有 `SDK_INT >= 28`，修复为明确的 `@RequiresApi(28)`，没有提高最低 Android 版本。
- `watercolor-build-r2.log`：主 APK、测试 APK、255 项单元（36 suites，失败／错误／跳过均 0）、lint 全部成功；lint 为 0 errors / 39 warnings，没有添加 baseline 或关闭检查。警告涉及已有依赖版本、KTX 建议、平台 Exif 等，不能描述成零警告。
- 最终 `watercolor-build-r3.log`：将启动窗口与应用图标统一纸色／玫瑰色，并加夜间窗口配色后再次构建；255 项及 lint 同样通过，安装包以 r3 为准。
- `watercolor-native-ui-r1.log`：14 / 14，22.673 秒。包含水彩 4、头像背景 4、旧预设 3、新预设 2、飞行模式启动 1；其中验证 320dp 宽／1.4 字体的完整预设登录确认、360dp 矮空聊天、用户背景优先和透明气泡原文复制。
- `watercolor-native-ui-r3.log`：最终包同一批 14 / 14 复测，22.221 秒。不把重复执行相加成 28 项独立测试。
- `watercolor-native-ui-dark-r1.log`：深色聊天另 1 / 1，2.265 秒。
- 独立只读源码审查后已修共享 painter、短窗口滚动和测试位图生命周期问题；复核未发现本轮未解决的高风险问题。

仅使用隔离 `zhiyu_local_20261009` / `emulator-5582`；无 USB reverse、无 Key 和云请求，没有清除用户 Key、绑定或正式聊天。飞行模式只证明本机公司目录／登录页可以打开，云端 AI 聊天仍需要互联网和有效 Key；本轮没有重新调用收费云服务。

## 预览及安装

- 实际 MainActivity 飞行模式登录：`artifacts/watercolor-login-light.png`、`artifacts/watercolor-login-dark.png`。已临时切系统深色核对，并恢复隔离模拟器原浅色模式。
- 使用真实组件和合成原话的聊天：`artifacts/watercolor-chat-light.png`、`artifacts/watercolor-chat-dark.png`。合成聊天测试 Activity 的状态栏配置与 MainActivity 不完全相同；实际主 Activity 的浅色状态栏已单独查看。
- 手机候选为 0.2.0 / versionCode 6 / arm64-v8a / min Android 8.0。使用与旧包一致的调试证书，适合覆盖试用；它不是正式收费发行签名。
- 安装文件：`C:/Users/32202/Desktop/知语-0.2.0-水彩本地版.apk`，项目副本 `artifacts/ZhiYu-0.2.0-watercolor-arm64.apk`，94,891,411 字节（约 90.50 MiB）。两份 SHA-256 均为 `2fdab958c1be5b1ab9d3b626edd1b3c3938dc33d7808501d531cfd47c33f323c`；证书 SHA-256 `2f6842228b821c4f62a9842747e94ba1f4ec8c57683f063587ae750da00b4243` 与 0.1.3 一致。校验与随包资源清单见 `artifacts/watercolor-apk-proof-r3.json`、`watercolor-apk-signature-r3.txt`。

本轮仅改界面与上述兼容性标注，未改 RAG、向量模型、检索权重或数据库格式。完整模型拆层、Live2D rig、语音／视频全双工及商业发行仍见 [功能对照](MOBILE_FEATURE_PARITY.md)。完成的压力测试见 [原生长测总结](NATIVE_50M_AND_10000_SMOKE_SUMMARY_2026-10-09.md)。
