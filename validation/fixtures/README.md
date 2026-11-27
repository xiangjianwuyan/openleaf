# OpenLeaf QA-1 合成夹具

全部夹具由 `FixtureGenerator.java` 合成，不含任何真实用户文档；
内容固定、无时间戳与随机数，重复运行逐字节一致。

## 用途

- `txt/three-chapters-bom-crlf.txt`：三章长文本，UTF-8 BOM + CRLF；
  `[MARK-1-A]` / `[MARK-2-B]` / `[MARK-3-C]` 为唯一行标记；
  第 2 章（超长、含重复章标题）切分页共享 `src:1` 锚点 key。
- `txt/manual-gb18030.txt` / `txt/manual-big5.txt` / `txt/utf16-bom.txt`：
  手动指定编码样书，分别验证 GB18030、Big5 与 UTF-16 BOM 识别。
- `txt/boundary-128kib.txt`：正好 131072 字节（128 KiB）的正文上限边界样本。
- `markdown/fenced-sample.md`：三反引号围栏 + 诗歌 + 对白混排。
- `collision/a/same-name.txt` 与 `collision/b/same-name.txt`：同名不同内容，
  用于导入去重与 SAF 可写/只读提供器回归（文件相同，授权不同）。
- `backup/corrupt-backup.zip`：`manifest.json` 声明的 SHA-256 与 `data/books.json`
  实际内容不符，用于恢复前校验与失败不污染目标库验证。
- `comic/five-pages.cbz`：5 张合法 1x1 PNG（STORED ZIP），可枚举页数为 5。
- `media/short-audio.wav`：合法 0.5 秒 8kHz/16bit/单声道 PCM WAV。
- `media/short-video.PLACEHOLDER.md`：环境无可解码编码器，占位；
  **不声称可解码**，测试须显式标注未验。

## 重建命令

```powershell
$root = '<repo-root>'
$tmp  = 'C:\Users\Public\openleaf-fixture-gen'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
javac -encoding UTF-8 --release 17 -d $tmp (Join-Path $root 'validation\fixtures\FixtureGenerator.java')
java -Djava.io.tmpdir=$tmp -cp $tmp FixtureGenerator (Join-Path $root 'validation\fixtures')
```

从 `validation/fixtures` 目录执行 `sha256sum -c MANIFEST.sha256` 校验。
本目录 `.gitattributes` 声明 `* -text`，避免 `core.autocrlf` 在检出时改写换行、
导致清单哈希失效。

## 完整性记录

- 生成器本体 SHA-256：`d609163c42d5f82e66974bb83044ea703657414651e2157dc00e19b8c64d3890`
- 本次 MANIFEST.sha256 自身 SHA-256：`212626bd87e58cae006d5c112c36724e03c61c6409e5562232642c6312e67bdd`
- MANIFEST.sha256 覆盖 12 个夹具文件；
  不覆盖 `README.md`、`MANIFEST.sha256`、`.gitattributes` 与生成器本体（其哈希见上）。
- ZIP 使用 STORED + DOS 时间 1980-01-01，避免压缩器与本地时间影响哈希。

## library-1000（B 批 T07 书库性能夹具）

`library-1000/books.json` 为 1000 条**纯元数据**书架条目（id/title/format/sha256/size/added），
复用 `txt/` 下 5 个合成 TXT 的字节与 SHA-256，不复制、不修改用户真实文件。
独立生成器与清单：

```powershell
cd (Join-Path $root 'validation\fixtures\library-1000')
$tmp = 'C:\Users\Public\openleaf-lib1000-gen'
javac -encoding UTF-8 --release 17 -d $tmp GenerateLibrary1000.java
java -cp $tmp GenerateLibrary1000
cd ..
sha256sum -c MANIFEST.sha256
```

`library-1000/MANIFEST.sha256` 覆盖 `library-1000/books.json` 与所引用的 5 个 txt 夹具；
perf 采样设备/冷定义/20 次中位数与 P95 口径见该目录 `README.md`。
**基线由 QA 提供；未提供前 1000 本性能门禁 BLOCKED，不填数、不发包。**
