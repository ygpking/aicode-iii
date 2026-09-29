#!/usr/bin/env python3
"""回合级指标聚合器（A1）

把 EventTrace 落下的轨迹按「会话 + 回合」聚合成可比较的指标——这是「改动有没有变好」
能被量化回答的数据基座。此前轨迹里 token、工具调用、失败、耗时全都齐全，却没有任何
东西把它们按回合拼起来，于是只能靠「单个失败案例修没修好」来判断，容易过拟合。

只用标准库。用法：

    python3 trace_metrics.py <轨迹目录或文件...> [--out-dir DIR] [--csv]

轨迹格式（见 EventTrace.write）：

    2026-09-29 08:39:15.174  s=2285f102 t1 #1  TURN  轮次开始
    2026-09-29 08:39:17.084  s=2285f102 t1 #5 ←#4  EVENT  assistant_text 正文=0字 ... token=123in/45out 缓存=0
    2026-09-29 00:51:24.018  -      -    -    -  LIFECYCLE  APP VERSION v1.13.1(52) android=...

统计一律**先锚定行首**再匹配：本项目踩过「日志被命令正文污染 → grep 自污染 →
错误数字被写进结论」的坑（80% 的行不是日志行）。本脚本逐行用完整正则解析，
解析失败的行计入 `unparsed` 而非静默丢弃。
"""

from __future__ import annotations

import argparse
import csv
import glob
import os
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime

# 主格式：时间  s=<会话> t<n> #<seq> [←#<cause>]  <层>  <细节>
RE_LINE = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"s=(?P<s>\S+) (?P<t>t\d+|-)\s+#(?P<seq>\d+)"
    r"(?P<cause>\s+←#\d+)?\s+(?P<layer>\S+)\s+(?P<detail>.*)$"
)
# 生命周期行没有会话/回合字段，格式固定为 4 个占位短横线（会话/回合/序号/因果）
RE_LIFECYCLE = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"-\s+-\s+-\s+-\s+LIFECYCLE\s+(?P<detail>.*)$"
)
RE_ASSISTANT = re.compile(
    r"assistant_text 正文=(?P<text>\d+)字 思考=(?P<reason>\d+)字 工具调用=(?P<calls>\d+) "
    r"token=(?P<in>\d+)in/(?P<out>\d+)out 缓存=(?P<cache>\d+)"
)
RE_TOOL_FINISHED = re.compile(r"tool_finished (?P<name>\S+) id=\S+ (?P<status>成功|失败)(?: 结果=(?P<len>\d+)字)?")
RE_TOOL_STARTED = re.compile(r"tool_started (?P<name>\S+)")
RE_RETRY = re.compile(r"retrying 第(?P<attempt>\d+)/(?P<max>\d+)次 原因=(?P<kind>\S+)")
RE_TURN_END = re.compile(r"轮次结束/(?P<outcome>\S+) 共 (?P<total>\d+) 条")
RE_VERSION = re.compile(r"APP VERSION (?P<version>\S+)")
RE_UNFINISHED = re.compile(r"上轮有 (?P<n>\d+) 个回合未收尾")

TS_FMT = "%Y-%m-%d %H:%M:%S.%f"


@dataclass
class Turn:
    session: str
    turn: str
    version: str = "?"
    start: datetime | None = None
    end: datetime | None = None
    outcome: str = "未收尾"
    llm_calls: int = 0
    tool_calls: int = 0
    tool_failures: int = 0
    retries: int = 0
    compaction_starts: int = 0
    compaction_failures: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    cached_tokens: int = 0
    records: int = 0
    failures: list[tuple[str, str]] = field(default_factory=list)

    @property
    def duration_s(self) -> float | None:
        if self.start and self.end:
            return (self.end - self.start).total_seconds()
        return None


def _parse_ts(text: str) -> datetime | None:
    try:
        return datetime.strptime(text, TS_FMT)
    except ValueError:
        return None


