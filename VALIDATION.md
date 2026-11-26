# 验证记录

日期：2026-11-19～2026-11-20（Asia/Shanghai）。此记录区分已执行检查与尚未执行验证。

## 0.6.0 书内全文查找验证（MuMu，2026-11-20）

- 最终 `assembleDebug lintDebug`：**BUILD SUCCESSFUL**，22秒，45个任务（13执行 / 32已是最新），Lint **0 errors / 29 warnings**；APK v2 签名校验通过。
- 纯Java `BookSearchTest`：**PASS: 12 book search checks**，覆盖中文原文偏移、大小写及Unicode索引不变、字面元字符 / 空格、重叠匹配、当前章范围、不跨章拼接、上限精确边界、代理对上下文、换行、空词、参数校验、取消和结果不可变。
- 最终安装包在MuMu Android15/API35执行 **PASS: 34 bulk integration checks**；原有扫描、1050本入库、归档、自主整理、升级与删除保护继续通过。所有真实删除仍仅使用应用自建合成样本，不读取个人书籍。
- 用原创welcome示例书执行搜索界面验收：搜索 `TXT` 全书找到 **2处 / 2章**；章节可折叠展开；点击第一章上下文返回正文显示 **1 / 2**，下一处定位第二章显示 **2 / 2**；重新打开列表选择“当前章节”后显示 **1处 / 1章**；不存在的词显示无匹配；返回阅读页清除搜索后保留所在第二章。
- 匹配卡片和正文高亮由Spannable文本范围实现；交互证据来自控件层级、章节标签与匹配计数，不声称已完成所有图形渲染、大字体、横屏回收或长书压力验收。
- 书内查找仅支持TXT/EPUB/MD的已解析文字，最多10000处、关键词最多256 UTF-16代码单元；当前不含PDF文本搜索/OCR。解析后的整书仍在内存，搜索页与阅读页可能各持内容，大书内存优化与分章缓存仍待实现。
- 测试脚本已改为默认安装最新构建APK并打印SHA256，避免固定旧交付包：最终构建 / MuMu安装SHA256 `27cb7a496505e4cf73794994fd6d14b16f0881db3204a1e0d9667807d0c19c26`。
- 新增参考项目笔记已写入docs/research/additional-readers-2026-11-20.md；Ciallo两小源文件机制已核验，DZMeBookRead分页源码因官方接口限流未完成读取，明确区分已核实资料与候选借鉴。

## 导入设置页验证（MuMu，2026-11-20）

- MuMu Android 15 / API 35 实例中确认导入设置页显示：文件夹、EPUB / PDF / TXT-MD、文件大小上限、**直接引用原文件（不复制到书库）**、自动导入新书、从子文件夹创建分组。
- 上述记录写于「复制到书库」仍固定开启的旧版。0.10.2 起该项已改为「直接引用原文件」并固定禁用勾选：导入只登记原文件 URI，阅读时直接读取源文件；SHA256 去重、进度与删除前校验仍基于原文件内容，旧版副本继续可读。自动导入偏好仅保存入口，仍需手动扫描，没有后台监视。
- 后续补入「导入确认」层：点击导入按钮后先显示选中数量与预计大小，并可单独勾选「同时导入目录分组信息」；确认值覆盖扫描时快照并回写为默认值。设置页的原开关改名为「新导入默认从子文件夹创建分组」，扫描结果标题同步显示为 `扫描结果（N）`。
- 扫描器和单文件格式识别均增加 `.md`，归入 TXT 解析路径；当时 **34 项 MuMu 回归全部通过**，0.10.2 追加的引用导入 / 缓存 / 元数据 / 书评 / 媒体源回归见本文件下方与 `validation/` 日志。
- 最终 MuMu 脚本：`.tools/run-mumu-tests.ps1 -VmIndex 2 -Install`，不选择或操作真实手机；测试说明见 [TESTING.md](TESTING.md)。



