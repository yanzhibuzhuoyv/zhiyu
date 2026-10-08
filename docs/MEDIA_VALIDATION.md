# 0.1.3 OC、外观与主动通知验证

2026-10-08，Windows 电脑后端及隔离 API 36 Android 模拟器验证。按用户“先电脑验证、断 USB”的指示，本轮没有安装或操作物理手机，未读取真实 Key、私人 RAG 或原聊天数据库。

## 验证结果

| 范围 | 实际结果 | 本机证据 |
| --- | --- | --- |
| 后端全量基线 | 818 passed，覆盖率 93.99%，149.08 秒 | artifacts/media-backend-validation-20261008/summary.json、pytest.log |
| 最后受影响范围 | 54 passed，5.42 秒 | artifacts/media-backend-target-final.log |
| 最后原文检索纯提取 | 26 passed，18.30 秒，所有新 Python 函数 ≤50 行 | artifacts/media-assistant-refactor-final.log |
| Android unit | 101 passed，0 failed/error/skipped | android/app/build/test-results/testDebugUnitTest/TEST-*.xml |
| APK及测试包构建 | 成功 | artifacts/media-android-build-release-candidate.log |
| 原生定向 instrument | 19 passed，15.037 秒 | artifacts/media-native-instrument-final.log |
| 系统通知及主界面 | 2 通知 + 1 演示 UI passed，6.940 秒，无跳过 | artifacts/media-native-notification-ui.log |
| 最终 UI 等待条件 | 同 3 项通过，5.256 秒，等到 ImageView 真实 drawable 显示 | artifacts/media-native-notification-ui-final.log |
| Ruff与diff | 通过 | 最终运行与Git检查 |
| Docker Compose | 三份配置校验通过 | artifacts/media-compose-config.log |

818 全量记录早于最后动画 GIF、疏离无互动、表情间隔改动；最后 54 项覆盖这些改动，不能把两个数字相加或称最后版本重跑了全量。后端仍有一条既有 Starlette/httpx 弃用警告。主界面 smoke 是事先配置本地演示的模拟器流程，不验证真实 AI 回复质量；新测试包含模拟器前置限制。

19 项涵盖真实 Android Keystore 跨实例 CAS、清除与重鉴权竞争、头像/背景存储、镂空气泡透背景、隐藏记忆入口、通知请求耐久、OC 参考图上传/鉴权下载、生成服务未配置不扣两次机会。另 2 项实际读取 NotificationManager.activeNotifications，验证私密与公开替代内容、Activity PendingIntent、持久去重和前台拒绝；没有等待 15 分钟周期或证明 OEM 后台可靠性。

实际 MainActivity 首轮演示发送合成中文消息，表情附件在原生气泡中显示；最终重放恢复原文与附件，等待真实 ImageView 已有 drawable 与有效尺寸，再检查菜单。两个阶段是同 3 项，不叠加测试数。设置菜单保留头像背景/恋人外观/主动联系且没有记忆项。通过 Android 系统文件选择器更换背景，force-stop 后重新启动，背景保持。另实际预览并采用女性 OC 样例，并在真实界面开启/关闭主动联系，开关分别 true/false 且无错误。截图：artifacts/media-demo-ui.png、zhiyu-oc-preview.png；开关 XML：media-proactive-enabled.xml、media-proactive-disabled.xml。头像选择目标与持久化在原生 UI/存储测试覆盖，不冒称所有头像都进行了人工文件选择。

发现并修复两项 HIGH：多个 repository 的重鉴权晚结果可能恢复已清除 Key，改为跨实例原子 CAS；未确认的生图请求编号可能丢失，改为持久请求编号和明确错误码。独审还发现全局 SQLite sequence 影响贴图频率，已改为当前 owner/聊天的实际 assistant 消息数；两个身份交错及独立主动消息的有效 RED 保留，修后通过。审查报告：artifacts/android-media-final-review.md、artifacts/media-seed/independent-review.md。

## 素材与运行环境

6 张独立生成照片、8 张官方 MIT 表情图，共 16,830,019 字节源文件。全部 14 项清单经实际 CLI 导入，记录 artifacts/media-seed-import.json；规范化去掉 EXIF/注释，因此入库 SHA 可与原文件不同。完整 MIT 许可同时保存在原素材包和演示外置目录。首批人物身份由样例分组人工确认，元数据匹配不等于人脸识别保证。

演示后端 `http://127.0.0.1:8801`，仅合成数据 `artifacts/media-computer-data`，独立素材 `artifacts/media-computer-library`，demo=true、云调用为 0。API 36 AVD `zhiyu_media_test`，ADB `emulator-5580`，设备端 8791 reverse 到电脑 8801。health 的后端版本仍是 0.1.1；APK 版本为 0.1.3，不能混用。进程和转发属于易失状态，恢复时须重验。

Docker Engine 本轮未在线，新功能没有实际容器构建或运行验证；只校验配置，原 Docker 数据卷未修改。已有历史 Docker/千轮/万轮证据保留，不重计为本轮结果。

## 交付与剩余接入

APK：`artifacts/ZhiYu-0.1.3-media-debug.apk`；SHA256 `384ACC828B4F8116DCD8AF6ADC87F711FA79DD88319919927B3E6B07FBB35C09`。调试版本仍依赖电脑后端与 ADB reverse，公开 HTTPS 和正式签名发布待部署。

中转 image2.5 尚未接入，接口明确 503、UI明确未配置、不扣次数，未来注入 provider 后再做真实生成验证。现有照片、标签变化和 SHA 冷却减少重复，素材数量仍有限；自己的 OC 导入后不会冒用其他 OC 人物图，需要为该身份补充场景素材。不能承诺自动换脸、无限多样性、自然长期恋爱质量或全双工已实现。