def parse_file(path: str, agg: dict, failures: Counter, notes: list[str]) -> None:
    turns: dict[tuple[str, str], Turn] = agg["turns"]
    version = "?"
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line.strip():
                continue

            m = RE_LIFECYCLE.match(line)
            if m:
                agg["lifecycle"] += 1
                detail = m.group("detail")
                vm = RE_VERSION.search(detail)
                if vm:
                    version = vm.group("version")
                    agg["versions"].add(version)
                fm = RE_UNFINISHED.search(detail)
                if fm:
                    agg["stale_reported"] += int(fm.group("n"))
                if "PROCESS START" in detail:
                    agg["process_starts"] += 1
                continue

            m = RE_LINE.match(line)
            if not m:
                agg["unparsed"] += 1
                if len(notes) < 5:
                    notes.append(line[:160])
                continue

            ts = _parse_ts(m.group("ts"))
            session, turn_id, layer = m.group("s"), m.group("t"), m.group("layer")
            detail = m.group("detail")
            agg["parsed"] += 1
            agg["layers"][layer.split("/")[0]] += 1

            # 回合外、非回合记录（孤儿/丢弃告警）：只计数，不参与回合指标
            if turn_id == "-":
                if layer == "TRACE_DROPPED":
                    agg["trace_dropped"] += 1
                continue

            key = (session, turn_id)
            turn = turns.get(key)
            if turn is None:
                turn = Turn(session=session, turn=turn_id, version=version, start=ts)
                turns[key] = turn
            turn.records += 1

            if layer == "TURN":
                if "轮次开始" in detail:
                    turn.start = ts
                else:
                    em = RE_TURN_END.search(detail)
                    turn.end = ts
                    turn.outcome = em.group("outcome") if em else detail
                    agg["turn_end"] += 1
                continue

            if layer == "EVENT":
                am = RE_ASSISTANT.search(detail)
                if am:
                    turn.llm_calls += 1
                    turn.input_tokens += int(am.group("in"))
                    turn.output_tokens += int(am.group("out"))
                    turn.cached_tokens += int(am.group("cache"))
                    agg["llm_calls"] += 1
                    continue
                sm = RE_TOOL_STARTED.match(detail)
                if sm and detail.startswith("tool_started"):
                    turn.tool_calls += 1
                    continue
                fm = RE_TOOL_FINISHED.match(detail)
                if fm:
                    if fm.group("status") == "失败":
                        turn.tool_failures += 1
                        agg["tool_failures"] += 1
                        reason = _reason_of(detail)
                        turn.failures.append((fm.group("name"), reason))
                        failures[(fm.group("name"), reason)] += 1
                    continue
                rm = RE_RETRY.search(detail)
                if rm:
                    turn.retries += 1
                    agg["retries"] += 1
                    continue
                if detail.startswith("compaction_started"):
                    turn.compaction_starts += 1
                    continue
                if detail.startswith("compaction_failed"):
                    turn.compaction_failures += 1
                    continue
                continue

            if layer.startswith("SNAPSHOT"):
                continue


def _reason_of(detail: str) -> str:
    """从 tool_finished 失败行里取原因摘要，归一化成聚类友好的指纹。"""
    marker = "原因摘要="
    idx = detail.find(marker)
    text = detail[idx + len(marker):] if idx >= 0 else detail
    # 归一化：抹掉 id/数字/路径/引号内容，只留「说了什么错」的骨架
    text = re.sub(r"call_[0-9a-zA-Z_]+", "<id>", text)
    text = re.sub(r"/[^\s'\"]{2,}", "<path>", text)
    text = re.sub(r"\d+", "<n>", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:100] or "(无摘要)"


def collect(paths: list[str]) -> tuple[dict, Counter, list[str]]:
    agg = {
        "turns": {},
        "layers": Counter(),
        "versions": set(),
        "unparsed": 0,
        "parsed": 0,
        "lifecycle": 0,
        "process_starts": 0,
        "stale_reported": 0,
        "trace_dropped": 0,
        "turn_end": 0,
        "llm_calls": 0,
        "tool_failures": 0,
        "retries": 0,
    }
    failures: Counter = Counter()
    notes: list[str] = []
    for path in paths:
        parse_file(path, agg, failures, notes)
    return agg, failures, notes


