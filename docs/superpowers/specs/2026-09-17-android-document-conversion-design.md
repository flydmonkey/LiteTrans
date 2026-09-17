# Android 文档转换 Tab

在音视频底栏上再加一条「文档」。全程本机、不上传。叠在 [音频转换 Tab](2026-09-17-android-audio-conversion-design.md) 和 [引导式三步](2026-09-17-android-desktop-ui-parity-design.md) 上。队列、前台服务、历史仍是一套；FFmpeg 继续只管音视频。文档走新引擎：系统图片解码、`PdfRenderer`、PdfBox、Apache POI。

## 目标

- 底栏五个 Tab，从左到右：**视频转码**、**音频转换**、**文档**、**历史记录**、**我的**。
- 文档页复用同一套三步：添加 → 目标 → 存放。先选文件，第二步只出现该类型能转的目标。
- 同一批必须同类型；混选在入队前拦住。
- 第一期：图片互转与压缩；PDF 转图片、转 TXT、压缩、拆分；docx / xlsx → PDF。
- 图片目标：JPG、PNG、WebP、BMP、GIF（静图）。
- 存放按结果类型：图片默认相册；PDF / TXT 默认系统「文档」；都可改下载或自定义。
- 历史第三段：**视频 | 音频 | 文档**。
- 视频、音频、文档三份会话互相独立：切 Tab 不丢已选文件、步骤、页范围和存放。

## 方案

`RootTab` 增加 `Document`。`ConvertMode` 增加 `Document`。`AppViewModel` 持有第三份 `WizardSession`。`JobStore` 不拆表。不上 Navigation。文档不单独开 Service、不插队。

```
内容区（当前 Tab）
下一步 / 开始转换（文档 Tab 与音频相同文案）
视频转码 | 音频转换 | 文档 | 历史记录 | 我的
```

泵取出队头后：文档任务交给 `document` 引擎，其余交给现有 FFmpeg。同一时间只跑一个任务。

不采用：工具超市（先选「Word转PDF」再选文件）、把 PDF 整页光栅化再装回 PDF 当作压缩/拆分、文档单独队列、联网/OCR。

## 领域模型

音视频的 `resolveConfig` / FFmpeg 白名单不负责文档。文档任务仍进同一张 `Job` 表，用约定字段区分：

- `OutputConfig.preset` 为文档预设 id（`image-jpg`、`pdf-split`、`office-pdf` 等）。
- PDF 转图片的格式写在 `OutputConfig.container`（`jpg` / `png` / `webp`）。
- 页范围记在 `MediaInfo`：`pageCount`、`pageStart` / `pageEnd`（从 1 起，闭区间），语义对等于裁切起止，不复用秒。
- `Job.outputPath` 仍是打开/分享用的**第一份**输出。
- `Job.outputPaths` 为全部输出；只有一份时就是含 `outputPath` 的单元素列表。音视频任务可空，打开逻辑回退到 `outputPath`。
- `isDocumentHistoryJob` 看预设是否属于文档 id 集合。

## 会话状态

```
enum class ConvertMode { Video, Audio, Document }

enum class DocumentSourceKind { Image, Pdf, Word, Excel }

data class DocumentConfig(
    target,          // 见下方预设 id
    imageFormat,     // 仅「转图片」或图片互转：jpg/png/webp/bmp/gif
    quality,         // 压缩：high / standard / small
    pageStart,       // PDF，1-based，默认 1
    pageEnd,         // PDF，默认最后一页
)

data class WizardSession(
    sources,
    preset,          // 文档默认随源类型变，见下
    quality,
    size,            // 文档不用
    output,
    document,        // 仅 ConvertMode.Document
)
```

文档默认目标：

| 源 | 默认 |
| --- | --- |
| 图片 | 转 JPG |
| PDF | 转图片 · JPG |
| Word / Excel | 转 PDF |

`AppScreen` 为文档 mode 另保留 `step` / `showAll` / `selectedUri`。切走再回来仍在。

点「开始转换」且真正入队后：

1. 只 reset **文档**会话（清源、回到第 1 步）。
2. 切到「历史记录」。
3. 历史分段切到「文档」。

入队失败则留在文档页，照旧出提示条。

## 历史分流

内容区筛选为三段：**视频 | 音频 | 文档**。只渲染当前分段。新任务在上。打开、分享、重命名、删除、取消、再试一次复用 `JobRow`。

`isDocumentHistoryJob(job)` 为真当且仅当任务带文档类型 / 文档预设。音频规则不变；其余进视频。

空态：「还没有文档记录」。

**每个源文件一条任务。** 一个 PDF 转出 12 张图仍是一条：副标题「12 张图片」或「12 个 PDF」。打开 / 分享第一份输出；重命名只改历史展示名，不批量改磁盘文件名。删除这条任务时删掉它写出的全部文件。

多份输出的文件名：`原名-001.jpg` 这种三位序号，从页范围的第一页起算。

## 文档三步

### 1. 添加

标题「添加文件」，副标题「预览，PDF 可选择页范围」。空态两个入口：

| 按钮 | 行为 |
| --- | --- |
| 相册 | `PickMultipleVisualMedia`，仅图片 |
| 文件 | `OpenMultipleDocuments`，图片 / PDF / Word / Excel 的 MIME |

已选列表后再加：新文件必须与已选 `DocumentSourceKind` 相同，否则提示「请一次只加同一种文件」，已选保留、新文件不进入。

探测：

| 结果 | 行为 |
| --- | --- |
| jpg/png/webp/bmp/gif/heic 等系统能解的图 | `Image` |
| pdf | `Pdf`，记下页数 |
| docx | `Word` |
| xlsx | `Excel` |
| doc / xls / wps / 其它 | 该行标红，不可导入 |

