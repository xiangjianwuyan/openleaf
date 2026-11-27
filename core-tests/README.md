# BookParser 核心测试

在 `openleaf` 目录用 JDK 17 运行，无需 Android SDK、Gradle、JUnit 或外部依赖：

```sh
javac -encoding UTF-8 --release 17 -d core-tests/build app/src/main/java/org/openleaf/reader/core/BookParser.java core-tests/BookParserTest.java
java -Xmx512m -cp core-tests/build BookParserTest
```

测试生成临时 TXT / EPUB ZIP 样本并在结束时删除，任何 EPUB 条目均不会解压到磁盘。断言始终启用，不依赖 `-ea`。

## API

```java
BookParser.BookContent book = BookParser.parse(new File("book.epub"), "epub");
String title = book.title;
for (BookParser.Chapter chapter : book.chapters) {
    String chapterTitle = chapter.title;
    String plainText = chapter.text;
}
```

完整包名：`org.openleaf.reader.core.BookParser`。入口 `public static BookContent parse(File file, String format) throws Exception`。`BookContent`、`Chapter` 均为 `public static final`，字段是 `public final`；章节列表为防御性复制后的不可修改列表。也提供 `BookContent(String, List<Chapter>)` 和 `Chapter(String, String)` 公有构造器。

`format` 接受 `txt`、`epub`，忽略大小写和前后空白，可带前导点；`null` 或空白按文件扩展名推断。同步解析，Android 中应从后台线程调用。

## 行为及上限

- TXT：严格 UTF-8 解码；无 BOM 的 UTF-8 解码失败时严格尝试 GB18030。支持 UTF-8、UTF-16LE、UTF-16BE BOM；BOM 声明的编码不合法则报错，不悄悄回退。中文章节标题及序章、尾声等单独分章；标题保留在正文，前言文字独立保留，换行统一成 LF。
- EPUB：读取 `META-INF/container.xml` 定位 OPF，读取 metadata 首个 title，按 spine 的 itemref 顺序读取 manifest 正文；包括 `linear="no"` 项。正文支持 XML 格式正确的 XHTML / text/html；选取首个正文标题、head/title 或自动标题作为章名。忽略 head、script、style；段落双换行，br 单换行，表格仅保留文本。
- 路径：ZIP 区分大小写；本地 URI 支持 UTF-8 百分号转义及片段号；允许不越过 ZIP 根的 `../`。拒绝绝对路径、反斜杠、盘符、外部 URI、查询参数、越界路径以及含 `.` / `..` 的 ZIP 实体条目名称。全部读取在内存中完成。
- 安全 XML：SAX、禁用外部实体和外部 DTD、拒绝实体解析器；解析前在注释/CDATA/处理指令之外拒绝 DTD 和 ENTITY 声明。允许 `<!DOCTYPE html>`；对精确识别的 W3C XHTML 1.0/1.1 标准 PUBLIC/SYSTEM DTD 标识符直接移除声明而不访问网络。拒绝其余 DTD、所有内部子集及实体声明。注释和 CDATA 内的字面内容不会被 HTML 实体预处理改写。XML 深度上限 256。
- HTML 实体：五个 XML 内置实体、数字实体及一组常用 HTML 实体（nbsp、ldquo、rdquo、mdash、hellip、copy 等）；并非完整 HTML5 实体集合。未知命名实体或不完整 HTML 报错，不执行 HTML5 容错修复。
- DRM：`META-INF/rights.xml`、`META-INF/license.lcpl` 或不支持的 encryption.xml 加密算法返回中文 DRM 错误。不解密；仅 IDPF / Adobe 字体混淆标识可忽略，因为不读取字体。不是完整 DRM 识别引擎。
- 所有容量单位均为二进制 MiB（错误提示写 MB）：导入文件 512 MiB，TXT/MD 同为 512 MiB（>32 MiB 走流式逐行校验，不整本解码），EPUB 全部条目实际展开总和 512 MiB，每条目 24 MiB，ZIP 条目 30000，输出章节及 spine 项 5000。即便未引用的图片也会计入展开预算；重复 spine 引用另计独立读取预算 512 MiB。预算依据实际流读取，不仅依赖 ZIP 声明大小。因 512 MiB 预算下制造真实越界数据成本过高，总展开与重复引用的预算用例改为验证常量自洽性。
- 每个输出章节最多 20000 个 UTF-16 代码单元，优先在段落/换行/空格处切割，避免切开代理对；分片章名加序号。切割边界的空白会修剪。EPUB 书名及所有章节标题过长时截为 200 个代码单元左右加省略号。
- 空白 TXT、所有正文均为空的 EPUB 报中文空书错误。仅有章节标题的书仍算有文本。

