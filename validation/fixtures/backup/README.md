# OpenLeaf DATA-1 合成备份样本（静态验证用）

**语义声明：本目录全部 zip 均为合成样本，不是当前版本真实 SAF 导出，**
**也不是 ol037 backup-v6.zip 历史本体。** 仅用于 core 层静态校验，
不得据此声称端到端回环、旧样本兼容或恢复后不污染库。

## 样本清单

- `current-v6-valid.zip`：合成 schema v6 合法包；16 个 DATA 段（覆盖 BackupManifest.DATA_SECTIONS） + 1 个书文件条目，清单 entries 共 17 条，zip 另含 manifest.json 共 18 个文件；
  含 1 本书（`book-fixture-1`）、reading_time 1 行、reader_prefs 2 个阅读锚点。
  `createdAt=0`、`app=OpenLeaf/synthetic-fixture`，明确区别于真实导出。
- `corrupt-bad-hash.zip`：结构合法但 `data/books.json` 声明 SHA-256 全 0；
  用于断言 `BackupManifest.verifyPayload` 拒绝，不代表真实损坏备份本体。
- `corrupt-bad-json.zip`：`manifest.json` 被截断为非法 JSON；断言解析阶段拒绝。
- `corrupt-truncated.zip`：合法包尾部截断（中央目录缺失）；断言 zip 读取失败。
- `historical-v4-synthetic.zip` / `historical-v5-synthetic.zip`：用当前结构合成、
  `schemaVersion=4/5` 的版本矩阵变体；只反映 `validateStructure` 对 1..6 的接受行为，
  **不能**证明 ol037 旧备份可恢复或运行时迁移兼容。

## 重建与校验

```powershell
$root = '<repo-root>'
$tmp  = '<repo-root>\.tools\tmp-build\backup-fixture-gen'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
javac -encoding UTF-8 --release 17 -d $tmp (Join-Path $root 'validation\fixtures\backup\BackupFixtureGenerator.java')
java -cp $tmp BackupFixtureGenerator (Join-Path $root 'validation\fixtures\backup')
```

从 `validation/fixtures/backup` 执行 `sha256sum -c MANIFEST.sha256`；
MANIFEST 只覆盖本目录 6 个 zip 样本，不覆盖 README.md、MANIFEST.sha256、
生成器本体，也不覆盖 QA-1 遗留的 `corrupt-backup.zip`。
QA-1 顶层 `validation/fixtures/MANIFEST.sha256` 保持不变，仍只覆盖其原有 12 个夹具。
本目录继承上级 `.gitattributes` 的 `* -text`，避免检出换行改写哈希。

## 本轮新增：0.63 备份恢复回归测试（不针对 ol037 本体）

- `BackupRoundtripInstrumentation` 新增的 SAF 回归，测的是 **0.62.0 修复后的导出路径**：
  `LibraryBackup.export()` 对 `books.file` 为空的 SAF 引用书，经 `ContentResolver` 读取
  `book_references.source_uri` 的书文件字节。它在 debug 插桩内用合成 provider 夹具做
  导出→恢复往返；**不是** ol037 `backup-v6.zip` 本体，也不能据此声称旧本体兼容。
- `BackupLegacyShapeTest`（纯 JVM）只测 `BackupManifest.validateStructure` 的结构层契约
  （v1..v6 接受、畸形条目致命拒绝、设备字段/重新生成字段分类），输出
  `SCOPE: structural contract only; ol037 backup-v6.zip body absent`；同样只是结构层接受，
  **不是**恢复成功保证。
- 真正拿到 ol037 `backup-v6.zip` 后仍需逐项核对的清单：
  1. `schemaVersion` 是否为受支持的 1..6，且清单结构校验零致命；
  2. 16 个 DATA 段（含 `reader_prefs`）齐全且与 `BackupManifest.DATA_SECTIONS` 一致；
  3. 逐本核对 `books.sha256` 与 `files/<bookId>.<ext>` 实际字节的 SHA-256；
  4. 悬挂 FK（例如统计行指向已删书）被跳过，且不导致整次恢复回滚；
  5. `books.id` / `folders.id` 重映射后，标注、想法、统计、进度锚点全部指向目标 id；
  6. 阅读进度、标注/想法、阅读时长（含分列口径）确实从备份迁移到目标设备；
  7. 恢复后逐本实际打开验证，而不是只看清单计数或数据库行数。

## 完整性记录

- 生成器本体 SHA-256：`b3f8a30c8b76b0431f6bf632e7973f009c3dd6d50db596c35e6ba01e5a3832eb`
- MANIFEST.sha256 自身 SHA-256：`dbc8941048be4bed16a492b8051e4a85b6a6dc9c330356c3c850b773aaca00a0`
- ZIP 使用 STORED + DOS 时间 1980-01-01，无时间戳、随机数或网络依赖，重复运行逐字节一致。

## 验证边界（未验，不伪装为通过）

- 当前版本真实 SAF 导出回环：无目标 adb 设备，待验。
- ol037 backup-v6.zip 历史本体兼容：样本本体不存在，待验。
- `LibraryRestore.commit()` 文件落盘 / 事务边界、`BackupActivity.doImport()` 取消：
  本批不改运行时、不提供设备证据。
