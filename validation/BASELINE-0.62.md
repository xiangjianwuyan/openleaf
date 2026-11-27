# OpenLeaf QA-1｜可重复验收底座（0.62 候选前置）

> 本文件是 **A 批 QA-1** 的基线记录，不是发版说明。本轮**不改 build.gradle 版本号**，
> 版本仍为 `0.61.0 / vc73`；版本留给后续 READ-1 / EDIT-1 修复卡。

## 1. 锁定基线

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | `C:/Users/欧阳/Documents/deepseek-harness/default-workspace/openleaf-ui-061` | 本轮工作目录（非 WorkBuddy 主线副本） |
| 分支 | `reading-ui-061` | `git branch --show-current` |
| HEAD | `12aaed272f5f4018fdf0eb28e449a307938d4639`（`12aaed2`） | `git rev-parse HEAD` |
| 改动前工作区 | 干净 | `git status --short` 为空 |
| 版本 | `versionCode 73` / `versionName 0.61.0` | `app/build.gradle:29-30` |
| 标准调试 APK | `deliverables/openleaf-0.61.0-debug.apk` | 0.61 验收交付 |
| APK SHA-256 | `0150ea203c87dec74dcbc4a863e5cd355381113e63ce5dbc0852e9fa8a76a9b0` | 本机 `sha256sum` 实测，与验收报告一致 |
| 签名身份 | DN `C=US, O=Android, CN=Android Debug` | `apksigner verify --print-certs`（build-tools 35.0.0），已实测 |
| 证书 SHA-256 指纹 | `a8fabc3217fb80105381d671c3c9e970769704d223f5b1dd3b35e2089bd00aac` | 同上 |
| 证书 SHA-1 指纹 | `4d4ad3153517a4c733507a3f2bd4a12e45c73451` | 同上 |
| 构建身份 | `build.gradle` 无 `signingConfigs`，仅调试签名 | 本机检索 |
| debug authority | `org.openleaf.reader.debugfixtures`（`${applicationId}.debugfixtures`，无 applicationIdSuffix） | `app/src/debug/AndroidManifest.xml:4` |

### 目标设备

| 设备 | 已知信息 | 待采 |
|---|---|---|
| Android 12 MuMu | 横屏；0.61 界面改版验收使用同源码隔离包，未覆盖标准包 | 分辨率、字体倍率、动画开关 |
| Android 17 真机 `25102RKBEC` | API 37、竖屏；0.61 真机记录使用隔离包 `org.openleaf.reader.readerqa060`，未覆盖标准包 | 分辨率、字体倍率、动画开关 |

设备纪律：只对明确序列号执行 ADB；禁止清理标准包数据；测试包与标准包分开。
本轮 `adb devices` 可见 `9c35911a`（Redmi K20 Pro / Android 11 / 1080x2340），
**不是两台目标设备之一**，因此本轮未跑任何设备仪器测试。

## 2. 夹具清单

- 生成器：`validation/fixtures/FixtureGenerator.java`（纯 JDK 17，无第三方依赖）
- 清单：`validation/fixtures/MANIFEST.sha256`，**12 个条目**，标准 `sha256sum` 格式
- 清单自身 SHA-256：`212626bd87e58cae006d5c112c36724e03c61c6409e5562232642c6312e67bdd`
- 生成器本体 SHA-256：`d609163c42d5f82e66974bb83044ea703657414651e2157dc00e19b8c64d3890`
- 确定性：连续运行生成器两次，两份 MANIFEST 逐字节一致；`sha256sum -c MANIFEST.sha256` 12/12 OK
- 说明与重建命令：`validation/fixtures/README.md`
- `validation/fixtures/.gitattributes` 声明 `* -text`，防止 `core.autocrlf` 检出时改写换行使哈希失效

夹具覆盖：三章长 TXT（UTF-8 BOM + CRLF、唯一标记、超长章共享 `src:1`）、GB18030 / Big5 /
UTF-16 BOM、Markdown 围栏、128 KiB 整边界、同名不同内容、损坏备份、五页 CBZ、
合法短 WAV；短视频为明确标注的占位（**环境无可解码编码器，占位**，不伪造可解码性）。