- 按用户要求，后续测试默认使用本机 MuMu，不再操作真实手机。复用已运行的实例 2（Android 15 / API 35），通过 MuMu CLI 查询得到本地端口 `127.0.0.1:16448`。
- 仅向该模拟器安装现有 0.5.0 APK，安装成功，MainActivity 冷启动成功。
- **PASS: 34 bulk integration checks**，包含1050份合成文件实际入库、目录归档、手动整理、数据库升级与删除保护；所有测试使用隔离上下文。
- 基础界面层级检查通过：书架、整理、全部书籍、新建文件夹、扫描文件夹及排序控件可见，应用进程存活。本次没有操作真实手机，也没有下载或创建替代模拟器。
- 工作区新增 `.tools/run-mumu-tests.ps1`，动态查询MuMu实例并显式指定本地目标；旧测试脚本转发到MuMu入口，移除USB手机目标。测试输出必须包含34项通过标志，不能仅依赖Instrumentation命令退出码。
- `uiautomator dump` 本次虽生成有效XML仍返回非零，已成功拉取并解析界面文件；不把该工具退出状态误判为应用崩溃。更多测试方式见 [模拟器测试说明](TESTING.md)。

## 0.5.0 自主整理书架验证（2026-11-20）

- `assembleDebug lintDebug`：**BUILD SUCCESSFUL**，23 秒，45 个任务（19 执行 / 26 已是最新），Lint **0 errors / 24 warnings**；未禁用错误检查。APK v2 签名通过。
- **PASS: 34 bulk integration checks**。新增八项：自建嵌套文件夹与名称限制、来源显示名重命名后重扫保留、批量归档 / 当前目录移动保留其他关联和进度、全局移动替换关联、移出后书籍回未分类、删除分类树保留书籍 / 来源 / 进度、无效批次事务回滚、SQLite v3→v4 升级保留全部记录。此前批量入库、归档、原文件删除保护的26项继续通过。
- 新装及数据库 v1 / v2 / v3 升级均由回归测试覆盖；schema v4 仅增加 folders.display_name，原来源目录的身份和路径保留，重扫只更新来源名，不覆盖自定义显示名。
- 实际手机 UI 验证：点击“整理”进入多选，选择当前页 **40 本**，打开“归档到”目标列表与“新建文件夹”名称对话框后取消，返回并完成整理。前后用户藏书均为 **67 本**；未更改用户真实归档、书籍内容或原文件。
- UI 已提供新建文件夹、长按分类重命名 / 删除、归档到 / 移动到 / 移出文件夹、最近导入 / 书名 / 最近阅读排序；已选集合跨页和旋转保存，切换目录或搜索范围时清空并提示。最近阅读从本版开始记录书籍打开时间。
- 手机已安装 versionCode 5 / versionName 0.5.0，与交付 APK SHA256 一致：`090e6036e26bdd6a8136f1b5a66c2252a21f43d448a1617ec1fecd6079c8bf11`。
- 书籍批量整理使用数据库事务，只修改关联；删除文件夹递归清除子文件夹归档，书籍无剩余关联时出现在未分类。删除来源分类后再扫描来源，会重建该来源分类；尚无文件夹整体拖拽移动或自定义拖拽排序。
- 设备端已检查整理取消流程；多选运行中旋转 / 后台回收、大字体布局、各排序实际阅读历史的长期场景仍需专项交互验收。没有声称完成这些未执行测试。

## 0.4.0 书架删除验证（2026-11-20）

- `assembleDebug lintDebug`：**BUILD SUCCESSFUL**，退出码 0，20 秒，45 个任务（13 执行 / 32 已是最新）；Lint **0 errors / 20 warnings**。
- 包名不变，versionCode 4 / versionName 0.4.0；SQLite v1 / v2 均可升级到 v3，新增来源表，不清空书籍、目录、哈希或进度。
- **PASS: 26 bulk integration checks**。前 18 项批量入库和归档回归继续通过；新增 v2→v3 归档保留、副本删除保留原文件、核对后删除原文件再清理副本、拒绝已变化 / 错误来源、提供器拒绝时保留书架、只删除勾选来源、旧书手动补来源与 URI 别名去重、取消删除无副作用等八项。
- 真实删除操作仅作用于 debug DocumentsProvider 在应用缓存中创建的合成文件，路径经限定校验；没有对用户原文件执行删除测试。
- 调试提供器补充系统 DocumentsProvider 识别声明，仍受 MANAGE_DOCUMENTS 保护，且 queryRoots 返回空集合，不在用户文件选择器显示合成根目录；release 不包含该组件。
- 实际书架界面验证“删除”按钮可进入删除页；两种范围与“选择或重新授权原文件”均可见；默认未勾选原文件，原文件删除按钮禁用。打开仅删副本确认框后点击取消，前后藏书均为 **67 本**，未确认删除任何用户书籍。
- 手机实际安装包与交付 APK SHA256 一致：`c8ed95ddc1e5dc33394b9582d25841305c4d014e6d9b76afd9e068515fdaf594`；APK v2 签名校验通过。
- 原文件删除前重新比对内容与导入哈希；原文件已变化、缺失、不可读、没有删除权限或提供器拒绝时，不移除书架副本。多个来源发生部分失败时，已经成功删除的原文件不会回滚，失败和未选来源不自动重试。
- 原文件与应用数据库由不同组件管理，不能承诺跨文件提供器的原子删除；应用列明部分成功状态。更多第三方 / 云提供器权限行为、后台终止、横屏与大字体交互仍需专项验收。

