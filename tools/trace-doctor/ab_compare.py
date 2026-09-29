#!/usr/bin/env python3
"""A/B 回合指标对比（B2）

用途：回答「这次改动到底有没有变好」。拿改动前、改动后两批轨迹各自跑
trace_metrics.py 得到 turns.csv，本脚本比较两组的指标分布，给出带样本量与差异方向的结论。

**边界（务必知道）**：本脚本只做「两组已有轨迹」的对比，不自动重放请求。
自动重放需要真调模型（把历史 request body 再发一遍），那属于在线动作，
本脚本刻意不碰——否则会在你不知情时产生真实调用与费用。要重放请显式做。

只用标准库。用法：

    python3 ab_compare.py baseline.csv candidate.csv [--metric tool_calls]

默认比较：完成率、平均工具调用数、平均 LLM 调用数、平均输入/输出 token、
未收尾率、工具失败率。样本量过小会明确警告，而不是硬给个结论。
"""

from __future__ import annotations

import argparse
import csv
import math
import sys
from dataclasses import dataclass
from typing import Callable


@dataclass
class Group:
    name: str
    rows: list[dict]

    def values(self, fn: Callable[[dict], float]) -> list[float]:
        out = []
        for r in self.rows:
            try:
                out.append(float(fn(r)))
            except (KeyError, TypeError, ValueError):
                continue
        return out


def load(path: str, name: str) -> Group:
    with open(path, "r", encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
    return Group(name=name, rows=rows)


def mean(xs: list[float]) -> float:
    return sum(xs) / len(xs) if xs else 0.0


def stdev(xs: list[float]) -> float:
    if len(xs) < 2:
        return 0.0
    m = mean(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))


def welch_t(a: list[float], b: list[float]) -> tuple[float, float]:
    """Welch t 检验，返回 (t, 粗略双尾 p)。样本不足返回 (0, 1)。

    不引 scipy：判「是否值得细看」够用，p 用正态近似（大样本下与 t 分布接近）。
    """
    if len(a) < 2 or len(b) < 2:
        return 0.0, 1.0
    va, vb = stdev(a) ** 2, stdev(b) ** 2
    se = math.sqrt(va / len(a) + vb / len(b))
    if se == 0:
        return 0.0, 1.0
    t = (mean(a) - mean(b)) / se
    # 正态近似双尾 p
    p = 2 * (1 - 0.5 * (1 + math.erf(abs(t) / math.sqrt(2))))
    return t, p


def fmt(x: float, nd: int = 2) -> str:
    return f"{x:,.{nd}f}"


def rate(rows: list[dict], pred: Callable[[dict], bool]) -> float:
    if not rows:
        return 0.0
    return sum(1 for r in rows if pred(r)) / len(rows)


def compare(base: Group, cand: Group, min_n: int) -> str:
    lines: list[str] = []
    add = lines.append
    n_b, n_c = len(base.rows), len(cand.rows)
    add(f"# A/B 对比：{base.name}  vs  {cand.name}\n")
    add(f"- A（{base.name}）：{n_b} 个回合")
    add(f"- B（{cand.name}）：{n_c} 个回合")
    if n_b < min_n or n_c < min_n:
        add(f"\n> **样本不足**：每组至少需要 {min_n} 个回合才有统计意义。当前结论仅供参考，"
            f"不要据此下「变好/变坏」的判断。\n")
    add("")

    metrics: list[tuple[str, Callable[[list[dict]], float], str]] = [
        ("完成率", lambda rs: rate(rs, lambda r: r.get("outcome") == "finished"), "%"),
        ("未收尾率", lambda rs: rate(rs, lambda r: r.get("outcome") == "未收尾"), "%"),
        ("工具失败率", lambda rs: rate(rs, lambda r: float(r.get("tool_failures") or 0) > 0), "%"),
        ("平均工具调用/回合", lambda rs: mean([float(r.get("tool_calls") or 0) for r in rs]), ""),
        ("平均 LLM 调用/回合", lambda rs: mean([float(r.get("llm_calls") or 0) for r in rs]), ""),
        ("平均输入 token", lambda rs: mean([float(r.get("input_tokens") or 0) for r in rs]), ""),
        ("平均输出 token", lambda rs: mean([float(r.get("output_tokens") or 0) for r in rs]), ""),
        ("平均缓存 token", lambda rs: mean([float(r.get("cached_tokens") or 0) for r in rs]), ""),
    ]
    dur = [r for r in base.rows + cand.rows if r.get("duration_s")]
    if dur:
        metrics.append(
            ("平均回合耗时(s)",
             lambda rs: mean([float(r["duration_s"]) for r in rs if r.get("duration_s")]), "s")
        )

    add("| 指标 | A | B | 变化 | 方向 |")
    add("| --- | --- | --- | --- | --- |")
    for label, fn, unit in metrics:
        a, b = fn(base.rows), fn(cand.rows)
        if unit == "%":
            a_f, b_f, delta = a * 100, b * 100, (b - a) * 100
            suffix = "pp"
        else:
            a_f, b_f, delta = a, b, b - a
            suffix = ""
        pct = (delta / a_f * 100) if a_f else float("inf")
        arrow = "—" if abs(pct) < 1 else ("↑" if delta > 0 else "↓")
        add(f"| {label} | {fmt(a_f)}{unit if unit=='%' else ''} | {fmt(b_f)}{unit if unit=='%' else ''} "
            f"| {delta:+.2f}{suffix}{'' if unit=='%' else ''} | {arrow} |")
    add("")

    add("## 差异显著性（Welch t 检验）")
    add("")
    add("| 指标 | t | 近似 p | 判定 |")
    add("| --- | --- | --- | --- |")
    for col in ["tool_calls", "llm_calls", "input_tokens", "output_tokens"]:
        va = base.values(lambda r, c=col: r[c])
        vb = cand.values(lambda r, c=col: r[c])
        t, p = welch_t(vb, va)
        verdict = "显著" if p < 0.05 and len(va) >= min_n and len(vb) >= min_n else "不显著/样本不足"
        add(f"| {col} | {t:+.2f} | {p:.4f} | {verdict} |")
    add("")

    add("## 怎么读")
    add("")
    add("- **完成率 ↑ 且 工具调用数 ↓ 且 token 不涨** = 真变好（更省更快还更准）。")
    add("- **完成率持平但 token ↑** = 用更多成本换同样结果，通常不划算。")
    add("- **单个指标动了、其余没动** = 先别下结论，看是不是样本波动；p ≥ 0.05 尤其要谨慎。")
    add("- 差异**只出现一次**不算结论：换一批回合再跑，仍复现才算数。")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="A/B 回合指标对比")
    ap.add_argument("baseline", help="A 组 turns.csv（改动前）")
    ap.add_argument("candidate", help="B 组 turns.csv（改动后）")
    ap.add_argument("--out", help="报告输出路径；不填则打印到 stdout")
    ap.add_argument("--min-n", type=int, default=30, help="每组最少回合数（默认 30）")
    args = ap.parse_args()

    try:
        base = load(args.baseline, "A")
        cand = load(args.candidate, "B")
    except OSError as e:
        print(f"读取失败：{e}", file=sys.stderr)
        return 1

    report = compare(base, cand, args.min_n)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(report + "\n")
        print(f"已写出 {args.out}")
    else:
        print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