## 明确局限

以上是 `BookParser.parse()` **纯文字路径**的局限。另有 `BookParser.openIllustrated()` 图文路径（`EpubActivity` 使用）：支持书内嵌 PNG / JPEG / GIF / WebP 位图、表格、注音与白名单基础 CSS，插图可在 `EpubImagePreview` 中放大查看；该路径不加载外部图片 / 字体、不执行脚本，也不保证还原固定版式、复杂 CSS 或 SVG。两条路径都不支持 PDF、MOBI、AZW、加密解密、脚注跳转、音视频、EPUB navigation API、xml:base、manifest fallback 或 SVG 正文；不跨文件合并章节。`pre` 中的空白按普通正文折叠，仅保留基本块级段落。`BookParser.parse()` 仍一次性返回全部章节；阅读页与搜索页现已改用 `LazyChapterBook`，建立索引后把正文内容存入私有缓存并按需加载，大小上限不等于任何设备上的堆内存保证；导入应在后台线程执行。另提供 `BookParser.discoverCover()` 做 EPUB 内嵌封面发现。Android API 26+ 所需代码仅使用该级别已有的 Java/SAX API；纯 JVM 测试不替代真机测试。


## BookSearch 书内查找回归

`BookSearchTest.java` 使用中性原创样本覆盖中文与章节偏移、大小写与Unicode展开、字面元字符、重叠匹配、章节范围、恰好达到与超过结果上限、代理对上下文、换行、空词、输入限制、取消和不可变结果。

```sh
javac -encoding UTF-8 --release 17 -d core-tests/build app/src/main/java/org/openleaf/reader/core/BookParser.java app/src/main/java/org/openleaf/reader/core/BookSearch.java core-tests/BookSearchTest.java
java -cp core-tests/build BookSearchTest
```

## 想法区三视图 core 回归（T01）

4 个纯 JVM 测试：`ChapterOutlineTest`（层级推断 / 折叠投影）、`DigestBuilderTest`（首句 / 拼装）、`TagIndexTest`（标签聚合）、`ChapterSurveyTest`（章节前缀读取 / 章数上限 / 时间预算）。全部不含 Android 类型、无 JUnit、无第三方依赖；`DigestBuilderTest` / `ChapterSurveyTest` 会临时写 TXT 并在结束时删除。断言始终启用，不依赖 `-ea`。若本机没有 JDK 17，用更高版本 JDK 的 `javac --release 17` 编译、并用同一 JDK 运行即可，运行环境以实际为准。

```sh
javac -encoding UTF-8 --release 17 -d core-tests/build17 \
  app/src/main/java/org/openleaf/reader/core/LazyChapterBook.java \
  app/src/main/java/org/openleaf/reader/core/TextChapterIndexer.java \
  app/src/main/java/org/openleaf/reader/core/TextChapterHeadings.java \
  app/src/main/java/org/openleaf/reader/core/BookParser.java \
  app/src/main/java/org/openleaf/reader/core/ChapterOutline.java \
  app/src/main/java/org/openleaf/reader/core/DigestBuilder.java \
  app/src/main/java/org/openleaf/reader/core/TagIndex.java \
  app/src/main/java/org/openleaf/reader/core/ChapterSurvey.java \
  core-tests/ChapterOutlineTest.java core-tests/DigestBuilderTest.java \
  core-tests/TagIndexTest.java core-tests/ChapterSurveyTest.java
java -Xmx512m -cp core-tests/build17 ChapterOutlineTest
java -Xmx512m -cp core-tests/build17 DigestBuilderTest
java -Xmx512m -cp core-tests/build17 TagIndexTest
java -Xmx512m -cp core-tests/build17 ChapterSurveyTest
```

## 章节规则与索引注入 core 回归（0.27.0 P0-2）

`ChapterRuleTest` 覆盖保守档与 `TextChapterHeadings` 逐样本一致、三档命中递增、
两条自定义正则命中、非法正则校验与降级、命中预览计数与行数上限。
`TextChapterIndexerRuleTest` 覆盖原 `index(f,cacheDir)` 与注入保守档的元数据/缓存逐字一致（零回归）、
不同档位重建后章数/标题/`chapterKey`/`sourceStart` 变化与 UTF-16LE 缓存回读、
`LazyChapterBook.open(..., rule)` 规则透传。全部纯 JVM、无 Android/JUnit/第三方依赖，断言始终启用。

