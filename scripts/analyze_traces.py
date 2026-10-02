#!/usr/bin/env python3
"""事件轨迹只读分析脚本。

把 `traces/` 目录下的轨迹日志汇总成「可据以行动」的结论，而不是让人逐行翻。

## 为什么需要它

轨迹本身设计成「人可读、grep 友好」（见 `core/util/EventTrace.kt`），但真出问题时
要回答的是**跨回合的模式**，而非某一行的内容：哪些错误码反复出现？哪些回合没正常收尾？
哪些记录被丢弃了（说明某层没接上轨迹）？这些问题用 grep 逐条看会漏，必须做聚合。

本脚本**只读**：不修改、不删除任何轨迹文件，也不产生新的轨迹——
分析工具若写入被分析的数据，会污染下一次分析（自举链路的第一条纪律）。

## 用法

    python3 scripts/analyze_traces.py                      # 自动定位默认目录
    python3 scripts/analyze_traces.py --dir /path/to/traces
    python3 scripts/analyze_traces.py --days 3             # 只看最近 3 天
    python3 scripts/analyze_traces.py --top 20

默认目录（宿主侧；容器内需先挂载才可见）：
    /storage/emulated/0/Android/data/com.aicode.iii/files/traces/

退出码：0 = 有数据并完成分析；1 = 目录不存在或无轨迹文件。

## 行格式（解析契约）

    2026-09-26 22:59:01.123  s=a1b2c3d4 t3 #42 ←#41  TOOL  todo 写入 5 项 [completed=5]

字段依次为：时间、会话短号、回合号、序号、因果来源、层标签、细节。
另有两种特殊行：`LIFECYCLE`（进程起止等）与 `TRACE_DROPPED`（本该记录却进不去的丢弃事实）。
"""

import argparse
import os
import re
import sys
from collections import Counter, defaultdict

DEFAULT_TRACE_DIR = "/storage/emulated/0/Android/data/com.aicode.iii/files/traces"

# 正常事件行：时间戳 + s=会话 + 回合 + #seq [+ ←#cause] + 层 + 细节
#
# 回合号有两种形态（均来自 EventTrace.write）：
#   - `tN`：正常回合；
#   - `-` ：无回合上下文（如 TRACE_DROPPED 由 write(turnId="-") 写出，seq 为 0）。
# 故 turn 必须允许 `-`，否则「被丢弃的记录」这一关键诊断会被整行漏掉。
LINE_RE = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"s=(?P<session>\S+)\s+"
    r"(?P<turn>t\d+|-)\s+"
    r"#(?P<seq>\d+)"
    r"(?:\s+←#(?P<cause>\d+))?\s+"
    r"(?P<layer>\S+)\s+"
    r"(?P<detail>.*)$"
)

# 无 `s=` 前缀的行：只有 LIFECYCLE（由 EventTrace.appendLine 手写拼接，字段全是占位符 `-`）：
#   2026-10-02 10:00:00.100  -      -    -    -  LIFECYCLE  PROCESS START
# 这些行恰恰是最关键的诊断证据（进程起止、上次异常退出），必须单独识别，
# 否则会被当成「格式变更」而整行丢掉——等于把最有价值的部分静默忽略。
ORPHAN_RE = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"-\s+-\s+-\s+-\s+"
    r"(?P<layer>LIFECYCLE)\s+"
    r"(?P<detail>.*)$"
)

# 错误码识别：用**白名单**而非黑名单。
#
# 为什么不能靠「排除层标签」的黒名单：黑名单只能挡住已知的干扰词，代码里新增一个层标签、
# 或日志里出现一个新的全大写缩写（如 `HTTP`、`SSE`），就会被当成错误码统计，且这类误报
# 不会报错、只会让排名失真——正是「看起来在用、实际结论不可信」的典型。
#
# 白名单来源：`ToolResult.Error` 的 code 实参全集（由 scripts/audit_error_codes.py 从源码提取，
# 可用 --codes-file 传入最新快照）。白名单之外的大写词会被**单独列出**而非丢弃——
# 它们要么是新出现的码（白名单该更新），要么是噪声（黑名单该更新），两种都需要人看见。
CODE_TOKEN_RE = re.compile(r"\b([A-Z][A-Z0-9_]{3,})\b")

