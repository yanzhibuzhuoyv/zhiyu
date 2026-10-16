# schema v2 契约说明

对应实施计划 §2（阶段 0）与设计文档 §4 阶段 0 / §8（迁移与兼容）。

本文件说明**契约的形状、每一条校验为什么存在、以及契约明确不保证什么**。
实现见 `src/main/kotlin/rig2d/domain/`，测试见 `src/test/kotlin/rig2d/domain/`。

---

## 1. 数据结构

```kotlin
OcRigAsset(                                  // schema v2 顶层
    views: List<OcViewLayers>,               // 1..8，按 yawCenter 降序归一化
    sourceSha: String,                       // 绑定导入原图（64 位十六进制）
    approvedAt: Long,                        // 人工确认时刻；0 = 未确认
    proxy: OcProxy?,                         // 可选的 3D 头部代理
    schemaVersion: Int = 2,
)

OcViewLayers(
    viewId: String,                          // "front" | "right45" | "right90" | "left45" | "left90"
    yawCenter: Float,                        // -90..90
    yawHalfWidth: Float,                     // 5..45（计划 §5.1 建议 30）
    layers: List<OcLayerAsset>,              // 1..64
    rig: OcRigV1,                            // **每视角独立**（计划 §7.2）
    mesh: OcViewMesh,                        // 该视角的变形网格
)

OcLayerAsset(
    id: String, kind: String,                // kind ∈ layerKinds（设计文档 §6.1）
    z: Int,                                  // 层序，越大越靠前；同一视角内必须唯一
    depth: Float,                            // 代理深度 -1..1，用于层序校验
    asset: OcAssetRefV2,                     // PNG/WebP RGBA 引用（含 sha256 + visualVersion）
    bounds: List<Float>,                     // 归一化 x, y, w, h
    margin: Float,                           // 边界外扩冗余 0..1（露缝的根源，§5.3）
)

OcProxy(mesh, symmetryPlane, depth, normal, version)   // 对称平面法线必须已归一化
OcViewMesh(columns, rows, asset, vertexCount)          // vertexCount == (columns+1)*(rows+1)
```

### 与设计文档的两处**必要偏离**（不是笔误）

| 设计文档写法 | 本实现 | 理由 |
| --- | --- | --- |
| `proxy: OcAssetRef?` | `proxy: OcProxy?` | 计划 §4.1 要求阶段 2 产出「对称平面 / 深度+法线 / 变形网格」三样，三者必须**一起**校验；否则「不同图之间对称平面翻转」（计划 §4.4）只能到运行期才发现 |
| `mesh: OcAssetRef` | `mesh: OcViewMesh` | 「顶点数 = (columns+1)*(rows+1)」无法从不透明文件引用里校验，而计划 §2.1 明确要求它必须被拒 |

两处偏离都**扩大了可校验面**，不放松任何约束。

---

## 2. 校验规则（全部 fail closed）

`OcRigAssetRules.check` 抛 `IllegalArgumentException`，消息为中文、可直接展示给导入期用户。
`isValid` 是同一逻辑的布尔包装，供分支判断使用。

| 规则 | 取值 | 为什么 |
| --- | --- | --- |
| `schemaVersion == 2` | 精确匹配 | v1 走 v1 分支；未知版本拒绝，不做「尽力而为」的解析 |
| `sourceSha` | `[a-f0-9]{64}` | 资产必须可追溯到确定的一张源图 |
| `views.size` | 1..8 | 计划锁定的 5 视角是**推荐形状**，不是硬编码：契约允许更少（±45 让步选项）与更多 |
| `viewId` | `[a-z0-9_]{1,24}` + 唯一 | 会参与文件名与哈希 |
| `yawCenter` | -90..90，有限 | 计划 §5.1 的视角带 |
| `yawHalfWidth` | 5..45，有限 | 太窄会漏角，太宽会让「相邻视角重叠」失去意义 |
| 视角带覆盖 | 相邻带必须重叠（容差 1e-3°） | **漏角比糊更糟**（设计文档 §4 阶段 0） |
| `layers.size` | 1..64 | 资源上限，不是解剖学上限 |
| 层 `id` | `[a-zA-Z0-9_-]{1,80}` + 唯一 | 稳定 ID，避免每帧重启加载 |
| 层 `z` | -128..128 且**同视角内唯一** | 「层序不可含糊」：z 相同无法决定谁盖谁 |
| 层 `kind` | ∈ `layerKinds`（19 类） | 未知类别必须拒绝，否则绑定阶段会静默丢层 |
| 层 `depth` | -1..1，有限 | 代理深度 |
| 层 `bounds` | 4 个有限数，`x,y ≥ 0`、`w,h > 0`、`x+w ≤ 1.001` | 越界会让纹理采样读到图外 |
| 层 `margin` | 0..1 | 形状校验；**是否「足够」由 `adequacy` 判定**（见 §4） |
| 资源 `sha256` | `[a-f0-9]{64}` | 同 v1 标准 |
| 资源 `visualVersion` | ≥ 0 | 对齐 zhiyu 既有 `expectedVisualVersion` 语义 |
| 网格 `columns/rows` | 2..64 | 复用 `OcRigRules.vertices` 的上限 |
| 网格 `vertexCount` | **(columns+1)×(rows+1)** | 计划 §2.1 最后一条 |
| 对称平面 | 4 个有限数且法线**已归一化**（±1e-3） | 退化法线在运行期表现为镜像乱码 |
| 顶点缓冲 | 长度 = `vertexCount*2` 且**全为有限数** | NaN/Inf 一旦进入渲染就是黑块或崩溃 |

