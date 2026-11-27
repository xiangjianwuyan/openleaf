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

## 完整性记录

- 生成器本体 SHA-256：`b3f8a30c8b76b0431f6bf632e7973f009c3dd6d50db596c35e6ba01e5a3832eb`
- MANIFEST.sha256 自身 SHA-256：`dbc8941048be4bed16a492b8051e4a85b6a6dc9c330356c3c850b773aaca00a0`
- ZIP 使用 STORED + DOS 时间 1980-01-01，无时间戳、随机数或网络依赖，重复运行逐字节一致。

## 验证边界（未验，不伪装为通过）

- 当前版本真实 SAF 导出回环：无目标 adb 设备，待验。
- ol037 backup-v6.zip 历史本体兼容：样本本体不存在，待验。
- `LibraryRestore.commit()` 文件落盘 / 事务边界、`BackupActivity.doImport()` 取消：
  本批不改运行时、不提供设备证据。
