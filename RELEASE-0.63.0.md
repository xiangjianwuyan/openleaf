# OpenLeaf 0.63.0：备份恢复回归测试与 ID 映射修复

本版把 0.62 真机发现的失败模式固化为自动化回归，并修复新增测试暴露的恢复 ID 映射缺陷。提交链 `00c3741 → add92e5 → d04812a → 4d26bb3 → e5b38d6`，版本 `0.63.0 / vc75`。

## 修复

- **恢复 ID 映射**：`LibraryRestore.preflight()` 的 `planIdMap()` 第三参原传表名而非列名，导致 `bookIdMap` / `folderIdMap` 恒空、恢复时退化为原 id 保留。现改为 `"id"`，恢复时按 SHA-256 命中目标书则复用 id，否则生成新 UUID；进度、锚点、书签、阅读时长、会话、文件夹引用按映射迁移。
- **debug 插桩清单**：8 个 instrumentation 由相对名改为全限定名，修复 `applicationIdSuffix` 包中相对名解析成不存在类的问题；仅影响 debug 测试包。

## 新增回归测试

- `BackupRoundtripInstrumentation` 第 7 个 check：SAF 引用书（file 为空、字节在 content URI）导出→恢复往返，断言 FILE 段、哈希、大小、无缺失提示、授权不导出、目标文件字节一致、`BookStore.open()` 可读。
- `BackupLegacyShapeTest`：32 项纯 JVM 检查，覆盖 v1..v6 结构接受、坏清单逐项拒绝、旧包缺 `reader_prefs` 不致命、设备字段/表不进备份。输出明确声明仅结构层，不代表 ol037 本体兼容。
- core 83 → **84/84 全绿**；插桩 **7/7 PASS**；真机 A→B 回环 **8/8 PASS**（3 本新 UUID + welcome 按 SHA 复用，书签/锚点/进度/时长按新 id 迁移）。

## 升级注意（重要）

1. **恢复 ID 语义变更**：0.63 起恢复备份后书 id 可能改变，不再是旧 id 原样保留。进度、锚点、书签、阅读时长、会话按映射迁移；跨版本恢复后不要按旧 id 直接查库或对接外部系统。
2. **ol037 历史备份本体仍缺**：用户确认本机找不到 ol037 `backup-v6.zip`，该项维持 **BLOCKED**。v4/v5 合成样本只证明结构层接受，不证明旧备份可恢复。
3. **旧备份缺本体的书恢复后不可读**：真实 v1 老包（0.11/0.17）验证显示，备份中有 FILE 段的书（含 19MB 大文件、epub、txt）能正确落盘可读；未打包 FILE 段的书 `file` 指向不存在文件、打不开。这是老备份的历史局限，**不要期待 0.63 从备份中变出原文件**。
4. 真实 v1 验证只覆盖 0.11/0.17 的 v1 包，**不覆盖 ol037 的 v6 本体**，两者不可互相替代。

## 构建

- debug APK SHA-256：`9af92f439db41b03751aeef4ee6f7fd5394b96475a8786a3f7245ed831b89ca4`
- 包名 `org.openleaf.reader`，versionCode 75 / versionName 0.63.0，minSdk 26 / targetSdk 35。