## 0.3.0 来源目录归档验证（2026-11-20）

- `assembleDebug lintDebug`：**BUILD SUCCESSFUL**，退出码 0，22 秒，45 个任务（19 执行 / 26 已是最新）；Lint **0 errors / 18 warnings**，未关闭错误检查。
- 包名不变，versionCode 3 / versionName 0.3.0；SQLite schema 从 1 升级到 2，仅新增来源文件夹和书籍目录关联表，保留原书 ID、文件、哈希和阅读进度。
- **PASS: 18 bulk integration checks**。新增六项验证：多级目录创建与直接子层浏览；同内容跨目录共用副本与进度；同名不同来源不合并；数据库 v1 升级及旧书重新扫描补归档；相对路径穿越拒绝；取消及删除时目录关联一致。原有 1050 本扫描 / 入库、重复跳过、旧 JSON / 备份迁移等回归继续通过。
- 隔离测试不向真实书架添加测试书籍。设备界面核对原有 **71 本书**可在“未分类”与“全部书籍”访问；“未分类 → 返回上一级 → 全部书籍 → 来源文件夹”导航通过，应用正常运行。
- APK v2 签名有效；手机实际安装包与交付文件 SHA256 均为 `371b860d78fb8de1b7a7fa4b9bd2105b7e575234e5564068f7843958fc50864f`。
- 目录归档使用逻辑关联，不移动来源文件。旧版未记录来源目录，旧书默认未分类；需要重新扫描原目录并导入，借助内容去重补充目录关联，保留已有进度。空目录可保留，暂未提供手工移动 / 重命名归档目录。
- 当前目录视图显示直接书籍数量（标注“当前层”），不是递归总数；全局搜索与全部书籍不会因多个目录关联重复列出同一本书。
- 已检查数据库和导航代码变更，生成相对 0.2.0 的源码差异。专项视觉验收、不同设备的大字体 / 横屏 / 后台回收、云文档目录改名映射仍需继续验证。

## 0.2.0 文件夹批量导入验证（2026-11-20）

- 最终 `assembleDebug lintDebug`：**BUILD SUCCESSFUL**，退出码 0，45 个任务（12 执行 / 33 已是最新）；Lint **0 errors / 17 warnings**。警告包含版本升级建议、文案国际化及调试测试为确定性断言使用同步 SharedPreferences 提交。
- APK v2 签名校验通过；包名 `org.openleaf.reader`，versionCode 2、versionName 0.2.0，minSdk 26、target / compile SDK 35。
- 最初的 0.2.0 调试包因测试 DocumentsProvider 声明未导出，在组件加载阶段触发系统 SecurityException。已按照 DocumentsProvider 契约设置 exported 并用系统签名权限 MANAGE_DOCUMENTS 保护；release 不包含该调试组件。修复后已在原手机原地更新、启动成功，旧书架的示例书及 **36%** 进度保留。
- 原生 Android Instrumentation：**PASS: 12 bulk integration checks**。测试使用独立 Context、数据库、书籍目录和偏好名称，不向真实书架插入测试数据。
- 通过项：嵌套目录与三种格式、大小写扩展名、重复文档 ID 与目录环、不可读子目录、1050 个候选、10000 候选上限提示、128 层深度限制、扫描中取消、旧 JSON 迁移、仅 AtomicFile 备份恢复、坏索引拒绝、同内容异名去重且保留进度、取消 / 空文件清理，以及 **1050 本实际入库 + 30 次重复导入跳过**。
- 1050 本入库使用的是合成短 TXT 文件；该结果证明数量及事务 / 去重行为，不代表同数量大书的实际速度。
- UI 层级检查确认书架“扫描文件夹”入口和批量页的选择目录、格式筛选、全选、停止、导入按钮均出现；手机保持在新功能页面。未对个人目录进行自动扫描。
- 手机实际安装包与交付 APK 的 SHA256 均为 `50897f9d52abd1e2fcd376925ec3acbd7bb795448991ec637c25d67d5a1cf97f`。
- 尚需更多厂商系统文件选择器、云文档提供器、大型 EPUB / PDF、任务运行中旋转 / 后台回收等专项交互测试；进程被系统终止后不会自动恢复扫描会话。

