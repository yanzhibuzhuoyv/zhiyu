# 知语 0.2.5：选一次文件安装学生模型

用户选择离线模型包方式，本轮仅修改 App 和电脑验证，没有连接手机。四个权重已整理为一个 `.zyqwen` 文件，无需解压或逐个导入。

## 使用

1. 覆盖安装桌面的 `知语-0.2.5-手机独立版.apk`，保留 Key、绑定、聊天及已安装模型。
2. 将桌面的 `知语-学生生图模型-v1.zyqwen` 保存到手机。
3. App 菜单 → **本机照片 → 导入模型包**，选择这个文件一次；App 会自动校验、复制并初始化。看到“学生模型已导入”才代表安装完成。

文件约 **5.29 GB**。将包存入手机之后，安装还需约 **5.29 GB＋512 MiB** 空闲空间；安装时外部包和内部模型合计约 10.6 GB，另加余量与 App 本身体积。成功后可自行删除手机上的外部 `.zyqwen` 文件，App 内模型仍可使用。普通更新 App 无需重新导入同一模型。首次安装过程中保持此页面，取消导入会保留原模型。

模型仍负责背景 T2I，人物沿用固定原 OC 主体合成；本轮不改变原模型、姿势能力、RAG 或语音。原先文件夹方式保留为备用入口。

## 文件与复现

| 文件 | 字节 | SHA256 |
| --- | ---: | --- |
| 知语-0.2.5-手机独立版.apk | 231471121 | `e44fbbdb70a6430f0a07c4eca2f79dd8d384d94ada759762cd6909f6953a201d` |
| 知语-学生生图模型-v1.zyqwen | 5290985270 | `cb588e3ce4eda51dabcb1fefad904565c31bde867a5fca9f3dc0875691f6bdad` |

两文件均在 `C:/Users/32202/Desktop/`。APK versionName 0.2.5、versionCode 11、ARM64、min26／target36，与 0.2.4 同调试签名 `2f6842228b821c4f62a9842747e94ba1f4ec8c57683f063587ae750da00b4243`。

包为 `ZYQPACK1` magic＋big-endian uint32 清单长度＋原受信清单＋顺序四个原始 payload，末尾必须是 EOF。清单固定 SHA `9232afcbed6d188a9bdc58c0b8841b35bb12773aaee9fc745dc8027641c656e6`；四权重总字节仍为 5290983724，原 SHA 全部保留。它是顺序读取的专用模型文件，不是 ZIP，也没有执行脚本或任意解包路径。

可使用 `scripts/package_qwen_model.py --source artifacts/model-packs/qwen-edge --output <包.zyqwen>` 制作，`--verify <包.zyqwen>` 逐字节重新核验。CLI 只信任固定发行摘要，不能从输入包自行选择信任记录。输出通过独占临时文件、fsync 和不覆盖已有文件的原子发布完成；异常只清理自己创建的临时文件。

## TDD 与实际验证

| 保证 | 证据 | 结果 |
| --- | --- | --- |
| producer 固定信任、四 payload／SHA／顺序、损坏／取消／并发输出保护、64 KiB 读取 | `qwen-single-pack-tdd-red-r2.log` → `qwen-single-pack-tdd-green-r2.log` | 22 测试 RED（57 缺实现错误）→22 GREEN，0 skip |
| importer 外部信任、无符号长度、每段截断／污染、尾垃圾发布前拒绝、取消保原包、既有包仍完整核验、>IntMax Long 计数 | `single-pack-importer-red-r1.log` → `single-pack-integrated-green-r1.log` | 同14测试 RED→GREEN，全量528 JVM通过，0 failure／error／skip |
| 一次选择文件、openable 文档、错误后可重试 | `single-pack-ui-red-r1.log` → `single-pack-native-green-r2.log` | 同3 Compose测试 RED→GREEN |
| Android真实文件系统原子发布／SHA、尾垃圾／取消保原包、实际发行清单截断拒绝 | `single-pack-native-green-r2.log` | 4/4；与UI合计7/7，5.579s |
| 完整5.29GB产物逐文件再次读取 | `qwen-single-pack-full-build-r1.json`／`qwen-single-pack-full-verify-r1.json` | 两份完整 receipt 一致，四 SHA、包 SHA、总长度、EOF均通过 |
| APK／测试 APK／静态检查 | `single-pack-integrated-green-r1.log`／`single-pack-picker-build-r3.log` | 构建成功；lint 0 error、59 warnings |
| 签名、16K zipalign、CRC、JNI身份与固定清单、主APK无权重／测试CP | `single-pack-release-audit-r1.json`＋实际SDK工具 | 通过 |

初始 Python GREEN 有一个 Windows GBK 解码的 fixture 错误，只将测试源码读取明确为 UTF-8后通过。首轮原生 GREEN 为6/7，查明 AndroidX OpenDocument 默认没有 CATEGORY_OPENABLE；生产选择器已明确添加，补跑7/7。失败记录全部保留，没有放宽原来的断言。

`python -m trace` 的实测 producer 行覆盖为 **89%（265可执行行）**；不是 Kotlin 或全 App 覆盖率，分支覆盖未测。Android原生测试使用隔离小型合成权重，未声称在模拟器或真实手机完成整个5.29GB包导入，也没有重跑模型图像推理、万轮或五千万测试。

当前 APK 七个 ARM64 库 SHA 与192项资产大小／CRC和0.2.4一致。包体减小来自ZIP布局空隙减少，未删除原模型／语音资产。导入失败不修改原安装模型，容器尾部检查在 marker／原子发布前完成；既有完整模型也不能把坏新文件报告为安装成功。

检查点：`f30cfb1` producer RED → `2ed2854` GREEN；`ce99cd7` importer／UI RED → `29dce73` importer GREEN。UI GREEN 与交付文档提交记录见 Git。

本轮由根代理复核最终源码；两个实现代理在初始测试或部分实现后因额度中断，未声称完成新的独立终审。原同 SHA 损坏目录无法直接替换修复、强制杀进程不保证安装恢复等限制保留；正常取消已验证清理 staging。无需服务器或 USB 的日常运行方式保持不变。
