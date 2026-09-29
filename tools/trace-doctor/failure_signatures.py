#!/usr/bin/env python3
"""失败指纹聚类 + 补强建议（B3）

把轨迹里的工具失败按「工具 + 归一化错误摘要」聚成指纹，跨会话统计频次，
再给出该补什么的候选建议——目标是从失败分布反推「提示词/工具定义缺了什么约束」，
而不是拍脑袋加工具。

**建议是线索，不是结论**：每条建议都附可回溯证据（会话/回合/原始行），
必须能回读原文确认同类，才值得动手。

只用标准库。用法：

    python3 failure_signatures.py <轨迹目录或文件...> [--out FILE] [--min-count N]
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass

# 复用 A1 的解析：同一条 tool_finished 行的语义只应有一处实现
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from trace_metrics import RE_LINE, RE_TOOL_FINISHED, _reason_of, expand  # noqa: E402

# 指纹 → 建议模板。key 是归一化原因里的特征子串（小写匹配）。
# 每条都对应一类「该补的约束」，而非「该修的 bug」。
SUGGESTIONS: list[tuple[str, str, str]] = [
    ("no such file", "路径不存在", "工具调用前未校验路径。在提示词里加「写/读文件前先用 list 确认路径存在」，并让工具失败时回显可用候选路径。"),
    ("file exists", "目标已存在", "缺覆盖语义。给写入类工具加明确参数（如 overwrite=true），并让错误信息里直接给出「加参数即可覆盖」。"),
    ("permission denied", "权限不足", "降权或凭据问题。失败信息应指明是 uid、目录权限还是密钥；必要时在工具层预检可写性。"),
    ("timeout", "超时", "缺超时预算与退避。给长命令默认超时+分片输出；对网络类失败区分「服务端慢」与「我们等太短」。"),
    ("command not found", "命令不存在", "容器/环境认知缺失。在提示词里列出当前环境可用命令，或让工具失败时提示「该命令不在本环境」。"),
    ("not found", "未找到", "目标缺失。失败信息应区分「路径不存在」「资源 404」「字段缺失」三种，避免同一个词指三件事。"),
    ("syntax", "语法错误", "模型生成的内容本身有语法错。考虑在写入前做一次解析预检，把错误回给模型自我修正。"),
    ("context", "上下文相关", "上下文超限或缺失。检查压缩策略与该次调用的历史长度；必要时降级为分次读取。"),
    ("exceeded", "超出上限", "缺配额/上限提示。工具应在接近上限时预警，而不是到失败才说。"),
    ("invalid", "参数非法", "参数约束未在工具定义里写清（类型、枚举、边界）。补 schema 校验与更具体的错误消息。"),
    ("connection", "连接失败", "网络/服务不可达。区分临时性与持续性失败，临时性应自动重试而非上报。"),
]

DEFAULT_SUGGESTION = "无匹配模板。先人工看几条原文判断是「工具缺口」还是「提示词没说清」，再决定归属。"


@dataclass
class Hit:
    session: str
    turn: str
    tool: str
    reason: str
    line: str


def collect(paths: list[str]) -> tuple[Counter, dict[tuple[str, str], list[Hit]]]:
    counter: Counter = Counter()
    examples: dict[tuple[str, str], list[Hit]] = defaultdict(list)
    for path in paths:
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                for raw in fh:
                    m = RE_LINE.match(raw.rstrip("\n"))
                    if not m or m.group("layer") != "EVENT":
                        continue
                    detail = m.group("detail")
                    fm = RE_TOOL_FINISHED.match(detail)
                    if not fm or fm.group("status") != "失败":
                        continue
                    tool = fm.group("name")
                    reason = _reason_of(detail)
                    key = (tool, reason)
                    counter[key] += 1
                    if len(examples[key]) < 3:
                        examples[key].append(
                            Hit(m.group("s"), m.group("t"), tool, reason, raw.rstrip("\n")[:200])
                        )
        except OSError as e:
            print(f"跳过 {path}：{e}", file=sys.stderr)
    return counter, examples


def suggest_for(reason: str) -> str:
    low = reason.lower()
    for key, _label, advice in SUGGESTIONS:
        if key in low:
            return advice
    return DEFAULT_SUGGESTION


def label_of(reason: str) -> str:
    low = reason.lower()
    for key, label, _advice in SUGGESTIONS:
        if key in low:
            return label
    return "未分类"


def report(counter: Counter, examples: dict, min_count: int) -> str:
    lines: list[str] = []
    add = lines.append
    total = sum(counter.values())
    add("# 失败指纹聚类与补强建议\n")
    add(f"- 失败总数：{total}")
    add(f"- 不同指纹：{len(counter)}")
    add(f"- 覆盖会话：{len({h.session for hits in examples.values() for h in hits})}(样例统计)")
    add("")
    if not counter:
        add("（轨迹里没有工具失败记录）")
        return "\n".join(lines)

    add("## 指纹排行\n")
    add("| # | 工具 | 分类 | 归一化原因 | 次数 | 占比 |")
    add("| --- | --- | --- | --- | --- | --- |")
    for i, ((tool, reason), count) in enumerate(counter.most_common(), 1):
        add(f"| {i} | {tool} | {label_of(reason)} | {reason} | {count} | {count / total * 100:.1f}% |")
    add("")

    add("## 值得动手的候选（次数 ≥ %d）\n" % min_count)
    for (tool, reason), count in counter.most_common():
        if count < min_count:
            continue
        add(f"### {tool} · {label_of(reason)}（{count} 次）")
        add("")
        add(f"- **归一化原因**：`{reason}`")
        add(f"- **建议**：{suggest_for(reason)}")
        add("- **证据（可回溯）**：")
        for h in examples[(tool, reason)]:
            add(f"  - `s={h.session} {h.turn}` — {h.line}")
        add("")
    add("## 使用纪律")
    add("")
    add("- 每条建议都带原始行；**动手前先回读原文**，确认同类再改。")
    add("- 失败次数高 ≠ 该改工具。也可能是模型用法错、或环境本身缺东西——先分类再定责。")
    add("- 若某工具失败率高但都在重试后成功，说明是**稳定性**问题而非**能力**问题。")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="失败指纹聚类与建议")
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--out", help="输出路径；不填则打印")
    ap.add_argument("--min-count", type=int, default=3, help="进入建议清单的最低次数")
    args = ap.parse_args()

    files = expand(args.paths)
    if not files:
        print("没有匹配到轨迹文件", file=sys.stderr)
        return 1
    counter, examples = collect(files)
    text = report(counter, examples, args.min_count)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
        print(f"已写出 {args.out}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