# 内置快照：截至提交时的 code 全集。可能滞后于代码，故仅作默认值，
# 并会在报告中提示「用了内置快照」。
BUILTIN_ERROR_CODES = frozenset({
    "A11Y_NOT_ENABLED", "AGENT_NOT_FOUND", "APPLY_FAILED", "ARGS_MISMATCH",
    "BAD_ARG", "BROWSER_ERROR", "BROWSER_NOT_READY", "CANCELLED",
    "CLICK_FAILED", "CLOSE_FAILED", "COMMAND_FAILED", "COMMAND_NOT_FOUND",
    "CONTAINER_NOT_READY", "CURATION_FAILED", "DELETE_FAILED", "EDIT_ERROR",
    "EMPTY_OLD_STRING", "EMPTY_RESULT", "EMPTY_SEARCH", "FILE_EXISTS",
    "FILE_NOT_FOUND", "FETCH_FAILED", "GIT_ERROR", "GIT_NOT_REPO",
    "IMAGE_GEN_FAILED", "INPUT_FAILED", "INVALID_ACTION", "INVALID_ARGS",
    "INVALID_ARGUMENT", "INVALID_FILE", "INVALID_ITEM", "INVALID_OPTIONS",
    "INVALID_PARAMS", "INVALID_PIPE", "INVALID_QUESTION", "INVALID_SERVER_NAME",
    "INVALID_STALE_DAYS", "INVALID_TOOL_ARGUMENTS", "INVALID_URL",
    "LIST_ERROR", "MAX_SUBAGENTS_REACHED", "MCP_MANAGE_FAILED",
    "MCP_SERVER_NOT_FOUND", "MCP_TOOL_ERROR", "MCP_TOOL_EXEC_FAILED",
    "MEMORY_FAILED", "MEMORY_NOT_FOUND", "MISSING_ACTION", "MISSING_ARG",
    "MISSING_ARGS", "MISSING_ARGUMENT", "MISSING_COMMAND", "MISSING_CONDITION",
    "MISSING_CONTENT", "MISSING_DESCRIPTION", "MISSING_EDITS", "MISSING_INPUT",
    "MISSING_ITEMS", "MISSING_KEY", "MISSING_NAME", "MISSING_PATHS",
    "MISSING_PROMPT", "MISSING_QUERY", "MISSING_QUESTIONS", "MISSING_REASON",
    "MISSING_RECEIPT_ID", "MISSING_SCRIPT", "MISSING_SELECTOR",
    "MISSING_SERVER_NAME", "MISSING_SESSION", "MISSING_SKILL_NAME",
    "MISSING_STALE_DAYS", "MISSING_STREAM_RESULT", "MISSING_TAB_ID",
    "MISSING_URL", "MISSING_VALUE", "MODE_SWITCH_REJECTED", "MULTIPLE_MATCHES",
    "NO_MATCH", "NO_OP", "NO_PARENT", "NO_SESSION", "NO_WORKSPACE",
    "NOT_FOUND", "NOT_IN_PLAN", "NOT_READ", "NOT_YOUR_SUBAGENT", "OPEN_FAILED",
    "PARSE_ERROR", "READ_ERROR", "RG_ERROR", "RG_MISSING", "ROUND_TOOL_LIMIT",
    "SAVE_FAILED", "SCREENSHOT_FAILED", "SEARCH_ERROR", "SEARCH_FAILED",
    "SEARCH_HTTP_ERROR", "SEND_FILE_ERROR", "SESSION_NOT_FOUND",
    "SHIZUKU_EXEC_FAILED", "SHIZUKU_NOT_READY", "SKILL_NOT_FOUND",
    "SLEEP_BLOCKED", "SPILL_NOT_FOUND", "STALE_CONTENT", "START_FAILED",
    "SWIPE_FAILED", "TERMINAL_NOT_ACTIVE", "TERMINAL_NOT_FOUND", "TIMEOUT",
    "TODO_FAILED", "TOO_FEW_OPTIONS", "TOO_MANY_FILES", "TOO_MANY_IMAGES",
    "TOO_MANY_OPTIONS", "TOO_MANY_QUESTIONS", "TOOL_EXECUTION_FAILED",
    "TOOL_NOT_EXECUTED", "TOOL_NOT_FOUND", "TOOL_TIMEOUT", "UNKNOWN_ACTION",
    "UNSUPPORTED_ACTION", "UNSUPPORTED_OPTION", "VISION_CALL_FAILED",
    "VSCREEN_FAILED", "WRITE_ERROR", "WRITE_FAILED", "WRITE_LEASE_CONFLICT",
    "WRITE_LEASE_DENIED",
})

# 已知的非错误码大写词（层标签 / 事实性缩写）。仅用于「未知词」报告的降噪，
# **不参与**错误码判定——判定只认白名单。
KNOWN_NOISE_WORDS = frozenset({
    "TURN", "EVENT", "UI", "TOOL", "SESSION", "LIFECYCLE", "SNAPSHOT",
    "TRACE_DROPPED", "START", "STOP", "PROCESS", "APP", "VERSION", "ANDROID",
    "ABI", "HTTP", "URL", "JSON", "SSE", "API", "OK", "ID", "IDS", "CPU",
    "GPU", "RAM", "MB", "KB", "GB", "EOF", "NULL", "TRUE", "FALSE",
})


