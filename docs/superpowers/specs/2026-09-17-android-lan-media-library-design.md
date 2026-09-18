# Android 局域网媒体库（电脑浏览器可播可下）

把现有局域网历史页升级成电脑上正式能用的只读媒体库：能播放/预览，也能下载。叠在已落地的 `LanShareService`、口令、Wi‑Fi 绑定和 `JobStore` 上。不改手机「局域网访问」设置页的开关/口令/地址行为。不改桌面端。

本规格**替换** `2026-09-17-android-lan-history-share-design.md` 里「HTTP 接口 / 网页 / 范围外的预览播放」三节。设置、绑定、安全、默认关闭仍以那份为准。

## 目标

- 同一局域网的**电脑浏览器**打开地址后：左侧选记录，右侧播放或预览，并始终能下载已完成且仍存在的文件。
- 视频、音频：网页内播放 + 下载。
- PDF、图片：网页内预览 + 下载。
- Word / Excel（及其它 Office）：不预览，只下载。
- 相册 / 下载 / 文档 / 自选目录里的 `content://` 成品必须能播能下，不能再只认磁盘路径。
- 页面是正式媒体库布局，不是三段纯文字列表。

## 方案

继续轻量 HTTP，**不引入 Ktor / NanoHTTPD**。在现有 `ServerSocket` 上补 Range、内联播放路由，以及按 URI/文件打开输出。

```
电脑浏览器
  GET /          左列表 + 右播放器（浅壳深播放区）
  GET /m/{id}    内联播放/预览（Range）
  GET /d/{id}    附件下载
LanShareService
  JobStore 只读
  ContentResolver 或 File 打开该任务的输出
```

不采用：换 HTTP 框架、把文件拷进 App 缓存再发、网页改记录、上传、公网穿透、HTTPS、mDNS。

## 网页

单页 HTML，无外链脚本、无 CDN。CSS 与少量 JS 内嵌。视觉：外壳浅色（`#ecece8` / `#1f2428` / 强调 `#c45a2a`），**右侧播放区深色**。有口令时所有链接带同一 `k=`（URL 编码）。无口令时保留现有一句风险说明。

布局固定为 **左列表 + 右详情**：

- 顶栏：应用名 `LiteTrans`；不展示版本号。
- 左侧：视频 | 音频 | 文档，分组规则与 App 相同（`isDocumentHistoryJob`、`isAudioHistoryJob`，其余为视频）。段内新任务在上。
- 每条：文件名、格式、状态。完成且资源可打开的可点选；当前选中左边强调条。
- 多输出（如 PDF 拆分）：同一条下展开子项，每项可单独选、单独播/下。
- 右侧随选中切换播放器/预览，并固定提供 **下载**。
- 默认选中：当前段里第一条「完成且可打开」的项；没有则右侧空态。
- 深链接：`#m/{jobId}` 或 `#m/{jobId}/{index}` 选中对应项（口令仍在 query `k=`，不放进 hash）。

右侧内容：

| 类型 | 判定（扩展名，小写） | 右侧 |
| --- | --- | --- |
| 视频 | `mp4` `mov` `mkv` `webm` `avi` | `<video controls>`，`src=/m/...` |
| 音频 | `mp3` `m4a` `wav` `ogg` `flac` `amr` | `<audio controls>`，`src=/m/...` |
| PDF | `pdf` | 内嵌预览（`<iframe>` 或 `<embed>` 指向 `/m/...`）+ 下载 |
| 图片 | `jpg` `jpeg` `png` `webp` `gif` `bmp` | `<img src=/m/...>` + 下载 |
| Office | `doc` `docx` `xls` `xlsx` `ppt` `pptx` | 文案「请下载后打开」+ 下载，不预览 |
| 其它完成项 | 上表以外 | 同 Office：只下载 |
| 排队/进行中/失败/取消/文件缺失 | — | 无播放器，只显示状态，无下载 |

浏览器解不了的编码（例如部分 `mkv`）：原生控件失败即可，页面提示「无法预览，请下载」。不在手机上为局域网再转一层码。

JS 只做：点选列表、切换右侧 `src`/预览、读 hash。不请求第三方，不发 POST。

## HTTP

