#!/usr/bin/env python3
"""错误码审计：从源码提取 `ToolResult.Error` 的 code 全集，做三项对账。

## 为什么需要它

错误码是 AI 自我纠正的唯一稳定检索键。它一旦分裂（同一语义出现多个写法）或失文档
（代码里有、文档里没有），AI 就无法可靠地按码定位——而这**不会报错**，只体现在
「AI 用得不准」这种难以归因的现象里。故必须能机械地对账，而不是靠人记得。

三项检查：
1. **码全集**：从源码提取所有 code，可 `--emit-codes` 输出给
   `scripts/analyze_traces.py --codes-file` 用（避免分析脚本的白名单滞后于代码）。
2. **同义码分裂**：把码按「语义前缀」聚类，同一前缀下出现多个不同后缀时告警。
   例：`MISSING_ARG` / `MISSING_ARGS` / `MISSING_ARGUMENT`。
3. **文档覆盖**：`docs-site/docs/guide/error-codes.md` 是否覆盖了全部码。

## 用法

    python3 scripts/audit_error_codes.py                  # 三项全查
    python3 scripts/audit_error_codes.py --emit-codes     # 只输出码全集（每行一个）
    python3 scripts/audit_error_codes.py --emit-codes --out /tmp/codes.txt

退出码：0 = 未发现问题；1 = 发现分裂或未文档化的码（可据此在 CI 中拦截）。
"""

import argparse
import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC_ROOT = ROOT / "app/src/main"
DOC_FILE = ROOT / "docs-site/docs/guide/error-codes.md"

# `ToolResult.Error(` 的调用点；code 是最后一个字符串字面量实参，或 `code = "X"` 具名实参
CALL_RE = re.compile(r"ToolResult\.Error\s*\(", re.S)
CODE_LITERAL_RE = re.compile(r'"([A-Z][A-Z0-9_]{2,})"\s*$')
CODE_NAMED_RE = re.compile(r'code\s*=\s*"([A-Z][A-Z0-9_]{2,})"')

# 同义分裂检测：检测「同一语义的多种写法」。
#
# 不能按「同前缀聚类」——`MISSING_COMMAND` / `MISSING_URL` / `MISSING_PATH` 是同前缀但
# **合法区分**（各自指不同的缺失参数）；把它们判成「分裂」是误报，会让告警失去意义。
#
# 真正的分裂是**同一个概念有多个拼写**。故用「词干归一」：把常见的复数/长尾变体折成同一
# 词干，若同一词干下出现多个**原始写法**，才是分裂。
#   例：MISSING_ARG / MISSING_ARGS / MISSING_ARGUMENT → 词干均为 MISSING_ARG → 分裂
#       MISSING_COMMAND / MISSING_URL → 词干不同 → 正常
STEM_RULES = [
    ("MISSING_ARGUMENT", "MISSING_ARG"),
    ("MISSING_ARGS", "MISSING_ARG"),
    ("MISSING_ARG", "MISSING_ARG"),
    ("INVALID_ARGUMENT", "INVALID_ARG"),
    ("INVALID_ARGS", "INVALID_ARG"),
    ("INVALID_ARG", "INVALID_ARG"),
    ("INVALID_PARAMS", "INVALID_PARAM"),
    ("INVALID_PARAM", "INVALID_PARAM"),
    ("BAD_ARG", "INVALID_ARG"),
    ("UNKNOWN_ACTION", "INVALID_ACTION"),
    ("UNSUPPORTED_ACTION", "INVALID_ACTION"),
    ("INVALID_ACTION", "INVALID_ACTION"),
    ("WRITE_ERROR", "WRITE_FAILED"),
    ("WRITE_FAILED", "WRITE_FAILED"),
    ("SAVE_FAILED", "WRITE_FAILED"),
    ("SEARCH_ERROR", "SEARCH_FAILED"),
    ("SEARCH_FAILED", "SEARCH_FAILED"),
    ("SEARCH_HTTP_ERROR", "SEARCH_FAILED"),
]


def stem_of(code):
    """把码归一到词干；无规则命中则用自身（即不参与合并）。"""
    for variant, stem in STEM_RULES:
        if code == variant:
            return stem
    return code


