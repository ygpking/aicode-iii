#!/usr/bin/env python3
"""文档表格体检：带首尾竖线的 GFM 表格，每行必须与同表表头列数一致。

## 为什么需要它

`docs-site/docs/` 下的 Markdown 表格没有编译器兜底。单元格内的 `|` 忘了转义、
或编辑时误删一行的某个 `|`，渲染出来就是错列或空列——**本地探针、单测、
`check_architecture.py` 全都不会发现**，只有人肉眼读渲染结果才看得出来。

2026-10-13 实例：补 `error-codes.md` 时误删行尾换行，两行挤成一行（竖线数变 5），
该缺陷随 tag 发进了 rc10；另一份 `dashboard-cards.md` 的分隔行少一列，同批扫出。

## 判据覆盖范围的边界

`[OK]` 只说明「**本判据覆盖的那类表格**没问题」，不是「所有表格都没问题」。

- **不覆盖**（均实测）：省略**行首**竖线的合法行（`a | b |`）、引用块内的表格
  （`> | a | b |`）、只有表头没有分隔行的两行表、单行表。
- **有意判红**（本仓约定：表格行必须带首尾竖线）：省略**尾部**竖线的 `| d | e | f`
  会被当作缺陷拦下，而不是按 GFM 合法行放行。
- **能抓**：反引号内的裸 `|` 在 GFM 里仍算列分隔符，故 ``| `A` 或 `B | C` 混 | x |``
  这类错列会被抓到，不需要为代码段做豁免。
- 围栏代码块（``` / ~~~）内的内容一律跳过；**围栏未闭合**会使该文件自此行起的内容
  无法解析，故判红（避免「解析不了」被静默当成「没问题」）。

## 用法

    python3 scripts/check_doc_tables.py                    # 查 docs-site/docs 全量
    python3 scripts/check_doc_tables.py a.md b.md          # 查指定文件（须逐个列文件，不支持目录）

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


def scan(lines: list[str], label: str) -> tuple[list[str], int]:
    """扫描一个文件的行，返回（问题描述列表, 实际检查的表格行数）。

    `label` 仅用于问题描述里的定位前缀（调用方已知文件路径）。
    """
    problems: list[str] = []
    checked_rows = 0
    i = 0
    fence: str | None = None
    fence_start = 0
    while i < len(lines):
        opened = _fence_open(lines[i])
        if opened is not None:
            # 围栏内不解析表格；遇到同种围栏即闭合
            if fence == opened:
                fence = None
            elif fence is None:
                fence, fence_start = opened, i + 1
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
            checked_rows += 1
            cols = cell_count(row)
            if cols != head:
                problems.append(
                    f"{label}:{start + offset + 1} 表头 {head} 列、本行 {cols} 列 — {row.strip()[:80]}"
                )
    if fence is not None:
        problems.append(
            f"{label}:{fence_start} 围栏代码块（{fence}）未闭合，自该行起余下内容未被检查"
        )
    return problems, checked_rows


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
            print(f"[FAIL] 文件不存在：{f}（只接受文件路径，不支持目录）")
            return 1
        lines = f.read_text(encoding="utf-8").split("\n")
        found, checked = scan(lines, str(f))
        rows += checked
        if found:
            bad_files += 1
        problems += found

    print(f"文档表格体检：{len(files)} 个文件 / {rows} 个表格行")
    if problems:
        print(f"[FAIL] {bad_files} 个文件含 {len(problems)} 处问题：")
        for p in problems:
            print(f"  {p}")
        return 1
    print("[OK] 全部表格列数与表头一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