所有业务路由只接受 GET（`HEAD` 对 `/m` `/d` 只回头部、无正文，便于部分播放器探测）。有口令则 `k=` 恒等比较（先 URL 解码），与现网一致。其它方法 `405`。未知路径 `404`。

| 方法 | 路径 | 成功 | 失败 |
| --- | --- | --- | --- |
| GET | `/` | `200` `text/html; charset=utf-8` | 口令错 `401` |
| GET/HEAD | `/m/{jobId}`、`/m/{jobId}/{index}` | 内联媒体；完整 `200`，合法 Range `206` | 口令 `401`；不可播资源 `404`；非法 Range `416` |
| GET/HEAD | `/d/{jobId}`、`/d/{jobId}/{index}` | 附件流；完整 `200`，合法 Range 可 `206` | 同上 |

`{index}` 从 0 起，是 `outputPaths`（空则回退 `outputPath`）的**原始下标**，与现网 `/d` 一致。省略 index 一律当 `0`（即使第 0 份缺失也是 404，不会自动跳到下一份）。HTML 里的播放/下载链接只生成「仍可打开」的那些原始下标。

`/m` 响应：

- `Content-Type` 按扩展名猜测（沿用并补齐现有 `lanContentType`：至少含 `mp4`/`mov`/`mkv`/`webm`/`avi` 视频类型）。
- `Content-Disposition: inline`（文件名仍做 RFC 5987，防换行注入）。
- `Accept-Ranges: bytes`
- `Content-Length`；Range 时 `Content-Range: bytes start-end/total`，状态 `206`。
- 未带 Range：整文件 `200`。

`/d` 响应：`Content-Disposition: attachment`，其余与 `/m` 相同的打开与 Range 规则。

Range：只支持单个 `bytes=start-end` 或 `bytes=start-`。多段、倒序、越界 → `416`，并带 `Content-Range: bytes */total`。`HEAD` 的 Range 处理与 GET 相同但不写正文。

打开输出：

1. 任务必须 `JobStatus.Completed`。
2. 路径必须是该任务输出列表中的一项。
3. 拒绝 `..`、伪造 id、符号链接逃逸。磁盘路径用 `NOFOLLOW_LINKS` 的普通文件。
4. `content:` / `content://`：用 `ContentResolver` 打开（优先可 seek 的 `AssetFileDescriptor` / `ParcelFileDescriptor`）；打不开视为缺失。
5. 禁止把 `Job.sourceUri` 或内部绝对路径原文写进 HTML 可见文本。

## 手机端

「我的 → 局域网访问」的开关、口令、地址、通知、无网提示、端口 17890 起最多 10 个，全部保持现状。隐私协议里「可查看历史并下载」改为「可查看、播放/预览并下载已完成文件」。

新增/补齐字符串走现有 i18n（en 默认，zh-rCN / zh-rTW / zh-rHK / ja / ko），包括：下载、无法预览请下载、请下载后打开、以及网页分段/空态（可复用历史空态）。

## 错误

| 情况 | 表现 |
| --- | --- |
| 开关关 / 服务停 | 电脑连不上 |
| 无局域网 IPv4 | 设置页提示；不 listen |
| 口令错或缺失 | `401` 纯文本「需要正确口令」，无任务列表 |
| 任务不存在 / 未完成 / 越界 / 打不开 | `404` |
| 非法 Range | `416` |
| 端口全占用 | 设置页报错；开关可仍为开但无地址 |
| 播放器解不了 | 页面「无法预览，请下载」 |

不在日志中打印口令、`k=`、源 URI。

## 测试

JVM 单测为主，不把真浏览器拖进度当门槛：

- `/m` 为 inline + `Accept-Ranges`；`/d` 为 attachment。
- Range：`200` 整文件、`206` 合法切片、`416` 非法。
- 磁盘普通文件与 `content://` 都能解析；符号链接、伪造 id、未完成任务拒绝。
- 历史分组与 App 三段一致。
- HTML 含列表/播放器/下载标记，不含 `sourceUri`。
- 现有局域网鉴权、路由、下载约束测试继续通过。

## 范围外

网页删除/重命名/搜索、IPv6 展示、自定义端口 UI、账号、HTTPS、NAS、开机自启、为局域网再转码、生成视频封面、Office 在线预览、手机/平板专项布局（电脑优先；窄屏能用即可，不做单独设计）。
