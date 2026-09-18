# 轻转码

本地桌面转码应用。把视频转成常用格式、裁一段、或只抽出音频。文件始终留在本机，不会上传。

能转哪些格式，取决于捆绑的 FFmpeg 能否解码。不是云服务，也不保证「任意编码都能完美转出」。

## 能做什么

1. 拖入或选择视频（可多选）
2. 选目标格式
3. 需要时再选画质、分辨率、裁切区间
4. 点「开始转码」

默认输出目录是「下载 / 轻转码」。下次打开会记住上次的格式、画质和输出位置。

| 目标 | 说明 |
| --- | --- |
| MP4 · H.264 | 兼容性最好，适合分享、上传 |
| MP4 · 不重编码 | 只换容器，不重新压缩，速度最快 |
| MP4 · H.265 | 同样是 MP4，体积通常更小 |
| MOV · H.264 | 苹果设备和剪辑软件常用 |
| MKV · H.264 / H.265 | 适合封装保存 |
| WebM · VP9 | 适合网页播放，会比 MP4 慢 |
| AVI · MPEG-4 | 旧电脑和投影常用 |
| GIF | 短视频转成动图 |
| MP3 / M4A | 只导出音频 |

画质和分辨率是两回事：

- **画质**：压得紧不紧（原画 / 标准 / 节省体积）
- **分辨率**：画面有多大（原尺寸 / 1080p / 720p / 480p）

例如可以选「原画 + 1080p」：画面缩小，细节尽量留着。

在 macOS 上，H.264 / H.265 会优先走系统硬件编码（VideoToolbox）。WebM / GIF / 部分音频仍是软件编码。

## 安装包

| 系统 | 产物 | 备注 |
| --- | --- | --- |
| macOS Apple Silicon | `.dmg` / `.app` | `轻转码-macos-arm64` |
| macOS Intel | `.dmg` / `.app` | `轻转码-macos-x64` |
| Windows x64 | NSIS 安装程序 `.exe` | `轻转码-windows-x64` |
| Linux x64 | `.deb` | `轻转码-linux-x64`；包名 `qing-zhuama`，菜单显示「轻转码」 |
| Android arm64 | Debug APK | `app-debug.apk`，需侧载 |
| iOS arm64 | Xcode 真机 / TestFlight | 无 App Store |

### macOS

没有苹果开发者签名。第一次打开可能被系统拦截：

1. 打开「系统设置 → 隐私与安全性」
2. 允许打开「轻转码」
3. 或右键 `.app` → 打开

请按芯片选包：M 系列用 arm64，Intel 用 x64。

### Windows

