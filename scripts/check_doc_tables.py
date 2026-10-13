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
- 围栏行按 CommonMark 判定：前导空格 ≤ 3 才算围栏，缩进 ≥ 4 的行在围栏内只是内容；
  闭合围栏须与开栏同字符、长度 ≥ 开栏长度、且不带 info string。

## 用法

    python3 scripts/check_doc_tables.py                    # 查 docs-site/docs 全量
    python3 scripts/check_doc_tables.py a.md b.md          # 查指定文件（须逐个列文件，不支持目录）
    python3 scripts/check_doc_tables.py --self-test        # 跑内嵌判别力自检

退出码：0 = 全部表格列数一致；1 = 有不一致或自检失败（可据此在 CI 中拦截）。
"""

import pathlib
import sys

DOCS_ROOT = "docs-site/docs"
MAX_FENCE_INDENT = 3


def cell_count(line: str) -> int:
    """数一行的竖线数（先剥掉单元格内已转义的 `\\|`，它们不是列分隔符）。"""
    return line.replace("\\|", "\x00").count("|")


def _fence_open(line: str) -> tuple[str, int, str] | None:
    """该行是否为围栏行；是则返回（围栏字符, 长度, info string），否则 None。

    三处都必须对，否则围栏与内容的边界会算错，进而漏检或误报：
    - 带长度：GFM 规定闭合围栏须「同字符且长度 ≥ 开栏长度」，只比字符种类会让
      ````markdown 外层包住 ``` 示例时误配对（4 反引号开、3 反引号被当闭合）。
    - 限定缩进：CommonMark 规定缩进 ≥ 4 的行在围栏内只是内容，不构成围栏；
      不限定的话，围栏里用缩进展示的示例会被当成闭合。
    - 带 info string：闭合围栏不得带 info（`` ```markdown `` 不能当闭栏）。
    """
    indent = len(line) - len(line.lstrip(" "))
    if indent > MAX_FENCE_INDENT:
        return None
    stripped = line[indent:]
    for ch in ("`", "~"):
        n = len(stripped) - len(stripped.lstrip(ch))
        if n >= 3:
            return ch, n, stripped[n:].strip()
    return None


def scan(lines: list[str], label: str) -> tuple[list[str], int]:
    """扫描一个文件的行，返回（问题描述列表, 实际检查的表格行数）。

    `label` 仅用于问题描述里的定位前缀（调用方已知文件路径）。
    """
    problems: list[str] = []
    checked_rows = 0
    i = 0
    fence: tuple[str, int, str] | None = None
    fence_start = 0
    while i < len(lines):
        opened = _fence_open(lines[i])
        if opened is not None:
            # 围栏内不解析表格；闭合须同字符、长度 ≥ 开栏、且不带 info string
            if fence is not None and opened[0] == fence[0] and opened[1] >= fence[1] and not opened[2]:
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
            f"{label}:{fence_start} 围栏代码块（{fence[0] * fence[1]}）未闭合，自该行起余下内容未被检查"
        )
    return problems, checked_rows


# 判别力自检：(名字, 内容行, 期望退出是否为红)
SELF_TESTS: list[tuple[str, list[str], bool]] = [
    ("围栏内坏表不应假红", ["```", "| a | b |", "|---|---|---|", "```"], False),
    ("围栏闭合后的坏表应红", ["```", "| a | b |", "```", "| h1 | h2 |", "| --- | --- |", "| x | y | z |"], True),
    ("未闭合围栏应红", ["```", "x", "| a | b |"], True),
    ("未闭合 ~~~ 应红", ["~~~", "x"], True),
    ("4 反引号外层包 3 反引号示例后跟坏表应红",
     ["````markdown", "```", "````", "| h1 | h2 |", "| --- | --- |", "| x | y | z |", "```"], True),
    ("缩进 4 空格的围栏行是内容不构成闭合",
     ["```", "    ```", "    demo", "```", "", "| h1 | h2 |", "| --- | --- |", "| a | b |"], False),
    ("缩进 4 空格示例后跟坏表应红",
     ["```", "    ```", "    demo", "```", "", "| h1 | h2 |", "| --- | --- |", "| x | y | z |"], True),
    ("闭合围栏带 info string 不算闭合",
     ["```", "x", "```markdown", "| h1 | h2 |", "| --- | --- |", "| x | y | z |"], True),
    ("省略尾竖线按本仓约定判红",
     ["| h1 | h2 | h3 |", "| --- | --- | --- |", "| d | e | f"], True),
    ("反引号内裸竖线算分隔符",
     ["| h1 | h2 |", "| --- | --- |", "| `A` 或 `B | C` 混 | x |"], True),
    ("正常表格不应红", ["| h1 | h2 |", "| --- | --- |", "| a | b |"], False),
]


def self_test() -> int:
    failures = 0
    for name, lines, want_red in SELF_TESTS:
        problems, _ = scan(lines, "<case>")
        got_red = bool(problems)
        if got_red != want_red:
            failures += 1
            print(f"  [FAIL] {name}：期望{'红' if want_red else '绿'}，实际{'红' if got_red else '绿'} {problems}")
    total = len(SELF_TESTS)
    if failures:
        print(f"自检失败：{failures}/{total} 条不符（判据已失去判别力）")
        return 1
    print(f"自检通过：{total} 条样例的期望红/绿全部符合")
    return 0


def main() -> int:
    targets = sys.argv[1:]
    if targets == ["--self-test"]:
        return self_test()
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
    print("[OK] 表格列数一致且围栏闭合")
    return 0


if __name__ == "__main__":
    sys.exit(main())