图片、PDF 可预览。PDF 用 `PdfRenderer` 看当前页，页范围默认 1…N，可改。Word / Excel 只显示文件名，不做排版预览。

没有可导入文件时「下一步」禁用。

### 2. 目标

标题「选择格式」。选项由当前批的 `DocumentSourceKind` 决定，无「更多」。

**图片**

| 预设 | 标题 | 说明 |
| --- | --- | --- |
| `image-jpg` | JPG | 兼容性最好 |
| `image-png` | PNG | 无损，文件更大 |
| `image-webp` | WebP | 同样清晰，体积更小 |
| `image-bmp` | BMP | 无压缩 |
| `image-gif` | GIF | 静图，只保留一帧 |
| `image-compress` | 压缩 | 尽量保持格式，缩小体积 |

压缩显示质量三档（高 / 标准 / 更小）。JPG/WebP/GIF 调编码质量；PNG/BMP 以缩小边长为主。互转不显示音视频那种分辨率网格。

**PDF**

| 预设 | 标题 | 说明 |
| --- | --- | --- |
| `pdf-image` | 转图片 | 每页一张图，还要选 JPG / PNG / WebP |
| `pdf-txt` | 转 TXT | 抽取文字；扫描件会失败 |
| `pdf-compress` | 压缩 | 缩小内嵌图，不把整页拍成图 |
| `pdf-split` | 拆分 | 范围内每页一个 PDF |

页范围沿用第 1 步。`pdf-image` 默认 JPG。压缩三档只动内嵌图分辨率/质量。卡片下说明：扫描版 PDF 转 TXT 抽不出字。

**Word / Excel**

| 预设 | 标题 | 说明 |
| --- | --- | --- |
| `office-pdf` | 转 PDF | 简单文字和表格可以，复杂排版会对不齐 |

输入只认 **docx / xlsx**。

### 3. 存放

| 结果 | 默认 | 也可选 |
| --- | --- | --- |
| 图片（互转、压缩、PDF 转图） | 相册 `Pictures/轻转码` | 下载、自定义 |
| PDF、TXT | 系统文档 `Documents/轻转码` | 下载、自定义 |

`OutputTarget.Kind` 增加 `Documents`。自定义未选好时「开始转换」不可用。

Dock：第 1 / 2 步「下一步」；第 3 步「开始转换」。摘要用「文件 / 目标」，PDF 带页范围，不提分辨率。

## 引擎与输出

新包 `document/`，不往 `Presets.kt` 音视频表里塞文档预设。

| 单元 | 做什么 | 依赖 |
| --- | --- | --- |
| 图片 | `ImageDecoder` / `Bitmap` 解码，按目标编码。HEIC 等只作输入。GIF 输出为静图。 | Android 图形 API；解不了再失败，不走 FFmpeg |
| PDF 预览与转图 | `PdfRenderer` 按页渲染 | framework |
| PDF 拆分 / TXT / 压缩 | PdfBox：拷贝页面、抽文本、缩小内嵌图后写回 | PdfBox（Apache 2.0） |
| Office → PDF | POI 读 docx/xlsx，系统 `PdfDocument` 画简单版式（段落、表格） | `poi-ooxml`；不要 hwpf/HSSF 旧格式栈 |

写出后登记 `Job.outputUris`（有序列表）。MIME：`image/jpeg`、`image/png`、`image/webp`、`image/bmp`、`image/gif`、`application/pdf`、`text/plain`。

进度：PDF 类按页；单张图片一次编码视为 0→100。

## 返回

| 当前 | 系统返回 |
| --- | --- |
| 我的 · 详情 | 我的根页 |
| 视频 / 音频 / 文档 · 第 2/3 步 | 上一步（只改当前 Tab 的 step） |
| 其它 Tab 根页 | 交给系统 |

## 错误与空态

- 混选类型：提示条，不加入新文件。
- `.doc` / `.xls` / WPS：该行「不支持此格式」，不计入可导入。
- 加密 PDF：任务失败，「不支持加密 PDF」。第一期无密码框。
- 扫描件转 TXT 抽不出字：失败，「没有可提取的文字」。
- 页范围超出：夹紧到 1…N；夹完为空则第 2/3 步不可开始。
- 坏文件 / 内存不足：失败，文案带文件名。
- Office 画不出来：失败，「无法转换此文档」。
- 自定义目录不可写：提示条，不入队。

## 测试

- `rootTabLabel` 五个文案与顺序。
- 源类型 → 目标列表：图片六项、PDF 四项、Office 仅 PDF。
- 同类型校验：两个 PDF 通过；PDF+docx 拒绝。
- 页范围夹紧；历史 `HistorySegment.Document` 只含文档任务。
- 多输出命名：`原名-001` 起。
- PdfBox：测试里生成带文字的小 PDF，拆分后仍是文字 PDF（不是整页位图）；抽 TXT 含原文；压缩后仍可选中文字。
- POI：一份标题+表格的 docx 与一份 xlsx 能出可打开的 PDF。
- 现有音视频向导、队列、输出测试继续通过。
- 真机冒烟：相册图转 WebP；PDF 转 JPG 多页进相册；PDF 拆分进文档；docx 转 PDF。完成后历史「文档」可见。

## 成功标准

侧载后能进「文档」并走完三步。历史能分三段看。转出的图片能在相册打开；PDF / TXT 能用系统应用打开。音视频路径不被这次改动打断。

## 明确不做

- PDF → Excel、PDF → PPT
- `.doc` / `.xls` / WPS、扫描件 OCR、PDF 密码
- HEIC/TIFF 作为输出；GIF 动画
- 多页打 zip、逐页勾选、Word/Excel 排版预览
- 桌面端文档 Tab、iOS
- 文档单独队列或单独前台服务
- 账号、联网