需要 [WebView2](https://developer.microsoft.com/microsoft-edge/webview2/)。多数 Windows 10/11 已自带；若提示缺失，按安装向导安装即可。安装程序默认装到当前用户，不需要管理员权限。

### Linux

Debian / Ubuntu 用 `.deb`。本机也可打 `.AppImage`。桌面菜单名称是「轻转码」，软件包文件名是 `qing-zhuama`（Debian 不允许中文包名）。

### Android

Android 构建需要 JDK 17 和 Android SDK 35，目前仅支持 arm64。先拉取固定版本的 FFmpeg / FFprobe 二进制，再构建 Debug APK：

```bash
node android/scripts/fetch-ffmpeg.mjs
cd android && ./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`（相对于 `android/`），需要手动侧载到 Android 设备。Android 捆绑的 FFmpeg 构建涉及 GPL；与桌面版相同，发布前需自行确认许可证并保留相应版权与源码获取说明。

#### Android 真机冒烟清单

请连接 arm64 真机或模拟器。先确认系统 PATH 中没有可用的 `ffmpeg`，确保测试的是 APK 捆绑的二进制：

```bash
adb shell 'PATH=/system/bin:/vendor/bin command -v ffmpeg || true'
cd android && ./gradlew connectedDebugAndroidTest
```

在真机上各选一段可辨认的原片，逐项确认：

- [ ] **MP4 H.264**：完成完整转码流程，导出文件可播放且有声音。
- [ ] **mp4-copy**：使用 H.264/AAC 源，快速导出且文件可播放。
- [ ] **抽音频**：导出 MP3 或 M4A，可播放且时长正确。
- [ ] **裁切**：设置明确的起止时间，导出片段时长和内容正确。
- [ ] 上述操作均在系统 PATH 无 `ffmpeg` 时成功。
- [ ] 每次完成、失败或取消后，原片仍在且可正常播放。

### iOS

第 3 刀支持 Live Activity 与后台 processing 任务。Word 可转 PDF；Excel 尚未提供。许可仍按根 README 的本机处理说明。

```bash
cd ios && node scripts/fetch-ffmpeg.mjs && xcodegen generate && xcodebuild -scheme LiteTrans -destination 'generic/platform=iOS Simulator' build
```

产物通过 Xcode 真机 / TestFlight 安装，无 App Store。FFmpeg xcframework 由 `ios/scripts/fetch-ffmpeg.mjs` 拉取到 `ios/Vendor/FFmpeg/`，不入库。

#### iOS 真机冒烟清单

1. 浅色 / 深色外观都像系统设置页
2. 相册加一条视频，改格式为 MOV，开始转换
3. 自动跳历史，完成后 Quick Look
4. 我的 → 关于含「轻转码」和版本；隐私含「不上传」
5. 音频文件转 MP3 进下载
6. 相册图转 JPG 进照片
7. 小 PDF 拆分在「文件」里可见
8. 四张主视频仍成功
9. Word 转 PDF 进「文件」；Excel 行标红不能开始
10. 入队后锁屏或 Dynamic Island 显示文件名与百分比；完成后立刻消失
11. Expanded / 锁屏上的取消能停当前任务
12. 视频选「合并」，加两段相册视频，拖动对调后开始；历史一条记录，成片时长相加且顺序为对调后；竖屏拼横屏有黑边

## 从源码运行

源码仓库：[`git@github.com:flydmonkey/LiteTrans.git`](https://github.com/flydmonkey/LiteTrans)

```bash
git clone git@github.com:flydmonkey/LiteTrans.git
cd LiteTrans
```

需要：

- Node.js 20+
- 通过 [rustup](https://rustup.rs/) 安装的稳定版 Rust（不要用 Homebrew 的 `rustc`，可能和系统 LLVM 冲突）

```bash
export PATH="$HOME/.cargo/bin:$PATH"
npm install
npm run tauri dev
```

`npm install` 会为**当前电脑架构**下载 FFmpeg / FFprobe，放到 `src-tauri/binaries/`。也可以单独执行：

```bash
npm run fetch-ffmpeg
```

验证捆绑的 FFmpeg 不依赖系统 PATH：

```bash
npm run smoke
```

## 本地打包

安装包必须在对应操作系统上构建，或走 GitHub Actions。不能只在一台 Mac 上打出 Windows / Linux 安装包。

```bash
npm install
npm run fetch-ffmpeg
```

| 系统 | 命令 | 产物 |
| --- | --- | --- |
| macOS Apple Silicon | `npm run tauri:build:macos` | `.app` / `.dmg`（arm64） |
| macOS Intel | `npm run tauri:build:macos-intel` | `.app` / `.dmg`（x64） |
| Windows | `npm run tauri:build:windows` | NSIS `.exe` |
| Linux | `npm run tauri:build:linux` | `.deb` / `.AppImage` |

Linux 还需要：

```bash
sudo apt-get install -y libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev librsvg2-dev patchelf libfuse2
```

Apple Silicon 电脑上打 Intel 包，需要先安装交叉编译目标，并准备 x64 的 FFmpeg sidecar。更省事的方式是用下面的 GitHub Actions。

## GitHub Actions

工作流：`.github/workflows/build-desktop.yml`

代码推到 GitHub 后：

1. 打开仓库的 **Actions**
2. 运行 **Build desktop**
3. 下载四个产物：
   - `轻转码-macos-arm64`
   - `轻转码-macos-x64`
   - `轻转码-windows-x64`
   - `轻转码-linux-x64`

打 `v*` 标签（例如 `v0.1.0`）也会触发同样的打包。

## FFmpeg 许可

应用通过 sidecar 调用 FFmpeg / FFprobe，不静态链接 libav。开发时默认使用 `ffmpeg-static` / `ffprobe-static` 提供的二进制，这些构建通常按 GPL / LGPL 分发。

发布产品前请自行确认所用构建的许可证，并保留 FFmpeg 版权与源码获取说明。如需 LGPL 构建，可从 [FFmpeg 官网](https://ffmpeg.org/download.html) 或各平台官方包替换 `src-tauri/binaries/` 中的文件。