def load_codes_file(path):
    """从文件读取错误码全集（每行一个，或从源码里 grep 出的 code 列表）。

    空行与 `#` 开头视为注释。用于让脚本跟随代码演进，而不必手改内置快照。
    """
    codes = set()
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            codes.add(line)
    return frozenset(codes)


def iter_trace_files(trace_dir, days):
    """按文件名倒序（最新在前）返回轨迹文件，days>0 时只取最近 N 天。

    轨迹文件名格式 `trace-YYYY-MM-DD.log[.N]`，故直接按名字倒序即按时间倒序，
    无需依赖文件 mtime（mtime 会因拷贝/挂载而失真）。
    """
    if not os.path.isdir(trace_dir):
        return []
    files = [f for f in os.listdir(trace_dir) if f.startswith("trace-")]
    files.sort(reverse=True)
    if days > 0:
        # 取最近 days 天：按文件名里的日期前缀去重计数
        seen_days = []
        kept = []
        for name in files:
            day = name.split(".")[0].replace("trace-", "")
            if day not in seen_days:
                if len(seen_days) >= days:
                    break
                seen_days.append(day)
            kept.append(name)
        files = kept
    return [os.path.join(trace_dir, f) for f in files]


def parse_traces(paths, known_codes=BUILTIN_ERROR_CODES):
    """解析所有轨迹行，返回统计结果。只读，不缓存到磁盘。

    [known_codes] 是错误码白名单：只有落在其中的大写词才计入错误码排名。
    白名单外的大写词进 [unknown_tokens]，供人工判断是「新码」还是「噪声」。
    """
    stats = {
        "total_lines": 0,
        "parsed_lines": 0,
        "unparsed": [],          # 无法解析的行（前若干条，供排查格式变更）
        "layers": Counter(),
        "error_codes": Counter(),      # 白名单内的码
        "unknown_tokens": Counter(),   # 白名单外的大写词（新码 or 噪声）
        "dropped": [],           # TRACE_DROPPED 明细
        "lifecycle": [],         # LIFECYCLE 明细
        "turns_started": 0,
        "turns_ended": 0,
        "open_turns": defaultdict(list),   # session -> [turn...] 未收尾
        "sessions": Counter(),
        "snapshots": [],         # SNAPSHOT 残留清点
    }
    started = set()
    ended = set()

    for path in paths:
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                for raw in fh:
                    stats["total_lines"] += 1
                    line = raw.rstrip("\n")
                    m = LINE_RE.match(line)
                    if not m:
                        # 先试「无 s= 前缀」的 LIFECYCLE 行，再判为无法解析
                        om = ORPHAN_RE.match(line)
                        if om:
                            stats["parsed_lines"] += 1
                            stats["layers"][om.group("layer")] += 1
                            stats["lifecycle"].append(line)
                            continue
                        if len(stats["unparsed"]) < 10:
                            stats["unparsed"].append(line[:160])
                        continue
                    stats["parsed_lines"] += 1
                    layer = m.group("layer")
                    detail = m.group("detail")
                    session = m.group("session")
                    turn = m.group("turn")
                    stats["layers"][layer] += 1
                    stats["sessions"][session] += 1

                    if layer == "LIFECYCLE":
                        stats["lifecycle"].append(line)
                        continue
                    if layer == "TRACE_DROPPED":
                        stats["dropped"].append(line)
                        continue
                    if layer.startswith("SNAPSHOT"):
                        stats["snapshots"].append(line)
                        continue

                    # 错误码：只统计白名单内的码；白名单外的大写词单独收集，
                    # 不静默丢弃——它们要么是代码新增的码（白名单该更新），
                    # 要么是噪声（KNOWN_NOISE_WORDS 该更新），两种都该被人看见。
                    for word in CODE_TOKEN_RE.findall(detail):
                        if word in known_codes:
                            stats["error_codes"][word] += 1
                        elif word not in KNOWN_NOISE_WORDS:
                            stats["unknown_tokens"][word] += 1

                    if layer == "TURN":
                        if "轮次开始" in detail:
                            stats["turns_started"] += 1
                            started.add((session, turn))
                        elif "轮次结束" in detail:
                            stats["turns_ended"] += 1
                            ended.add((session, turn))
        except OSError as exc:
            # 单份文件读不了不该让整轮分析失败（轮转归档可能正好在被清理）。
            print(f"⚠ 跳过无法读取的文件 {path}: {exc}", file=sys.stderr)
            continue

    # 未收尾回合：开始过但没结束（进程中途消失的痕迹）
    for key in started - ended:
        stats["open_turns"][key[0]].append(key[1])
    return stats


