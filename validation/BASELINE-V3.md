# OpenLeaf v3 全域 UI · G1 可重复验收基线（BASE-01）

> T-A 阶段冻结的 0.64/v3 基线口径。本文件**只记录**，不是发版说明；本轮未改
> `build.gradle` 版本号、未构建 APK、未安装设备、未跑真机。后续 T-B/T-C/T-E 取证一律引用本文件。
>
> **重要：`validation/BASELINE-0.62.md` 是 0.61/0.62 口径（HEAD `12aaed2`、vc73），
> 不能当 0.64/v3 基线使用。** 它的设备与夹具结论可作历史参考，但身份、APK、core 计数与
> 令牌口径均已过期。

## 1. 基线身份（0.64.0 / vc76）

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | `C:/Users/欧阳/Documents/deepseek-harness/default-workspace/openleaf-qa-c0b6e3` | 本轮工作目录 |
| 基线分支 / HEAD | `main@9952e616a53cdf9ea7b0bed64fbaac673743f8d4`（`9952e61`） | `git rev-parse HEAD`，开工前工作树干净 |
| T-A 工作分支 | `ui-v3-redesign`（从上述 main 新建） | `git checkout -b ui-v3-redesign` |
| 版本 | `versionCode 76` / `versionName 0.64.0` | `app/build.gradle:42-43` |
| applicationId | `org.openleaf.reader` | `app/build.gradle:40` |
| debug authority | `org.openleaf.reader.debugfixtures`（`${applicationId}.debugfixtures`） | `app/src/debug/AndroidManifest.xml:6` |
| 参考 0.64 包 | `deliverables/openleaf-0.64.0-ui-complete.apk` | 已发布交付物（本轮未重建） |
| 参考包 SHA-256 | `9e1c2bf23ddc6960fdf051447d3214cbe20403775d9e148d2fecacaff630df0d` | 本机 `sha256sum` 实测 |
| 签名身份 | DN `CN=OpenLeaf Beta, OU=OpenLeaf Beta Testing, O=OpenLeaf, L=Unknown, ST=Unknown, C=CN` | `apksigner verify --print-certs`（build-tools 35.0.0） |
| 证书 SHA-256 指纹 | `aac2f3b58990fa71ab47b1464847394e8e15a32c8f74902427a2e29e77f3ddf9` | 同上 |
| 夹具清单 | `validation/fixtures/MANIFEST.sha256`，**18 条目** | `wc -l` 实测 |
| 夹具清单 SHA-256 | `0fb26a401c1a3526e0c0f791c802a52c5e6f158a72b4c70587af4d3b52fa1072` | 本机 `sha256sum` 实测 |

## 2. 采集条件（设备 / 字号 / 主题 / 文件 / 页锚）

后续每个验收记录必须固定以下口径；基线候选成对采集，缺任一项标"待验"，不得用原型或构建成功替代。

| 维度 | 冻结口径 | 说明 |
|---|---|---|
| 设备 | MuMu `127.0.0.1:16384`（Android 12 横屏）；Android 17 真机 `25102RKBEC`（竖屏） | 仅**指定**；本轮未连设备、未跑仪器测试。验收人与档期待 team-lead 指定（Q8/G4） |
| 设备纪律 | 只对明确序列号执行 ADB；标准包与测试包分开；不清理标准包数据 | 沿用 0.62 底座纪律 |
| 字号倍率 | 系统字体倍率 `1.0 / 1.3 / 1.5` 三档 | tab 单行 12 组断言（4 主题 × 3 倍率）在 T-B3 取证；遵循系统倍率 |
| 主题 | `0 纸白 / 1 暖纸 / 2 夜间 / 3 AMOLED 纯黑` | v3 chrome 四档恒浅；正文纸/墨由 `ThemePalette.resolve(theme, customBg, customInk)` 提供；不启用全局深色探索态 |
| 页面尺寸 | 同设备、同方向、同窗口与同动画开关成对采集 | 像素对照必须固定分辨率与方向 |
| 文件 / 内容 | `validation/fixtures/` 18 条目夹具 + 固定书名/章节/编码；不改写夹具 | 配对基线/候选必须用同一份夹具；动画与滚动位置归零后采集 |
| 页锚 | 以夹具中的唯一标记定位：首末可见行、内容范围、锚点三件 | 无操作开合工具/目录/面板时三者必须一致；主动应用字号/字体允许重排，但仍对应同一原文锚点 |

