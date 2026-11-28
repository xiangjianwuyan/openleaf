#!/usr/bin/env python3
"""把 library-1000 纯元数据夹具转换成可被当前 0.63.0 恢复入口导入的 v6 备份 zip。

只做本地文件操作：读 library-1000/books.json，输出一个仅含
  data/books.json  +  manifest.json
的 zip。不碰设备、不装包、不碰用户真实文件、不提交 git。

依据（只读核实的源码事实，HEAD e5b38d6）：
- BackupManifest.validateStructure 只校验条目合法性，不要求 16 个 DATA 段齐全；
- LibraryRestore.writeRow 把 books.file 视为 regenerate 字段跳过，随后统一填
  "<sourceId>.txt"，因此 rows 不需要 file 列，也不需要任何 FILE 段；
- BookStore.all() 全表无 LIMIT，1000 条独立 rows 会全部进入 renderShelf。

用法：
  python build-restore-zip.py --out <zip 路径> [--fixture <books.json>] [--created-at <ms>]

可重复生成：rows 顺序、createdAt、zip 条目时间戳与压缩方式均固定。
"""

import argparse
import hashlib
import json
import zipfile
from pathlib import Path

COLUMNS = [
    "id", "title", "format", "file", "added", "sha256",
    "order_index", "media_type", "page_count", "cover_entry",
]
DEFAULT_CREATED_AT = 1_700_000_000_000
EXPECTED_COUNT = 1000


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", default=str(Path(__file__).resolve().parent / "books.json"))
    parser.add_argument("--out", required=True, help="输出 zip 绝对路径")
    parser.add_argument("--created-at", type=int, default=DEFAULT_CREATED_AT)
    args = parser.parse_args()

    fixture = Path(args.fixture).resolve()
    source = json.loads(fixture.read_text(encoding="utf-8"))
    if source.get("schema") != 1:
        raise SystemExit("夹具 schema 不是 1，拒绝生成")
    books = source.get("books")
    if not isinstance(books, list) or len(books) != EXPECTED_COUNT:
        raise SystemExit(f"夹具 books 条数不是 {EXPECTED_COUNT}，拒绝生成")

    # 转成备份 rows 格式。file 列刻意不写：writeRow 会 regenerate 成 <sourceId>.txt。
    rows = []
    for i, book in enumerate(books):
        rows.append({
            "id": book["id"],
            "title": book["title"],
            "format": book["format"],
            "added": book["added"],
            "sha256": book["sha256"],
            "order_index": -(i + 1),
            "media_type": "book",
            "page_count": None,
            "cover_entry": None,
        })

    books_payload = json.dumps(
        {"table": "books", "columns": COLUMNS, "rows": rows},
        ensure_ascii=False, separators=(",", ":"), sort_keys=False,
    ).encode("utf-8")

    manifest = {
        "schemaVersion": 6,
        "createdAt": args.created_at,
        "app": "OpenLeaf/0.63.0",
        "entries": [{
            "path": "data/books.json",
            "kind": "DATA",
            "sha256": sha256_hex(books_payload),
            "size": len(books_payload),
            "part": "LIBRARY",
        }],
    }
    manifest_payload = json.dumps(
        manifest, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")

    out = Path(args.out).resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, payload in (("data/books.json", books_payload), ("manifest.json", manifest_payload)):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, payload)

    print(f"OUT={out}")
    print(f"OUT_SHA256={sha256_hex(out.read_bytes())}")
    print(f"BOOKS_SHA256={sha256_hex(books_payload)}")
    print(f"BOOKS_SIZE={len(books_payload)}")
    print(f"ROWS={len(rows)}")


if __name__ == "__main__":
    main()