def report(stats, top, codes_source="内置快照"):
    """把统计结果打印成人类可读的报告。[codes_source] 说明错误码白名单的来源，
    让读者知道结论的可信度边界（内置快照可能滞后于代码）。"""
    print("=" * 72)
    print("事件轨迹分析（只读）")
    print("=" * 72)
    print(f"错误码白名单: {codes_source}")
    print(f"总行数     : {stats['total_lines']}")
    print(f"可解析行数 : {stats['parsed_lines']}")
    if stats["total_lines"] and stats["parsed_lines"] < stats["total_lines"]:
        print(f"⚠ 有 {stats['total_lines'] - stats['parsed_lines']} 行无法解析（格式可能已变更）")
        for line in stats["unparsed"]:
            print("    ?", line)
    print()

    print("-" * 72)
    print(f"回合概况：开始 {stats['turns_started']} / 结束 {stats['turns_ended']}")
    open_turns = stats["open_turns"]
    total_open = sum(len(v) for v in open_turns.values())
    if total_open:
        print(f"⚠ 有 {total_open} 个回合未收尾——进程在回合中途消失，这些回合的收尾事实不可考：")
        for session, turns in sorted(open_turns.items()):
            print(f"    会话 {session}: {', '.join(sorted(turns))}")
    else:
        print("✅ 所有回合都有收尾记录")
    print()

    print("-" * 72)
    print(f"层分布（前 {top}）：")
    for layer, n in stats["layers"].most_common(top):
        print(f"    {layer:24s} {n}")
    print()

    print("-" * 72)
    print(f"错误码频次（前 {top}）——反复出现的码是最值得优先排查的：")
    if stats["error_codes"]:
        for code, n in stats["error_codes"].most_common(top):
            print(f"    {code:28s} {n}")
    else:
        print("    （未发现错误码）")
    print()

    if stats["unknown_tokens"]:
        print("-" * 72)
        print(f"⚠ 白名单外的大写词（{len(stats['unknown_tokens'])} 种）——需人工判断归类：")
        print("    新的错误码 → 更新白名单（--codes-file 或 BUILTIN_ERROR_CODES）")
        print("    层标签/事实缩写 → 加入 KNOWN_NOISE_WORDS")
        for word, n in stats["unknown_tokens"].most_common(top):
            print(f"    {word:28s} {n}")
        print()

    if stats["dropped"]:
        print("-" * 72)
        print(f"⚠ 被丢弃的轨迹记录（{len(stats['dropped'])} 条）——说明某层没接上轨迹，属设计缺口：")
        for line in stats["dropped"][:top]:
            print("   ", line)
        print()

    if stats["snapshots"]:
        print("-" * 72)
        print(f"收尾残留快照（{len(stats['snapshots'])} 条）——用于发现「该清理却没清理」的状态：")
        for line in stats["snapshots"][:top]:
            print("   ", line)
        print()

    print("-" * 72)
    print("进程生命周期事件：")
    for line in stats["lifecycle"][-top:]:
        print("   ", line)
    print()

    print("-" * 72)
    print("结论提示：")
    print("  - 错误码高频出现 → 优先看该工具的错误分支是否给足了下一步建议")
    print("  - TRACE_DROPPED 存在 → 该层漏接轨迹，属真实缺口，应补 recordFor/record 调用")
    print("  - 回合未收尾偏多 → 长任务被杀，参见 docs-site/docs/guide/app-permissions.md")
    print("  - SNAPSHOT 残留 → 「创建了却没清理」类问题的直接证据")


def main():
    ap = argparse.ArgumentParser(description="事件轨迹只读分析")
    ap.add_argument("--dir", default=DEFAULT_TRACE_DIR, help="轨迹目录")
    ap.add_argument("--days", type=int, default=7, help="只看最近 N 天（0 = 全部）")
    ap.add_argument("--top", type=int, default=15, help="每类最多显示条数")
    ap.add_argument(
        "--codes-file",
        help="错误码白名单文件（每行一个码）。不传则用内置快照——"
             "后者可能滞后于代码，建议配合 scripts/audit_error_codes.py 生成最新快照",
    )
    args = ap.parse_args()

    if args.codes_file:
        try:
            known = load_codes_file(args.codes_file)
        except OSError as exc:
            print(f"无法读取白名单文件 {args.codes_file}: {exc}", file=sys.stderr)
            return 1
        codes_source = f"{args.codes_file}（{len(known)} 个）"
    else:
        known = BUILTIN_ERROR_CODES
        codes_source = f"内置快照（{len(known)} 个）"

    paths = iter_trace_files(args.dir, args.days)
    if not paths:
        print(f"未找到轨迹文件：{args.dir}", file=sys.stderr)
        print("提示：容器内需先挂载该目录；或使用 --dir 指定实际路径。", file=sys.stderr)
        return 1

    print(f"扫描 {len(paths)} 个轨迹文件（最近 {args.days} 天）\n")
    stats = parse_traces(paths, known_codes=known)
    report(stats, args.top, codes_source=codes_source)
    return 0


if __name__ == "__main__":
    sys.exit(main())
