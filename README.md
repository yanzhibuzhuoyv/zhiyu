# 知语 · ZhiYu

原生 Android AI 伴侣应用，含端侧图像引擎（Qwen-Image 剪层/蒸馏）。本文件说明**如何从源码构建**与**用了哪些技术**；逐版变更见 [CHANGELOG.md](CHANGELOG.md)，整仓库结构见 [README-OVERVIEW.md](README-OVERVIEW.md)。

---

## 一、仓库构成

```
zhiyu/
├── android/            手机端主产品（Kotlin + Jetpack Compose），唯一功能实现
├── backend/            Python 后端（FastAPI），已冻结为 legacy
├── native/qwen-edge/   端侧图像引擎原生层（C++ / JNI / stable-diffusion.cpp 分支）
├── rig2d/              参数化 2D 形象引擎（Kotlin/JVM 契约库，被 android 端以 JAR 引用）
├── shared/             两端共享的行为契约（由脚本生成，构建时校验）
├── scripts/            评测、冒烟、量化、打包与联调脚本
├── qwen-image-edge/    上游实验工程：剪层与蒸馏的实验报告与工具
├── compose*.yaml       Docker Compose 编排
└── docs/               各功能的设计、验证与边界记录
```

---

## 二、Android 端构建

### 2.1 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | `sourceCompatibility` / `targetCompatibility` = 17 |
| Android SDK Platform | **android-36** | `compileSdk = 36` |
| Android NDK | **30.0.16248370** | `ndkVersion` 已锁定；ABI 过滤 `arm64-v8a` / `armeabi-v7a` / `x86_64` |
| Gradle | 9.5.0 | 由 wrapper 自动下载 |
| Android Gradle Plugin | 9.3.0 | |
| Kotlin | 2.3.21 | 含 Compose 编译器插件 |
| CMake + Ninja | — | 仅在需要重新编译 JNI 时用到 |

`android/local.properties` 需指向 SDK（该文件不入库）：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

### 2.2 构建命令

```powershell
cd android
.\gradlew.bat :app:assembleDebug              # debug APK
.\gradlew.bat :app:assembleRelease            # release APK（R8 混淆已开启）
.\gradlew.bat :app:testDebugUnitTest          # JVM 单元测试
.\gradlew.bat :app:connectedDebugAndroidTest  # 设备/模拟器上的仪器测试
```

ABI 分包已启用（`splits.abi`），产物在 `android/app/build/outputs/apk/`，同时生成 universal APK。

> release 构建支持 `-PzhiyuBackendUrl=https://你的域名` 注入后端地址；**非 HTTPS 会被拒绝构建**。

### 2.3 构建前置：模型与原生产物

`app/build.gradle.kts` 里多个自定义 task 挂在 `preBuild` 上，它们**从仓库外的 `../artifacts/` 读取模型与原生库并做 SHA-256 硬校验，校验失败即中断构建**。全新克隆后必须先准备这些产物：

| 目录 | 内容 | 来源 |
|------|------|------|
| `artifacts/native-voice/` | sherpa-onnx 1.13.8 AAR、multi-zh 流式 ASR（int8 encoder 66.9MB）、vits-icefall-zh-aishell3 中文 TTS | `scripts/prepare_native_voice.py`（从 k2-fsa/sherpa-onnx 官方 release 固定版本下载） |
| `artifacts/portrait-models/` | `u2netp.onnx` 人像分割 | `backend/prepare_portrait_model.py`（固定 URL + SHA） |
| `artifacts/semantic-models/` | `bge-small-zh-v1.5-int8.onnx` + 词表 + manifest | `backend/prepare_semantic_model.py`（BAAI/bge-small-zh-v1.5，固定 revision） |
| `artifacts/native-media-seed/` | 打包进 APK 的示例素材 | `scripts/prepare_native_media.py` |
| `artifacts/model-packs/qwen-edge/` | Qwen 图像模型包 manifest | `scripts/package_qwen_model.py` |
| `artifacts/android-qwen-jni/r2/jniLibs/` | `libzhiyu_qwen_text_jni.so`、`libzhiyu_qwen_image_jni.so` | `native/qwen-edge/android-jni/build.py` |

模型准备脚本只做「固定版本 + 哈希校验」的下载与导出，不执行下载来的源码。

**接入但不在本仓库的依赖**：`android/app/libs/rig2d-0.2.0-studio.jar`（角色动画 2D 网格核心，来自同级 `../rig2d` 工程，SHA-256 `0b0d161e…`，构建时由 `verifyPlanarCore` 校验）。缺这个 jar 无法构建。

### 2.4 构建期完整性校验

这些 task 会在构建前强制校验，任何一个哈希不匹配都会**直接失败而不是警告**：

