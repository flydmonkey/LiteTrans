# Android 局域网媒体库：四 Tab 成品页

把现有局域网媒体库网页从「三段叠在左侧的草稿列表」做成电脑上能正式用的四 Tab 成品页。叠在已落地的 `LanShareService`、口令、`/m` `/d`、Range 上。不改手机「局域网访问」设置页。不改桌面端。不改 App 历史三段。

本规格**替换** `2026-09-17-android-lan-media-library-design.md` 的「网页」一节。HTTP、打开输出、手机端设置、绑定与安全仍以那份为准。

## 目标

同一局域网的电脑浏览器打开地址后：

- 顶栏四个 Tab：**视频、音频、图片、文档**。点哪个只出现哪一类已完成且文件仍在的条目。
- 四种 Tab 同一骨架：左选文件，右深色预览 + 下载。
- 看起来是成品媒体库，不是分组标题加纯文字清单。

## 方案

继续轻量 HTTP，**不引入 Ktor**。仍是单页 `GET /`，四组 DOM 一次下发，JS 显隐当前 Tab。不新增路由，不按 Tab 重新请求。

```
电脑浏览器
  GET /          顶栏四 Tab + 左列表/缩略图 + 右预览
  GET /m/{id}    内联播放/预览（Range）— 不变
  GET /d/{id}    附件下载 — 不变
LanShareService
  JobStore 只读
```

不采用：换 HTTP 框架、`/?tab=` 服务端重渲染、`/video` 等分路径、灯箱全屏、全页深色、卡片墙、网页改记录。

## 分组

按**该条输出文件**的 `lanPreviewKind`（扩展名，小写），不是按 App 的 `HistorySegment`。

| Tab | 收入 |
| --- | --- |
| 视频 | `LanPreviewKind.Video`：`mp4` `mov` `mkv` `webm` `avi` |
| 音频 | `LanPreviewKind.Audio`：`mp3` `m4a` `wav` `ogg` `flac` `amr` |
| 图片 | `LanPreviewKind.Image`：`jpg` `jpeg` `png` `webp` `gif` `bmp` |
| 文档 | `LanPreviewKind.Pdf` + `File`：`pdf`、Office、其它只下载类型 |

入选条件（必须同时满足）：

1. `JobStatus.Completed`
2. 该输出路径 `fileExists` 为真（磁盘普通文件或可打开的 `content://`，规则与现网相同）

排队、进行中、失败、取消、文件缺失：**不出现在任何 Tab**。直接请求 `/m` `/d` 仍按现网 `404`。

多输出（如 PDF 拆成多图）：每一份可打开的输出单独成条，按该份扩展名进对应 Tab。父任务名不单独占一行。

同一任务若有一份 mp4 和一份 png（实际少见），两份分别进视频和图片，互不影响。

## 网页

单页 HTML，无外链脚本、无 CDN。CSS 与少量 JS 内嵌。有口令时所有 `/m` `/d` 链接带同一 `k=`（URL 编码）。无口令时顶栏保留一句现有风险说明，不挡 Tab。

视觉（LiteTrans 精修，frontend-design 约束）：

- 外壳浅色：页面底 `#ecece8`，墨色 `#1f2428`，次要字 `#5c6460`，描边 `#d5d2cc`，强调 `#c45a2a`。
- 左栏白底；右预览区 `#111`。
- 系统 UI 字体栈（中文走系统字体）。不使用 Inter / Roboto / 外链字体。
- Tab：横排下划线，当前项 `#c45a2a` 字重 600 + 2px 底边；未选项 `#5c6460`。
- 每个 Tab 文案旁显示该类条数（含 `0`）。
- 动效只用于切 Tab / 切选中的短显隐；`prefers-reduced-motion` 时关闭。
- 不做卡片阴影套件、不做全大写 eyebrow、不拉第三方。

结构：

```
header: LiteTrans | 无口令风险句
nav tabs: 视频(n)  音频(n)  图片(n)  文档(n)
main:
  左栏（约 320px，当前 Tab 的列表或缩略图）
  右栏（深色预览 + 文件名 + 下载）
```

四种 Tab 同一骨架，左栏内容不同：