## 3. core 回归底座

- 一键入口：`validation/run-coretests.ps1`（从 `.tools/qa-coretests.ps1` 移植，`$src` 指向当前仓库根）
- 实测结果：**PASS=78 / FAIL=0 / TOTAL=78**（JDK 17.0.21.1，UTF-8，`--release 17`，逐类启动）
- 排除：`CatalogProtocolTest`（需 `android.os` / `org.json`）；`StyleDraftProbe`（需 `android.jar`，独立探针，无测试引用）
- 移植时对本仓库的必要适配：纳入 3 个纯 JVM app 层类（`LocalTextDocument` / `PluginTextReport` / `ReaderPaging`）
  与 2 个纯 JVM Probe；按 `package` 声明计算主类名；测试工作目录设为仓库根。
- 环境注意（已在本机处理，非代码改动）：
  1. `core-tests/t02build` 被 `.gitignore` 排除且本机缺失；`DigestExportTest` 依赖其中的
     org.json 与已编译 core 类，已在本机补齐后才达到 78/78。新环境需同样补齐。
  2. `core.autocrlf=true`，`NotePackage.java` 检出为 CRLF；`DigestExportTest` 按 LF 匹配源码，
     本机已将工作区文件规范为 git 中原有的 LF（git 内容无差异）。重新检出可能恢复 CRLF。
  3. 受限环境下子进程 JVM 无权在 `C:\Users\Public` 建目录；runner 先用 Java 探针验证 ASCII tmpdir，
     不可写时回退到工作区内 tmpdir 并打印 `NOTE`。普通环境默认使用 ASCII tmpdir。

## 4. 过期断言修正

唯一改动的现有源码：`app/src/debug/java/org/openleaf/reader/BackupRoundtripInstrumentation.java`

| 位置 | 修正 |
|---|---|
| 58-59 行 | check 描述由生产常量拼装，不再写死 `15 sections at version 4` |
| 116-117 行 | `eq(BackupManifest.SCHEMA_VERSION, manifest.optInt("schemaVersion"), …)` |
| 124-125 行 | `eq(BackupManifest.DATA_SECTIONS.length, dataCount, …)` |

依据：`core/BackupManifest.java:25` 的 `SCHEMA_VERSION = 6`、34-39 行的 `DATA_SECTIONS`（16 项，15 表 + `reader_prefs`）。
`BackupManifest` 早在第 15 行 import；`parseEntries` / `corruptRejected` 及其它检查保持不变。

## 5. 验证状态

| 项目 | 状态 |
|---|---|
| core 全量 78 类 | ✅ 本机已验证，78 PASS / 0 FAIL |
| 夹具 SHA 两次一致 | ✅ 本机已验证，12 条目，清单哈希见上 |
| 仪器断言 PARSE | ✅ Compiler Tree API 仅解析：1 单元 / 0 错误 |
| 断言常量存在且类型匹配 | ✅ 独立探针编译运行：`SCHEMA_VERSION=6`、`DATA_SECTIONS.length=16`（均 `int`） |
| 设备仪器测试（Android 12 / 17） | ⏳ **待验**：当前可见设备不是目标设备；本轮未构建隔离测试包，也未跑仪器 |
| 视频可解码性 | ⏳ **待验**：只有占位，后续须用外部编码器生成并记录哈希 |

## 6. 与后续卡的关系

QA-1 是 A 批前置：**READ-1**（分页/锚点/动画）、**EDIT-1**（正文编辑与原件保护）、
**DATA-1**（导入/备份/升级）都将复用本底座的基线哈希、合成夹具与 core runner。
后续修复须另开提交、独立版本号，不得把版本 bump 混进底座。

## 7. 0.61 对照报告

- `deliverables/OpenLeaf-0.61.0-界面改版验收.md`：APK SHA、MuMu Android 12 横屏结论、标准 `assembleDebug lintDebug`。
- `deliverables/OpenLeaf-0.61.0-真机调试.md`：25102RKBEC / Android 17 / 竖屏、隔离包结论。
