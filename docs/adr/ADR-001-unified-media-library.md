# ADR-001：统一媒体库影子表（设计 Y）

- 状态：已实施（0.37.0 / DB v18 / 清单 v6）
- 日期：2026-11-25
- 相关：LibraryDatabase.java、BookStore.java、LibraryBackup/Restore、`OpenLeaf-books.title物理删列评审.md`

## 背景

漫画成为一等公民后，`books` 表同时承载文字书与漫画；音频/视频又有独立的媒体归属表。遇到三个问题：
1. 同一路径可能在不同表里有不同标题/类型，列表与搜索口径不一致。
2. 新增媒体类型要不断往 `books` 加列。
3. 备份恢复时类型判断分散在多个写入点。

## 选项

- **A. 继续在 books 加列**：改动小，但类型越多列越杂，且 `books.title` 语义混乱。
- **B. 统一媒体表 + 物理删除 books.title**：架构干净，但 SQLite 3.32（MuMu 实机）不支持 DROP COLUMN，需重建表；`books(id)` 被 10 张表 REFERENCES；备份 v4/v5/v6 要双向兼容——收益近零、风险极高。
- **C. 影子表 `media_items` + 读切换**：新增影子表存权威 title/kind，读路径优先读影子表、无行时回退 books.title；保留 books.title 兼容列。

## 决定

选 **C**：新增 `media_items(path PRIMARY KEY, kind, title, duration, cover, mtime, size)` 影子表；5 处 books 插入后双写；`toBook()` 读切换（media_items 无行回退 books.title）；清单 v5→v6；逻辑去重而非物理删列。

## 后果

- 正面：类型扩展不再污染 books；列表/搜索/详情标题一个权威源；DB v18 与清单 v6 平稳迁移；旧备份可读。
- 负面：双写有遗漏风险（0.37 曾出现 `file` 为空导致影子表零行，修法为派生 `book://<id>.<format>` 稳定 path）；读切换后必须全仓 grep 所有 `toBook` 调用对称传 db。
- 关键教训：**备份恢复的悬挂 FK**（旧备份 `reading_time.book_id` 不在 books）导致 787 错误——修复为恢复前过滤并生成非 fatal Issue。这成为"数据正确但用户不知情"类问题的教材。
- 不做：books.title 物理删列——见 `OpenLeaf-books.title物理删列评审.md`，SQLite 版本与 10 张 FK 表决定收益不抵风险。