```sh
javac -encoding UTF-8 --release 17 -d core-tests/build17 \
  app/src/main/java/org/openleaf/reader/core/*.java \
  app/src/main/java/org/openleaf/reader/MobiNative.java \
  core-tests/ChapterRuleTest.java core-tests/TextChapterIndexerRuleTest.java
java -Xmx512m -cp core-tests/build17 ChapterRuleTest
java -Xmx512m -cp core-tests/build17 TextChapterIndexerRuleTest
```

## 提要导出 core 回归（T5）

`DigestExportTest.java` 验证 `NotePackage.exportDigestMarkdown` 的书名标题、每章 `- **title**：lead`、tags 行、truncated 尾注、空 digest 与 null 边界。该类在临时目录读取**真实的** `NotePackage.java`，仅去掉 `android.content.Context` 的 import 并追加最小桩类后编译（导出方法体逐字取自真实源码），再反射调用；无需 Android SDK / JUnit / 第三方依赖，断言始终启用，不依赖 `-ea`，结束删除临时目录。编译时会用到仓库已有的 `core-tests/t02build`（org.json 预编译类）。

```sh
javac -encoding UTF-8 --release 17 -d core-tests/build17 core-tests/DigestExportTest.java
java -Xmx512m -cp core-tests/build17 DigestExportTest
```

## EPUB 流式索引受限堆对照（T04）

`EpubStreamingOomProbe.java` 用 `ZipOutputStream` 合成**真实可解压**样本（`Archive` 会真解压每个条目，稀疏文件无效），并在同一份样本、同一 JVM 参数下只切换 `old` / `new` 分支：

```sh
javac -encoding UTF-8 --release 17 -cp core-tests/t02build -d core-tests/build17 \
  app/src/main/java/org/openleaf/reader/core/BookParser.java \
  app/src/main/java/org/openleaf/reader/core/EpubChapterIndexer.java \
  app/src/main/java/org/openleaf/reader/core/LazyChapterBook.java \
  core-tests/EpubStreamingOomProbe.java core-tests/EpubChapterIndexerTest.java

# 样本生成（正常堆）：S2≈30MiB 展开 / 30 spine；S4≈23MiB 单条目；S4over>24MiB；S6=UTF-16LE BOM
java -Xmx512m -cp core-tests/build17 EpubStreamingOomProbe gen s2 /tmp/s2.epub
java -Xmx512m -cp core-tests/build17 EpubStreamingOomProbe gen s4 /tmp/s4.epub
java -Xmx512m -cp core-tests/build17 EpubStreamingOomProbe gen s4over /tmp/s4over.epub
java -Xmx512m -cp core-tests/build17 EpubStreamingOomProbe gen s6 /tmp/s6.epub

# 档 1（主证据）：-Xmx64m + S2 —— old 应 OOM，new 应正常完成
java -Xmx64m -cp core-tests/build17 EpubStreamingOomProbe old /tmp/s2.epub -Xmx64m
java -Xmx64m -cp core-tests/build17 EpubStreamingOomProbe new /tmp/s2.epub -Xmx64m
# 档 2（边界）：-Xmx192m + S4 —— new 可通过；S4 在 -Xmx64m 下连 new 也会 OOM
java -Xmx192m -cp core-tests/build17 EpubStreamingOomProbe new /tmp/s4.epub -Xmx192m
java -Xmx64m  -cp core-tests/build17 EpubStreamingOomProbe new /tmp/s4.epub -Xmx64m
# 档 3（负向）：单条目 24MiB+ 被 Archive 拒绝，且无缓存残留
java -Xmx192m -cp core-tests/build17 EpubStreamingOomProbe new /tmp/s4over.epub -Xmx192m
```

**诚实边界**：本方案消除的是**全本累积峰值**，不是**单章瞬态峰值**。`decodeXml` → `prepareXml` 的单条目链路仍有约 3~4 倍瞬态放大；`-Xmx64m` 下 23MiB 极限单条目即使新实现也会 OOM，必须用 `-Xmx192m` 档验证。若要覆盖 64m 下的极限单条目，需要二期的 `HtmlHandler` 字符级流式改造（会扰动 `plainText()` 语义，首期不做）。

## MOBI 流式索引回归与受限堆对照（0.18.0）

`MobiParserBridgeTest`（Fake 注入分支 + stream parity + 两档入口 + DRM/降级/空段上限）、
`MobiChapterIndexerTest`（端到端 index→open→load parity，含中文/emoji/代理对逐字回读）、
`MobiStreamingOomProbe`（受限堆 old/new 对照）均为纯 JVM，不加载 native so。
Fake 的 `stream()` 逐段生成后直接 `sink.accept`，不构建 `List`/`BookContent`；`streamAggregate=true`
时退化为 `read()+accept`，供 old 档复现全本峰值。