def _pct(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(int(q * (len(ordered) - 1) + 0.5), len(ordered) - 1)
    return ordered[idx]


def summarize(agg: dict, failures: Counter, out_dir: str) -> str:
    turns: list[Turn] = list(agg["turns"].values())
    done = [t for t in turns if t.outcome != "未收尾"]
    finished = [t for t in turns if t.outcome == "finished"]
    cancelled = [t for t in turns if t.outcome == "cancelled"]
    stale = [t for t in turns if t.outcome == "未收尾"]

    inp = sum(t.input_tokens for t in turns)
    out = sum(t.output_tokens for t in turns)
    cache = sum(t.cached_tokens for t in turns)
    durations = [t.duration_s for t in done if t.duration_s is not None]

    lines: list[str] = []
    add = lines.append
    add("# 回合级指标报告\n")
    add(f"- 生成时间：{datetime.now():%Y-%m-%d %H:%M:%S}")
    add(f"- 已解析行：{agg['parsed']}（无法解析 {agg['unparsed']}）")
    add(f"- 版本：{', '.join(sorted(agg['versions'])) or '未记录（轨迹里没有 APP VERSION 行）'}")
    add("")
    add("## 回合")
    add("")
    add(f"| 指标 | 值 |")
    add("| --- | --- |")
    add(f"| 回合总数 | {len(turns)} |")
    add(f"| finished | {len(finished)} |")
    add(f"| cancelled | {len(cancelled)} |")
    add(f"| **未收尾** | **{len(stale)}** |")
    rate = len(finished) / len(turns) * 100 if turns else 0.0
    add(f"| 完成率 | {rate:.1f}% |")
    add(f"| 进程启动次数 | {agg['process_starts']} |")
    add(f"| 启动时报告的上轮未收尾数 | {agg['stale_reported']} |")
    add(f"| TRACE_DROPPED（本该入轨迹却丢了） | {agg['trace_dropped']} |")
    add("")
    add("## 成本与效率")
    add("")
    add("| 指标 | 值 |")
    add("| --- | --- |")
    add(f"| LLM 调用次数 | {agg['llm_calls']} |")
    add(f"| 平均 LLM 调用/回合 | {agg['llm_calls'] / len(turns):.2f}" if turns else "| 平均 LLM 调用/回合 | - |")
    add(f"| 工具调用总数 | {sum(t.tool_calls for t in turns)} |")
    add(f"| 平均工具调用/回合 | {sum(t.tool_calls for t in turns) / len(turns):.2f}" if turns else "| - |")
    add(f"| 工具失败 | {agg['tool_failures']} |")
    add(f"| 重试 | {agg['retries']} |")
    add(f"| 输入 token | {inp:,} |")
    add(f"| 输出 token | {out:,} |")
    add(f"| 缓存 token | {cache:,} |")
    add(f"| 缓存命中率 | {cache / (cache + inp) * 100:.1f}%（缓存/(缓存+输入)）|")
    add(f"| 回合耗时 p50 | {_pct(durations, 0.5):.1f}s |")
    add(f"| 回合耗时 p90 | {_pct(durations, 0.9):.1f}s |")
    add("")
    add("## 层分布（找噪声看这里）")
    add("")
    add("| 层 | 行数 | 占比 |")
    add("| --- | --- | --- |")
    total = sum(agg["layers"].values()) or 1
    for layer, count in agg["layers"].most_common():
        add(f"| {layer} | {count} | {count / total * 100:.1f}% |")
    add("")
    add("## 失败指纹 Top 20")
    add("")
    if failures:
        add("| 工具 | 归一化原因 | 次数 |")
        add("| --- | --- | --- |")
        for (tool, reason), count in failures.most_common(20):
            add(f"| {tool} | {reason} | {count} |")
    else:
        add("（无工具失败记录）")
    add("")
    add("## 按版本")
    add("")
    add("| 版本 | 回合数 | finished | 未收尾 | 平均工具调用 | 平均输入 token |")
    add("| --- | --- | --- | --- | --- | --- |")
    by_ver: dict[str, list[Turn]] = defaultdict(list)
    for t in turns:
        by_ver[t.version].append(t)
    for version, items in sorted(by_ver.items()):
        avg_tools = sum(t.tool_calls for t in items) / len(items)
        avg_in = sum(t.input_tokens for t in items) / len(items)
        add(
            f"| {version} | {len(items)} | {sum(1 for t in items if t.outcome == 'finished')} "
            f"| {sum(1 for t in items if t.outcome == '未收尾')} | {avg_tools:.2f} | {avg_in:,.0f} |"
        )
    add("")
    if agg["unparsed"]:
        add(f"> 注意：有 {agg['unparsed']} 行无法解析，若占比高说明轨迹格式已变，指标不可信。")

    report = "\n".join(lines)
    os.makedirs(out_dir, exist_ok=True)
    md_path = os.path.join(out_dir, "metrics.md")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write(report + "\n")

    csv_path = os.path.join(out_dir, "turns.csv")
    with open(csv_path, "w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(
            ["session", "turn", "version", "outcome", "duration_s", "llm_calls", "tool_calls",
             "tool_failures", "retries", "compaction_starts", "compaction_failures",
             "input_tokens", "output_tokens", "cached_tokens", "records", "start"]
        )
        for t in sorted(turns, key=lambda x: (x.version, x.session, x.start or datetime.min)):
            writer.writerow([
                t.session, t.turn, t.version, t.outcome,
                f"{t.duration_s:.3f}" if t.duration_s is not None else "",
                t.llm_calls, t.tool_calls, t.tool_failures, t.retries,
                t.compaction_starts, t.compaction_failures,
                t.input_tokens, t.output_tokens, t.cached_tokens, t.records,
                t.start.strftime(TS_FMT) if t.start else "",
            ])
    return f"{md_path}（明细 {csv_path}）"


def expand(paths: list[str]) -> list[str]:
    out: list[str] = []
    for p in paths:
        if os.path.isdir(p):
            out.extend(sorted(glob.glob(os.path.join(p, "trace-*.log*"))))
        else:
            out.extend(sorted(glob.glob(p)))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="轨迹 → 回合级指标")
    ap.add_argument("paths", nargs="+", help="轨迹目录或文件（支持通配）")
    ap.add_argument("--out-dir", default="trace-report", help="报告输出目录")
    args = ap.parse_args()

    files = expand(args.paths)
    if not files:
        print("没有匹配到任何 trace-*.log 文件", file=sys.stderr)
        return 1
    agg, failures, notes = collect(files)
    print(f"读取 {len(files)} 个文件，解析 {agg['parsed']} 行，回合 {len(agg['turns'])} 个")
    if notes:
        print("无法解析的样例：")
        for n in notes:
            print("  " + n)
    print("已写出：" + summarize(agg, failures, args.out_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
