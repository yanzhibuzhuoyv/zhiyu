# 0.2.7 手机动态形象工坊与通话动画

2026-10-14，承接用户“自动大部分可以，剩下能自己做”和“继续，可以做其他的”。已把桌面工坊的包、数值核心和人工纠正接入原生 Android。APK 为 `C:/Users/32202/Desktop/知语-0.2.7-手机独立版.apk`。制作和保存角色不依赖电脑、Docker 或图像云 API；云聊天继续使用用户选定公司的 Key。

本报告记录 0.2.7 已验证的边界。随后新增的固定三观/兴趣、保留背景、图片2与三张新图、中转站 image 2.5 替换要求进入下一轮，不能算作本版已完成。真正的全双工按最新要求继续最后实现。

## 使用

1. 在“恋人外观”确认导入或生成的 OC 图片。
2. 聊天右上角菜单进入“动态形象”。初稿为比例位置建议，必须自己核对眼睛、嘴巴、头颈。
3. 拖动框选眼口，点击定位头颈；头发、翅膀、武器、道具可按独立附件添加，点轮廓或用保留/擦除画笔修正。可改变顺序和随动幅度，删除可撤销。
4. 使用撤销/重做、原图对照检查，点击“保存并用于通话”。动画预览显示上次保存的版本；未保存时界面明确提示。
5. 视频页优先使用本次聊天和已确认原图对应的动态角色，接现有声音口型。没有动态角色则沿用旧图片预览。

可以打开桌面工坊角色 ZIP，也可导出本机保存的角色包。打开包先进入草稿，须确认保存才替换通话角色；它不替换用于日常照片生成的“恋人外观”原参考图，界面已说明。新 Key/聊天或新原图不会套用旧角色，文件选择器回来的旧操作也不能应用到新绑定。

## 实现

- 直接复用固定 SHA 的 275804B 纯 JVM 核心和 contract；Android 不另写混合器。Float32 basis / Double 累加规则保持，四组生成器数值 SHA 固定用例通过。
- 本机 ZIP 不解压到外部路径。压缩 64MiB、展开 128MiB、68 条目，验证中心目录与本地记录、路径、重复条目、CRC、SHA、有限坐标和 PNG 实际展开；拒绝加密、符号链接、ZIP64、APNG 和危险扩展元数据。严格 JSON 拒绝重复键。
- 源图最长边 1536、单 PNG 20MiB，纹理总计 24M 像素。Android 新制作采用更保守的 24MB 原始纹理预算，导入桌面包保留契约的 96MB 上限；源图单独受限，不重复扣纹理预算。共享纹理编码数据去重。
- 文件在私有目录按聊天与确认原图 SHA 分开；保存前验证、写临时文件、fsync、原子替换。取消或坏包不会覆盖有效角色，保存失败保留草稿。
- 草稿使用不可变列表和稀疏笔迹，最多 60 附件、512 笔迹/16384 点。撤销历史最多 40 步/32MiB 估算载荷，不含原图，也不是整个应用堆上限。导入 mask 后重画轮廓会清旧 mask 并保留可撤销的旧状态。
- GLES2 索引网格/预乘 alpha 绘制；纹理/GPU 操作在 GL 线程，暂停销毁并按新 context 重建资源。可见前台闲置最多 30fps，后台/隐藏/释放停止调度，暂停不积累动画时间。绘制失败退回原图。
- 原生眼口隐藏底层采用邻近源像素补片，不是 Python/OpenCV Telea；导出明确使用 `rig2d-planar-native-package-1` / `android-neighborhood-v1`，不伪称桌面派生像素等价。
- 本轮不改 RAG、原文存储、三层记忆、日常分享或学生权重；旧后端的 9 tracked/23 untracked 改动留在工作树，不混入提交。

## 验证