## 3. `differingPixels=0` 的适用范围（重要边界）

- **适用**：正文区（EPUB/TXT/MOBI/漫画图像区）的像素基线。无操作开合工具栏、目录、设置面板或
  工具显隐时，正文区 `differingPixels=0`；首末可见行、内容范围与原文锚点不变。
- **不适用**：chrome 可变——顶/底工具栏、页脚色带、状态栏/导航栏、背景底色、按钮/图标/圆角、
  tab 选中态、弹层与空态，均按 v3 令牌重绘，**允许且预期变化**，不计入 `differingPixels`。
- 工具显隐只切 `setVisibility`/alpha，不改正文宽高、页边距、页锚与翻页动画（规格 §3.3/§6）。
- 主动应用字号/字体属于用户主动重排：不要求 `differingPixels=0`，但必须能定位到同一原文锚点。

## 4. 主导航与入口登记（T-A 盘点，结构不改）

5 项主导航逐字保留（`MainActivity.java:332-346`）：`▤ 书库` / `◎ 发现` / `✧ 想法` / `▦ 记录` / `⚙ 关于`。
本轮只修标签 12sp 单行（`setSingleLine(true)` / `setMaxLines(1)` / `ellipsize(END)`），
路由、contentDescription、返回栈与选中逻辑全部不动；**不新增"媒体" tab、不改成三段式 IA**。

页面入口归属（规格 §4.1–4.9）：书库、导入（B）；阅读/EPUB、排版/外观设置、漫画、管理/统计/想法/本地文件/Legado（C）；
音频/TTS、视频（E）。管理归属按架构 §8 Q1 建议归 C4，独立提交回退。

## 5. T-A 已冻结的令牌门禁（供各期复用）

- 单一真源 `app/src/main/java/org/openleaf/reader/core/UiTokens.java`：11 角色 × 4 主题查表，
  字级/圆角/间距/热力 alpha；paper/paperInk 委托 `ThemePalette.resolve()`，对比度委托 `ThemePalette.ratio()`，
  theme 入口一律 `ThemePalette.clamp()`。
- 实测对比度：ink/bg 11.34 · ink/surface 12.29 · muted/surface 4.88 PASS · **muted/soft 4.25 FAIL** ·
  accent/surface 7.50 · on-accent/accent 7.57 · accent/accent-soft 6.37 · line/surface 1.38（仅装饰）·
  danger/surface 5.59 · danger/bg 5.16 · on-danger/danger 5.64。
- `muted/bg` 实测 **4.499:1**，是 WCAG AA 4.5 的 0.001 近失；`canPlaceSecondaryOn()` 采用严格
  ratio≥4.5 门禁，SURFACE=true、SOFT=false、BACKGROUND=false。次级文字只能放 SURFACE 或改用 ink。
- 门禁实测：`validation/run-coretests.ps1` **PASS=90 / FAIL=0 / TOTAL=90**（原 89 + 新 `UiTokensTest` 8 checks）；
  main+debug 全量 javac `JAVAC_EXIT=0`。
- A11Y 锚点：`Ui.MUTED=0xFF706A65` / `Ui.PANEL=0xFFF2F1EE` 一行未改，`Ui.button` 原签名未改，
  T-A 未触碰 `Ui.java`；48dp 命中与焦点回退基线保持。

## 6. 验收 ID 与阶段归属

