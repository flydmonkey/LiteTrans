# Android 多语言（跟系统 + 设置可选）

Android 界面、通知、错误和局域网网页支持 **简体中文、繁體中文、English、日本語、한국어**。默认 **跟随系统**；系统语言对不上这五种时用 **英文**。用户可在「我的」里改语言并立刻生效。不改桌面端。

## 目标

- 五种语言齐全；应用名各语言均为 `LiteTrans`。
- 默认跟随系统。系统为 `zh-CN` / `zh-SG` → 简体；`zh-TW` / `zh-HK` / `zh-MO` → 繁体；`en*` → 英文；`ja*` → 日文；`ko*` → 韩文。其余（如法、德）→ 英文。
- 「我的」增加 **语言** 页：跟随系统、简体中文、繁體中文、English、日本語、한국어。选择会记住。
- 改语言后当前界面马上换成对应文案，无需重装。
- 转码服务通知、局域网 HTTP HTML 跟 **当前应用语言**（含跟随系统解析后的结果）走，不另做一套。

## 方案

用 Android 资源限定符，不自建翻译表、不做第三方 i18n 库。

```
values/            English（默认，对不上时的回退）
values-zh-rCN/     简体
values-zh-rTW/     繁体
values-zh-rHK/     与 TW 相同文案
values-ja/         日文
values-ko/         韩文
```

**不要**使用笼统的 `values-zh/`：否则 `zh-TW` 会先落到简体而不是英文。繁体必须靠 `zh-rTW` / `zh-rHK`。

Compose 用 `stringResource`。Service / 纯 Kotlin 用 `context.getString`。局域网 HTML 渲染改为接收已翻译好的文案或 `Resources`，禁止再写死中文。

应用语言覆盖用 AndroidX `AppCompatDelegate.setApplicationLocales`（minSdk 29）。不改系统设置。

不采用：仅跟系统不能选手动、Kotlin `when (locale)` 大表、桌面同步、RTL。

## 语言选择

```
enum class AppLanguage {
    System,   // 跟随系统，默认
    ZhCn,     // zh-CN
    ZhTw,     // zh-TW
    En,       // en
    Ja,       // ja
    Ko,       // ko
}
```

- `System`：`setApplicationLocales(LocaleListCompat.getEmptyLocaleList())`，让资源按系统解析，未匹配则 `values/` 英文。
- 其余：写入对应 BCP 47 标签并持久化（AppCompat 已存 per-app locales；再加一份本应用设置仅当需要在选中项上显示勾选）。
- 切换后 `Activity.recreate()`（或等价立刻重组），语言页仍打开在「语言」，勾在新选项上。

列表项标题用 **目标语言自己的名称**（English / 简体中文 / 繁體中文 / 日本語 / 한국어），「跟随系统」用当前界面语言翻译。

## 「我的」界面

根列表顺序：

1. 局域网访问  
2. **语言**  
3. 隐私协议  
4. 使用条款  
5. 关于  

`MinePage.Language`。语言页是单选列表，不是开关。返回逻辑与隐私页相同。

## 文案范围

必须进 `strings.xml` 的包括但不限于：

- 底栏五 Tab、向导三步、格式/存放/历史/空态  
- 历史操作（打开、分享、重命名、删除、取消、再试一次）  
- 「我的」各入口与法律/关于正文  
- 局域网访问页、通知「局域网访问已开启」/「关闭」、网页分段与下载、401「需要正确口令」  
- 转码通知与中断/失败提示  

预设名里的格式代号（MP4、H.264）可保留拉丁文；说明短句要翻译。

翻译要求：意思对、语气接近现有中文产品口吻。不要求母语级润色。繁体用台湾用词即可（檔案、資料夾、轉檔），港澳目录与台湾共用同一套。

## 测试

纯函数 / 资源解析，不测文学质量：

- 未设覆盖时，`fr` / `de` 解析到英文默认包。  
- `zh-CN` → 简体 key；`zh-TW` → 繁体 key。  
- `AppLanguage` 与 locale 标签互转（含 System → empty list）。  
- `mineItems` 含 Language，且顺序符合上表。  
- 现有 JVM 单测改为读测试用英文或注入字符串，避免再断言写死中文（断言 `R.string.*` 的 id 或通过 `ApplicationProvider` 取默认 `values/` 英文）。迁移时允许测试语言为英文默认。

## 范围外

桌面端、更多语言、按地区细分日韩、运行中热切换不 recreate 的动画、用户贡献翻译流程。
