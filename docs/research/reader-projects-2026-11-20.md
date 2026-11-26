# 五个开源阅读器的学习笔记与青简改进建议

核验日期：2026-11-20。目标项目：青简 OpenLeaf 0.5.0，Android 原生 Java，SQLite 本地书架，当前默认用 MuMu 测试。

## 研究方式与边界

本次读取官方仓库的说明、许可证、构建配置和部分关键源文件，不安装或执行这些项目。以下“已确认”指文档或源码中可定位的事实，不代表已经完成它们的真机体验、性能评测或全面代码审计。星数、平台数量和截图观感不作为架构结论。

公开仓库会变化，关键结论尽量引用固定提交。没有把这些项目的源码或依赖合入青简，也没有改变青简现有 MIT 许可证。本研究未修改青简应用代码，只产生研究笔记和候选计划。

## 先确认一个重要变化：Legado 当前官方仓库已无应用源码

核验到 `gedoor/legado` 当前默认分支为 main，提交 `9bb0569269ed557753fc3866250970de6d454fcf`。该提交根目录仅包含 README 公告与公告图片，没有原 Android 工程和 LICENSE 文件。

因此，本次不能从这个现状确认其旧版书架 DAO、导入、缓存、排版或许可证条款，也不能把历史印象当作现行源码结论。不改用未核验的第三方镜像来替代官方项目。

