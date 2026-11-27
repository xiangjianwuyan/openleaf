# library-1000：1000 本纯元数据书架夹具与 perf 采样口径

| 项 | 内容 |
|---|---|
| 目录 | `validation/fixtures/library-1000/` |
| 内容 | `books.json`：1000 条纯元数据（id/title/format/sha256/size/added），不含任何真实用户文档 |
| 数据来源 | 复用 `../txt/` 的 5 个合成 TXT 文件字节与 SHA-256；不复制、不修改用户文件 |
| 门禁状态 | **BLOCKED —— 基线数值由 QA 提供；未提供前不填数、不发包、不判 PASS** |

## 生成与校验

在 `library-1000` 目录：

```powershell
$tmp = 'C:\Users\Public\openleaf-lib1000-gen'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
javac -encoding UTF-8 --release 17 -d $tmp GenerateLibrary1000.java
java -cp $tmp GenerateLibrary1000
```

在 `validation/fixtures` 目录校验清单：

```powershell
sha256sum -c MANIFEST.sha256
```

生成确定性固定：无时间戳/随机数/网络，固定 `added = 1700000000000 + i*60000`（毫秒），
标题与 id 按 `%04d` 编号；`MANIFEST.sha256` 覆盖 `library-1000/books.json` 与所引用的 5 个 txt 夹具。
`.gitattributes`（上级目录，`* -text`）保证检出不改写换行，清单哈希可复现。

## perf 采样口径（只定义，本轮不改生产计时实现）

- **计时区间**：从 `MainActivity.renderShelf()` 的 `libraryRequest++` 起，到 `populateShelf()` 首屏卡片完成（列表可见、可交互）止。
- **口径边界**：首屏不含 `addContinueCard()` 单独计时；封面解码按 T06 每页 ≤4 次计入该区间；不含后续滚动加载。
- **样本量**：冷启动 20 次 + 热启动 20 次，分别记录**中位数**与 **P95**；原始 40 个数值必须随报告附上，不以单点代替。
- **定义**：
  - 冷：进程被杀后重新打开应用，直接进书库并渲染首屏；
  - 热：应用已在后台、书库已渲染过一次，切回后触发 `renderShelf()` 重算。
- **判定**：QA 提供的基线到达后才解冻门禁；未解冻时本项记为 `BLOCKED`，不写"通过/不通过"。

## 设备登记（采样前必填，缺项不采样）

| 字段 | 登记值 |
|---|---|
| 设备 A | Android 12 MuMu 模拟器；分辨率 ____ × ____；dpi ____ |
| 设备 B | Android 17 真机；分辨率 ____ × ____；dpi ____ |
| 字体倍率 | 系统字体缩放 1.0× / 1.3×（两种各采 20 次或注明只采 1.0×） |
| 动画 | 系统动画开关：开 / 关（记录实际状态） |
| 包 | 由 QA 从 `reading-batch-064` 构建；本任务不构建、不跑真机 |

## 覆盖边界

- 本夹具是**纯元数据**，不构成 1000 本书的真实文件导入；它验证的是书库渲染/分页/封面预算路径，不验证导入吞吐。
- 生产代码未加任何计时钩子（架构允许但本轮不加，避免污染性能路径）；采样依赖外部录屏/日志打点，口径以本文件为准。
- 基线由 QA 提供；本目录不预填任何性能数值。