---

## 3. packageSha：v1 与 v2 的隔离

| | v1 | v2 |
| --- | --- | --- |
| 前缀 | `oc-v1` | `oc-v2` |
| token 规则 | `长度:文本`（长度是**字符数**） | 同一条规则，复用同一个 `CanonicalBuilder` |
| 字段顺序 | 冻结，见 `OcIdentityV1.kt` | `sourceSha → approvedAt → proxy → views（yaw 降序）→ 每视角 mesh/rig/layers（z 升序）` |

**为什么前缀必须不同**：同一个 v1 档案在两条路径上若共用前缀，就可能出现哈希碰撞，
而 `packageSha` 是发布 CAS 与「外观版本」判定的依据（计划 §6.1）。

**归一化顺序的代价与收益**：v2 在计算哈希前把视角按 yaw 降序、层按 z 升序排列，
因此**等价的列表重排不改变哈希**，而任何语义字段（sha / z / depth / bounds / margin /
对称平面 / 网格尺寸）的改动**必然**改变哈希。这两条都有测试（`OcPackageShaV2Test`）。

---

## 4. 视角混合与 margin 充足性

### 4.1 混合权重（`OcViewBlendRules.blend`）

- **唯一权威定义**是 `pair(bands, yaw)`：`left` = 中心 ≤ yaw 中最大者，`right` = 中心 > left 中最小者。
  `blend` 与 `primary`（`nearest` 的委托目标）都由它派生，保证两者永远一致。
- 权重是相邻两个视角中心之间的 **smoothstep** `S(t)=t²(3-2t)`：
  中心处恰为单视角（权重 1）、交叉点（±22.5 / ±67.5）恰为 0.5/0.5、全域权重和恒为 1 且 C1 连续。
- **返回值按权重降序**，调用方可以依赖「第一个即主轴」；交叉点按「取中心更大的一侧」定主轴。
- yaw 落在全部视角带之外 → **拒绝**（`IllegalArgumentException`），不静默外推。

### 4.2 margin 充足性（`OcViewBlendRules.adequacy`）—— §5.3 的硬门禁

```
对每个视角：
  1. 取带内两侧边界各内缩 1° 的两个最不利 yaw；
  2. 在这两个 yaw 上混合，取「其他视角在本视角带内的最大权重 w」；
  3. 对该视角**每一层**算所需冗余
       需要 = (锚点? 0.10 : 0) + w × |层深度| × 15° × (1/90)
  4. margin < 需要的层全部计入 gaps → passed = false
```

- **为什么逐层算而不是用层深均值**：页面上真正会露的是**某一层**，均值会互相掩盖。
  第一版用均值，把 ±90° 的要求算成 1/6 层宽（按设计文档的 10–15% 边距根本无法满足）。
- `PIXELS_PER_DEGREE_PER_DEPTH = 1/90` 是**本实现引入的显式建模假设**
  （yaw 每转 1°、深度 ±1 的层相对前后平面横向位移 1/90 层宽）。
  它是可核对、可替换的常量，不是埋在公式里的魔数，并且有专门的测试锁定它的取值。
- `requiredMargins(asset)` 直接返回 `adequacy` 的逐层需求，因此**写回后必然通过 adequacy**（有测试保证）。
- 语义澄清：设计文档 §6.3 的 10–15% 是**锚点视角的基线**；视差折算更大时以视差为准，
  即 `margin = max(基线, 视差折算)`。这不是上限。

---

## 5. headTurn 的语义分界（`MotionSemantics`）

| | v1 | v2 |
| --- | --- | --- |
| 解释 | `headTurn * 3.5f` 度旋转 + `* 13f` 像素平移（纯装饰性摆动） | `headTurn * 90f` 度真实 yaw（视角混合 + 代理视差） |
| 取值域 | -1..1（`OcRigRules.motion` 校验） | -1..1（**同一个**校验函数） |

两者数值域相同但含义不同，因此**解释必须显式分派**：`MotionSemantics.yawDegrees(schemaVersion, headTurn)`。
v1 档案必须走 v1 路径，否则老角色的画面会变化（`OcLegacyRigV1Test` 守住这条）。

---

## 6. 契约**不**保证的事

- **不保证 ±90° 可用**。契约只让后续阶段有可能（计划 §2.5）。
  可行性判定在 P0 探针的门禁（`artifacts/rig-probe-*/probe.json` 的 `gate` 字段）。
- **不保证 v1 在真机上的像素逐字节不变**。本仓库证明的是**宿主端**变换与顶点缓冲逐字节不变
  （见 `OcFlatRendererV1` 的说明）；真机 Skia 像素比对需要 androidTest，见 [PORTING.md](PORTING.md)。
- **不保证 margin 公式在真实几何下正确**。它是一条显式建模假设，
  必须由阶段 3 的**露缝扫描**（计划 §5.6，yaw 全程逐角度检查）来校准。
  在露缝扫描通过之前，`adequacy` 只能当作「导入期初筛」，不能当作「露缝已解决」。