以下保留 0.1.0 基线验证历史。

## 已执行

- Microsoft OpenJDK 17.0.21.1+2：`javac --release 17 -Xlint:all` 编译核心解析器与测试，无警告。
- `java -Xmx512m -cp core-tests/build BookParserTest`：**PASS: 37 tests**。
- 核心解析器额外使用 `javac --release 8 -Xlint:all` 编译通过，检查没有误用 Java 9+ 标准库 API。
- 37 组测试包括 UTF-8 / GB18030 / UTF-16、章节切分、EPUB spine 顺序、段落与实体、标准 DTD 本地处理、外部实体和自定义 DTD 拒绝、DRM、路径穿越、缺失 ZIP entry、伪造展开大小、实际展开预算与重复引用预算。
- 代码审查修复了阅读位置布局时机、AtomicFile 索引恢复、跨 Activity 实例导入事务锁、无 metadata 书名时显示 UUID、网页失败后刷新错页及 WebView 渲染进程退出处理。
- Gradle Wrapper 由 Gradle 8.13 生成，分发包 SHA256 固定在 wrapper 配置中。

## Android 构建

- 使用 Gradle 8.13、AGP 8.13.2、Android SDK Platform 35、Build Tools 35.0.0，执行 `assembleDebug lintDebug`：**BUILD SUCCESSFUL**，45 个任务执行完成，退出码 0。
- 当前 Windows 工作区包含中文，实际构建额外使用 `-Pandroid.overridePathCheck=true --no-watch-fs`，JDK / SDK / Gradle 缓存和 Android 用户目录均定向工作区本地工具目录。
- 另以 JDK 17 对 SDK 35 的 `android.jar` 独立编译全部应用 Java 源码通过；有弃用 API 提示，无 Java 编译错误。
- 调试 APK 路径：`app/build/outputs/apk/debug/app-debug.apk`；Lint 报告位于 `app/build/reports/lint-results-debug.html`。
- Lint 无错误；保留了目标 SDK / 工具版本升级建议与中文文案国际化等非阻断警告，没有把警告全局关闭。
- 根据 Lint 结果补充了 Android 12+ 数据提取规则，显式排除云备份与设备迁移中的当前应用数据域，并保留旧版本的 `allowBackup=false`。
- 最终增量 `assembleDebug lintDebug` 再次通过：退出码 0，22 个任务执行、23 个任务已是最新，Lint 为 0 errors / 14 warnings。
- `apksigner verify --verbose` 退出码 0，APK v2 签名有效、1 个签名者；`aapt dump badging` 确认包名 `org.openleaf.reader`、版本 0.1.0、minSdk 26、target / compile SDK 35。
- 构建通过不等同于已经运行设备交互测试。

## 真机基础检查

- 已在连接的 Android 手机上由用户手动安装；设备 ADB 报告 Android 17 / API 37。USB 直装被手机系统返回 `INSTALL_FAILED_USER_RESTRICTED: Invalid apk`，流式与非流式均拒绝；同一 APK 传入 Download 后手动安装成功，传输前后 SHA256 一致。
- `am start -W` 成功，MainActivity 处于前台，进程保持运行；首页 UI 层级确认书架与内置示例正常出现。
- 通过 UI 自动化打开示例、切换至第一章、读取中文正文、滚动、返回书架，书架保存显示已读 36%。
- 冷启动测试发现：原实现可恢复章节，但章末进度从 36% 回落为 8%。已修复为同时持久化字符锚点、行内滚动比例与章末状态，并在布局后恢复位置。
- 2026-11-20 修复版真机回归通过：阅读页章末为 36%，返回书架仍为 36%；执行 `am force-stop` 后 `am start -W` 报告 COLD 启动，重新打开示例仍在“第一章 安静的一页 · 2 / 4”，进度保持 **36%**，应用进程存活。
- 手机实际安装 APK 的 SHA256 为 `ff8405c870bd00f2ee6e61c92bf45ba92b067df6bf4da3fab6de6c9a5a619ddf`，与交付修复版一致。
- 检查依据为 ADB 返回、进程状态和 UI 层级；已采集截图，但当前模型不支持图像输入，未声称完成像素级视觉验收。

