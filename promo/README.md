# 知语 · 水彩宣传片

48 秒中文项目宣传片，使用 Remotion 4.0.537 / React 制作，含原创轻钢琴配乐、纸纹、水彩花卉、描边气泡、逐条出现的对话、记忆卡片与生活照片。

| 合成 | 尺寸 | 帧率 / 时长 | 用途 |
| --- | --- | --- | --- |
| `ZhiyuWide` | 1920 × 1080 | 30 fps / 48 秒 | 电脑、项目介绍、横屏播放 |
| `ZhiyuVertical` | 1080 × 1920 | 30 fps / 48 秒 | 手机竖屏 |

影片中的界面与对话是演绎，不是 App 的实际屏幕录像。没有导出用户聊天、Key、OC 原图或摄像头画面。通话部分明确说明当前实现为“开启摄像头后，语音携带当前单帧”，没有宣称连续实时视频理解。

## 预览与导出

在本目录运行，已验证 Node 24：

```powershell
npm ci
npm run check
npx remotion browser ensure
npm run studio
```

导出两种比例：

```powershell
npm run stills
npm run render
```

输出在 `out/`：两份 H.264 / AAC MP4、12 张审阅关键帧和 `render.json`。`node_modules/`、浏览器缓存和渲染结果不进入 Git。首次安装需要联网下载依赖与 Chromium；渲染期间全部图像、字体和音轨从本地加载。

也可以只渲染一种：

```powershell
npm run render:wide
npm run render:vertical
```

## 分镜

| 时间 | 内容 |
| --- | --- |
| 00:00–00:07 | 水彩花卉与品牌：“把日常，说给知语。” |
| 00:06–00:15 | 手机描边气泡，几句短短的日常回应 |
| 00:14–00:23 | 原话卡片与今天的回应，展示原文记忆概念 |
| 00:22–00:31 | 猫与雨后散步的照片，展示日常分享 |
| 00:30–00:40 | 声音波形与通话卡片，说明单帧看画面 |
| 00:39–00:48 | 品牌收尾、项目地址、Android / API Key / 本地存储 |

相邻镜头有 1 秒叠化。所有运动以 `useCurrentFrame()` 驱动，没有依赖浏览器实时钟的 CSS 动画。

## 编辑入口

- [src/Film.tsx](src/Film.tsx)：文案、镜头内容、顺序和时长。
- [src/design.tsx](src/design.tsx)：颜色、纸纹、字体、横竖版排布、入场动画。
- [src/Phone.tsx](src/Phone.tsx)：演绎手机、聊天气泡、波形。
- [src/Root.tsx](src/Root.tsx)：分辨率、帧率、总帧数。
- [scripts/render.mjs](scripts/render.mjs)：统一渲染与关键帧导出。
- [scripts/make_music.py](scripts/make_music.py)：本轮原创合成配乐；Python + NumPy 可重新生成 `public/daylight.wav`。

中文字体为本片文案子集。改文案后先 `python -m pip install fonttools brotli`，再执行 `python scripts/update_fonts.py`，或提供完整且许可允许的字体；不要依赖系统字体回退来掩盖缺字。刷新脚本兼容 Google Fonts 返回的 TTF 与 WOFF2。

## 素材来源

| 文件 | 来源与用途 |
| --- | --- |
| `public/bouquet.png` | 项目已有 `android/app/src/main/res/drawable-nodpi/watercolor_bouquet_v1.png`，沿用产品水彩视觉 |
| `public/cat.png`、`public/park.png` | 项目 `artifacts/native-media-seed/` 的橘猫窗边、雨后公园 AI 生成示例；虚构生活场景 |
| `public/daylight.wav` | 本轮由 `make_music.py` 合成的原创配乐；不使用外部录音或他人曲谱 |
| `public/fonts/serif.woff2` | [Noto Serif SC](https://fonts.google.com/noto/specimen/Noto+Serif+SC) 的文案子集，SIL OFL |
| `public/fonts/sans.woff2` | [Noto Sans SC](https://fonts.google.com/noto/specimen/Noto+Sans+SC) 的文案子集，SIL OFL |

字体许可全文保存在 `public/fonts/*-OFL.txt`。没有分发 Windows 自带字体。Remotion 使用方式参见其 [renderMedia 官方文档](https://www.remotion.dev/docs/renderer/render-media)。

## 本次验证

TypeScript 检查通过；两种比例共 12 个关键帧逐一检查，修正聊天气泡遮挡和竖版片尾花卉位置。最终输出另由 FFprobe 核验分辨率、帧数与音视频时长，并由 FFmpeg 完整解码检查。

摄像头源码修复与验证结果见 [摄像头审查报告](../docs/CAMERA_AUDIT_2026-10-20.md)：974 项 Android JVM、23 项专项 Native、26 项语音核心专项通过。另有 3 项旧抠图测试因历史评估素材缺失失败，未冒称完整核心测试全绿。本轮未做物理手机摄像头与真实模型视觉质量验收。
