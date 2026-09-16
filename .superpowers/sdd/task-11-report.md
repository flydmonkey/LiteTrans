# Task 11 报告：仪器测试与冒烟清单

## 状态

**DONE_WITH_CONCERNS**

实现内容已完成，JVM 测试与仪器测试 APK 编译通过。连接真机运行仪器测试时，设备拒绝安装 APK，因此没有把仪器测试记为通过。

## 实现内容

- 新增 `android/app/src/androidTest/assets/tiny.mp4`。
  - 由宿主机 FFmpeg 生成。
  - 参数：1 秒、320x240、H.264、yuv420p。
  - `ffprobe` 验证结果：`codec_name=h264`、`width=320`、`height=240`、`duration=1.000000`。
- 新增 `EnqueueFlowTest`，覆盖：
  - 从 instrumentation asset 复制 `tiny.mp4` 到 cache。
  - 使用捆绑的 FFprobe 探测媒体，并断言 H.264、320x240。
  - 使用 `enqueueJobs` 入队。
  - 断言同名输出依次分配为 `tiny.mp4`、`tiny-1.mp4`。
  - 启动任务后立即取消，断言状态为 `Cancelled` 且无 `.partial.` 残留。
  - 使用无效 SAF 输出目录制造失败，再改用有效的应用外部目录重试，断言完成。
  - 断言测试结束时原始 `tiny.mp4` 仍存在。
- README 新增 Android arm64 真机/模拟器说明及冒烟清单：
  - MP4 H.264 全流程。
  - `mp4-copy`（H.264/AAC 源）。
  - 抽取 MP3/M4A 音频。
  - 裁切。
  - 系统 PATH 无 `ffmpeg`。
  - 操作后原片仍在。

## 验证

### JVM 与编译

```bash
JAVA_HOME=/Users/wuyu/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew :app:testDebugUnitTest --rerun-tasks
```

结果：`BUILD SUCCESSFUL`，15 个测试类共 90 个测试，0 failure、0 error。

```bash
JAVA_HOME=/Users/wuyu/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

结果：`BUILD SUCCESSFUL`，`EnqueueFlowTest` 编译并打入 instrumentation APK。

### 连接真机

检测到设备 `83da96a0321`（M2010J19SC，Android 12），执行：

```bash
JAVA_HOME=/Users/wuyu/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew connectedDebugAndroidTest
```

结果：未执行测试。设备安装阶段返回：

```text
INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
Finished 0 tests
```

## 关注项

- 需要在真机上允许通过 USB 安装应用（部分 MIUI 设备需开启“USB 安装”并确认安装提示），然后重新运行 `connectedDebugAndroidTest`。
- 由于设备拒绝安装，本次不能声明 `EnqueueFlowTest` 已在真机运行通过。
- JVM 重跑输出包含既有的 `android:extractNativeLibs` AGP 警告，与本任务改动无关。

## 评审修正（2026-09-16）

- `tiny.mp4` 现从 `InstrumentationRegistry.getInstrumentation().context.assets` 读取，确保使用 instrumentation/test APK 的 assets。
- 唯一命名测试现连续调用两次真实 `enqueueJobs`，并使用应用相同的 `File(path).exists()` 回调；第一次入队后在规划路径创建真实文件，第二次据此分配 `tiny-1.mp4`，不再使用测试私有集合。
- 取消测试不再预占进程槽。测试启动真实转码，并在首个 FFmpeg progress 回调到达后取消，随后断言 `Cancelled` 且 staging 目录无 `.partial.` 文件。

### 修正后验证

```bash
JAVA_HOME=/Users/wuyu/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

结果：`BUILD SUCCESSFUL`。

```bash
JAVA_HOME=/Users/wuyu/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
ANDROID_HOME=/Users/wuyu/Library/Android/sdk \
./gradlew connectedDebugAndroidTest
```

结果：设备 `M2010J19SC` 仍在安装应用 APK 时返回
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，共执行 0 个测试；因此不声明真机仪器测试通过。

## Whole-branch fix

状态：**DONE_WITH_CONCERNS**。

### 已修复

- 转码优先复用探测阶段生成的稳定 cache 文件；仅当 `/proc/self/fd/N` 明确不可读时复制到 cache 并重试一次。
- 打开输出使用 `setDataAndType(uri, mime)`，避免 `setType` 清空 URI。
- 损坏的 `jobs.json` 返回空列表并移动为 `jobs.json.bad`；启动恢复移到 IO 协程并隔离存储异常。
- FFmpeg 进度线程隔离回调异常，NaN/Infinity 进度被拒绝，失败 stderr 尾部只写入 logcat。
- FFmpeg/FFprobe 改为首次使用时定位；缺失提示包含
  `node android/scripts/fetch-ffmpeg.mjs`。
- Service 销毁使用非用户取消的 interrupt，运行中任务恢复为 Failed/「转码被中断」；排队任务取消不再唤醒 pump。
- 获取脚本在校验和不匹配时删除缓存归档，并从 FFmpeg ELF 的 strings 中验证
  libx264、libx265、libvpx、libmp3lame、libopus 与 MediaCodec 配置。

### C1 关注项

未找到可直接替换的、同时包含 `ffmpeg`/`ffprobe` CLI 且启用全部必需库的公开
Android arm64 tarball，因此没有伪造 URL 或 SHA256，也没有安装不完整二进制。

检查过：

- `fazi-gondal/ffmpeg` 当前 25 MB CLI tarball：实际嵌入配置只有 libx264 和
  MediaCodec，缺 libx265、libvpx、libmp3lame、libopus。
- `Khang-NT/ffmpeg-binary-android`：2018 年 full 包缺 x265/MediaCodec 覆盖。
- `hzw1199/Android-FFmpeg-Prebuilt`：LGPL 构建，不含 GPL x264/x265。
- `mzgs/FFmpegX-Android`：发行资产为 JNI/static libraries，不是独立 CLI tarball，
  且公布编码器清单未覆盖所需 x265/VP9/Opus 组合。
- `mobile-ffmpeg` / `ffmpeg-kit` full-gpl：AAR/JNI 库，不提供所需独立 CLI。
- `Quickits/Thor`、`zhivoglas/ffmpeg-android-arm64-gpl`：分别缺所需 codec/ffprobe，
  或只有共享库。

当前脚本会明确失败并列出缺失配置；用现有 fazi 归档验证得到：
`--enable-libx265, --enable-libvpx, --enable-libmp3lame, --enable-libopus`。