```sh
javac -encoding UTF-8 --release 17 -d core-tests/build17 \
  app/src/main/java/org/openleaf/reader/core/*.java \
  app/src/main/java/org/openleaf/reader/MobiNative.java \
  core-tests/MobiParserBridgeTest.java core-tests/MobiChapterIndexerTest.java \
  core-tests/MobiStreamingOomProbe.java
java -Xmx512m -cp core-tests/build17 MobiParserBridgeTest
java -Xmx512m -cp core-tests/build17 MobiChapterIndexerTest

# 样本（占位 .mobi + .cfg 规格；正文由 Fake 按规格合成，不读 MOBI 二进制）
java -Xmx512m -cp core-tests/build17 MobiStreamingOomProbe gen s2 /tmp/s2.mobi
java -Xmx512m -cp core-tests/build17 MobiStreamingOomProbe gen s4 /tmp/s4.mobi
java -Xmx512m -cp core-tests/build17 MobiStreamingOomProbe gen over /tmp/over.mobi
# 档 1（主证据）：-Xmx64m + s2（48 段 × 1MiB 中文码元）—— old 真实 OOM，new 正常完成
java -Xmx64m -cp core-tests/build17 MobiStreamingOomProbe old /tmp/s2.mobi -Xmx64m
java -Xmx64m -cp core-tests/build17 MobiStreamingOomProbe new /tmp/s2.mobi -Xmx64m
# 档 2（边界）：s4 在 -Xmx64m 两档都通过；s2 的 old 在 -Xmx512m 可通过（堆依赖）
java -Xmx64m -cp core-tests/build17 MobiStreamingOomProbe old /tmp/s4.mobi -Xmx64m
java -Xmx64m -cp core-tests/build17 MobiStreamingOomProbe new /tmp/s4.mobi -Xmx64m
java -Xmx512m -cp core-tests/build17 MobiStreamingOomProbe old /tmp/s2.mobi -Xmx512m
# 档 3（负向）：512MiB+ 导入上限拒绝，无缓存残留（用覆写 length() 的 File 占位，避免真实分配 513MiB）
java -Xmx64m -cp core-tests/build17 MobiStreamingOomProbe new /tmp/over.mobi -Xmx64m
```

**诚实边界**：本对照只证明 Java 侧「全本章节列表聚合峰值」被消除。真实 `MobiNative.Bridge`
的 native `ensure_parsed()` 一次性 strdup 全部 segments（native 堆 ≤64MiB）与单段 jstring
（≤16MiB → Java String ≤32MiB）的瞬态峰值仍在；改它需动 `mobi_bridge.c` 并重编 `.so`，
列二期。不得声称「任意大 MOBI 在 64m 下都不 OOM」。真机还需覆盖导入→打开→翻章→进度/锚点
`chapterKey` 读回→退出重进→DRM 拒绝→无崩溃。

## 0.28.0 P1 core 回归（预设 / 目录 / 编码 / 段落重排）

`ReaderPresetTest`（五档映射合法、"标准"=0.27.0 默认、非法 id 降级、微调后 detectPreset=0）、
`TocFilterTest`（子串/大小写不敏感、空查询全返回、无匹配空数组、与 `ChapterOutline.visible()`
折叠投影求交不越界、当前章定位）、`BookParserEncodingTest`（四编码严格解码命中预期文字、
AUTO 路径零回归、override 章数/标题正确、强制 UTF-16 无 BOM 不丢前两字节）、
`ParagraphReflowTest`（硬换行合并以及诗歌/对话/fence 不误合、空行折叠、缩进判定、
**map M1–M7 全断言**、mapSpan 后标注/搜索落点、关闭态恒等）均为纯 JVM、无 Android/JUnit/
第三方依赖，断言始终启用，不依赖 `-ea`。

```sh
javac -encoding UTF-8 --release 17 -d core-tests/build17 \
  app/src/main/java/org/openleaf/reader/core/*.java \
  app/src/main/java/org/openleaf/reader/MobiNative.java \
  core-tests/ReaderPresetTest.java core-tests/TocFilterTest.java \
  core-tests/BookParserEncodingTest.java core-tests/ParagraphReflowTest.java
java -Xmx512m -cp core-tests/build17 ReaderPresetTest
java -Xmx512m -cp core-tests/build17 TocFilterTest
java -Xmx512m -cp core-tests/build17 BookParserEncodingTest
java -Xmx512m -cp core-tests/build17 ParagraphReflowTest
```