## 未执行

- 尚未完成 Android 8 / 15、横屏、系统字体放大、文件提供器、极端 PDF 和长时间阅读的真机检查。
- TXT / EPUB 外部文件导入、PDF 页渲染及网络网页的设备交互流程尚未逐项验收；37 组解析器测试覆盖核心导入逻辑，但不替代系统文件选择器等集成检查。
- GitHub Actions 配置已提供，但尚未推送公共远程仓库或执行远程工作流。
- 尚未进行发布签名、商店审核、无障碍专项测试或完整第三方安全审计。

## 安装后的最小验收

1. 首次启动后打开内置示例，切换章节、字号和主题，返回书架再打开，确认恢复。
2. 分别导入一份自有 TXT、EPUB 和 PDF，检查标题、章节顺序、中文和 PDF 页码。
3. 阅读中旋转手机、切至后台和重新启动，确认进度没有跳回。
4. 导入第二本书后长按移除，确认原文件仍存在；批量导入损坏文件应显示错误而不破坏已有书架。
5. 用 HTTPS 静态页面检查打开、前后导航、刷新、缩放、收藏与移除收藏。
6. 开启飞行模式，确认本地书架与 TXT / EPUB / PDF 仍可使用。

## Readest 借鉴五项能力的验证（2026-11-22）

- 惰性章节源：新增 `core/LazyChapterBook`，打开时建立章节索引并把正文写入私有 UTF-16LE 缓存，阅读页与搜索页只保留当前及邻近章节；`core-tests` 的解析器 / 搜索 / 目录测试全部通过（JDK 27 下 XML 深度用例因 JDK 自身 100 层限制报错，原始代码同样失败，非本次回归）。
- 结构锚点与进度：`core/ReadingAnchor` 持久化章节键 + 源偏移，`core/ProgressModel` 按章节 UTF-8 字节加权计算全书百分比并支持比例反查；旧 `chapter_/offset_` 键继续镜像写入。
- 搜索模式：`core/BookSearch` 增加字面 / 正则 / 全词 / 附近词，命中携带逐段 `matchStarts/matchEnds`；MuMu 中以正则 `OpenLeaf` 命中 1 处。
- 持久标注：SQLite 升级到 v9 新增 `annotations` 表，纯文字阅读页支持选区高亮 / 笔记与位置书签；MuMu 中添加书签后强停重启仍显示。
- 封面与 XML 容错：EPUB 详情页按多级回退自动提取内嵌封面；XML 预处理补齐常见 void 元素并扩展命名实体白名单。无封面样本仍正确保留文字占位。

## 想法区与导入返回验证（2026-11-22）

- 数据库升级到 v10 新增 `ideas` 表，分享者署名随 `.olbook` schemaVersion 3 写入；导出前可填写署名，旧版 v1/v2 书包继续可读。
- `.olbook` 导入遇到不同书评时不再整本失败：外来书评写入想法区，本地书评、标签与归档继续合并。
- MuMu 验证：为 `FooterSynthetic0` 设置本地书评后，导入带分享者「张三」和不同书评的同内容书包，想法区显示 `张三 — FooterSynthetic0 — BBBB…`，格式符合要求。
- 批量导入完成后自动关闭导入页并返回书架，并显示「导入完成：已添加 X · 重复 Y · 失败 Z」提示；MuMu 中确认不再停留在扫描结果页。
- 书架侧栏与窄屏底部导航均新增「想法」入口，可查看、长按删除或清空想法。

## 阅读中编辑验证（2026-11-22）

