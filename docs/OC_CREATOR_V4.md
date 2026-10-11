# 手机端 OC 制作与固定身份

2026-10-10。本文记录本轮实际源码、集成接口和验证边界；最终构建及仪器结果以统一 v4 验证日志为准。

## 已实现的制作方式

| 路线 | 编辑与渲染 | 身份来源 |
| --- | --- | --- |
| 真人照片 | 先导入、修整、确认完整主体，再手动放置头、身体、眼、嘴、道具的活动区域与锚点；Canvas bitmap mesh 做局部呼吸、眨眼、张嘴和轻微摆动 | 已确认原图／主体的资源 ID 与 SHA；保存动画预览不会用预览 PNG 替换下一次编辑的原始主体 |
| 非真人 2D | 可调整脸型、眼鼻嘴、身体比例、发型、肤色及服装颜色；独立添加、定位、旋转和分层翅膀／武器等部件；Canvas 矢量角色实际显示、保存、再次编辑及场景渲染 | 固定部件、身体参数、稳定 `ocId` 与修订 |
| 非真人 3D | 同一编辑参数产生实际三维三角网格；可绕人物、上下查看，调整独立部件的前后位置；GLES2 深度测试与着色，EGL pbuffer 生成 PNG | 固定几何生成规则、部件参数、稳定 `ocId` 与修订 |

这些入口只修改形象，不涉及服务商、聊天模型或性格绑定。旧用户没有制作包时按 `unknown` 初始化，不根据旧图片猜真人或非真人。

照片动画是自研的区域参数 rig。用户明确放置区域，应用不声称识别并拆出了隐藏层，也不声称照片已经生成完整 Cubism Live2D。单张照片不能提供被遮住的嘴、背面、新姿势或真实侧脸。照片中分离的武器、翅膀等像素仍来自确认后的完整主体；没有自动裁成人类双翼模板。复杂背景的自动显著前景模型仍需要用户核对和笔刷修整。

2D、3D 当前采用内置组件／程序几何，能制作和渲染基础角色；尚不支持任意 PSD/Cubism、VRM/glTF 或照片自动生成高精度 3D。照片导入到非真人路线不会自动成为可编辑的分层绘画或 3D 拓扑。

## 部件与资源限制

部件用独立列表保存，编号唯一。单翼、十二翼、仅左侧武器、独立耳角尾巴都可以添加；角色自身的左／右语义与画面位置分别保存，不自动复制或镜像。用户可以移动已标记为左侧的部件，应用不会替他换成右侧。

每个制作包最多 128 个部件和 128 个活动区域，这是本机资源上限，不是翼数或左右配对规则。参数必须有限且位于允许范围，名称最多 80 字符。PNG 尺寸范围为 32–1536，每张不超过 240 万像素；原主体有界解码。清单最大 256 KiB，私有目录中的读取也检查大小。

三维顶点使用有界 primitive FloatArray，最多 500 万 float；预览复用 direct buffer。默认相机根据全部部件的旋转后包围盒适配画幅和深度，避免高角、边翼和武器被固定人体画幅裁掉。用户主动增大 `zoom` 会放大构图，不承诺仍显示全身。不同角度的透视遮挡可能令部件暂时看不见，但不会删除其数据。

活动区域在重叠处按活跃影响加权混合，128 个重叠区不会把位移放大 128 倍。零动作时网格坐标与原图严格相同；区域外的顶点不受全图平移影响。自动预览目标约每 50 ms 更新，尚未获得低端真机的持续帧率／温度证据。

## 持久清单及发布恢复

`OcCreatorStore` 在应用私有目录 `oc_identity_v1/<conversation>.json` 保存 AtomicFile 清单。主体图片仍由既有本机媒体库保管，清单只保存资源 ID／SHA／版本。身份 SHA 使用有序、长度前缀字段编码和 float bit 值，包含身体、所有部件、活动区域、主体来源、参考资源、确认状态及修订；读取时重新校验，不依赖对象默认 `toString()`。

保存流程为：

1. 重新读取当前外观参考和版本，尝试恢复上一笔保存。
2. 校验同一角色、当前修订和参数，生成新的不可变 PNG。
3. 写入 AtomicFile publication intent，其中包括预期旧修订、预期外观版本、制作参数及 PNG SHA；进程内给该会话加发布 lease。
4. 通过现有媒体发布边界，以 `expectedVisualVersion` 做 CAS；外观在渲染期间改变则拒绝覆盖。
5. 返回资源 SHA 必须等于这张 PNG，版本必须为预期加一；随后 CAS 提交身份清单。
6. 提交／恢复成功后才发 `onCommitted`，供当天场景库存排队；删除 intent 并释放 lease。

如果图片发布成功而清单写入中断，下一次读取只在当前参考 SHA 和外观版本都匹配 intent 时恢复相同制作包，不重渲染、不再调用付费生成。活跃 lease 期间另一个 controller 不能抢先恢复；错误 SHA、错误版本、过期修订均不能采用。清理失败仍释放进程 lease、回收 Bitmap 并复原 busy，未清理 intent 留待下次核对。已发出的旧消息图片与原始素材不改写。

## 集成接口