| Tab | 左栏 | 右栏 |
| --- | --- | --- |
| 视频 | 文件名列表，选中左边焦糖条 | `<video controls>` |
| 音频 | 同上 | `<audio controls>` |
| 图片 | 2 列缩略图（`img src=/m/...`），选中描边 | 大图 `<img>` |
| 文档 | 文件名 + 格式 | PDF：`<iframe src=/m/...>`；Office/其它：文案「请下载后打开」，不预览 |

右侧始终提供 **下载**（`/d/...`）。预览失败时下载仍在，提示「无法预览，请下载」。当前 Tab 为空或未选中可打开项时，隐藏下载链接，右侧空。

默认 Tab：按 **视频 → 音频 → 图片 → 文档** 找第一个条数 > 0 的；四个都空则停在视频 Tab，四处空态。

进入某个非空 Tab 后，自动选中该组第一条（新任务在上，与现网 `asReversed()` 一致）。

空态文案按 Tab：暂无视频 / 暂无音频 / 暂无图片 / 暂无文档（走 i18n，可新建图片空态，其它可复用历史空态）。

深链接：

- `#m/{jobId}` 或 `#m/{jobId}/{index}`：找到该项，切到它所在 Tab 并选中。口令仍在 query `k=`，不放进 hash。
- 无 hash：走默认 Tab + 该组第一条。
- 不单独做 `#image` 一类 Tab-only hash；Tab 切换不必写 history（避免打乱 `#m/`）。

JS 只做：切 Tab、点选左栏、切换右侧 `src`、读 hash。不请求第三方，不发 POST。切 Tab 时停掉上一个媒体（清 `src` + `load()`），避免后台继续出声。

窄屏：主区上下叠（先左栏后预览），能用即可，不做独立移动设计。

## 文案与 i18n

新增/补齐走现有 locale（`values` 英文默认，`zh-rCN` / `zh-rTW` / `zh-rHK` / `ja` / `ko`）：

- Tab：视频、音频、图片、文档（视频/音频/文档可复用 `lan_segment_*`；**必须新增图片**）
- 空态：暂无图片（其它可复用 `history_empty_*` / 现有 LAN 空态）
- 预览失败、请下载后打开、下载：沿用媒体库已有字符串

网页语言跟 App 当前语言（服务端渲染时用 App locale 的 `LanHistoryCopy`），不跟电脑浏览器语言。

## 错误

| 情况 | 表现 |
| --- | --- |
| 开关关 / 服务停 | 电脑连不上 |
| 口令错或缺失 | `401` 纯文本「需要正确口令」，无任务列表 |
| 某 Tab 无条目 | 该 Tab 可点，左栏空态，右侧空，无下载 |
| 四 Tab 都空 | 停在视频 Tab，空态 |
| 浏览器解不了编码 / 图或 PDF 失败 | 右侧「无法预览，请下载」 |
| 未完成 / 缺失 / 伪造 id | 不进 HTML；`/m` `/d` 仍 `404` |
| 非法 Range | `416` |

不在日志中打印口令、`k=`、源 URI。禁止把 `Job.sourceUri` 或内部绝对路径写进 HTML 可见文本。

## 测试

JVM 单测为主，不把真浏览器拖进度当门槛：

- HTML 含四个 Tab 标记（含图片）。
- 分组：png 只在图片组，mp3 只在音频组，pdf/docx 只在文档组，mp4 只在视频组。
- 未完成或 `fileExists=false` 的任务不出现在任何 Tab。
- 多输出拆条：两份 png 为两条图片，不残留父任务占位行。
- 默认 Tab：有视频则视频；否则按音频 → 图片 → 文档。
- `#m/{id}` 的 `data-*` 仍指向正确 `/m` `/d`；项带所在 Tab 信息，供 JS 切换。
- 无外链 `<script src>` / CDN；不含 `sourceUri`。
- 现有 `/m` `/d`、Range、口令、content URI、鉴权测试继续通过。

## 范围外

网页删除/重命名/搜索、IPv6、自定义端口 UI、账号、HTTPS、NAS、开机自启、为局域网再转码、生成视频封面、Office 在线预览、灯箱、全页深色、手机/平板专项布局、改 App 历史为四段、改 `applicationId`。
