#!/usr/bin/env python3
"""文档表格体检：每个表格行必须与同表表头列数一致。

## 为什么需要它

`docs-site/docs/` 下的 Markdown 表格没有编译器兜底。单元格内的 `|` 忘了转义、
或编辑时误删一行的某个 `|`，渲染出来就是错列或空列——**本地探针、单测、
`check_architecture.py`、CI 全都不会发现**，只有人肉眼读渲染结果才看得出来。

2026-10-13 实例：补 `error-codes.md` 时误删行尾换行，两行挤成一行（竖线数变 5），
该缺陷随 tag 发进了 rc10；另一份 `dashboard-cards.md` 的分隔行少一列，同批扫出。

## 用法

    python3 scripts/check_doc_tables.py                    # 查 docs-site/docs 全量
    python3 scripts/check_doc_tables.py docs-site/docs/guide/error-codes.md

退出码：0 = 全部表格列数一致；1 = 有不一致（可据此在 CI 中拦截）。
"""

import pathlib
import sys

DOCS_ROOT = "docs-site/docs"


def cell_count(line: str) -> int:
    """数一行的竖线数（先剥掉单元格内已转义的 `\\|`，它们不是列分隔符）。"""
    return line.replace("\\|", "\x00").count("|")


def find_mismatches(path: pathlib.Path) -> list[str]:
    """返回该文件里所有「与同表表头列数不一致」的行描述。"""
    problems: list[str] = []
    lines = path.read_text(encoding="utf-8").split("\n")
    i = 0
    while i < len(lines):
        if not lines[i].lstrip().startswith("|"):
            i += 1
            continue
        # 连续以 `|` 开头的行视为一个表格
        start = i
        while i < len(lines) and lines[i].lstrip().startswith("|"):
            i += 1
        block = lines[start:i]
        head = cell_count(block[0])
        for offset, row in enumerate(block):
            cols = cell_count(row)
            if cols != head:
                problems.append(
                    f"{path}:{start + offset + 1} 表头 {head} 列、本行 {cols} 列 — {row.strip()[:80]}"
                )
    return problems


def main() -> int:
    targets = sys.argv[1:]
    if targets:
        files = [pathlib.Path(t) for t in targets]
    else:
        root = pathlib.Path(DOCS_ROOT)
        if not root.is_dir():
            print(f"[FAIL] 找不到 {DOCS_ROOT}（工作目录对吗？）")
            return 1
        files = sorted(root.rglob("*.md"))

    tables = 0
    rows = 0
    problems: list[str] = []
    for f in files:
        if not f.is_file():
            print(f"[FAIL] 文件不存在：{f}")
            return 1
        found = find_mismatches(f)
        rows += sum(1 for ln in f.read_text(encoding="utf-8").split("\n") if ln.lstrip().startswith("|"))
        tables += 1 if found else 0
        problems += found

    print(f"文档表格体检：{len(files)} 个文件 / {rows} 个表格行")
    if problems:
        print(f"[FAIL] {len(problems)} 处列数与表头不一致：")
        for p in problems:
            print(f"  {p}")
        return 1
    print("[OK] 全部表格列数与表头一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