```kotlin
OcCreatorController(
    context,
    scope,
    publish = suspend { chatId, identity, png, expectedVisualVersion -> OcAssetRef(...) },
    loadImage = suspend { source: OcAssetRef -> /* immutable original bytes */ },
    refreshReference = suspend { /* freshly read profile reference with CURRENT profile version */ },
    onCommitted = suspend { chatId, snapshot: OcIdentitySnapshot -> /* enqueue derived stock */ }
)
```

`refreshReference` 需要当前 profile 的版本，不可直接使用旧资源创建时的版本。`onCommitted` 默认空，兼容既有构造测试；普通重开也可能通知已保存快照，调用方应按会话／日期／外观版本幂等排队。回调失败不撤销已经保存的身份。

`VisualSetupDialog(controller, ocCreator = null, close)` 保留 controller 加尾 lambda 的旧调用。已有 Agnes 候选以资源 ID 和 SHA 显示；按“使用这张外观”才采用，显示候选不会替换当前外观。没有独立 Agnes Key 时保留两次机会，入口提示到图像服务配置。

`CharacterSceneRenderer.render(chatId, expectedPackageSha, scene, width, height, background)` 读取已确认清单，校验固定主体 SHA，返回：

```text
ocId / revision / packageSha / visualVersion / sourceSha / PNG / PNG SHA
```

返回 PNG getter 给防御性副本。渲染不修改制作参数；库存落库前仍需由调用方同时检查当前外观版本、日期与制作包 SHA，不能仅依赖渲染开始时的快照。2D／照片支持传入背景；3D 默认可输出透明 PNG，由库存层合成背景。

`CharacterPreview(identity, motion, loadSource, modifier, cameraYaw)` 是通话形象插槽。`motion` 暴露嘴型、眨眼、呼吸、头部和附属物摆动；采用局部 mesh 或实际三维几何。音频播放与 motion 的连接由通话控制层负责，本模块不把整张照片移动称人物口型，也不实现全双工。

## 验证清单

以下测试已写入源码。代理未自行运行 Gradle／ADB，由 root 统一编译和运行。

| 测试文件 | 当前数量 | 检查内容 |
| --- | --- | --- |
| `OcIdentityRulesTest` | 10 JVM | 非对称部件、稳定身份／修订／SHA、输入验证、128 部件资源、原坐标、中性／局部 rig、重叠区域混合、有限三维深度几何、动画不改身份 |
| `OcCreatorNativeTest` | 11 Android | AtomicFile 重开／篡改、照片每像素及分离道具、2D 高度极值、真实 EGL 相机差异、多翼武器角全画幅、已有 EGL context/surface 在离屏渲染后仍可用、原主体资源／SHA／快照、发布中断恢复、错误参考拒绝、并发 lease 与已提交 intent 清理 |
| `OcCreatorUiTest` | 3 Compose | 实际 2D 添加非对称部件并保存、3D 参数／相机操作、照片来源缺失时禁止保存 |

测试只创建自有合成角色、测试上下文私有目录和有界图片；没有 API Key、付费模型调用、私人历史库、真机 USB 或旧 OC 数据访问。旧长测结果不作为本模块的渲染或 UI 通过证据。

已核对 `artifacts/native-v4-oc-calendar-green-r1.log`：本模块 **11 Native + 3 UI 全部成功**，与日历 5 项、背景并发 1 项同批共 **20/20，12.693 秒**。真实 EGL 三相机多翼画幅、原 EGL 绑定恢复、照片每像素、发布恢复和实际编辑器均在该日志中；不能把整批 12.693 秒说成本模块独立耗时。此后只新增 GL 预览的错误态处理；最终统一 r6 构建与回归仍需记到 v4 发布验证，不把前一批绿灯代替最终构建。

已只读核对 r6 的 `android/app/build/test-results/testDebugUnitTest/TEST-com.zhiyu.app.domain.OcIdentityRulesTest.xml`（UTC `2026-10-10T06:39:07.762Z`）：**10 JVM、0 失败／错误／跳过，0.173 秒**，包括 128 翼资源与重叠 rig 的新测试。当前模块累计 24 项有实跑记录；最终包的统一测试仍需单独列明。

## 研究依据

采用平台现有 GLES、GLSurfaceView、Canvas 和 EGL 能力，没有新增图形库或未知授权素材依赖。实际三角绘制和生命周期依据 [Android OpenGL 绘制](https://developer.android.com/develop/ui/views/graphics/opengl/draw)、[GLSurfaceView API](https://developer.android.com/reference/android/opengl/GLSurfaceView)；局部网格来自 [Canvas drawBitmapMesh](https://developer.android.com/reference/android/graphics/Canvas#drawBitmapMesh(android.graphics.Bitmap,int,int,float[],int,int[],int,android.graphics.Paint))。

离屏渲染每次创建自己的 context／surface，不抓取预览画面，并恢复调用线程之前的 EGL 绑定。Android 默认 display 共享；本实现对 initialize／terminate 成对使用，引用计数依据 [AOSP EGL display 生命周期实现](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/opengl/libs/EGL/egl_display.cpp)。这是 Android 平台行为；通用 [EGL 1.5 规范](https://registry.khronos.org/EGL/specs/eglspec.1.5.pdf) 的 display 终止语义不能直接当作跨平台引用计数保证。相关并存情况还要以原生测试结果确认。
