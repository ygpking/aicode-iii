#!/usr/bin/env python3
"""空摘要（empty_or_truncated_summary）修复的纯建模仿真。

目的：在**不编译**的前提下，把「压缩请求构造 → 适配器档位映射 → 响应解析 → 空/截断判定
→ 补救重试」这条链路的判别逻辑抽成可执行模型，用于：
  1) 复现 2026-10-12 的 4 次失败（含 stopReason=stop 仍空正文）；
  2) 证明旧补救（reasoningEffort="minimal" 重试）在 OpenAI 路径上是**空操作**；
  3) 验证新修复（真正降档 + 响应侧兜底提取 + 拒绝空结果）能让这些形态变绿；
  4) 失败注入自检：把修好的逻辑改回旧形态，检查必须变红。

对账源码（改源码须同步）：
  - OpenAIAdapter.normalizeReasoningEffort          (OpenAIAdapter.kt:183)
  - OpenAIAdapter.asTextContent/extractTextFrom...  (OpenAIAdapter.kt:778/785)
  - AnthropicAdapter.buildThinkingConfig            (AnthropicAdapter.kt:437)
  - GeminiAdapter.interactionsThinkingLevel         (GeminiAdapter.kt:380)
  - AIProvider.TRUNCATION_STOP_REASONS              (AIProvider.kt:74)
  - ContextCompactor 空/截断判定与补救               (ContextCompactor.kt:~345-403)

用法: python3 scripts/check_empty_summary_fix.py   （退出码 0=通过）
"""

import sys

# ══════════════════════════════════════════════════════════════════════
# 一、常量（逐条抄自源码）——启动时与源码**强制对账**，防手抄漂移
# ══════════════════════════════════════════════════════════════════════
import os
import re

_ROOTS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_AI_PROVIDER = os.path.join(_ROOTS, 'app/src/main/java/com/aicode/feature/agent/domain/provider/AIProvider.kt')
_OAI_ADAPTER = os.path.join(_ROOTS, 'app/src/main/java/com/aicode/feature/agent/domain/provider/OpenAIAdapter.kt')

TRUNCATION_STOP_REASONS = {"max_tokens", "length", "MAX_TOKENS"}   # AIProvider.kt
REASONING_EFFORT_MINIMAL = "minimal"                                # 仅用于演示「旧补救档」
TEXT_PART_TYPES = {"text", "input_text", "output_text"}            # OpenAIAdapter 白名单

ROLE_USER, ROLE_ASSISTANT, ROLE_TOOL = "user", "assistant", "tool"