| task | 校验对象 |
|------|---------|
| `verifyPlanarCore` | `rig2d-0.2.0-studio.jar`、`assets/planar/contract.json` |
| `verifyQwenJni` | 4 个 Qwen JNI `.so`（arm64-v8a / x86_64） |
| `prepareQwenAssets` | Qwen 模型包 `manifest.json` |
| `preparePortraitAssets` | `u2netp.onnx` + 示例素材（仅允许 2 张图入包） |
| `prepareSemanticAssets` | BGE int8 onnx、`vocab.txt`、`config.json`、`tokenizer_config.json`、`manifest.json` |
| `prepareVoiceAssets` | 12 个语音模型文件 + sherpa AAR |
| `prepareQwenTestAssets` | 公开测试 checkpoint（长度 262288 字节） |
| `verifyCompanionContract` | 执行 `python shared/generate_companion_contract.py --check`，保证两端契约一致 |

> 设计意图：模型件、原生库、契约都属于「供应链输入」，任何一个被替换都让构建失败，避免静默地用错权重出图。

### 2.5 Android 依赖清单

| 依赖 | 版本 | 用途 |
|------|------|------|
| `androidx.compose:compose-bom` | 2026.06.01 | Compose 版本统一约束 |
| `androidx.activity:activity-compose` | 1.13.0 | Compose Activity 入口 |
| `androidx.compose.material3:material3` | BOM 托管 | Material 3 |
| `androidx.compose.material:material-icons-extended` | BOM 托管 | 扩展图标 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.10.0 | ViewModel 集成 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.10.0 | `collectAsStateWithLifecycle` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | 协程 |
| `com.squareup.okhttp3:okhttp` | 5.4.0 | 直连各家 AI 服务的 HTTPS 客户端 |
| `androidx.exifinterface:exifinterface` | 1.4.2 | 图片方向元数据 |
| `com.google.mlkit:text-recognition-chinese` | 16.0.1 | 图片中文 OCR |
| `androidx.work:work-runtime-ktx` | 2.12.0 | 后台任务调度 |
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.30.0 | 端侧 ONNX 推理（BGE 语义、u2netp 人像） |
| `com.tom-roush:pdfbox-android` | 2.0.27.0 | 资料导入解析 PDF |
| `sherpa-onnx` AAR（本地） | 1.13.8 | 端侧 ASR + TTS |
| `rig2d-0.2.0-studio.jar`（本地） | 0.2.0 | 角色网格动画核心 |
| `junit:junit` | 4.13.2 | JVM 单元测试 |
| `androidx.test.ext:junit` | 1.3.0 | 仪器测试 |

### 2.6 rig2d：Android 依赖的纯 JVM 契约库

`android/app/libs/rig2d-0.2.0-studio.jar` 来自本仓库的 `rig2d/`。它是**纯 JVM 模块，不需要 Android SDK / NDK / 真机即可全绿跑测试**，与 Android 侧的对接方式见 `rig2d/docs/PORTING.md`。

| 项 | 值 |
|---|---|
| Kotlin | 2.3.21（`kotlin("jvm")`） |
| 目标字节码 | JVM 17（与 android 端 `VERSION_17` 对齐） |
| 依赖 | `kotlinx-coroutines-core:1.10.2` |
| 测试 | JUnit 4.13.2 + `kotlinx-coroutines-test`；JaCoCo 0.8.13 |
| 产物 | `build/libs/rig2d-0.2.0-studio.jar` |

```powershell
cd rig2d
.\gradlew.bat test jacocoTestReport jar    # 测试 + 覆盖率 + 打包 JAR
.\gradlew.bat duplexFixture                # 合成 PCM/ASR/LLM/TTS 复演（不录麦克风）
.\gradlew.bat partsFixture                 # 重放冻结的合成 12 翼素材
python -m unittest discover -s probe/tests -v
python -m unittest discover -s tools/tests -v
```

**注意两点**：

1. `build.gradle.kts` **刻意不声明 java toolchain**。声明 toolchain 17 会让 Gradle 去下载 JDK，本机未配置 toolchain 仓库时会直接构建失败；因此改为用当前 JDK 编译、把 `jvmTarget` 固定为 17。所以用 **JDK 17 及以上**即可，不需要额外的 toolchain 配置。
2. 打出的 JAR **只含可移植核心**：`tasks.jar` 排除了 `rig2d/voice/demo/**` 与 `rig2d/parts/demo/**`（含 Java ImageIO 的宿主专用驱动），JaCoCo 覆盖率统计也排除这两者。它**不含** Python、Web 工坊、分割模型或语音模型。
3. `partsFixture` 依赖 `rig2d/artifacts/parts-fixture-2026-10-13-r1`（合成素材，约 2.5 MB）。该目录已入库；但**根 `.gitignore` 的 `artifacts/` 规则会忽略整个 `artifacts/`**，因此更新这些素材时需要用 `git add -f`。