证据：[当前固定提交目录](https://github.com/gedoor/legado/tree/9bb0569269ed557753fc3866250970de6d454fcf)、[README 公告](https://github.com/gedoor/legado/blob/9bb0569269ed557753fc3866250970de6d454fcf/README.md)。

## Koodo Reader：学习元数据与阅读定位模型

核验快照：dev，`f756853555c8e2f1a5fd880ebb5681e5a022e1d7`。

### 已确认

- Book 模型包含标题、作者、描述、格式、尺寸、路径、字符集、语言、标识符、ISBN、版式等字段；这些字段没有和某一个阅读页面绑定。
- BookLocation 不只是一个百分比，同时记录章节标题、章节文档索引、href、文本、CFI、页码等定位信息。
- Collection 是独立模型，书籍和集合不是同一个对象。
- 当前构建配置含 Electron、epub.js、pdfjs-dist、better-sqlite3 等，桌面构建及文件关联不能原样视为 Android Java 实现。

### 给青简的借鉴

1. 把“书籍内容身份、可选元数据、用户整理、阅读位置、来源授权”分层。现有 SHA256、目录关联和来源表可保留，作者、封面、语言与阅读状态逐步补齐。
2. 阅读位置使用格式相应的稳定锚点：TXT 章节 + 字符偏移；EPUB 章节 href / CFI 等；PDF 页号 + 页内位置，并关联内容版本。不把像素位置或百分比当作唯一依据。
3. 收藏、书单、来源目录应是书籍的组织关系，不能每换分类就新建一本书或重置进度。

证据：[Book](https://github.com/koodo-reader/koodo-reader/blob/f756853555c8e2f1a5fd880ebb5681e5a022e1d7/src/models/Book.ts)、[BookLocation](https://github.com/koodo-reader/koodo-reader/blob/f756853555c8e2f1a5fd880ebb5681e5a022e1d7/src/models/BookLocation.ts)、[Collection](https://github.com/koodo-reader/koodo-reader/blob/f756853555c8e2f1a5fd880ebb5681e5a022e1d7/src/models/Collection.ts)、[构建配置](https://github.com/koodo-reader/koodo-reader/blob/f756853555c8e2f1a5fd880ebb5681e5a022e1d7/package.json)。

## Readest：学习大书架状态和阅读状态分离

核验快照：main，`761c29f808c1eca28ff831494de7bf751213d245`。

### 已确认

- libraryStore 分开管理书库、当前书架、已选书籍、分组、hash 索引、可见书籍和封面缩略图，避免把所有状态都揉在一个数组里。
- readerProgressStore 将高频阅读进度更新从其他阅读器状态中隔离，提供按书、按字段读取与更新的接口，避免所有阅读组件被进度刷新牵动。
- fileSyncStore 等同步状态与本地书库状态分开。单凭这些文件不能宣称已审计其全部云同步冲突规则。
- 应用是 React / Next / Tauri 相关工程，依赖包含工作区 foliate-js、虚拟列表等；平台构建配置有 Android / iOS 路径。不能把它的前端 store 或 Tauri 平台层直接放入原生 Java 工程。

### 给青简的借鉴

1. 当前主 Activity 应拆成界面状态、书库仓储和任务协调三层，UI 只观察状态，不负责串起所有数据库与文件操作。
2. 将已选 ID 集合、当前目录、排序、可见分页列表独立管理；目录移动只更新关联，搜索与分类切换明确处理选择范围。
3. 大量藏书采用可回收的列表 / 网格和按需查询，封面异步加载缓存。青简当前每页40项是可用过渡方案，不是大书架交互的最终形态。
4. 进度单独持久化并节流写入；“未开始 / 阅读中 / 已读完”和最后阅读时间不等同于百分比。

证据：[libraryStore](https://github.com/readest/readest/blob/761c29f808c1eca28ff831494de7bf751213d245/apps/readest-app/src/store/libraryStore.ts)、[readerProgressStore](https://github.com/readest/readest/blob/761c29f808c1eca28ff831494de7bf751213d245/apps/readest-app/src/store/readerProgressStore.ts)、[fileSyncStore](https://github.com/readest/readest/blob/761c29f808c1eca28ff831494de7bf751213d245/apps/readest-app/src/store/fileSyncStore.ts)、[应用构建配置](https://github.com/readest/readest/blob/761c29f808c1eca28ff831494de7bf751213d245/apps/readest-app/package.json)。

## LightRead：学习本地导入、可迁移书库和同步边界

核验快照：main，`5e2d61e4c35d82c6148f5ff1341a450bbc3097d7`。

### 已确认

- 技术栈为 Vue / TypeScript / Tauri / Rust，依赖包含 foliate-js、PDFium 和 MuPDF。多格式来自不同处理路径，并非一个纯文本解析器能覆盖全部版式。
- 桌面目录导入在 Rust 层扫描和暂存，目录扫描不跟随符号链接，记录截断、深度限制和错误；批量导入按项读取并串行处理，某本失败继续其余项目。源码中的桌面默认值为5000文件、8层，不能直接推定其 Android 限制或全部性能。
- folderBooklists 用输入项与结果的顺序对应关系归组，而非按文件 basename 匹配，避免不同子目录中的同名文件混淆；其分类按书单名称组织，并不等于青简已有的“来源URI + 路径层级”身份模型。
- 备份支持带版本的清单及书籍、标注、书单等载荷；恢复前先校验载荷，重映射书籍 ID，再关联标注 / 书单。读取到的恢复去重使用标题与原文件名，不应视为比青简内容哈希更强的去重保证；通过全部载荷校验也不等于整个恢复过程具备数据库级全局原子性。
- 同步合并单独写为纯函数，使用带设备身份与时间戳的寄存器以及稳定序列化处理冲突，便于独立测试。不能简化成“把 SQLite 文件上传网盘”。
- README 明确：本地阅读无需账号、默认本地存储；Android 听书默认在线语音，在线语音、同步、AI 按服务联网，远程 AI 会接收相关文本。iOS 为 TestFlight 测试版；手机截图说明为手机尺寸浏览器渲染，不能当作本次已做真机视觉评测。

### 给青简的借鉴

1. 导入始终保存逐项结果和相对路径，失败可定位并只重试失败项；继续保留青简按内容哈希去重和来源URI隔离。
2. 备份先于云同步：书籍文件、元数据、分类关系、进度采用有版本清单和完整性校验，明确不同ID的恢复映射；不要把访问授权或删除权限当作可以跨设备恢复的普通数据。
3. 离线阅读、离线TTS与联网语音 / AI / 同步是不同能力，界面需要让用户知道何时联网、发送哪些数据。
4. 同步规则先作为纯核心设计并测试幂等、离线并发、删除与恢复冲突，再接 WebDAV 等传输提供器。

证据：[README](https://github.com/yzfly/LightRead/blob/5e2d61e4c35d82c6148f5ff1341a450bbc3097d7/README.md)、[构建依赖](https://github.com/yzfly/LightRead/blob/5e2d61e4c35d82c6148f5ff1341a450bbc3097d7/package.json)、[目录扫描](https://github.com/yzfly/LightRead/blob/5e2d61e4c35d82c6148f5ff1341a450bbc3097d7/src-tauri/src/library_import.rs)、[导入流程](https://github.com/yzfly/LightRead/blob/5e2d61e4c35d82c6148f5ff1341a450bbc3097d7/src/services/importer.ts)、[目录书单](https://github.com/yzfly/LightRead/blob/5e2d61e4c35d82c6148f5ff1341a450bbc3097d7/src/services/folderBooklists.ts)、[备份恢复](https://github.com/yzfly/LightRead/blob/5e2d61e4c35d82c6148f5ff1341a450bbc3097d7/src/services/backup.ts)、[同步合并](https://github.com/yzfly/LightRead/blob/5e2d61e4c35d82c6148f5ff1341a450bbc3097d7/src/services/sync/merge.ts)。

## KOReader：学习双层缓存和单一 UI 调度

核验快照：master，`042d9b39d4835408121970229cc37624e4f794bc`。

### 已确认

- README 把它描述为主要面向电子墨水设备的文档查看器，同时支持 Android / Linux；列出 PDF、DjVu、CBT / CBZ、EPUB、FB2、Mobi、DOC、RTF、HTML、CHM、TXT 等格式，以及页边距、行距、字体、样式和连字等能力。
- `frontend/cache.lua` 实现按容量 / 槽位管理的缓存，包含内存与磁盘缓存、LRU 淘汰、对象大小预算；过大的对象不会写入，磁盘缓存加载失败会删除坏条目，内存紧张时裁剪缓存并回收。
- `frontend/ui/uimanager.lua` 使用单例 UIManager、窗口栈、任务队列、dirty / refresh 状态和重绘 watchdog；这是事件调度设计，不能当作 Android UI 线程代码。
- COPYING 确认 GNU AGPL Version 3（19 November 2007）。本次没有把未在固定快照中读取到的 reader / registry / filemanager 路径写成结论。

### 给青简的借鉴

1. 对 EPUB / TXT 解析结果做“内存 LRU + 磁盘缓存”双层设计，缓存键绑定书籍内容哈希、解析器版本、字号 / 排版设置；按字节预算拒绝过大对象，加载坏缓存时删除并重算。
2. 把扫描、解析、缓存写入的结果统一投递到一个 UI 状态调度层，避免旧 Activity 回调继续改动新页面；任务需要取消、超时和进度节流。
3. 保留当前原生 PdfRenderer / BookParser，先实现缓存和稳定锚点；KOReader 的 Lua、C/C++、电子墨水刷新和原生引擎不能直接替换 Java 实现。

证据：[README](https://github.com/koreader/koreader/blob/042d9b39d4835408121970229cc37624e4f794bc/README.md)、[COPYING](https://github.com/koreader/koreader/blob/042d9b39d4835408121970229cc37624e4f794bc/COPYING)、[cache.lua](https://github.com/koreader/koreader/blob/042d9b39d4835408121970229cc37624e4f794bc/frontend/cache.lua)、[uimanager.lua](https://github.com/koreader/koreader/blob/042d9b39d4835408121970229cc37624e4f794bc/frontend/ui/uimanager.lua)。



| 优先级 | 改进 | 能解决的实际问题 | 验收重点 |
| --- | --- | --- | --- |
| P0 | 持久化导入任务与逐项状态 | 当前会话进程结束后不能恢复；大书库重复开始成本高 | 杀进程重开可继续；取消保留已完成；失败项独立重试 |
| P0 | 分章磁盘缓存和稳定定位 | 当前解析整本进内存、EPUB纯文本；字体与窗口变化易影响位置 | 大TXT / EPUB可打开；换字体、横竖屏和重开不跳章 |
| P1 | 书架网格 / 列表、封面与元数据编辑 | 目前主要为文字卡片和40项分页，长说明与按钮占用阅读空间 | 千本可回收滚动；多选状态清楚；单手操作与大字体可用 |
| P1 | 可导出、校验、恢复的本地备份 | 目前卸载会丢应用副本、分类与进度 | 另一模拟器恢复后内容、目录、进度一致；损坏包不覆盖好库 |
| P1 | 独立评估 EPUB 排版引擎 | 当前丢图片、CSS、脚注等版式信息 | 同一组合法样书检验图片、目录、脚注、重排及定位；先核许可证 |
| P2 | 系统TTS、标注 / 搜索、阅读统计 | 阅读体验尚缺基础辅助能力 | 听书停止恢复、标注稳定锚点、搜索定位、无网络可阅读 |
| P3 | 可选同步提供器 | 只有单设备使用 | 冲突有确定规则、删除不复活、不会覆盖更正确的进度 |

优先保留现有原生Android项目与可用SQLite模型，不因为参考项目跨平台就立即重写为React或Tauri。是否选用第三方排版引擎，应通过小型验证与许可证评估决定，不能以“支持格式最多”单项决定。

验证继续使用 MuMu，已有34项回归作为基线；新增任务恢复、真实大小混合书籍、目录变化、备份恢复和版式定位的专项样例。1050份小型合成文件测试只证明数量与事务边界，不能冒充几千本真实大书的性能结论。

## 许可证处理

已核验 Koodo Reader 和 Readest 根 LICENSE 为 GNU Affero GPL v3；LightRead 的 LICENSE / package.json / README 明确 AGPL-3.0-or-later，并说明 MuPDF 的许可影响。学习产品行为、架构思想与自行独立实现，和实际复制源码、打包组件，是不同的行为。

青简当前为MIT。不能把复制来的GPL / AGPL实现标为MIT原创或忽略其分发义务。真要复用时，应针对准确组件版本和整体分发方式检查源代码提供、声明及兼容性；并不是把代码放到单独模块或进程就自动免除要求。主项目许可也不能替代依赖引擎、字体、图标等各自许可。

许可证原文：[Koodo](https://github.com/koodo-reader/koodo-reader/blob/f756853555c8e2f1a5fd880ebb5681e5a022e1d7/LICENSE)、[Readest](https://github.com/readest/readest/blob/761c29f808c1eca28ff831494de7bf751213d245/LICENSE)、[LightRead](https://github.com/yzfly/LightRead/blob/5e2d61e4c35d82c6148f5ff1341a450bbc3097d7/LICENSE)。Legado当前快照不包含许可证，不作现行许可证结论。