- TXT / MD 阅读页新增「编辑」按钮，按书籍登记的源文件 URI 进入 `LocalTextEditorActivity`；多个来源时先选择文件。
- 编辑器新增「查找替换」（忽略大小写、全部替换）与「清理乱码」（U+FFFD、孤立控制字符、BOM、`ï¿½` 残留）。
- 成功保存（覆盖原文件或有写入授权）后自动回到阅读页并重新载入正文；无写入授权时仍可另存副本，覆盖按钮保持禁用。
- MuMu 验证：打开 `Reference-0101.txt` → 编辑 → 将 `OpenLeaf` 全部替换为 `EDITED`，提示替换 1 处；清理乱码提示「没有发现明显乱码字符」。

## 笔记导出与标注导入验证（2026-11-22）

- 阅读页「标注」面板新增「导出笔记（书评 / 标注 / 想法）」与「导入标注（JSON）」；`NotePackage` 定义 schemaVersion 1 的 JSON 格式，导出包含书评、标签、标注与想法，导入只消费标注，不覆盖本地书评。
- 导入校验：章节序号必须落在当前书范围内，起止偏移、文本长度与条目数均有限制，避免跨书错位。
- MuMu 验证：导入构造的 `import_notes.json` 后，标注面板出现「高亮 · 第一章」，标注成功入库。
- 导出文件在本机 MuMu 的 DownloadStorageProvider 下写入被拒（0 字节）；同一模拟器上现有的「另存副本」也出现相同拒绝，确认是 MuMu 提供器权限限制而非新功能逻辑错误。代码已带写入授权 flag 与错误日志，需在真机或其他文件提供器上复核写入。

## 多项目本地阅读借鉴复刻验证（2026-11-22）

功能设计借鉴 Koodo Reader / KOReader / LightRead / Anx Reader / legado（详见合规借鉴报告），均以 OpenLeaf 自有 Java 实现。`assembleDebug` + `lintDebug` 均通过；核心测试 BookSearch / EpubIllustrated / EpubToc / ImportValidation / TextChapterHeadings 全部 PASS（需指定可写 java.io.tmpdir）。

- 排版设置：阅读页工具栏新增「排版」，面板含行距（1.0–2.0 倍）、段距（0–20 sp）、字距（0–0.15 em）、页边距（12–64 dp）、对齐（左 / 两端 / 居中 / 右）、首行缩进（0–2 字）、简繁（原文 / 简→繁 / 繁→简）与重置，参数持久化。MuMu 确认面板 8 项齐全，切换简→繁后正文显示繁体、底层锚点不变。
- 简繁转换：单字映射表来自 foliate-js（MIT，1275 字等长表），仅作用于显示层，标注 / 搜索偏移按原文保持不变。
- 多色多样式标注：`annotations` 表 v11 增加 `style` / `updated_at` 列；支持高亮 / 下划线 / 删除线、5 色、按章 / 按时间排序、颜色筛选、改色 / 改样式 / 编辑笔记。MuMu 验证把一条高亮改为下划线后重新打开显示「下划线 · 第一章」，持久化生效。
- 阅读记录：新增 `reading_time` 表，有操作才计时并按 [3s, 300s] 截断；记录页展示今日 / 累计 / 连续天数、7 日柱状、12 周热力图与书籍榜。MuMu 验证阅读约 3 分钟后显示「累计 3 分钟 / 连续 1 天 / Reference-0101 3 分钟」。
- 自动翻页 / 音量键翻页：设置面板新增两开关，自动翻页 4/8/15/30/60 秒多档，任意触摸重置计时、onPause 取消；音量键拦截仅阅读页生效并吞掉系统音量条。MuMu 验证自动翻页切换到「4 秒」。
- 笔记导出扩展：新增 Markdown / TXT / CSV（GBK 兼容 Excel）三种格式，MuMu 验证格式选择菜单四项齐全；CSV 转义与 GBK 编码经独立单元验证。

## 想法窗口美化（2026-11-22）

- 顶部栏由三个等宽大按钮改为白底轻量布局：小圆角「‹ 返回」「清空」文字按钮 + 居中标题 + 细分隔线；清空按钮在无数据时隐藏。
- 列表项改为分层卡片：圆形首字头像 + 分享者 + 时间（IdeaStore/Idea 增加 createdAt）、浅绿书名标签、独立书评正文（最多 4 行）与「展开全部 / 收起」；副标题显示条数与长按提示；空状态改为居中文案引导。
- MuMu 验证：想法页显示为卡片结构，头像「张」、分享者「张三」、时间「11-22 11:19」、书名标签、书评与展开链接均正确；点击「展开全部」切换为「收起」；清空按钮弹出确认对话框（已取消，保留测试数据）。

