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
- 围栏闭合按 CommonMark 近似：同字符、长度 ≥ 开栏长度、不带 info string，
  且闭栏缩进不超过开栏缩进 +3（后者让列表项内缩进书写的围栏也能正确配对——
  用「绝对缩进 ≤3」会误判列表里的围栏）。
- **不处理缩进代码块**：顶层缩进 ≥4 的类围栏行（CommonMark 里属缩进代码块、不是围栏）
  会被当开栏，若其后没有匹配闭栏则报「围栏未闭合」。语料当前无此形状（缩进 ≥4 的
  围栏行实测 0 处）；若日后命中，症状就是这类「文件末尾未闭合」红灯。

## 用法

    python3 scripts/check_doc_tables.py                    # 查 docs-site/docs 全量
    python3 scripts/check_doc_tables.py a.md b.md          # 查指定文件（须逐个列文件，不支持目录）
    python3 scripts/check_doc_tables.py --self-test        # 跑内嵌判别力自检

退出码：0 = 全部表格列数一致；1 = 有不一致或自检失败（可据此在 CI 中拦截）。

## 维护策略

**只修真缺陷，不再扩判据、不再加对账强度。** 真实判据只有 9 条条件；语料侧全绿，
rc10 那类表格缺陷的直接判据已闭环。历轮已引入：围栏跳过、长度比较、缩进放宽（相对
缩进）、未闭合判红、转义剥离、`--self-test` 与锚点双向对账——继续加固的收益已归零。
新增对账机制前先问「它拦的是哪类真实缺陷，有没有实例」，拿不出实例就不加。
"""

import inspect
import pathlib
import re
import sys

DOCS_ROOT = "docs-site/docs"
# 闭栏相对开栏允许的最大额外缩进（开栏可任意缩进，以兼容列表项内的围栏）
FENCE_CLOSE_INDENT_SLACK = 3


def cell_count(line: str) -> int:
    """数一行的竖线数（先剥掉单元格内已转义的 `\\|`，它们不是列分隔符）。"""
    return line.replace("\\|", "\x00").count("|")  # RULE:转义剥离


def _fence_open(line: str) -> tuple[str, int, int, str] | None:
    """该行是否为围栏行；是则返回（围栏字符, 长度, 缩进, info string），否则 None。

    三处都必须对，否则围栏与内容的边界会算错，进而漏检或误报：
    - 带长度：GFM 规定闭合围栏须「同字符且长度 ≥ 开栏长度」，只比字符种类会让
      ````markdown 外层包住 ``` 示例时误配对（4 反引号开、3 反引号被当闭合）。
    - 带缩进：开栏可任意缩进（列表项内书写的围栏合法），但**闭栏缩进不得超过
      开栏缩进 +3**，否则顶格围栏里用缩进展示的示例会被当成闭合。
    - 带 info string：闭合围栏不得带 info（`` ```markdown `` 不能当闭栏）。
    """
    indent = len(line) - len(line.lstrip(" "))  # RULE:开栏任意缩进
    stripped = line[indent:]
    for ch in ("`", "~"):
        n = len(stripped) - len(stripped.lstrip(ch))
        if n >= 3:                              # RULE:三反引号门槛
            return ch, n, indent, stripped[n:].strip()
    return None


def scan(lines: list[str], label: str) -> tuple[list[str], int]:
    """扫描一个文件的行，返回（问题描述列表, 实际检查的表格行数）。

    `label` 仅用于问题描述里的定位前缀（调用方已知文件路径）。
    """
    problems: list[str] = []
    checked_rows = 0
    i = 0
    fence: tuple[str, int, int, str] | None = None
    fence_start = 0
    while i < len(lines):
        opened = _fence_open(lines[i])
        if opened is not None:
            closes = (
                fence is not None
                and opened[0] == fence[0]           # RULE:同字符
                and opened[1] >= fence[1]           # RULE:长度比较
                and opened[2] <= fence[2] + FENCE_CLOSE_INDENT_SLACK  # RULE:缩进放宽
                and not opened[3]                   # RULE:info规则
            )
            if closes:
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
            if cols != head:                        # RULE:表格列数
                problems.append(
                    f"{label}:{start + offset + 1} 表头 {head} 列、本行 {cols} 列 — {row.strip()[:80]}"
                )
    if fence is not None:                           # RULE:未闭合红灯
        problems.append(
            f"{label}:{fence_start} 围栏代码块（{fence[0] * fence[1]}）未闭合，自该行起余下内容未被检查"
        )
    return problems, checked_rows


# 判别力自检：(名字, 内容行, 期望退出是否为红)
#
# 规则 ↔ 样例的勾稽由 RULE_TO_SAMPLES 维护，并在 self_test 末尾机器对账：
# ① 源码里的 `# RULE:xxx` 锚点集合 与 字典键集合 取双向差集；
# ② 字典引用的样例名必须在 SELF_TESTS 里存在；
# ③ 每条样例必须至少被一个规则引用。
#
# 边界（两类都抓不到，都要靠人）：
# ① 新增判据时既不给样例、也不写 `# RULE:` 锚点——它无从得知那个 if 是判据；
# ② 名义配对：锚点写了、键登记了，但样例挂错（挂了一条跟该判据无关的红/绿样例）。
#    此时双向对账全绿，而判据行为已坏。要抓这类需变异对账（实现在内存里改坏
#    判据、断言声明样例必须翻转），成本高，当前不引入。
# 锚点是「人为声明」，能扭住的是「声明与样例不匹配」，扭不住上述两类。
RULE_TO_SAMPLES: dict[str, list[str]] = {
    "开栏任意缩进": ["列表项内缩进的围栏不应假红"],
    "三反引号门槛": ["围栏内坏表不应假红"],
    "同字符": ["异种围栏不能闭合"],
    "长度比较": ["4 反引号外层包 3 反引号示例后跟坏表应红"],
    "缩进放宽": ["顶格围栏内缩进 4 空格的示例不应红"],
    "info规则": ["带 info 的伪闭栏后跟合法表应红"],
    "未闭合红灯": ["未闭合围栏应红", "未闭合 ~~~ 应红"],
    "表格列数": [
        "围栏闭合后的坏表应红",
        "顶格围栏内缩进示例后跟坏表应红",
        "省略尾竖线按本仓约定判红",
        "反引号内裸竖线算分隔符",
        "围栏内坏表不应假红",
        "正常表格不应红",
    ],
    "转义剥离": ["单元格内含转义竖线不应假红"],
}

SELF_TESTS: list[tuple[str, list[str], bool]] = [
    ("围栏内坏表不应假红", ["```", "| a | b |", "|---|---|---|", "```"], False),
    ("围栏闭合后的坏表应红", ["```", "| a | b |", "```", "| h1 | h2 |", "| --- | --- |", "| x | y | z |"], True),
    ("未闭合围栏应红", ["```", "x", "| a | b |"], True),
    ("未闭合 ~~~ 应红", ["~~~", "x"], True),
    ("4 反引号外层包 3 反引号示例后跟坏表应红",
     ["````markdown", "```", "````", "| h1 | h2 |", "| --- | --- |", "| x | y | z |", "```"], True),
    ("顶格围栏内缩进 4 空格的示例不应红",
     ["```", "    ```", "    demo", "```", "", "| h1 | h2 |", "| --- | --- |", "| a | b |"], False),
    ("顶格围栏内缩进示例后跟坏表应红",
     ["```", "    ```", "    demo", "```", "", "| h1 | h2 |", "| --- | --- |", "| x | y | z |"], True),
    ("列表项内缩进的围栏不应假红",
     ["- step:", "", "    ```", "    | a | b |", "    |---|---|---|", "    ```"], False),
    ("异种围栏不能闭合",
     ["```", "x", "~~~", "", "| h1 | h2 |", "| --- | --- |", "| a | b |"], True),
    ("带 info 的伪闭栏后跟合法表应红",
     ["```", "x", "```markdown", "| h1 | h2 |", "| --- | --- |", "| a | b |"], True),
    ("单元格内含转义竖线不应假红",
     ["| h1 | h2 |", "| --- | --- |", "| a \\| b | c |"], False),
    ("省略尾竖线按本仓约定判红",
     ["| h1 | h2 | h3 |", "| --- | --- | --- |", "| d | e | f"], True),
    ("反引号内裸竖线算分隔符",
     ["| h1 | h2 |", "| --- | --- |", "| `A` 或 `B | C` 混 | x |"], True),
    ("正常表格不应红", ["| h1 | h2 |", "| --- | --- |", "| a | b |"], False),
]


def self_test() -> int:
    scan_original = scan
    failures = 0
    for name, lines, want_red in SELF_TESTS:
        problems, _ = scan_original(lines, "<case>")
        got_red = bool(problems)
        if got_red != want_red:
            failures += 1
            print(f"  [FAIL] {name}：期望{'红' if want_red else '绿'}，实际{'红' if got_red else '绿'} {problems}")
    total = len(SELF_TESTS)

    # 阳性对照：把 scan 桩成「恒不报」，必须有样例因此失败；否则说明样例本身
    # 已失去判别力（判据改瞎和样例改瞎两种失效都要被这条命令抓到）。
    # 直接用模块字典替换，不用 global（避免与 scan 的直接引用冲突）。
    stubbed = lambda _lines, _label: ([], 0)  # noqa: E731
    globals()["scan"] = stubbed
    try:
        still_green = [name for name, lines, want_red in SELF_TESTS if want_red and not scan(lines, "<case>")[0]]
    finally:
        globals()["scan"] = scan_original
    if not still_green:
        failures += 1
        print("  [FAIL] 阳性对照失败：没有任何样例会在「判据恒不报」时变红，样例无判别力")

    # 勾稽对账（双向）：从源码里抽 `# RULE:xxx` 锚点得到「实现里有多少条判据」，
    # 与 RULE_TO_SAMPLES 的键取双向差集。
    # 只对样例侧单向对账不够——实测：规则存在但无样例时静默全绿（这正是本脚本的
    # 真实成因：「同字符」条件一度无任何样例钉住）。双向差集能把「新增判据忘配样例」
    # 也变成红灯；反过来锚点被删/改名同样立刻报红。
    #
    # 正则要求锚点**独占行尾**（`# RULE:名字` 后只能是行尾）：
    # 早期用 `\S+` 会吞下散文里的字面量（如注释里写「见 `# RULE:xxx` 的形式」），
    # 一旦这类句子进了函数体，锚点集会被污染成假红。
    # 字符类用 [^\s`]+：\S+ 会吞反引号、也不允许名字带空格（正是上一笔
    # 「info 规则」与「info规则」错配的成因）。注意：锚点必须**独占行尾**才被认出，
    # 所以「带空格的锚点会被显式报出」只在字典里还留着该键时成立（此时报「找不到
    # 锚点」）；键也没写时等价于压根没写锚点，归入下方边界①。
    # 对账作用域只含「真正带判据的函数」：三个函数体外还有散文提到 `# RULE:xxx`，
    # 不放进来就天然免疫；放进来会多一层依赖反引号才能不误匹配。
    scope = "".join(inspect.getsource(f) for f in (cell_count, _fence_open, scan))
    anchored = set(re.findall(r"#\s*RULE:([^\s`]+)\s*$", scope, re.M))
    # 形态卫生：域内出现 `# RULE:` 字面量但形态不合法（未独占行尾）时直接报出。
    # 否则作者以为「写了锚点」，而它等于没写。
    # 用**计数式**而非集合相减：集合相减下，畸形锚点若与某条合法锚点同名会被减掉而静默
    # （实测：复用一个已存在的规则名再挂尾注 → 退 0，那处锚点实际没被认出）。
    total_markers = len(re.findall(r"#\s*RULE:", scope))
    if total_markers != len(anchored):
        malformed = set(re.findall(r"#\s*RULE:(\S+)", scope)) - anchored
        for bad in sorted(malformed) or ["(与合法锚点同名，看不出名字)"]:
            failures += 1
            print(f"  [FAIL] 疑似锚点但形态不合法：「{bad}」——`# RULE:名字` 后必须是行尾")
    declared = set(RULE_TO_SAMPLES)
    for rule in sorted(anchored - declared):
        failures += 1
        print(f"  [FAIL] 源码里有判据锚点「{rule}」但 RULE_TO_SAMPLES 未登记（新增判据忘配样例？）")
    for rule in sorted(declared - anchored):
        failures += 1
        print(f"  [FAIL] RULE_TO_SAMPLES 里的「{rule}」在源码里找不到 `# RULE:` 锚点（判据可能已被删/改名）")

    sample_names = {name for name, _, _ in SELF_TESTS}
    referenced: set[str] = set()
    for rule, names in RULE_TO_SAMPLES.items():
        for n in names:
            if n not in sample_names:
                failures += 1
                print(f"  [FAIL] 规则「{rule}」引用了不存在的样例：{n}")
            referenced.add(n)
    unreferenced = sorted(sample_names - referenced)
    if unreferenced:
        failures += 1
        print(f"  [FAIL] 有样例未被任何规则引用（改名未同步，或该样例已无对应判据）：{unreferenced}")

    if failures:
        print(f"自检失败：{failures} 项不符（判据已失去判别力）")
        return 1
    print(f"自检通过：{total} 条样例的期望红/绿全部符合，阳性对照有效，{len(RULE_TO_SAMPLES)} 条规则勾稽完整")
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
