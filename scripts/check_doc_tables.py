#!/usr/bin/env python3
"""文档表格体检：带首尾竖线的 GFM 表格，每行必须与同表表头列数一致。

## 为什么需要它

`docs-site/docs/` 下的 Markdown 表格没有编译器兜底。单元格内的 `|` 忘了转义、
或编辑时误删一行的某个 `|`，渲染出来就是错列或空列——**本地探针、单测、
`check_architecture.py` 全都不会发现**，只有人肉眼读渲染结果才看得出来。

2026-10-13 实例：补 `error-codes.md` 时误删行尾换行，两行挤成一行（竖线数变 5），
该缺陷随 tag 发进了 rc10；另一份 `dashboard-cards.md` 的分隔行少一列，同批扫出。

**判据覆盖范围的边界**（`[OK]` 只说明「本判据覆盖的那类表格没问题」，不是「所有表格都没问题」）：
只检查**带首尾竖线**、且**至少两行连排**的 GFM 表。以下四类不覆盖（均实测）：
省略行首竖线的合法行（`a | b |`）、引用块内的表格（`> | a | b |`）、
只有表头没有分隔行的两行表、单行表。围栏代码块（``` / ~~~）内的内容会被跳过。

## 用法

    python3 scripts/check_doc_tables.py                    # 查 docs-site/docs 全量
    python3 scripts/check_doc_tables.py a.md b.md          # 查指定文件

退出码：0 = 全部表格列数一致；1 = 有不一致（可据此在 CI 中拦截）。
"""

import pathlib
import sys

DOCS_ROOT = "docs-site/docs"


def cell_count(line: str) -> int:
    """数一行的竖线数（先剥掉单元格内已转义的 `\\|`，它们不是列分隔符）。"""
    return line.replace("\\|", "\x00").count("|")


def _fence_open(line: str) -> str | None:
    """该行是否为围栏代码块的起始围栏；是则返回其围栏串（``` 或 ~~~），否则 None。"""
    stripped = line.lstrip()
    for fence in ("```", "~~~"):
        if stripped.startswith(fence):
            return fence
    return None


def find_mismatches(path: pathlib.Path) -> list[str]:
    """返回该文件里所有「与同表表头列数不一致」的行描述。"""
    problems: list[str] = []
    lines = path.read_text(encoding="utf-8").split("\n")
    i = 0
    fence: str | None = None
    while i < len(lines):
        opened = _fence_open(lines[i])
        if opened is not None:
            # 围栏内不解析表格；遇到同种围栏即闭合
            fence = None if fence == opened else (fence or opened)
            i += 1
            continue
        if fence is not None:
            i += 1
            continue
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

    rows = 0
    problems: list[str] = []
    bad_files = 0
    for f in files:
        if not f.is_file():
            print(f"[FAIL] 文件不存在：{f}")
            return 1
        found = find_mismatches(f)
        rows += sum(1 for ln in f.read_text(encoding="utf-8").split("\n") if ln.lstrip().startswith("|"))
        if found:
            bad_files += 1
        problems += found

    print(f"文档表格体检：{len(files)} 个文件 / {rows} 个表格行")
    if problems:
        print(f"[FAIL] {bad_files} 个文件含 {len(problems)} 处列数与表头不一致：")
        for p in problems:
            print(f"  {p}")
        return 1
    print("[OK] 全部表格列数与表头一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