## 架构路线图首个开发切片（2026-11-22）

按 `ARCHITECTURE-ROADMAP.zh-CN.md` 的"首个开发切片"落地，未做全应用迁移：
- 纯 JVM 契约：`ContentContracts`（AssetGateway/AssetHandle/Revision/EditOutcome/Job/JobRepository + SHA-256 hex 工具）与 `ImportUseCase`（枚举→校验→入库编排、逐项检查点续跑），均在 core 包、无 Android 依赖。
- 类型化编辑回传：`LocalTextEditorActivity` 覆盖成功回传 `covered + sha256 hex + size + uri`，另存回传 `saved_copy`，冲突回传 `conflict`；`ReaderActivity.handleEditOutcome` 对 covered 调 `RevisionStore.register` 登记新版本并同步 `books.sha256`，修复"覆盖后重载被哈希校验拒绝"的链路断点；另存副本不改当前书。
- 可恢复导入检查点：`jobs` / `job_items` 表（DB v12），`SqliteJobRepository` 实现 JobRepository；`FolderImportActivity.startImport` 创建 job 并逐项写 DONE/DUPLICATE/FAILED 与计数，任务状态 RUNNING/PAUSED/DONE/CANCELLED。
- 测试夹具：`ImportResumeTest` 6 项（SHA-256 稳定、Revision 按内容比较、类型化 EditOutcome、导入成功、中断续跑不重复入库、重复/失败分类）全部 PASS。
- 回归：6 组核心测试全 PASS（BookSearch 12 / EpubIllustrated 14 / EpubToc 3 / ImportValidation 126 / TextChapterHeadings 95 / ImportResume 6）；`assembleDebug` + `lintDebug` BUILD SUCCESSFUL。
- MuMu 验证：v11→v12 迁移后应用正常启动；批量导入 3 项后查询 `jobs` 得 `folder-...|folder|DONE|3|0|3|0`，`job_items` 三项均 DUPLICATE 且绑定 book_id，检查点落库正确。覆盖写分支因 MuMu SAF 提供器无写权限未实测，已由纯 JVM 测试覆盖类型化回传与版本登记。

## 架构路线图地基补齐（2026-11-22）

- pending_write 中断修复：新增 `core/PendingWriteReconciler`（纯 JVM 状态机：COMMIT_VERSION / NOT_WRITTEN / CONFLICT / INVALID / NOTHING）与 `PendingWriteStore`（DB v13 表 pending_writes）。`LocalTextEditorActivity.save()` 写前 PREPARED、成功 COMMITTED、另存即 ACK、失败 FAILED/CONFLICT；`ReaderActivity` 版本登记成功后 ACK；`MainActivity.onCreate` 后台线程冷启动读取未决写入并重读目标指纹收敛（授权失效则保留记录）。
- 下载接入任务模型：新增 `core/DownloadPolicy`（配额/Range/文件名安全/哈希校验纯函数）、`core/DownloadUseCase`（复用 JobRepository 检查点、失败项可重试、已 DONE 跳过）、`AssetDownloader`（HTTPS 策略复用 CatalogClient.checkedUrl、临时 .part + 校验后 rename 提交、重定向≤5、大小上限）。CatalogActivity 的 OPDS 获取链接从"仅浏览器打开"增加"下载到本地缓存"，走 DownloadUseCase + Job 记录。
- 包边界切分第一批：新增 `application/ServiceRegistry` 手工注入容器（jobs/pendingWrites/revisions），MainActivity/ReaderActivity/FolderImportActivity/CatalogActivity 改为从 registry 取而不再直接 new；被注入的具体类与构造器 public 化，字段类型改为 ContentContracts.JobRepository 接口。未做 49 文件的物理搬家（风险/收益比差，且无 git rename 辅助）。
- 测试：`ArchitectureFollowupTest` 8 项（pending 收敛四态、配额、Range、文件名安全、下载任务分类与续跑）全 PASS；7 组核心测试共 354 项全 PASS；`assembleDebug` + `lintDebug` BUILD SUCCESSFUL。
- MuMu：v12→v13 迁移后 user_version=13、pending_writes 表可用（0 条未决写入，符合预期）。下载到真实 OPDS 源未实测（本机无稳定测试源），核心策略与任务模型由纯 JVM 测试覆盖。