Android 侧另有 `verifyPlanarCore` 校验 `rig2d-0.2.0-studio.jar` 与 `assets/planar/contract.json` 的 SHA-256；改库后必须同步契约并重跑回归，不能只替换文件。

---

## 三、后端构建（legacy）

### 3.1 环境要求

Python **3.11+**，SQLite **≥3.43**（需 FTS5）。依赖锁定在 `backend/uv.lock`，用 [uv](https://docs.astral.sh/uv/) 管理。

### 3.2 运行与测试

```powershell
.\scripts\Start-Backend.ps1          # 启动（默认 127.0.0.1:8790）
.\scripts\Start-Backend.ps1 -Demo    # 无 Key 本地演示
```

API 文档 <http://127.0.0.1:8790/docs>，健康检查 <http://127.0.0.1:8790/health>。

```powershell
cd backend
uv sync --frozen --extra dev
uv run --no-sync pytest --cov=app --cov-report=term-missing
uv run --no-sync ruff check app tests
```

Docker：

```powershell
docker compose up --build -d --wait
.\scripts\Test-Docker.ps1            # 独立容器测试
```

`backend/Dockerfile` 分 `base` / `test` / `runtime` 三阶段；`runtime` 阶段在镜像构建时执行 `prepare_portrait_model.py` 预置模型，**不在请求路径上下载模型**。Compose 已设 `cap_drop: ALL`、`no-new-privileges`、`pids_limit: 256`。

### 3.3 后端依赖

| 依赖 | 版本约束 | 用途 |
|------|---------|------|
| `fastapi` | ≥0.115,<1 | HTTP 服务 |
| `uvicorn[standard]` | ≥0.34,<1 | ASGI 服务器 |
| `httpx` | ≥0.28,<1 | 调用外部 AI 服务 |
| `python-multipart` | ≥0.0.20,<1 | 表单 / 文件上传 |
| `pypdf` | ≥6.18,<7 | PDF 资料解析 |
| `python-docx` | ≥1.2,<2 | DOCX 资料解析 |
| `pillow` | ≥12.3,<13 | 图片处理 |
| `tzdata` | ≥2026.5,<2027 | 时区数据 |

可选 extras：`dev`（pytest / pytest-asyncio / pytest-cov / ruff）、`semantic`（sentence-transformers）、`portrait`（numpy / onnxruntime）。

---

## 四、原生层构建（端侧图像引擎）

### 4.1 剪层选型：为什么停在 L24

文本编码器 Qwen3-VL-8B 有 36 层，剪层就是直接把第 j 层隐状态喂给 DiT（零训练）。取舍点不在余弦——余弦掉到 0.25 时 512² 文生图画面依然成立——而在两条实测：

- **L28 不能用**：编辑任务（扩图到全身）6 个 seed 全灭，而更浅的 L24 是 6/6，属任务特异性塌陷（Fisher p = 0.0022）。L28 又只比 L36 省 0.77 B，L24 能省 2.32 B —— 既不更省也不更好，被 L24 严格支配。
- **更浅不行**：1024² 二次元画风检验里 L24 是 11/12，L20 崩到 4/12。

所以 L24 是当前的安全线：比 L36 省 2.32 B 参数 / 1.16 GB（int4），画质与编辑能力与 L36 打平。它保留了视觉塔，因此还能图生图与编辑；蒸馏到 0.6B/1.7B 学生后编辑能力会丢，只做文生图时才用学生。依据见 `qwen-image-edge/实验一报告-文本编码器剪层.md` 与 `剪层确认实验报告-L36-L28-L24-6seed.md`。

### 4.2 原生层构建与上游锁定

原生层对 stable-diffusion.cpp 的**固定分支**做适配，锁定信息在 `native/qwen-edge/diffusion/upstream-lock.json`：

| 项 | 值 |
|---|---|
| 上游 | `github.com/leejet/stable-diffusion.cpp` |
| commit | `2988060a4338a5dcd94721c964a8e87003c1ac8b` |
| ggml 子模块 | `d25b121ce1d6ae0c7e62ad080460d9fc1e46dd93` |
| 补丁 SHA-256 | `9bf82bfd37dd1ffd5df28fd32e0a3948ae10ab8f086c886496c01203f300cc95` |

编译 JNI 动态库（产出 `artifacts/android-qwen-jni/`，并自动 audit 导出符号与依赖）：

```powershell
python native\qwen-edge\android-jni\build.py
```

原生层目录：`adapter/`（适配器推理）、`diffusion/`（采样与 VAE 设备端分阶段）、`android-jni/`（JNI 导出与输入契约）、`student/`。

端侧图像引擎的**上游实验工程**在 `qwen-image-edge/`：剪层（L24/L36）、蒸馏学生（Qwen3-0.6B/1.7B + 适配器）、GGUF 量化。交付清单与体积账本见 [qwen-image-edge/交付说明.md](qwen-image-edge/交付说明.md)，工具链在 `qwen-image-edge/tools/`。

模型权重不入库，发布在本仓库 Release（`te-encoder-L24`、`distill-students`）。

---

## 五、常见构建问题

| 现象 | 原因与处理 |
|------|-----------|
| `Qwen JNI SHA mismatch` / `Voice model SHA mismatch` / `Portrait model SHA256 mismatch` | `../artifacts/` 下的模型件被替换或缺失，按第 2.3 节重新准备 |
| `Planar core/contract SHA mismatch` | `rig2d-0.2.0-studio.jar` 缺失或被改动；改库必须同步 `contract.json` 并重跑回归 |
| `Semantic asset SHA256 mismatch` | BGE int8 导出件不匹配，重跑 `backend/prepare_semantic_model.py` |
| `SDK location not found` | 缺 `android/local.properties` |
| `NDK not configured` / 找不到 `30.0.16248370` | 用 SDK Manager 安装对应 NDK 版本 |
| 找不到 `libzhiyu_qwen_*.so` | 先跑 `native/qwen-edge/android-jni/build.py` |
| `verifyCompanionContract` 失败 | 两端契约不同步，跑 `python shared/generate_companion_contract.py` 重新生成 |
| 中文乱码 | 构建脚本与 Python 均依赖 UTF-8；PowerShell 下先设 `$env:PYTHONUTF8='1'` |

---

## 六、构建验证记录

### 6.1 实测数据

下列命令是在**本仓库 GitHub 上的实际克隆**中执行通过的，不是纸面推导（2026-10-16 实测）：

```
克隆：git clone --depth 1 --branch master https://github.com/yanzhibuzhuoyv/zhiyu.git
      → backend/tests 共 51 个测试文件

cd backend
uv sync --frozen --extra dev
  → exit 0，依赖按 uv.lock 完整安装

uv run --no-sync pytest --cov=app --cov-report=term-missing
  → 892 passed, 1 warning in 156.50s
  → app 覆盖率 TOTAL 4630 stmts / 252 miss = 95%

uv run --no-sync ruff check app tests
  → All checks passed!

python shared/generate_companion_contract.py --check
  → companion contract verified:
    7fae113eebdaf8075382978a45afe2463008b4cb5a2ec382d1ddc75354d3a937
```

> **Android 端未在此环境实测**：`app/build.gradle.kts` 的 `preBuild` 依赖仓库外的 `../artifacts/`（Qwen JNI、u2netp、BGE int8、sherpa 语音件、rig2d jar），本机虽已具备这些文件，但重新生成 JNI 需要完整 CMake/NDK 工具链，未在本轮验证。第 2.3 / 2.4 节描述的是构建脚本的**实际约束**（哈希与路径均取自源码），准备齐产物后即可按 2.2 节构建。

### 6.2 推送与网络

若 `git push` 报 `Failed to connect to github.com port 443`（或 `Recv failure: Connection was reset`），
但 `https://api.github.com` 能正常返回，说明是**针对 `github.com` 这一个域名的 SNI/IP 阻断**：
TCP 握手可能成功，TLS 阶段被重置；同域名的其他 IP 往往仍然可用。

此时改走本机代理即可。**代理端口由客户端每次启动时分配，不是固定值**（实测同一台机器上出现过
`63900` 与 `63048` 两个端口），所以先读出当前端口，再拼给 git：

```powershell
# 读取当前系统代理地址（形如 127.0.0.1:63048）
$proxy = (Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings').ProxyServer
$proxy

git -c http.proxy=http://$proxy push origin master
```

长期生效（注意端口会变，重设一次即可）：

```powershell
git config --global http.proxy "http://$proxy"
```

Release 附件上传（`gh release upload`）走 `uploads.github.com`，受同一阻断影响；同样可先设
`$env:HTTPS_PROXY = "http://$proxy"` 再执行。

> 排查顺序建议：① 先确认 `https://api.github.com` 是否可达（可达就说明是单域名阻断，不是断网）；
> ② 读系统代理端口并走代理；③ 若仍失败，再用 `curl --resolve github.com:443:<可达IP>` 验证
> 是否只是该 IP 被墙，而不是整站不可用。

---

## 七、免责声明

- 本项目为个人学习与自用项目，**非任何公司或机构的官方产品**，未用于任何商业用途。
- 应用访问第三方 AI 服务需自备 API Key，费用与合规由使用者承担。
- 端侧图像引擎的权重衍生于 Qwen-Image-2.1-Turbo（**Qwen Research License**），许可副本见 `qwen-image-edge/LICENSE-Qwen-Image-2.1-Turbo`；依其第 31 条标注 **Built with Qwen / Improved using Qwen**。
- 使用本项目产生的任何后果由使用者自行承担。