| 检查 | 最终结果 | 证据 |
| --- | --- | --- |
| 全部 Android JVM | 82 suites / 586 tests，0 failure/error/skip；其中新增 planar 32 | `artifacts/planar-final-green-r7-xml` |
| 构建与 lint | debug 各 ABI、androidTest 构建成功；lint 0 error，65 warning / 2 hint | `artifacts/planar-android-final-green-r7.log` / build lint XML |
| 真实 Android 仪器测试 | 44/44，63.881s；新角色 25 + 既有照片/通话回归 19 | `artifacts/planar-native-final-green-r8.log` |
| 包与升级 | ZIP CRC、同证书、ARM64、16KB 对齐通过；旧 192 assets / 7 libraries 原字节不变，只新增 contract | `artifacts/planar-release-audit-r7.json`、signature/badging/zipalign-r7 logs |
| 两张用户原图 | 中性重组像素差 0，源 ARGB 改动 0；各 64 单通道端点检查通过 | `artifacts/planar-user-native-r8.json` |

仪器测试在自有 `zhiyu_media_test` x86_64 AVD/SwiftShader，未连接红米或使用麦克风，不能作为红米速度或 GPU 性能证明。JVM 586 是测试数，不是 80% 覆盖率承诺。

两张实际用户图使用人工地标：持剑线稿 1045×1506，构建 1169.782342ms；浅线头像 1080×1438，434.005373ms。这只是 AVD 冒烟。各 4 层、PNG 总量分别 3849885/1907893B。原图只在忽略的本地 artifacts 和测试 APK 中，未放入用户安装 APK、Git 或上传第三方。

实际 TDD/修复历史保留：初始 JVM r1 25/24 fail、r2 27/26 fail；正确 builder native RED 7/7 fail；同步 busy 1 项真实失败后修；80 emoji Unicode 与纹理源图重复计预算 2 项 RED 后修；导入 mask 首个轮廓点 1 项 RED 后修。第一次 60 附件用例将 `part_5` 放进嘴区域导致空 mask，调整测试位置并保留 64 层断言，生成器未降规则。native r7 为 43/44，唯一失败是文件选择器 MIME 断言错误；按 Android `*/*` + `EXTRA_MIME_TYPES` 合约改测试后，r8 全部通过。错误类名/缺 Rule 引用的执行失败不计为业务 RED。旧 RED 原文件保留，不把失败日志冒称 GREEN；边界 RED XML 曾被下一轮覆盖，只有日志留存。

独立代码审查及最后轮廓修复补审未发现剩余 CRITICAL/HIGH。生命周期、损坏包、取消、换 OC、撤销、保存失败、眼口重复 alpha、纹理方向与图层顺序均有相应测试或审查证据。Android 实际 API 依据 [GLSurfaceView](https://developer.android.com/reference/android/opengl/GLSurfaceView) 与 [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)。

## 安装与限制

- versionCode 13 / versionName 0.2.7，min SDK 26 / target 36，ARM64 debug APK，231905168B。
- APK SHA-256：`fdf155e3882afe27ce2a39f401973b373161165f0338c7a6421befb6bc6237e9`。
- 调试签名证书 SHA-256：`2f6842228b821c4f62a9842747e94ba1f4ec8c57683f063587ae750da00b4243`，同旧包，可直接覆盖；无需卸载/清除原 Key、绑定、聊天或原模型包。没有实际操作用户手机。
- 旧 0.2.6 APK 与原 5.29GB 学生模型包均保留，未重新打包或重跑其长图推理；一万轮/五千万历史测试也未重跑。
- 是前视角局部形变和人工层编辑，不是自动 Cubism、完整眼皮/嘴内补画、被遮脸复原或真实 ±90° 侧背面。两张中性 0 差不能证明自动定位准确或夸张动作无缝。
- 未保存草稿在同一界面关闭/重开仍在；旋转重建或进程终止可能丢失未保存修改。已经保存的角色包可恢复。
- 最大桌面包在手机上的堆/GPU 峰值、60 层界面响应、OEM 生命周期以及真正全双工/AEC/打断仍待实机验证。测试后自有 AVD 已关闭；桌面工坊原服务保留，恢复需重新验证。