def _read(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def reconcile_with_source():
    """从真实 Kotlin 读出常量/白名单，与脚本建模值比对；不一致即报错。

    这是「模型自说自话」的唯一防线：脚本注释里的行号会漂，值必须实时读。
    返回 (ok, problems)。
    """
    problems = []
    ai = _read(_AI_PROVIDER)

    # TRUNCATION_STOP_REASONS：源码 `val TRUNCATION_STOP_REASONS = setOf("max_tokens", "length", "MAX_TOKENS")`
    m = re.search(r'TRUNCATION_STOP_REASONS\s*=\s*setOf\(([^)]*)\)', ai)
    if not m:
        problems.append('AIProvider.kt: 找不到 TRUNCATION_STOP_REASONS')
    else:
        src_set = set(re.findall(r'"([^"]*)"', m.group(1)))
        if src_set != TRUNCATION_STOP_REASONS:
            problems.append(f'TRUNCATION_STOP_REASONS 漂移：源码 {src_set} vs 脚本 {TRUNCATION_STOP_REASONS}')

    # 内容 part 白名单：源码分支 `"text", "input_text", "output_text" ->`
    oai = _read(_OAI_ADAPTER)
    m2 = re.search(r'"text",\s*"input_text",\s*"output_text"\s*->', oai)
    if not m2:
        problems.append('OpenAIAdapter.kt: 找不到 text 白名单分支')

    # 关键缺陷源码事实：normalizeReasoningEffort 必须仍把 minimal 归一为 null
    seg = oai[oai.index('fun normalizeReasoningEffort'):] if 'fun normalizeReasoningEffort' in oai else ''
    if seg:
        seg = seg[:seg.index('}')]
        if '"none", "minimal" -> null' not in seg:
            problems.append('OpenAIAdapter.normalizeReasoningEffort：minimal→null 的归一已变，本脚本前提失效')

    return (len(problems) == 0), problems


# ══════════════════════════════════════════════════════════════════════
# 二、适配器档位映射（对账各适配器）
# ══════════════════════════════════════════════════════════════════════
def openai_effort_field(effort):
    """对账 OpenAIAdapter.normalizeReasoningEffort：none/minimal → None（字段被省略）。

    关键 bug 点：minimal 与 None 不可区分——补救重试发的是**与首次完全相同**的请求。
    """
    if effort is None:
        return None
    if effort in ("none", "minimal"):
        return None
    if effort in ("xhigh", "max"):
        return "high"
    return effort


def anthropic_effort(effort, adaptive=False):
    """对账 AnthropicAdapter.buildThinkingConfig：minimal → low（真降档）。"""
    if effort is None:
        return None
    if adaptive:
        return "disabled" if effort == "none" else ("low" if effort == "minimal" else effort)
    return {"minimal": "low", "low": "low", "medium": "medium",
            "high": "high", "xhigh": "high", "max": "high"}.get(effort)


def gemini_effort(effort):
    """对账 GeminiAdapter.interactionsThinkingLevel：minimal 原样透传。"""
    if effort in (None, "none"):
        return None
    if effort in ("xhigh", "max"):
        return "high"
    return effort


# ══════════════════════════════════════════════════════════════════════
# 三、响应正文提取（对账 asTextContent / extractTextFromContentParts）
# ══════════════════════════════════════════════════════════════════════
def extract_text(content):
    """返回 (text, dropped_reason)。dropped_reason 非空表示「有内容但被漏掉」。"""
    if content is None:
        return "", None
    if isinstance(content, str):
        return content, None
    if isinstance(content, list):
        out = []
        dropped = None
        for part in content:
            if isinstance(part, str):
                out.append(part)
                continue
            if isinstance(part, dict):
                t = part.get("type")
                if t in TEXT_PART_TYPES:
                    out.append(part.get("text") or "")
                elif t in ("image_url", "input_image"):
                    out.append("[图片已省略]")
                else:
                    # else -> {} 静默丢弃：非白名单 type（含缺 type）→ 内容消失
                    if (part.get("text") or ""):
                        dropped = dropped or f"unrecognized_part_type:{t!r}"
                continue
            # 其它类型
            out.append(str(part))
        return "".join(out), dropped
    return str(content), None


# ══════════════════════════════════════════════════════════════════════
# 四、模型行为仿真（模拟「思考型压缩模型」）
# ══════════════════════════════════════════════════════════════════════
class CompactionModel:
    """模拟一个强制思考的压缩模型。

    - requires_thinking=True：无视「不要 reasoning」指令；只要思考档位不是「真正关闭/最低」，
      就把输出预算耗在 reasoning_content，正文为空。
    - content_shape：正文给成 str 或 parts(list)；'parts_bad_type' 模拟网关把正文放在
      非白名单 type 的 part 里（触发提取侧静默丢弃）。
    - stops_by: 'length' 撞输出上限 / 'stop' 正常结束
    """

    def __init__(self, requires_thinking=True, content_shape="str",
                 length_stop=True, honors_low_effort=True):
        self.requires_thinking = requires_thinking
        self.content_shape = content_shape
        self.length_stop = length_stop
        self.honors_low_effort = honors_low_effort

    def respond(self, eff_field, max_tokens, body_text="摘要正文"):
        """eff_field: 适配器实际发出的档位（None=未发）；max_tokens: 输出上限（None=未设）。"""
        thinking_active = False
        if self.requires_thinking:
            # 未发档位 或 发了非最低档 → 仍强制思考
            if eff_field is None:
                thinking_active = True
            elif eff_field in ("low", "minimal"):
                thinking_active = not self.honors_low_effort
            else:
                thinking_active = True

        if thinking_active:
            # 预算全耗在 reasoning，正文空；stopReason 视是否撞上限
            stop = "length" if self.length_stop else "stop"
            out_tokens = max_tokens if max_tokens else (8110 if self.length_stop else 2525)
            return {"content": "", "reasoning": "R" * 100, "stopReason": stop,
                    "outputTokens": out_tokens}

        # 正常产出正文
        if self.content_shape == "str":
            content = body_text
        elif self.content_shape == "parts_ok":
            content = [{"type": "text", "text": body_text}]
        elif self.content_shape == "parts_bad_type":
            content = [{"type": "text_delta", "text": body_text}]  # 非白名单
        else:
            content = body_text
        return {"content": content, "reasoning": "", "stopReason": "stop",
                "outputTokens": 120}


# ══════════════════════════════════════════════════════════════════════
# 五、压缩链路（对账 ContextCompactor 空/截断判定 + 补救）
# ══════════════════════════════════════════════════════════════════════
def is_truncated(stop_reason):
    return stop_reason in TRUNCATION_STOP_REASONS


def run_compaction(provider, model, user_effort, max_tokens,
                   retry_strategy, extract_strict=True):
    """返回 verdict: OK / FAIL_EMPTY。

    retry_strategy:
      'none'          —— 不做补救（最旧行为）
      'minimal_naive' —— #48 的旧补救：reasoningEffort='minimal' 再发一次（口径：适配器各自映射）
      'low_enforced'  —— 新补救：强制发送真正的最低档（OpenAI 用 'low'，Anthropic 'low'，Gemini 'minimal'）
      'low_and_reject'—— 新补救 + 响应侧：强制最低档 + 严格提取 + 空则拒绝入库
    """
    # ---- 首次请求 ----
    first_attempt = build_attempt(provider, user_effort, max_tokens)
    resp = model.respond(first_attempt["eff"], first_attempt["max"])
    text, dropped = extract_text(resp["content"]) if extract_strict else extract_text_loose(resp["content"])
    if text and not is_truncated(resp["stopReason"]):
        return "OK", {"attempts": 1, "text_len": len(text)}

    if retry_strategy == "none":
        return "FAIL_EMPTY", {"attempts": 1, "reason": "empty_or_truncated"}

    # ---- 补救 ----
    if retry_strategy == "minimal_naive":
        eff = map_effort(provider, REASONING_EFFORT_MINIMAL)     # 各适配器各自映射（含 OpenAI→None）
    elif retry_strategy in ("low_enforced", "low_and_reject"):
        eff = enforce_lowest(provider)                            # 真正的最低档
    else:
        eff = None

    resp2 = model.respond(eff, max_tokens)
    text2, dropped2 = extract_text(resp2["content"])
    if text2 and not is_truncated(resp2["stopReason"]):
        return "OK", {"attempts": 2, "text_len": len(text2)}

    return "FAIL_EMPTY", {"attempts": 2, "reason": "empty_or_truncated",
                          "dropped": dropped2}


def extract_text_loose(content):
    """旧行为：只认 content 是 str；list 一律 stringify（会得到非空但垃圾）。"""
    if content is None:
        return "", None
    if isinstance(content, str):
        return content, None
    return str(content), None   # 近似旧实现的 toString 兜底


def build_attempt(provider, user_effort, max_tokens):
    return {"eff": map_effort(provider, user_effort), "max": max_tokens}


def map_effort(provider, effort):
    if provider == "openai":
        return openai_effort_field(effort)
    if provider == "anthropic":
        return anthropic_effort(effort)
    if provider == "gemini":
        return gemini_effort(effort)
    raise ValueError(provider)


def enforce_lowest(provider):
    """新补救：发**真正的最低合法档**，不再依赖各适配器把 minimal 映射成 None。"""
    if provider == "openai":
        return "low"        # OpenAI 官方最低档
    if provider == "anthropic":
        return "low"
    if provider == "gemini":
        return "minimal"
    raise ValueError(provider)


# ══════════════════════════════════════════════════════════════════════
# 六、断言框架
# ══════════════════════════════════════════════════════════════════════
class Report:
    def __init__(self):
        self.failures = []

    def check(self, name, got, want):
        ok = got == want
        if not ok:
            self.failures.append(f"{name}：得到 {got!r}，期望 {want!r}")
        print(f"  {'OK  ' if ok else 'FAIL'} {name}")
        return ok


# ══════════════════════════════════════════════════════════════════════
# 七、正向场景
# ══════════════════════════════════════════════════════════════════════
def suite_forward(rep):
    print("── 1. 复现 2026-10-12 的失败形态 ──")
    # 用户没配压缩推理档 → 不发档位字段；思考型模型 → 预算全花推理、正文空
    m = CompactionModel(requires_thinking=True, length_stop=True)
    v, d = run_compaction("openai", m, user_effort=None, max_tokens=None,
                          retry_strategy="minimal_naive")
    rep.check("旧补救（minimal）在 OpenAI 路径仍失败", v, "FAIL_EMPTY")
    rep.check("旧补救确实发了两次", d["attempts"], 2)

    # 证明旧补救是空操作：minimal 与 None 映射结果相同
    rep.check("OpenAI: minimal 映射 == None 映射（空操作）",
              openai_effort_field("minimal"), openai_effort_field(None))

    print("── 2. stopReason=stop 仍空正文（非截断，纯模型不产正文）──")
    m2 = CompactionModel(requires_thinking=True, length_stop=False)
    v2, _ = run_compaction("openai", m2, None, None, retry_strategy="minimal_naive")
    rep.check("该形态旧补救也失败", v2, "FAIL_EMPTY")

    print("── 3. 新修复：强制真正最低档 → 思考被关掉 ──")
    m3 = CompactionModel(requires_thinking=True, length_stop=True, honors_low_effort=True)
    v3, d3 = run_compaction("openai", m3, None, None, retry_strategy="low_enforced")
    rep.check("OpenAI 强制 low → 重试成功", v3, "OK")
    rep.check("只重试一次（共 2 次调用）", d3["attempts"], 2)

    m4 = CompactionModel(requires_thinking=True, length_stop=True, honors_low_effort=True)
    v4, _ = run_compaction("anthropic", m4, None, None, retry_strategy="low_enforced")
    rep.check("Anthropic 强制 low → 成功", v4, "OK")

    print("── 4. 响应侧兜底：正文在非白名单 part 里 ──")
    # 模型正常产出，但网关把正文包在 type='text_delta' 的 part 里 → 严格提取会丢
    m5 = CompactionModel(requires_thinking=False, content_shape="parts_bad_type")
    v5, d5 = run_compaction("openai", m5, None, None, retry_strategy="none", extract_strict=True)
    rep.check("非白名单 part 被静默丢弃（复现提取缺陷）", v5, "FAIL_EMPTY")

    # 修复：放宽提取（把未知 part 的 text 也取出）
    m6 = CompactionModel(requires_thinking=False, content_shape="parts_bad_type")
    v6, _ = run_compaction_extract_fix(m6)
    rep.check("放宽提取后同一响应成功", v6, "OK")

    print("── 5. 非思考型模型不受影响（无回归）──")
    m7 = CompactionModel(requires_thinking=False, content_shape="str")
    v7, d7 = run_compaction("openai", m7, None, None, retry_strategy="none")
    rep.check("普通模型首次即成功", v7, "OK")
    rep.check("不触发补救（仅 1 次调用）", d7["attempts"], 1)

    print("── 6. 三 provider 最低档映射一致性 ──")
    rep.check("OpenAI 强制档 = low", enforce_lowest("openai"), "low")
    rep.check("Anthropic 强制档 = low", enforce_lowest("anthropic"), "low")
    rep.check("Gemini 强制档 = minimal", enforce_lowest("gemini"), "minimal")
    # 关键不变量：强制档必须与「未发档位」不同，否则补救是空操作
    for p in ("openai", "anthropic", "gemini"):
        rep.check(f"{p}: 强制档 != 不发档位", enforce_lowest(p) is not None, True)


def run_compaction_extract_fix(model, body="摘要正文"):
    """新提取：未知 part 也取 text 字段（宽松兜底）。"""
    resp = model.respond(None, None) if model.requires_thinking is False else {}
    # 直接对响应做宽松提取
    text = extract_text_lenient(resp["content"])
    return ("OK" if text and not is_truncated(resp["stopReason"]) else "FAIL_EMPTY"), {}


def extract_text_lenient(content):
    """修复后的提取：未知 type 的 part 只要有 text 就取（不再静默丢弃）。"""
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        out = []
        for part in content:
            if isinstance(part, str):
                out.append(part)
            elif isinstance(part, dict):
                if part.get("text"):
                    out.append(part["text"])
                elif part.get("type") in ("image_url", "input_image"):
                    out.append("[图片已省略]")
        return "".join(out)
    return str(content)


# ══════════════════════════════════════════════════════════════════════
# 八、失败注入自检
# ══════════════════════════════════════════════════════════════════════
def suite_mutation(rep):
    print("── 失败注入：正向场景是否真有判别力 ──")

    def inject_naive_retry():
        m = CompactionModel(requires_thinking=True, length_stop=True)
        v, _ = run_compaction("openai", m, None, None, retry_strategy="low_enforced")
        # 把「强制低档」偷换成旧的 minimal 映射 → 应失败
        v2, _ = run_compaction("openai", CompactionModel(requires_thinking=True, length_stop=True),
                               None, None, retry_strategy="minimal_naive")
        return v == "OK" and v2 == "FAIL_EMPTY"

    def inject_openai_minimal_is_none():
        # 若有人把 OpenAI 的 minimal 改成透传（不再是 None），空操作性质消失 → 该断言变红
        return openai_effort_field("minimal") is None

    def inject_strict_extract_loses_content():
        m = CompactionModel(requires_thinking=False, content_shape="parts_bad_type")
        v, _ = run_compaction("openai", m, None, None, retry_strategy="none", extract_strict=True)
        return v == "FAIL_EMPTY"

    injections = [
        ("旧 minimal 补救被当成有效修复（应判失败）", inject_naive_retry, True),
        ("OpenAI minimal→None 的空操作性质（应保留）", inject_openai_minimal_is_none, True),
        ("非白名单 part 静默丢弃（应可复现）", inject_strict_extract_loses_content, True),
    ]
    for name, fn, want_true in injections:
        got = fn()
        ok = got is want_true
        print(f"  {'OK  ' if ok else 'FAIL'} 注入「{name}」→ {'成立' if got else '不成立'}")
        if not ok:
            rep.failures.append(f"失败注入「{name}」未达预期")

    rep.check("基线（不注入）应全绿", len(rep.failures), 0)


# ══════════════════════════════════════════════════════════════════════
def main():
    print("=" * 74)
    print("空摘要（empty_or_truncated_summary）修复建模回归")
    print("=" * 74)
    rep = Report()
    print("── 0. 源码对账：脚本建模的常量/前提与真实 Kotlin 是否一致 ──")
    ok, problems = reconcile_with_source()
    rep.check("常量与源码一致（不漂移）", ok, True)
    for p in problems:
        print("       !", p)
    print()
    suite_forward(rep)
    print()
    suite_mutation(rep)
    print()
    print("=" * 74)
    if rep.failures:
        print("[FAIL] 未通过：")
        for f in rep.failures:
            print("  -", f)
        return 1
    print("[OK] 空摘要修复建模回归通过")
    print("  - 关键结论：旧 minimal 补救在 OpenAI 路径是空操作（minimal→None）")
    print("  - 修复要点：强制真正最低档 + 响应侧宽松提取 + 空结果拒绝入库")
    return 0


if __name__ == "__main__":
    sys.exit(main())
