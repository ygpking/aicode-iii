#!/usr/bin/env python3
"""主代理「该派子代理却自己干」的行为度量（只读，可复算）。

用途：为「子代理派发提醒」改动提供**基线**与**改动后对比**的同一把尺子。
判据口径固定如下，改动前后必须用同一口径，否则不可比：

- 主代理会话：出现在 trace 中、且不是任何「子代理已创建」记录的 session。
- 子代理会话：logs-view 中 `TaskTool` 的「子代理已创建: session=X parent=Y」的 X。
- 「派发」只算 **task create**：以 logs-view 的「子代理已创建」为准。
  （不能用 trace 的 tool_started task 计数——它把 read/list/send/stop 一并计入，
   实测同一会话会从 4 次虚高到 15 次，得不出「有没有派活」。）
- 「自己写文件」：trace 中 tool_started 的 editFile / writeFile 次数。

核心指标（症状的可判定形态，改动前后对比用）：
  ① 「写了多个文件且全程零派发」的会话数；
  ② 「首次自写早于首次派发」的会话数（先自己干、后补派发的形态）；
  ③ 自写文件总数 : 派发总数 的比值（派发次数应与实际干活的量级相称）。
  实测基线（2026-10-12~14）：① = 0 例，② 有真实实例（b3cd42d5 先自写 14:52:28、
  后派发 14:53:06），③ 需按会话看。即真实症状不是「从不派发」而是「干完才派」。

用法：
  python3 scripts/measure_delegation.py                 # 最近 3 天（按文件名日期）
  python3 scripts/measure_delegation.py --days 7
退出码恒为 0（度量工具，不是门禁）。

注意两个已知口径差异（拿指标当验收数时必须意识到）：
- 本工具统计写入用 `tool_started`（**含失败写入**），而机制只认**成功**写入 ——
  指标①因此系统性略高于机制实际覆盖面；改动前后用同一把尺对比无碍。
- 转档文件（`trace-YYYY-MM-DD.log.1`）与主文件同属一天，按文件名日期取窗口时
  须把它算进同一天，否则 create 日志与 trace 会跨窗口错位。
"""

import argparse
import glob
import os
import re
from collections import defaultdict

TRACE_DIR = os.path.expanduser("~/.aicode/traces-view")
LOG_DIR = os.path.expanduser("~/.aicode/logs-view")

RE_CREATE = re.compile(
    r"^(\S+ \S+).*子代理已创建: session=([0-9a-f-]+) parent=([0-9a-f-]+) agent=(\S+)"
)
RE_TOOL = re.compile(r"^(\S+ \S+)\s+s=([0-9a-f]{8})\b.*tool_started (\w+)")
WRITE_TOOLS = {"editFile", "writeFile"}


DATE_IN_NAME = re.compile(r"(\d{4}-\d{2}-\d{2})")


def files_in_window(files, days):
    """按**文件名里的日期**取最近 N 天（含该日的转档 `.log.1` 等）。

    不用「最近 N 个文件」：转档文件会让 N 文件 ≠ N 天，且 log 与 trace 的
    窗口错位时，父会话的 create 记录落在窗口外、其写入落在窗口内，会把该
    父会话误判成 root 且 d=0，虚高指标①。
    """
    dated = []
    for f in files:
        m = DATE_IN_NAME.search(os.path.basename(f))
        if m:
            dated.append((m.group(1), f))
    if not dated:
        return sorted(files)[-days:]
    keep = sorted({d for d, _ in dated}, reverse=True)[:days]
    return sorted(f for d, f in dated if d in keep)


def load_creates(days):
    creates = []          # (ts, sub_sid8, parent_sid8, agent)
    files = files_in_window(glob.glob(os.path.join(LOG_DIR, "log-*.txt")), days)
    for f in files:
        with open(f, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = RE_CREATE.search(line)
                if m:
                    creates.append((m.group(1), m.group(2)[:8], m.group(3)[:8], m.group(4)))
    return creates


def load_tools(days):
    tools = defaultdict(int)                 # sid8 -> 工具总数
    writes = defaultdict(int)                # sid8 -> 写文件次数
    first_write, first_any, last_any = {}, {}, {}
    files = files_in_window(glob.glob(os.path.join(TRACE_DIR, "trace-*.log*")), days)
    for f in files:
        with open(f, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = RE_TOOL.match(line)
                if not m:
                    continue
                ts, sid, tool = m.group(1), m.group(2), m.group(3)
                tools[sid] += 1
                first_any.setdefault(sid, ts)
                last_any[sid] = ts
                if tool in WRITE_TOOLS:
                    writes[sid] += 1
                    first_write.setdefault(sid, ts)
    return tools, writes, first_write, first_any, last_any


def main():
    ap = argparse.ArgumentParser(description="主代理派发行为度量（只读）")
    ap.add_argument("--days", type=int, default=3, help="取最近 N 天（按文件名日期，含转档文件）")
    args = ap.parse_args()

    creates = load_creates(args.days)
    tools, writes, first_write, first_any, _ = load_tools(args.days)

    sub_sids = {c[1] for c in creates}
    per_parent = defaultdict(int)
    for _, sub, parent, _ in creates:
        per_parent[parent] += 1

    print("=" * 78)
    print(f"口径：派发=日志「子代理已创建」；自己写文件=trace 的 editFile/writeFile")
    print(f"范围：最近 {args.days} 天（按文件名日期）")
    print("=" * 78)
    print(f"\n子代理创建总次数: {len(creates)}（覆盖 {len(sub_sids)} 个子会话、{len(per_parent)} 个父会话）")

    print(f"\n{'主代理会话':10} {'起':17} {'工具':>5} {'写文件':>6} {'派发':>5}  判定")
    roots = [s for s in tools if s not in sub_sids and tools[s] >= 5]
    zero_deleg, late_deleg = 0, 0
    for sid in sorted(roots, key=lambda s: first_any.get(s, "")):
        w, d = writes[sid], per_parent.get(sid, 0)
        sym = ""
        if w >= 2 and d == 0:
            sym = "!! 写了多个文件且全程零派发"
            zero_deleg += 1
        elif w >= 2 and d > 0:
            fw = first_write.get(sid)
            fc = None
            for ts, sub, parent, _ in creates:
                if parent == sid and (fc is None or ts < fc):
                    fc = ts
            if fw and fc and fw < fc:
                sym = f"先自己写({fw[11:19]}) 后派发({fc[11:19]})"
                late_deleg += 1
        print(f"{sid:10} {first_any.get(sid,'')[:17]:17} {tools[sid]:5} {w:6} {d:5}  {sym}")

    tot_w = sum(writes[s] for s in roots)
    tot_d = sum(per_parent.get(s, 0) for s in roots)
    print(f"\n结论指标（改动后重跑本脚本对比这三个数）：")
    print(f"  ① 写了多个文件且全程零派发 = {zero_deleg} 例")
    print(f"  ② 先自己写、后（或不）派发 = {late_deleg} 例")
    print(f"  ③ 自写文件总数 {tot_w} : 派发总数 {tot_d} = {tot_w/max(tot_d,1):.1f} : 1")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
