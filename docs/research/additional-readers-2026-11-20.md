# 新增参考项目：Ciallo-Reader、DZMeBookRead、legado-E 与 Anx Reader

核验日期：2026-11-20。补充到既有五项目研究中；未复制任何参考项目实现到青简。青简书内搜索使用独立编写的 Java 匹配核心。

## Ciallo-Reader

固定提交：`50f374cde1cfa41cb16c072b7455e9b402863d0f`，main。

官方 README 描述为 Android 阅读器，包含 TXT、EPUB、CBZ、本地书架、多来源搜索、下载与离线阅读；工程使用 Kotlin / Compose。许可证原文为 MIT。此处的格式和平台范围是仓库声明，不代表本次逐一运行验证。

实际读取了两个小型源码文件：

- `KeywordSearchPreview.kt`：这是**多来源搜书的关键词预览**，不是书内全文搜索。它按关键词、提交来源和 provider 状态组织预览，区分找到、空结果、歧义和失败，提供重试及展开 / 折叠状态。对青简可借鉴“查询状态清楚、分组结果可折叠、失败可重试”的交互，不把它说成已经提供书内搜索引擎。
- `ReadingRecordResolver.kt`：先用本地 Book.id 解析阅读记录，再尝试收藏 / 缓存映射；未解析项按标题归组查询来源，使用限并发与超时，并清理未完成任务。对青简可借鉴“稳定书籍 ID 优先，标题仅作明确兜底”“后台查询有限流、超时、取消”，不能把网络结果当成本地内容身份。

源码目录有独立书架、下载、传输、来源等职责；但大型 Activity / ViewModel 本身不是应照搬的结构。青简后续仍应控制界面与数据任务耦合，不因参考项目使用 Compose 就立即重写工程。

来源：[README](https://github.com/roxycon-dev/Ciallo-Reader/blob/50f374cde1cfa41cb16c072b7455e9b402863d0f/README.md)、[MIT LICENSE](https://github.com/roxycon-dev/Ciallo-Reader/blob/50f374cde1cfa41cb16c072b7455e9b402863d0f/LICENSE)、[关键词预览](https://github.com/roxycon-dev/Ciallo-Reader/blob/50f374cde1cfa41cb16c072b7455e9b402863d0f/app/src/main/java/com/example/library/KeywordSearchPreview.kt)、[阅读记录解析](https://github.com/roxycon-dev/Ciallo-Reader/blob/50f374cde1cfa41cb16c072b7455e9b402863d0f/app/src/main/java/com/example/library/ReadingRecordResolver.kt)。

## DZMeBookRead

官方项目是 Swift / iOS 阅读器示例，仓库描述包含仿掌阅 / QQ 阅读的阅读页、仿真 / 覆盖 / 平移 / 滚动等翻页形式、字体、书签、阅读记录、亮度和背景等功能。GitHub 仓库元数据将许可证识别为 MIT；本次未读取许可证原文，不能替代复用前的逐项许可核对。

已确认的 README 更新描述了按书籍 / 章节身份缓存章节、分离阅读记录与书签的设计。但本轮官方 API 源码请求受限流影响，**没有完成具体分页、CoreText 或排版源码核验**，所以以下只列为后续待验证的借鉴方向，不宣称实现已审计：

1. 阅读位置与排版页号分离；字号和窗口变化后，由稳定锚点重新映射页面。
2. 按书籍与章节缓存，避免每次打开都解析整本书；缓存应附内容哈希与解析版本，避免过期缓存错位。
3. 翻页效果与正文排版接口分离，在同一内容模型上切换翻页交互，不因换动画就丢进度。

这些方向需要后续读取准确源码后才能形成移植方案。iOS / Swift 的系统排版与视图接口不能直接放进 Android Java；复用代码前仍需检查原许可证和依赖声明。

来源：[官方仓库](https://github.com/dengzemiao/DZMeBookRead)、[官方元数据](https://api.github.com/repos/dengzemiao/DZMeBookRead)、[已核验 README 更新提交](https://github.com/dengzemiao/DZMeBookRead/commit/d27d122a38469f7e7ff7adb6632d439c2428d1af)。

## legado-E（阅读 Sigma）

[仓库元数据](https://api.github.com/repos/Luoyacheng/legado-E)标识 Kotlin 和 GPL-3.0；[官方 README](https://github.com/Luoyacheng/legado-E/blob/main/README.md)将它描述为延续 Legado 的独立项目，列出本地 TXT/EPUB、列表与网格书架、自定义排版与多种翻页方式。它与前一篇笔记中的 `gedoor/legado` 是不同仓库。本轮未核验许可证原文或具体实现，不据此认定继承项目与原版行为完全相同。

青简可优先参考列表/网格切换、中文 TXT 目录规则和翻页交互；不把书源规则作为近期必做项，也不把 GPL 代码直接作为青简的 MIT 实现发布。

## Anx Reader

[官方 README](https://raw.githubusercontent.com/Anxcye/anx-reader/develop/README.md)说明使用 Flutter，并注明 foliate-js 用于电子书渲染；列出 WebDAV 同步书籍、笔记与进度，笔记按章节或时间组织并可导出，以及朗读语速和定时控制。README 说明项目曾从 MIT 切到 GPLv3，在重写选区与高亮后切回 MIT；[当前仓库元数据](https://api.github.com/repos/Anxcye/anx-reader)也标识 MIT。实际复用仍须核对具体版本、文件和依赖许可。本轮只核验仓库说明和元数据，未验证同步冲突策略或笔记定位源码。

青简可借鉴独立的笔记工作区和开放格式导出；先将批注关联到稳定正文位置，再做 WebDAV。朗读优先评估系统 TTS，并明确语音是否需要联网，不默认发送正文给外部服务。

## 与青简当前工作的关系

- 本轮先落地独立的书内搜索：按章节统计 / 折叠、原文上下文高亮、纯字面匹配、结果点击定位、全书 / 当前章范围和大小写选项。
- 来源网络搜索、书内全文搜索和书架书名搜索是不同功能，界面入口与数据接口保持分开。
- 后续性能优先补分章缓存、任务持久化和稳定定位；不同参考项目的界面或翻页效果先用 MuMu 样书验证，不凭截图宣称质量或速度。
- Ciallo 的 MIT 不免除保留版权及检查依赖许可；DZMeBookRead 的元数据 MIT 标记也不等于已核验所有文件。当前研究只借鉴思想，青简许可不变。