## 导入大小上限提高至 512 MiB（2026-11-22）

- 统一上限来源：`BookParser.MAX_IMPORT_BYTES` 80→512 MiB，`MAX_FILE`/`importLimit`/`TaggedBookPackage`/`AssetDownloader`/`CatalogClient`/`DirectoryScanner`/`FolderImportActivity` 均改为引用该常量；错误文案动态拼接 MiB 值。
- EPUB 配套：全展开预算 80→512 MiB，重复引用预算拆为独立常量 512 MiB，单项 8→24 MiB，ZIP 条目 10000→30000，章节 2000→5000；TXT/MD 20→512 MiB，导入校验 >32 MiB 自动切流式逐行校验（有界章节窗口，复用 countTextChunks 边界规则），避免大文本整本解码 OOM；TXT / MD 打开亦走流式索引，无全本建索引峰值。
- 防 OOM：`validateForImport` 的 EPUB 分支改为轻量 `validateEpubMeta`（Archive 流式安全校验 + container/OPF 元数据 + spine 完整性），不再为取标题构建整本 `BookContent`；`LazyChapterBook.open()` 对 TXT / MD / EPUB 已走流式索引（`TextChapterIndexer` / `EpubChapterIndexer`，无全本建索引峰值；`-Xmx64m` + 30 spine 大书 old OOM / new PASS 已实测）；单个接近 24 MiB 的超大 XHTML 条目仍有单章瞬态峰值（`-Xmx64m` 下 new 也可能 OOM，需 `-Xmx192m`，二期改 `HtmlHandler` 字符级流式才能覆盖）；MOBI / AZW3 打开也走流式章节索引（`MobiChapterIndexer` + decoder.stream 受控迭代，Java 侧无全本章节聚合峰值；`-Xmx64m` + 48 段大书 old OOM / new PASS 已实测），但 native `ensure_parsed()` 一次性 strdup 全部 segments（≤64MiB）与单段 jstring（≤16MiB→Java String ≤32MiB）的瞬态峰值 Java 侧无法消除，改 `mobi_bridge.c` 并重编 `.so` 列二期；已在文档标注。
- 测试：BookParserTest 39 项全 PASS（总展开/重复引用用例改为预算常量自洽验证，避免 512MiB 测试数据）；7 组核心测试全 PASS；`assembleDebug` + `lintDebug` BUILD SUCCESSFUL。
- MuMu：安装新包后通过「添加文件」选择 100 MiB 稀疏 EPUB，导入结果未再出现旧的"80MB 上限"拒绝（实际因测试文件时授权未授予而失败，与大小无关），证明 80 MiB 闸口已解除。

### 追加：TXT/MD 导入上限提升至 512 MiB（用户要求「TXT 格式导入上限也提高」）
- 常量：`MAX_TXT_BYTES` 20→512 MiB，与 `MAX_IMPORT_BYTES` 对齐；所有 "20MB" 硬编码文案改为动态 `mib()` 拼接；`BookStore`/`TaggedBookPackage` 等引用方自动跟随。
- 流式校验：`validateForImport` 的 TXT/MD 分支在 >32 MiB 时走 `validateTextStream`（逐块解码、有界行缓冲、有界章节窗口），不保留整本解码文本；小文件沿用原精确校验路径，保证与 `parse()` 接受性/文案 parity。章节切分与上限判定复用同一套 `countTextChunks`/`countOneChunk`，`ChunkCounter` 增量结算避免重复解码。
- 健壮性：行缓冲设 `MAX_CHAPTER_CHARS` 上限，超长无换行文本（如整文件空格）直接流式入窗，避免单行触发 OOM；BOM/GB18030 回退、CRLF 归一化、fence 代码块状态与既有路径一致。
- 验证：ImportValidationTest 126 项 parity 全 PASS；33 MiB 多行文本在 -Xmx64m 下流式校验通过；34 MiB 正常文本接受、34 MiB 超长无换行空白文本正确拒绝；`assembleDebug` + `lintDebug` BUILD SUCCESSFUL（JDK 17 / Gradle 8.13，45 任务）。JDK 27 下 BookParserTest 的 XML 深度用例因 JDK 自身 100 层限制报错，与本改动无关。