> 若本机 `javac` 在含中文路径的工作区写 `.class` 报"拒绝访问"，把 `-d` 输出目录与
> `java.io.tmpdir` 指向本机临时目录即可（测试本身不依赖固定输出路径）。

## 0.29.0 P2 字体管理 core 回归（收藏 / 解析 / 预览契约）

`FontLibraryTest`（内容 id 只由摘要决定、序列化往返与坏记录容错、增删重命名上限去重、
legacy 惰性迁移判定）、`FontResolutionTest`（R0–R6 优先级矩阵、无按书键时与 0.27.0 三态
逐字一致、按书失效清键回落、引用键精确扫描）、`FontPreviewContractTest`（原创示例文本、
损坏不可选、控制字符往返）均为纯 JVM、无 Android/JUnit/第三方依赖，断言始终启用。

```sh
javac -encoding UTF-8 --release 17 -d /tmp/ol029-ct \
  app/src/main/java/org/openleaf/reader/core/*.java \
  core-tests/FontLibraryTest.java core-tests/FontResolutionTest.java core-tests/FontPreviewContractTest.java
java -cp /tmp/ol029-ct FontLibraryTest
java -cp /tmp/ol029-ct FontResolutionTest
java -cp /tmp/ol029-ct FontPreviewContractTest
```

> Windows 中文路径下把 `/tmp/ol029-ct` 换成 harness 临时目录（如
> `C:/Users/<用户>/Documents/deepseek-harness/default-workspace/.tools/tmp-build/ol029-ct`），
> 并用 JDK 17 运行；高版本 JDK 运行时其他 XML 深度用例可能失败。

## 0.30.0 阶段 1A core 回归（配色 / 对比度 / 歌词四格式）

`ThemePaletteTest`（三套预设与 0.29.0 PAPER/INK 逐字一致、自定义覆盖与 0 回退、preset clamp、
WCAG 对比度同色 1 / 黑白 21 / 对称性、4.5 阈值与深色判定）、
`LyricsTest`（标准 LRC 与旧 `parseLrc` 逐字等价、增强 LRC 一行多标签展开、SRT 序号/空行/毫秒归一、
TXT 静态、UTF-8 严格解码失败回退 GB18030、1 MiB / 5000 行 / 500 字符上限、`best()` 择优、
空/垃圾/畸形输入不抛异常）均为纯 JVM、无 Android/JUnit/第三方依赖，断言始终启用。

```sh
javac -encoding UTF-8 --release 17 -d /tmp/ol030-ct \
  app/src/main/java/org/openleaf/reader/core/*.java \
  core-tests/ThemePaletteTest.java core-tests/LyricsTest.java
java -cp /tmp/ol030-ct ThemePaletteTest
java -cp /tmp/ol030-ct LyricsTest
```

## B 批 T01 core 身份底座（spec 指纹 / 作品级偏移 / 搜索 Hit 偏移）

`TextIndexSpecTest`（同字节不同 prefs 命名 specId 只随编码/规则变、SHA 变化即过期、
`PositionSync.newRevision()` 连续变化但 specId 不变、canonical 键序无关 + 长度前缀消歧、
非法档位 clamp 与 null sha 安全）、`SourceOffsetTest`（累加单调、章边界相接、与
`ReaderActivity.anchorSourceOffset/resolveAnchor` 金标逐字一致、越界/null 返回 -1/{-1,-1}）、
`BookSearchSourceOffsetTest`（旧 Hit 构造器仍为 -1、新构造器填充作品级偏移、单调与边界相接、
正则/附近词同口径）均为纯 JVM、无 Android/JUnit/第三方依赖，断言始终启用。

```sh
javac -encoding UTF-8 --release 17 -d /tmp/ol064-ct \
  app/src/main/java/org/openleaf/reader/core/*.java \
  core-tests/TextIndexSpecTest.java core-tests/SourceOffsetTest.java \
  core-tests/BookSearchSourceOffsetTest.java
java -cp /tmp/ol064-ct TextIndexSpecTest
java -cp /tmp/ol064-ct SourceOffsetTest
java -cp /tmp/ol064-ct BookSearchSourceOffsetTest
```

> Windows 中文路径下把 `/tmp/ol064-ct` 换成 harness 临时目录（JDK 17），
> 或用 `powershell -ExecutionPolicy Bypass -File validation/run-coretests.ps1` 一键跑全量。
