# 轻转码 iOS 第 4 刀：局域网访问

叠在 [iOS 主规格](2026-09-18-ios-app-design.md) 已落地的三 Tab App 上，覆盖落地顺序第 4 项。电脑网页协议对齐现网 Android（[局域网媒体库四 Tab](2026-09-17-android-lan-library-tabs-design.md)），不改路径、口令查询、Range。

本刀**不做** Live Activity / `BGTaskScheduler`（第 3 刀）。不申请音频后台、不伪装成播放器保活。

## 目标与约束

- 「我的」可打开「局域网访问」：开关、口令、地址、复制。默认关闭。
- 打开开关时再请求系统「本地网络」；被拒则开关关回去，并提供「打开设置」。
- 仅当 App **在前台** 时监听 HTTP。进后台或被杀即停；设置里的开关可仍为开，回到前台再绑。
- 只绑 Wi‑Fi / 热点 IPv4，不绑蜂窝、不绑 loopback。首选端口 `17890`，被占用则向后试 10 次。
- 电脑浏览器打开地址后：四 Tab 成品库（视频 / 音频 / 图片 / 文档），口令 `?k=`，`GET/HEAD` `/` `/d/{id}` `/m/{id}`，支持 Range。
- 最低 iOS 18，只打 iPhone。不上 App Store。不引入第三方 HTTP 库。生产 UI 不用 hex；网页 CSS 可沿用 Android 现页（电脑页，不是 App 界面）。
- 触控 ≥ 44pt。不在启动时弹本地网络权限。

## 方案

纯 Domain 函数移植 Android `LanShare` / `LanLibrary` / `LanMedia` / `handleLanRequest`。Engine 用 `NWListener` 收 TCP、解析 HTTP/1.1、按 Domain 响应写回。UI 为「我的」push 页。`scenePhase != .active` 时停监听。

不采用：GCDWebServer / FlyingFox、Bonjour 广告（本刀只出 IPv4 地址）、IPv6、自定义端口 UI、HTTPS、后台保活。

## 模块边界

| 单元 | 做什么 | 怎么用 | 依赖 |
| --- | --- | --- | --- |
| `Domain/LanShare.swift` | 口令、路由、查询、端口、URL、HTTP 处理 | 纯函数 | Job |
| `Domain/LanLibrary.swift` | 四 Tab 条目、默认 Tab | 渲染 HTML | Job、LanMedia |
| `Domain/LanMedia.swift` | 预览类型、Content-Type、Range | 下载/预览 | Job |
| `Domain/LanHistoryPage.swift` | `renderLanHistoryHtml` | Home 200 | 上三者 |
| `LanShareStore` | `enabled` + `token` | UserDefaults | Domain 设置结构 |
| `LanShareServer` | `NWListener` + 写文件 Range | App 前台且开关开 | Domain、Jobs |
| `LanShareView` | 开关、口令、地址、复制、权限说明 | 我的 push | Store、Server |

## 导航

「我的」第一组：局域网访问、语言。局域网是 `MinePage.lan`，push，返回标签「我的」。

局域网页：分组列表。开关行、口令文本框、地址（可点复制）、未开或未连 Wi‑Fi 时说明。被拒本地网络：开关关、说明 +「打开设置」。

## 生命周期

- `enabled == true` 且 `scenePhase == .active`：选 Wi‑Fi IPv4、选端口、开始 `NWListener`。
- 后台 / 非 active：停监听，不清 `enabled`。
- 关掉开关：停监听并持久化 `enabled = false`。
- 无 Wi‑Fi IPv4：开关可开，地址处显示错误，不听端口。

## 权限

`NSLocalNetworkUsageDescription`。打开开关时用一次本地 `NWConnection` 或绑定触发系统对话框。被拒：`enabled = false`。

## 明确不做

- Live Activity、音频后台、开机自启、IPv6、HTTPS、账号
- 改电脑网页路径或四 Tab 语义
- 为局域网再转码、网页删除/重命名
- App Store Bonjour 用途声明以外的多余服务类型（本刀不广播 `_http._tcp`）