| ID | 阶段 | 用途 | 本轮状态 |
|---|---|---|---|
| BASE-01 | T-A | 同 APK/偏好/夹具/设备/截图口径冻结 | ✅ 本文件 |
| BUILD-01 | T-A 起每期 | core runner 全绿 + `UiTokensTest` 纳入 + javac/lint 0 error | ✅ T-A 已过（90/90、javac 0） |
| A11Y-01 | T-A 起每期 | 锚点常量 diff 为零、48dp、tab 4 主题 × 3 倍率 12 组单行 | ⏳ 锚点 diff ✅；tab 12 组 T-B 取证 |
| LIB-01 | T-B | 书库续读卡/搜索/三态空态/筛选摘要/封面墙 | ⏳ 待 T-B |
| IMP-01 / IMP-02 | T-B | 导入 N/M/K/H 计数、提交确认与四类结果文案 | ⏳ 待 T-B |
| NAV-01 | T-B | 仅 5 项 tab 单行与路由/desc 不变（非导航迁移） | ⏳ 待 T-B |
| SET-01 | T-C | 四组设置状态、七项即时文案不回归 | ⏳ 待 T-C |
| READ-01 | T-C | 阅读工具层级、页脚单口径、正文像素基线 | ⏳ 待 T-C |
| ANCHOR-01 | T-C | 开合面板无操作原文锚点一致 | ⏳ 待 T-C |
| TOC-01 | T-C | 目录面板视觉层级（搜索/当前章/空态） | ⏳ 待 T-C |
| COMIC-01 | T-C | 漫画页码、方向/布局三态、首末页禁用 | ⏳ 待 T-C |
| AUDIO-01 / AUDIO-02 | T-E | 播控单焦点、停止/暂停语义、会话后台 | ⏳ 待 T-E |
| VIDEO-01 | T-E | 一条控制 + 音轨字幕面板、取消不重绑 | ⏳ 待 T-E |

## 7. 续读主卡权威口径（G2 已定稿，2026-11-28）

研究全文：`deliverables/v3-续读数据源-研究.md`（260 行）。**A 阶段已确定，无需真机、无需改 DB、无需新数据源。**

- **唯一权威**：SharedPreferences `"reader"` 中按 `book.id`(UUID) 存的 `opened_` + `progress_/anchor_/epubCfi_/pdf_page_`。
  - `BookStore` 只做守门：书存在 + 本地 `isFile/canRead` 或 SAF `requirePersistentRead` + 内容 SHA == `books.sha256`；
  - `ReadingLedger` / `reading_sessions` 只记时长，**不作续读权威**；
  - `ComicProgressStore` 是按内容指纹的独立 SP 例外；本地音频无逐文件位置，不纳入续读卡；TTS 按文本口径。
- **"可靠记录"三条全满足才显示续读卡**（否则**不显示**，绝不显示"点开报错"的卡）：
  1. 最近打开 `opened_<id> > 0`（排序：opened_ 降序、并列 id 升序，取 1 条）；
  2. 该格式进度键严格有效：TXT `progress_>0` 且锚点可解析 / EPUB spine>0 或 CFI 可解析 / PDF `0<=page<count` / 漫画 `page>0`；
  3. 书文件可读（BookStore.open() 口径，含 SAF 授权与 SHA 校验）。
- **现状根因**：main 已有"上次阅读"卡（`MainActivity.java:780,818-839`），显示条件只有 `opened_>0`（`:709,717`），不校验进度/文件——这是"看着有进度但打不开"的来源。v3 在既有路径加闸门，**不另起数据源**；`savedPosition()`（`:841-859`）按格式取位置文案可复用（comic 故意返回 ""）。
- **降级清单**（全部不显示卡）：旧备份无 FILE 段书、SAF 授权失效、原文件 SHA 变化、CFI 越界、漫画指纹失配、`progress_==0`。位置文案只展示可核对真实值，不展示 0%/估算/章节标题推定值。
- **T-B 实现建议**：`MainActivity.renderShelf()`（:664-758）沿用 :702-719 取 latest，只对这一本做可靠判定；建议新增纯 JVM `core/ResolvedContinueReading.java`（值对象 + `isReliable()` / `positionText()`，无 Android import）+ core-tests 单测钉死阈值，或放 MainActivity 私有静态方法——口径以本文件为准。不改 DB/扫描/授权/身份规则。

## 7b. 待确认项（不编造）

- Q8（验收人与设备档期）、G3（tab 12 组断言脚本）、G4（取证模板）由 team-lead 在 T-B 前指定。

## 8. 回退边界

T-A 只新增 `UiTokens.java`、`core-tests/UiTokensTest.java`、12 个 `res/drawable/ic_v3_*.xml` 与本文件；
`Ui.java` 与所有 Activity 未改。删除这些新增文件即回到 `main@9952e61` 行为。