def check_splits(codes):
    """返回疑似同义分裂：{词干: [原始写法...]}，仅当同一词干下 ≥2 种写法时报告。"""
    groups = collections.defaultdict(set)
    for code in codes:
        groups[stem_of(code)].add(code)
    return {
        stem: sorted(members)
        for stem, members in groups.items()
        if len(members) >= 2
    }


def extract_codes():
    """提取所有 code -> 出现位置（文件:行）的映射。"""
    found = collections.defaultdict(list)
    for path in SRC_ROOT.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for m in CALL_RE.finditer(text):
            i = m.end()
            depth = 1
            j = i
            instr = None
            esc = False
            while j < len(text) and depth > 0:
                c = text[j]
                if instr:
                    if esc:
                        esc = False
                    elif c == "\\":
                        esc = True
                    elif c == instr:
                        instr = None
                else:
                    if c in "\"'":
                        instr = c
                    elif c == "(":
                        depth += 1
                    elif c == ")":
                        depth -= 1
                j += 1
            body = text[i : j - 1]

            # 按顶层逗号切分实参
            args = []
            d = 0
            instr = None
            esc = False
            cur = ""
            for c in body:
                if instr:
                    cur += c
                    if esc:
                        esc = False
                    elif c == "\\":
                        esc = True
                    elif c == instr:
                        instr = None
                    continue
                if c in "\"'":
                    instr = c
                    cur += c
                    continue
                if c in "([{":
                    d += 1
                elif c in ")]}":
                    d -= 1
                if c == "," and d == 0:
                    args.append(cur)
                    cur = ""
                    continue
                cur += c
            args.append(cur)

            code = None
            if len(args) >= 2:
                m2 = CODE_LITERAL_RE.search(args[-1].strip())
                if m2:
                    code = m2.group(1)
            else:
                m2 = CODE_NAMED_RE.search(body)
                if m2:
                    code = m2.group(1)

            if code:
                line = text[: m.start()].count("\n") + 1
                rel = str(path).replace(str(SRC_ROOT) + "/", "")
                found[code].append(f"{rel}:{line}")
    return found


def check_doc_coverage(codes):
    """返回 (已覆盖数, 未覆盖的码列表)。"""
    doc = DOC_FILE.read_text(encoding="utf-8")
    missing = sorted(c for c in codes if f"`{c}`" not in doc)
    return len(codes) - len(missing), missing


def main():
    ap = argparse.ArgumentParser(description="错误码审计")
    ap.add_argument("--emit-codes", action="store_true", help="只输出码全集（每行一个）")
    ap.add_argument("--out", help="与 --emit-codes 配合：写入文件而非 stdout")
    args = ap.parse_args()

    found = extract_codes()
    codes = sorted(found)

    if args.emit_codes:
        text = "\n".join(codes) + "\n"
        if args.out:
            pathlib.Path(args.out).write_text(text, encoding="utf-8")
            print(f"已写入 {len(codes)} 个码到 {args.out}", file=sys.stderr)
        else:
            sys.stdout.write(text)
        return 0

    print("=" * 72)
    print("错误码审计")
    print("=" * 72)
    print(f"源码中唯一 code 数：{len(codes)}（共 {sum(len(v) for v in found.values())} 处调用）")
    print()

    problems = 0

    covered, missing = check_doc_coverage(codes)
    print("-" * 72)
    print(f"文档覆盖：{covered}/{len(codes)}")
    if missing:
        problems += len(missing)
        print(f"⚠ 有 {len(missing)} 个码未写进 docs-site/docs/guide/error-codes.md：")
        for c in missing:
            print(f"    {c:30s} {found[c][0]}")
    else:
        print("✅ 全部已文档化")
    print()

    splits = check_splits(codes)
    print("-" * 72)
    if splits:
        problems += len(splits)
        print(f"⚠ 疑似同义分裂（{len(splits)} 组）——同一语义多个写法会让 AI 无法稳定检索：")
        for stem, members in splits.items():
            print(f"    [词干 {stem}]")
            for c in members:
                print(f"        {c:28s} {found[c][0]}")
    else:
        print("✅ 未发现同义分裂")
    print()

    print("-" * 72)
    if problems:
        print(f"共 {problems} 项待处理。")
        print("  提示：文档缺口可直接补；分裂需人工统一（并保留旧码的兼容期）。")
        return 1
    print("✅ 全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
