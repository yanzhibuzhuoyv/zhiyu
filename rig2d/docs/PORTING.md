# 移植到 知语（`android/`）的指南

对应实施计划 §2.1（改动清单）、§10（全局纪律）。

**前提（硬性）**：本仓库与 `android/` 是两个独立目录。移植由 codex 或人工**手动**执行，
`android/` 目前对自动化是只读的。本文件只说明「搬什么、搬到哪、怎么验证」。

---

## 1. 移植顺序（依赖驱动的唯一合理顺序）

```
1. 纯规则层（无 Android 依赖）        ← 本仓库已有，风险最低
   OcRigAsset.kt / OcViewBlendRules.kt / OcIdentityV2 分派 / MotionSemantics
2. v1 冻结副本 → 替换为真实源码
   OcIdentityV1 → android/.../domain/OcIdentity.kt 的 v1 分支
   OcRigVerticesV1 → android/.../domain/OcRig.kt 的 OcRigRules.vertices
3. 渲染与 GLES（阶段 5）               ← 需要真机，最后做
```

**不要**先搬渲染。原因：阶段 0 的契约如果能先在纯 JVM 上全绿，
就把「Android 构建环境问题」与「契约逻辑问题」分开了；
一起搬会让失败原因无法区分。

---

## 2. 文件映射表

| 本仓库 | 目标（知语） | 移植方式 |
| --- | --- | --- |
| `src/main/kotlin/rig2d/domain/OcRigAssetTypes.kt` | 新增 `domain/OcRigAsset.kt` | 包名 `rig2d.domain` → `com.zhiyu.app.domain` |
| `src/main/kotlin/rig2d/domain/OcRigAsset.kt` | 合并进 `domain/OcRigAsset.kt` | 同上；`OcRigRulesV1.check` 调用改为真实的 `OcRigRules.check` |
| `src/main/kotlin/rig2d/domain/OcViewBlendRules.kt` | 新增 `domain/OcViewBlendRules.kt` | 无 Android 依赖，可原样搬 |
| `src/main/kotlin/rig2d/domain/MotionSemantics.kt` | 新增 `domain/MotionSemantics.kt` | 无 Android 依赖 |
| `src/main/kotlin/rig2d/domain/OcIdentityV2.kt` | 改 `domain/OcIdentity.kt` | ① `routes` 加 `photo_rig_2d`；② `check` 改为按 `schemaVersion` 分派；③ `packageSha` 中 v1 分支**原样保留**，v2 走新函数 |
| `src/test/kotlin/rig2d/domain/*` | 对应 `test/java/com/zhiyu/app/domain/*` | JUnit4 → JUnit4（版本一致，`junit:junit:4.13.2`） |
| `src/test/resources/golden/v1_identity_photo_subject.json` | `test/resources/golden/` | **原样搬**：它是 v1 门禁的参照物 |

### 改动 `OcIdentity.kt` 时必须同时满足的三条

1. **v1 行为逐字节不变**：`OcIdentityRulesV1` 的分支、token 规则、字段顺序一个字都不能改。
2. **route 只增不改**：`parts_2d` / `avatar_3d` 是已取消的建模路线，**不得复用其名字**
   （计划 §2.1）。新增 `photo_rig_2d`。
3. **`packageSha` 前缀隔离**：v1 继续用 `oc-v1`，v2 用 `oc-v2`。

---

## 3. 移植后的验证清单

### 3.1 宿主机（先做，不需要真机）

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

必须满足（对应计划 §2.4）：

- [ ] 新增的 5 个测试类（`OcRigAssetRulesTest` / `OcViewBlendRulesTest` / `MarginAdequacyTest` /
      `OcLegacyRigV1Test` / `OcPackageShaV2Test`）全部 GREEN。
- [ ] **golden 文件里的 canonical 明文与 sha256 一字不差**（这是 v1 硬门禁在 Android 侧的落点）。
- [ ] 现有 JVM 测试全绿，**总数不得回退**（计划 §2.4 记录的基线是 431 JVM / 58 suites；
      以实际 `git stash` 前后对比为准，不要抄这条数字）。

### 3.2 真机（阶段 5 才做，需要 androidTest）

宿主端移植**不能**替代真机像素比对。真机侧需要新写：

```kotlin
// androidTest/.../domain/OcLegacyRigPixelRegressionTest.kt （本仓库不提供，需在 Android 侧写）
// 1. 用固定的 v1 档案 + 固定 OcScene，渲染成 Bitmap
// 2. 与「移植前」的基线 PNG 做逐像素比对（基线在移植前先跑一次并存入 androidTest/assets）
```

**这一步在移植前就要跑一次并保存基线 PNG**，否则移植后没有对比物，
`OcLegacyRigV1Test` 的宿主端证明就只是「变换算术没变」，不是「像素没变」。

---

## 4. 环境说明（本机实测）

| 项 | 值 | 影响 |
| --- | --- | --- |
| JDK | 只有 JDK 23（`C:\Program Files\Java\jdk-23`） | `build.gradle.kts` **刻意不声明 java toolchain**：声明 toolchain 17 会让 Gradle 去下载 JDK，而本机没有配置 toolchain 仓库，构建直接失败。改为用当前 JDK 编译、`jvmTarget` 固定 17 |
| Gradle | wrapper 固定 `gradle-9.5.0-bin`（沿用 `android/` 的版本） | 本机 `~/.gradle/wrapper/dists` 已有该发行版，可离线复用 |
| Kotlin | 2.3.21（与 `android/build.gradle.kts` 的 compose 插件版本一致） | 版本一致性降低移植时的语言行为差异 |
| Android SDK/NDK | 未设置 `ANDROID_HOME` | **本仓库不需要**；`android/` 构建需要另行配置 |

---

## 5. 移植时容易踩的三个坑

1. **`OcRigV1` 会成为跨包类型**：v2 的 `OcViewLayers.rig` 直接复用 v1 的 rig 结构。
   移植后应改为引用真实的 `com.zhiyu.app.domain.OcRig`，并**删掉** `OcRigV1` 副本
   （否则会出现两个同名结构类型，且 v1 校验不再唯一）。
2. **`OcAssetRefV2` 与 `OcAssetRef` 不是同一个类型**：前者多了 `visualVersion` 的显式校验、
   且没有 `OcAssetRef.id` 的 `sourceId` 32 位限制（资产的 id 规则更宽）。
   迁移时不要图省事合并成一个类——两者的校验强度不同。
3. **`CanonicalBuilder` 的 token 规则是共享的**：v1 canonical 与 v2 canonical 用同一个写入器。
   移植时如果给 v2 单独写一个「更整洁」的版本（例如不加长度前缀），v1 哈希会连带漂移。
   共享是有意的：token 规则本身不能有第二份实现。

---

## 6. 移植后**不得**宣称

- 不得因为契约就位而声明「支持 ±90°」（计划 §2.5）。
- 不得因为宿主端测试全绿而声明「v1 渲染逐字节不变」——那需要 §3.2 的真机像素比对。
- 不得把「透明 PNG 分层」称为 Live2D（计划 §7.5）。
