#!/usr/bin/env python3
"""上下文压缩全流程回归（纯 py3 建模仿真 + 失败注入自检）。

为什么存在：压缩链路的缺陷不产生编译错误、也未必出现在日志里——本项目实测踩过三类：
  A 恢复段锚在最前端 → 拆分点恒为 0 → 压缩被永久跳过（会话上下文只涨不降）
  B 恢复段自身超预算仍受保护 → 每轮压缩只折掉寥寥几条 → 重复压缩、空转
  C 阈值自适应方向写反 → 低阈值用户被抬高到压不动
这三类都无法靠单测覆盖（依赖真实消息时序与 DAO 状态），故用独立建模仿真守住。

用法：python3 scripts/check_compaction_flow.py      （退出码 0 = 通过，1 = 有失败）

设计要点——**失败注入（变异测试）**：
本脚本先跑正向场景，再把已知正确逻辑替换成「已知错误的形态」，要求对应检查变红。
若某缺陷注入后仍全绿，说明该场景无判别力，脚本自身判为失败。
这一步不可省：本项目实测出现过「把事故 B 的修复整个删掉，正向场景仍 36/36 全绿」的假绿灯。

维护约定：改动 CompactionTailSelector / CompactionThreshold / ModelContextPolicy，
或改 AgentMessageDao 的压缩块 SQL、MessagePersistenceUseCase.buildHistory 的筛选与配对时，
须同步核对本脚本的建模并重跑。

覆盖面与已知近似（勿把它当作全量权威）：
  - **孤儿工具轮**：真实 buildHistoryUncached 会保留「全孤儿 assistant 轮」并给其补占位 tool 结果
    （MessagePersistenceUseCase.kt:315-337），本脚本直接丢弃该轮。本脚本的场景数据均为成对调用，
    故不影响判别力；但**不要**用本脚本推断孤儿轮行为。
  - **触发口径**：用 `fixed_overhead` 模拟「真实 usage 含 system prompt + tools」，
    即 `usage = est + fixed_overhead`（对账 ContextCompactor 的 `lastInputTokens` 优先口径）。
    但**触发阈值本身只建模 base_percent 与 fast_growth**，`reachedHardLimit` 硬上限兜底
    仍未建模；`lastCompactionSizeBySession` 的口径对齐（Δ 带过）亦未建模。
  - **摘要窗口**：已建模 head 超出「摘要模型窗口 × 70%」时的丢弃量化
    （CompactionSummaryWindow），但**未建模** trimLeadingForCompaction 对超长工具输出的
    前置截断（该截断会把 head 拉回预算内、减少丢弃量，故本模型偏保守）。
  - **adjustSplitIndex**（CompactionSplitAdjuster）与 **cleanSummary**（滚动摘要清理）已建模
    （正向组 12/13 + 失败注入各 1 项）：覆盖「marker/summary 对不劈开、末尾对不清空 tail」与
    「previous-summary 剥尾部块提示、保留丢弃注记」两条约束；其余细节由
    CompactionSplitAdjusterTest.kt 单测覆盖。
  - **SessionHistoryPager.pack 的「首条无条件放入」**未建模；在 MAX_CHARS_PER_MESSAGE(1200)
    < MAX_TOTAL_CHARS(24000) 的现常量下两者等价，两常量关系若反转则模型会偏离。
"""

import copy
import io
import os
import re
import sys
import contextlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ══════════════════════════════════════════════════════════════════════
# 一、常量（逐条抄自源码，改源码须同步）
# ══════════════════════════════════════════════════════════════════════
DEFAULT_CONTEXT_TOKENS = 128_000      # ModelContextPolicy.DEFAULT_CONTEXT_TOKENS
MAX_PRESERVE_RECENT_TOKENS = 20_000   # ModelContextPolicy.MAX_PRESERVE_RECENT_TOKENS
PRESERVE_WINDOW_RATIO = 0.10          # ModelContextPolicy.PRESERVE_WINDOW_RATIO
MIN_PRESERVE_RECENT_TOKENS = 2_000    # ModelContextPolicy.MIN_PRESERVE_RECENT_TOKENS
SUMMARY_WINDOW_RESERVE_RATIO = 0.7    # ModelContextPolicy / ContextCompactor.SUMMARY_WINDOW_RESERVE_RATIO
SUMMARY_TOKENS_ESTIMATE = 10_000      # ModelContextPolicy.SUMMARY_TOKENS_ESTIMATE
ADAPTIVE_STEP = 15                    # CompactionThreshold.ADAPTIVE_STEP
ADAPTIVE_FLOOR = 60                   # CompactionThreshold.ADAPTIVE_FLOOR
GROWTH_RATIO = 0.15                   # CompactionThreshold.GROWTH_RATIO

MAX_CHARS_PER_MESSAGE = 1_200         # SessionHistoryPager.MAX_CHARS_PER_MESSAGE
MAX_TOTAL_CHARS = 24_000              # SessionHistoryPager.MAX_TOTAL_CHARS
MAX_LIMIT = 50                        # SessionHistoryPager.MAX_LIMIT
DEFAULT_LIMIT = 20                    # SessionHistoryPager.DEFAULT_LIMIT

ROLE_USER, ROLE_ASSISTANT, ROLE_TOOL = "user", "assistant", "tool"
TRUNCATE_SUFFIX = "…（本条已截断，全文更长）"   # SessionHistoryPager.truncate
# AgentMessage.kt 的压缩锚点/摘要前缀（对账 ContextCompactor.cleanSummary 与 CompactionSplitAdjuster）
MARKER_TEXT = "What did we do so far?"
SUMMARY_LEGACY_PREFIX = "【系统提示：早期的对话已被压缩，以下是之前的核心状态摘要】"
SUMMARY_TIMESTAMP_PREFIX = "【摘要时点】"
# ContextCompactor.kt 的 blockHintAnchor 字面量（剥尾注锚点，组 14 与源码对账）。
# B6 修复后块提示不再内嵌随机 blockId，锚点随之简化为不含 id 的稳定前缀。
HINT_ANCHOR = "\n\n---\n> 本压缩块"


# ══════════════════════════════════════════════════════════════════════
# 二、被测逻辑（对账真实源码）
# ══════════════════════════════════════════════════════════════════════
class Entity:
    """对账 AgentMessageEntity。"""

    __slots__ = ("id", "role", "content", "timestamp", "is_compacted",
                 "is_context_summary", "is_compaction_marker", "block_id",
                 "tool_call_id", "tool_calls")

    def __init__(self, id, role, content, timestamp, tool_call_id=None, tool_calls=None):
        self.id = id
        self.role = role
        self.content = content
        self.timestamp = timestamp
        self.is_compacted = False
        self.is_context_summary = False
        self.is_compaction_marker = False
        self.block_id = None
        self.tool_call_id = tool_call_id
        self.tool_calls = tool_calls or []

    def tok(self):
        return max(1, len(self.content) // 3)   # 近似：字符 / 3


def preserve_recent_tokens(usable_tokens, context_limit=DEFAULT_CONTEXT_TOKENS):
    """对账 ModelContextPolicy.preserveRecentTokens。"""
    cap = max(MAX_PRESERVE_RECENT_TOKENS, int(context_limit * PRESERVE_WINDOW_RATIO))
    return max(MIN_PRESERVE_RECENT_TOKENS, min(usable_tokens // 4, cap))


def min_tail_for_summary_window(trigger, summary_window_tokens):
    """对账 ModelContextPolicy.minTailForSummaryWindow：head 须装进摘要输入预算。"""
    summary_input_budget = int(summary_window_tokens * SUMMARY_WINDOW_RESERVE_RATIO)
    head_allowance = summary_input_budget - SUMMARY_TOKENS_ESTIMATE
    return trigger - head_allowance


def min_summary_window_for_feasibility():
    """对账 ModelContextPolicy.minSummaryWindowForFeasibility：C1/C2 可行的最小摘要窗口。"""
    return int(-(-(2 * SUMMARY_TOKENS_ESTIMATE + 1) // SUMMARY_WINDOW_RESERVE_RATIO))


def tail_budget_plan(trigger, context_limit=DEFAULT_CONTEXT_TOKENS,
                     summary_window_tokens=DEFAULT_CONTEXT_TOKENS, overhead_tokens=0):
    """对账 ModelContextPolicy.tailBudgetPlan：返回 (tail, feasible, reason)。

    修复点（128k 静默丢历史）：旧实现 tail 被 max(20000, 窗口*10%) 封顶，
    128k 主 + 128k 摘要 + 90% 阈值下 head=95200 > 摘要预算 89600，每轮丢最旧 5600 token。
    新实现用 C2 下界抬高 tail 把 head 压回摘要窗口内；C1/C2 无交集（摘要窗口 ≲28.6k）时
    置 feasible=False，由调用方告警而非静默丢历史。

    修复点二（C1 未计固定开销 Δ）：C1 上界须扣 Δ（system prompt + tools + 记忆块），
    与触发判断（真实 usage 含 Δ）同口径；否则「摘要窗口 < 主窗口」压缩后 = tail+摘要+Δ ≥ 阈值
    → 压完立即再触发。见 ModelContextPolicy.tailBudgetPlan(overheadTokens)。
    """
    proportional = preserve_recent_tokens(trigger, context_limit)
    lower = min_tail_for_summary_window(trigger, summary_window_tokens)
    overhead = max(0, overhead_tokens)
    upper = max(MIN_PRESERVE_RECENT_TOKENS, trigger - SUMMARY_TOKENS_ESTIMATE - overhead - 1)
    lo = max(MIN_PRESERVE_RECENT_TOKENS, lower)
    feasible = lo <= upper
    tail = min(max(proportional, lo), upper) if feasible else upper
    if feasible:
        reason = "C1 与 C2 同时满足"
    elif overhead + SUMMARY_TOKENS_ESTIMATE >= trigger:
        reason = "固定开销过大"
    else:
        reason = "C1/C2 无交集"
    return tail, feasible, reason


def tail_budget(trigger, context_limit=DEFAULT_CONTEXT_TOKENS,
                summary_window_tokens=DEFAULT_CONTEXT_TOKENS, overhead_tokens=0):
    """对账 ModelContextPolicy.tailBudget（= tailBudgetPlan().tail）。"""
    return tail_budget_plan(trigger, context_limit, summary_window_tokens, overhead_tokens)[0]


def effective_percent(base_percent, fast_growth):
    """对账 CompactionThreshold.effectivePercent（下调只降不升）。"""
    if not fast_growth:
        return base_percent
    floor = min(base_percent, ADAPTIVE_FLOOR)
    return max(base_percent - ADAPTIVE_STEP, floor)


def trigger_tokens(context_limit, base_percent, fast_growth):
    """对账 CompactionThreshold.triggerTokens。"""
    return int(context_limit * effective_percent(base_percent, fast_growth) / 100.0)


def build_history(entities):
    """对账 MessagePersistenceUseCase.buildHistoryUncached：筛选 + 工具调用配对。"""
    live = [e for e in entities if not e.is_compacted]
    restored_ids = {e.id for e in live
                    if e.block_id and not e.is_context_summary and not e.is_compaction_marker}

    declared, result = set(), set()
    for e in live:
        if e.role == ROLE_ASSISTANT:
            for (cid, _) in e.tool_calls:
                declared.add(cid)
        elif e.role == ROLE_TOOL and e.tool_call_id:
            result.add(e.tool_call_id)
    valid = declared & result

    out = []
    for e in live:
        if e.role == ROLE_USER:
            out.append(e)
        elif e.role == ROLE_ASSISTANT:
            kept = [tc for tc in e.tool_calls if tc[0] in valid]
            if e.tool_calls and not kept:
                continue                       # 全是孤儿声明 → 丢弃
            out.append(e)
        elif e.role == ROLE_TOOL:
            if e.tool_call_id in valid:
                out.append(e)
    return out, restored_ids


def is_restored(e, restored_ids):
    return e.id in restored_ids


def compute_tail_split(msgs, budget, restored_ids):
    """对账 CompactionTailSelector.compute（含两处修复）。"""
    total, split = 0, len(msgs)
    anchor = None
    for i in range(len(msgs)):
        if i > 0 and is_restored(msgs[i], restored_ids) and not is_restored(msgs[i - 1], restored_ids):
            anchor = i
            break
    protected = anchor                         # 修复 A：段首为 0 时不保护（anchor 非 None 才算）
    if anchor is not None:
        # 修复 B（v2）：上界按「段首到末尾全部」验，不是只验段自身——
        # tail 是连续区间，保护段首必然连带其后所有新消息免预算，只验段自身
        # 会让段后积累无限膨胀（实测 70 万 token 空转事故）。
        if sum(m.tok() for m in msgs[anchor:]) > budget:
            protected = None                   # 段后消息超预算则不保护，恢复段按普通预算折回
    for idx in range(len(msgs) - 1, -1, -1):
        in_restored = protected is not None and idx >= protected
        nxt = msgs[idx].tok()
        if (not in_restored) and total + nxt > budget and split < len(msgs):
            break
        total += nxt
        split = idx
        if protected is not None and idx == protected:
            break
    return split


def adjust_split_index(msgs, initial_split):
    """对账 CompactionSplitAdjuster.adjust（两条约束：tail 不以孤立 tool 结果开头；marker/summary 对不劈开）。"""
    split = max(0, min(initial_split, len(msgs)))

    def walk_back(ix):
        while ix > 0 and msgs[ix].role == ROLE_TOOL:
            ix -= 1
        return ix

    split = walk_back(split)
    if (1 <= split < len(msgs) - 1 and
            msgs[split - 1].role == ROLE_USER and msgs[split - 1].content == MARKER_TEXT and
            msgs[split].role == ROLE_ASSISTANT):
        split += 1
    split = walk_back(split)
    return split


def clean_summary(text):
    """对账 ContextCompactor.cleanSummary：剥尾部块提示 + legacy 前缀 + 时点标注，丢弃注记保留。"""
    if HINT_ANCHOR in text:
        # rindex：正文若复读含锚点，取最后一次出现（真正的尾部提示）。
        text = text[:text.rindex(HINT_ANCHOR)].rstrip()
    if text.startswith(SUMMARY_LEGACY_PREFIX):
        text = text[len(SUMMARY_LEGACY_PREFIX):]
    if text.startswith(SUMMARY_TIMESTAMP_PREFIX):
        nl = text.find("\n")
        text = text[nl + 1:] if nl >= 0 else ""
        text = text.lstrip()
    return text


class Session:
    """对账 AgentMessageDao 的压缩块 SQL 语义 + 压缩流程。"""

    def __init__(self, context_limit=1_000_000, base_percent=15, fixed_overhead=87_000,
                 summary_window_tokens=None):
        self.entities = []
        self.seq = 0
        self.block_seq = 1
        self.context_limit = context_limit
        self.base_percent = base_percent
        self.fixed_overhead = fixed_overhead
        self.last_compacted_size = 0
        # 摘要模型窗口：None 表示与主窗口一致（ContextCompactor 的 resolve 回退 128k）。
        self.summary_window_tokens = (summary_window_tokens
                                      if summary_window_tokens is not None
                                      else context_limit)

    def add(self, role, content, tool_call_id=None, tool_calls=None):
        self.seq += 1
        e = Entity("m%d" % self.seq, role, content, self.seq * 1000,
                   tool_call_id=tool_call_id, tool_calls=tool_calls)
        self.entities.append(e)
        return e

    def _guard_budget(self, trigger, est, msgs, budget):
        """拆分点归零时的守卫（对账 ContextCompactor 的降级分支）。

        返回 (forced_split, degraded)：None 表示「确实无内容可压」→ 正当跳过；
        否则强制按返回下标拆分（划分逻辑异常，如恢复段保护吞掉全部）。

        **必须与选择器同用 `budget`**：选择器判「全装得下 → split=0」，
        守卫若用更小的预算就会把「无需压缩」误判成「划分失败」而折叠全部历史。
        """
        if est > budget and len(msgs) > 1:
            return len(msgs) - 1, True
        return None, False

    def _summary_window_drop(self, head):
        """对账 ContextCompactor 的摘要输入截断：head 装不进摘要窗口时丢弃最旧的消息。

        返回 (kept_head, dropped_tokens)。仅在「清理后仍超预算」时丢——
        真实实现会先 trimLeadingForCompaction 截断超长工具输出，本模型用 head 的
        原始 token 近似该清理后的量（工具输出截断语义不在此建模，见文件头覆盖面）。
        """
        budget = int(self.summary_window_tokens * SUMMARY_WINDOW_RESERVE_RATIO)
        total = sum(m.tok() for m in head)
        if total <= budget:
            return head, 0
        kept, acc = [], 0
        for m in reversed(head):
            t = m.tok()
            if kept and acc + t > budget:
                break
            acc += t
            kept.append(m)
        kept = list(reversed(kept))
        return kept, total - acc

    def auto_compact(self):
        msgs, restored = build_history(self.entities)
        est = sum(m.tok() for m in msgs)
        usage = est + self.fixed_overhead
        fast = (self.last_compacted_size > 0 and
                est - self.last_compacted_size > self.context_limit * GROWTH_RATIO)
        trig = trigger_tokens(self.context_limit, self.base_percent, fast)
        if usage < trig:
            return "BELOW", {"usage": usage, "trigger": trig, "est": est}

        # 选择器与守卫共用同一预算（对账 ContextCompactor 的 budgetPlan 单次求值）：
        # 历史 bug 是选择器用 tailBudget、守卫用更小的 preserveRecentTokens，把
        # 「全装得下＝无需压缩」误判成「划分失败」→ 强制折叠全部历史。
        budget, feasible, reason = tail_budget_plan(trig, self.context_limit, self.summary_window_tokens)
        split = compute_tail_split(msgs, budget, restored)
        if split <= 0:
            # 守卫走可注入方法（便于失败注入复现「口径分裂」形态）。
            forced_split, degraded = self._guard_budget(trig, est, msgs, budget)
            if forced_split is None:
                return "SKIP_NO_CONTENT", {"usage": usage, "trigger": trig, "budget": budget,
                                           "est": est, "n": len(msgs), "feasible": feasible,
                                           "degraded": False}
            split = forced_split
        else:
            degraded = False
        # 对账 ContextCompactor 拆分前的 adjustSplitIndex（正常与降级分支统一走一遍）。
        # 接线钩子：记录调用痕迹供集成层断言核「接线存在」。注意：现有正向场景中
        # adjust 从不改变拆分点（无边界落在 marker/summary 之间的样本），故此处只守
        # 「整段删除」；「丢弃赋值结果」由组 13 直接断言与注入项守住。
        split = adjust_split_index(msgs, split)
        getattr(self, "adjust_wiring", None) or setattr(self, "adjust_wiring", [])
        self.adjust_wiring.append(split)

        head, tail = msgs[:split], msgs[split:]
        kept_tok = sum(m.tok() for m in tail)
        verdict = "OK" if kept_tok <= budget else "OVER_BUDGET"
        # 摘要窗口截断：head 装不下时丢最旧（本模型只量化丢弃量，供断言与回归）。
        kept_head, dropped_tok = self._summary_window_drop(head)

        block_id = "B%d" % self.block_seq
        self.block_seq += 1
        for m in kept_head:
            m.is_compacted = True
            m.block_id = block_id
        self._add_marker_summary(block_id, 10_000)      # 实测摘要约 10k 字符
        self.last_compacted_size = kept_tok + 10_000 // 3
        return verdict, {"usage": usage, "trigger": trig, "budget": budget,
                         "split": split, "folded": len(kept_head), "kept": len(tail),
                         "kept_tok": kept_tok, "dropped_tok": dropped_tok,
                         "head_tok": sum(m.tok() for m in head),
                         "degraded": degraded, "feasible": feasible,
                         "summary_budget": int(self.summary_window_tokens
                                               * SUMMARY_WINDOW_RESERVE_RATIO)}

    def _add_marker_summary(self, block_id, summary_len):
        self.seq += 1
        mk = Entity("m%d" % self.seq, ROLE_USER, "What did we do so far?", self.seq * 1000)
        mk.is_compaction_marker = True
        mk.block_id = block_id
        self.entities.append(mk)
        self.seq += 1
        sm = Entity("m%d" % self.seq, ROLE_ASSISTANT, "S" * summary_len, self.seq * 1000)
        sm.is_context_summary = True
        sm.block_id = block_id
        self.entities.append(sm)

    def list_blocks(self):
        """对账 listCompactionBlocks。"""
        agg = {}
        for e in self.entities:
            if not e.block_id:
                continue
            b = agg.setdefault(e.block_id, {"total": 0, "compacted": 0, "min_ts": e.timestamp})
            b["total"] += 1
            b["compacted"] += 1 if e.is_compacted else 0
            b["min_ts"] = min(b["min_ts"], e.timestamp)
        return dict(sorted(agg.items(), key=lambda kv: kv[1]["min_ts"]))

    def count_compacted_by_block(self, block_id):
        """对账 countCompactedByBlock。"""
        return sum(1 for e in self.entities
                   if e.block_id == block_id and e.is_compacted
                   and not e.is_context_summary and not e.is_compaction_marker)

    def restore_block(self, block_id):
        """对账 restoreCompactionBlock：
        SET isCompacted = CASE WHEN isContextSummary=1 OR isCompactionMarker=1 THEN 1 ELSE 0 END
        WHERE compactionBlockId = :blockId。块归属不清除。"""
        for e in self.entities:
            if e.block_id != block_id:
                continue
            e.is_compacted = bool(e.is_context_summary or e.is_compaction_marker)

    def retake_block(self, block_id):
        """对账 retakeCompactionBlock（撤销恢复）。"""
        for e in self.entities:
            if e.block_id != block_id:
                continue
            e.is_compacted = not (e.is_context_summary or e.is_compaction_marker)

    def stats(self):
        msgs, restored = build_history(self.entities)
        return {"live_msgs": len(msgs), "est": sum(m.tok() for m in msgs),
                "usage": sum(m.tok() for m in msgs) + self.fixed_overhead,
                "restored_count": len(restored), "total_rows": len(self.entities)}


def pager_truncate(content, maxlen=MAX_CHARS_PER_MESSAGE):
    """对账 SessionHistoryPager.truncate。"""
    return content if len(content) <= maxlen else content[:maxlen] + TRUNCATE_SUFFIX


def pager_clamp_limit(raw):
    """对账 SessionHistoryPager.clampLimit。"""
    return min(max(raw if raw is not None else DEFAULT_LIMIT, 1), MAX_LIMIT)


def pager_preview(entities, keyword="", before_ts=0, limit=DEFAULT_LIMIT):
    """对账 RestoreCompactedRangeTool.preview：时间倒序 + 条数上限 + 总字符上限。"""
    hits = [e for e in entities
            if (not keyword or keyword in e.content)
            and (before_ts == 0 or e.timestamp < before_ts)]
    hits.sort(key=lambda e: e.timestamp, reverse=True)
    page, used, budget_hit = [], 0, False
    for e in hits[:limit]:
        body = pager_truncate(e.content)
        if used + len(body) > MAX_TOTAL_CHARS:
            budget_hit = True
            break
        used += len(body)
        item = Entity(e.id, e.role, body, e.timestamp)     # 页面给的是截断后文本
        item.is_compacted = e.is_compacted
        item.is_context_summary = e.is_context_summary
        item.is_compaction_marker = e.is_compaction_marker
        item.block_id = e.block_id
        page.append(item)
    return {"items": page, "total": len(hits), "has_more": len(page) < len(hits),
            "cursor": hits[len(page) - 1].timestamp if page else None,
            "budget_hit": budget_hit, "chars": used}


# ══════════════════════════════════════════════════════════════════════
# 三、断言框架
# ══════════════════════════════════════════════════════════════════════
class Report:
    def __init__(self):
        self.failures = []

    def check(self, name, got, want, verbose=True):
        ok = got == want
        if not ok:
            self.failures.append("%s：得到 %r，期望 %r" % (name, got, want))
        if verbose:
            print("  %s %s" % ("OK  " if ok else "FAIL", name))
        return ok


# ══════════════════════════════════════════════════════════════════════
# 四、场景构造
# ══════════════════════════════════════════════════════════════════════
def session_normal(rounds=70):
    """常规会话：user / assistant(toolCall) / tool 配对，内容够触发压缩。"""
    s = Session()
    for i in range(1, rounds + 1):
        tc = "c%d" % i
        s.add(ROLE_USER, "用户要求第 %d 条：" % i + "要" * 200)
        s.add(ROLE_ASSISTANT, "处理。" + "x" * 300, tool_calls=[(tc, "readFile")])
        s.add(ROLE_TOOL, "输出内容 %d：" % i + "y" * 3000, tool_call_id=tc)
    return s


def session_front_anchored():
    """事故形态：恢复段锚在下标 0。
    构造要点——必须先压缩、再解压（原文成为最早的记录），最后追加新内容。
    若先建主体后解压，恢复段前面还有 marker/summary，restoredStart 就不为 0，
    场景将失去判别力（本项目实测踩过这个构造陷阱）。"""
    s = Session()
    for i in range(1, 71):
        tc = "a%d" % i
        s.add(ROLE_USER, "早 %d：" % i + "x" * 300)
        s.add(ROLE_ASSISTANT, "处" + "y" * 300, tool_calls=[(tc, "readFile")])
        s.add(ROLE_TOOL, "出" + "z" * 3000, tool_call_id=tc)
    s.auto_compact()
    s.restore_block(list(s.list_blocks().keys())[0])
    for i in range(1, 51):
        tc = "b%d" % i
        s.add(ROLE_ASSISTANT, "新" + "p" * 400, tool_calls=[(tc, "readFile")])
        s.add(ROLE_TOOL, "结" + "q" * 3000, tool_call_id=tc)
    return s


def session_oversized_restored():
    """恢复段位于中段且自身超预算。"""
    s = Session()
    for i in range(1, 21):
        s.add(ROLE_USER, "旧" + "a" * 400)
    for i in range(1, 81):
        s.add(ROLE_USER, "恢复" + "b" * 1500)
    for i in range(1, 21):
        tc = "t%d" % i
        s.add(ROLE_ASSISTANT, "新" + "c" * 400, tool_calls=[(tc, "readFile")])
        s.add(ROLE_TOOL, "出" + "d" * 3000, tool_call_id=tc)
    for e in s.entities[21:101]:
        e.block_id = "BRESTORED"
        e.is_compacted = False
    return s


def session_oversized_after_restored():
    """事故 C：恢复段自身装得下预算，但段后积累超预算（旧修复漏掉的形态）。
    结构对齐 2026-10-11 真实事故：折叠头 → 恢复段（87 条原文，自身约 3 万 token）
    → 段后大量新消息（一夜积累 70 万 token）。旧上界只验段自身 → 保护启用 →
    段后全部免预算进 tail → 每轮压缩空转、上下文只涨不降。"""
    s = Session()
    s.add(ROLE_USER, "[系统说明] 更早步骤已折叠为摘要")
    # 恢复段：21 条、自身约 2.1 万 token（预算 37500 内）
    for i in range(1, 22):
        s.add(ROLE_USER, "恢复" + "b" * 1000)
        s.entities[-1].block_id = "BRESTC"
        s.entities[-1].is_compacted = False
    # 段后积累：远超预算的工具输出流
    for i in range(1, 61):
        tc = "c%d" % i
        s.add(ROLE_ASSISTANT, "新" + "c" * 200, tool_calls=[(tc, "readFile")])
        s.add(ROLE_TOOL, "出" + "d" * 3000, tool_call_id=tc)
    return s


# ══════════════════════════════════════════════════════════════════════
# 五、正向场景
# ══════════════════════════════════════════════════════════════════════
def suite_forward(rep):
    print("── 1. 正常压缩与收敛 ──")
    s = session_normal()
    st0 = s.stats()
    r1, d1 = s.auto_compact()
    st1 = s.stats()
    rep.check("首次压缩成功", r1, "OK")
    rep.check("压缩后回放消息减少", st1["live_msgs"] < st0["live_msgs"], True)
    rep.check("压缩后上下文下降", st1["est"] < st0["est"], True)
    rep.check("tail 未超预算", d1["kept_tok"] <= d1["budget"], True)
    r2, _ = s.auto_compact()
    rep.check("压缩后不再触发（已收敛）", r2, "BELOW")

    print("── 2. 解压 → 回放 → 再压缩（关键路径）──")
    s = session_normal()
    s.auto_compact()
    bid = list(s.list_blocks().keys())[0]
    before = s.stats()
    n_restored = s.count_compacted_by_block(bid)
    n_in_block = s.list_blocks()[bid]["compacted"]
    s.restore_block(bid)
    after = s.stats()
    rep.check("解压使回放消息变多", after["live_msgs"] > before["live_msgs"], True)
    rep.check("解压使上下文变大", after["est"] > before["est"], True)
    rep.check("解压后消息被标恢复段", after["restored_count"] > 0, True)
    rep.check("恢复条数等于块内折叠数", n_restored, n_in_block)
    r3, _ = s.auto_compact()
    rep.check("解压后再压缩确实执行（非跳过/非空转）", r3 in ("OK", "OVER_BUDGET"), True)

    print("── 3. 恢复段锚在最前端（事故 A 形态）──")
    s = session_front_anchored()
    msgs, rs = build_history(s.entities)
    anchor = next((i for i in range(len(msgs)) if is_restored(msgs[i], rs)), None)
    rep.check("构造有效：恢复段起始下标为 0", anchor, 0)
    budget = tail_budget(trigger_tokens(1_000_000, 15, False), 1_000_000, 1_000_000)
    rep.check("段首为 0 时拆分点不为 0", compute_tail_split(msgs, budget, rs) > 0, True)
    r4, d4 = s.auto_compact()
    rep.check("段首为 0 时压缩确实执行（非跳过/非空转）", r4 in ("OK", "OVER_BUDGET"), True)

    print("── 4. 恢复段自身超预算（事故 B 形态）──")
    s = session_oversized_restored()
    r5, d5 = s.auto_compact()
    rep.check("超预算恢复段不阻止压缩", r5, "OK")
    rep.check("tail 落在预算内", d5["kept_tok"] <= d5["budget"], True)

    print("── 4b. 段后积累超预算（事故 C 形态：保护必须退位）──")
    s = session_oversized_after_restored()
    r5c, d5c = s.auto_compact()
    rep.check("段后超预算时压缩不空转", r5c, "OK")
    rep.check("tail 落在预算内（无免预算膨胀）", d5c["kept_tok"] <= d5c["budget"], True)
    # 事故核心断言：修复前该形态下恢复段受保护 → 段后全部免预算进 tail → kept_tok 远超预算。
    # 压缩前的全量（恢复段+段后积累，不含已被折入 head 的头部）必须远大于 tail 预算，
    # 而 kept_tok 受预算约束——两者比值证明保护确实退位、恢复段被折回。
    pre_total = 21 * 1000 + 60 * (300 + 3000)
    rep.check("tail 仅占全量一小部分（保护确实退位）",
              d5c["kept_tok"] < pre_total // 4, True)
    # 连续收敛：追加足量新内容再压，仍不空转且 tail 有界（无限压缩 bug 的反向锁定）。
    # 注：修复前的旧实现下二次压缩会返回 OVER_BUDGET/超预算 tail，被首检拗红。
    for i in range(1, 31):
        tc = "z%d" % i
        s.add(ROLE_ASSISTANT, "再 " + "e" * 200, tool_calls=[(tc, "readFile")])
        s.add(ROLE_TOOL, "果" + "f" * 3000, tool_call_id=tc)
    r5c2, d5c2 = s.auto_compact()
    rep.check("追加后再压缩仍不空转", r5c2, "OK")
    rep.check("追加后 tail 仍有界", "kept_tok" in d5c2 and d5c2["kept_tok"] <= d5c2["budget"], True)

    print("── 5. 撤销恢复（retake）──")
    s = session_normal()
    s.auto_compact()
    bid = list(s.list_blocks().keys())[0]
    n0 = s.count_compacted_by_block(bid)
    s.restore_block(bid)
    rep.check("解压把块内折叠数归零", s.count_compacted_by_block(bid), 0)
    s.retake_block(bid)
    rep.check("撤销恢复还原折叠数", s.count_compacted_by_block(bid), n0)

    print("── 6. 多块并存 ──")
    s = Session(context_limit=1_000_000, base_percent=15, fixed_overhead=100_000)
    for rnd in range(1, 7):
        for i in range(1, 26):
            tc = "e%d_%d" % (rnd, i)
            s.add(ROLE_USER, "轮%d-%d：" % (rnd, i) + "a" * 200)
            s.add(ROLE_ASSISTANT, "处理。" + "b" * 200, tool_calls=[(tc, "readFile")])
            s.add(ROLE_TOOL, "输出" + "c" * 4000, tool_call_id=tc)
        s.auto_compact()
    blocks = s.list_blocks()
    rep.check("多轮产生多个块", len(blocks) >= 2, True)
    for bid in list(blocks.keys())[:2]:
        s.restore_block(bid)
    rep.check("多块解压后恢复段累积", s.stats()["restored_count"] > 0, True)
    wiring = getattr(s, "adjust_wiring", [])
    # 接线钩子的能力边界：现有正向场景里 tail 预算边界从未落在历史 marker/summary 之间，
    # 故 adjust 在该层从不改变拆分点——此断言只能抓住「整段删除」（wiring 空），
    # 抓不住「留钩子但丢弃赋值结果」；后者由组 13 直接断言与注入项守住。
    rep.check("集成层 adjust 接线被调用（wiring 非空）", bool(wiring), True)

    print("── 7. 阈值与预算 ──")
    rep.check("低阈值不被抬高（fast）", effective_percent(15, True), 15)
    rep.check("高阈值下调到下限", effective_percent(90, True), 75)
    # 大窗口下 tail 预算 = 阈值/4（未触达基础上限）——写死上限的实现会在这里变小。
    # 只比「大窗口 > 小窗口」不够：写死 20000 上限时 1M 窗口得 20000 仍大于 128k 窗口的 4800，测不出。
    big_trig = trigger_tokens(1_000_000, 15, False)
    rep.check("大窗口预算 = 阈值/4（未触上限）",
              preserve_recent_tokens(big_trig, 1_000_000), big_trig // 4)
    rep.check("窗口越大 tail 预算越大",
              preserve_recent_tokens(big_trig, 1_000_000)
              > preserve_recent_tokens(trigger_tokens(128_000, 15, False), 128_000), True)

    print("── 8. 翻阅上下文（分页）──")
    s = session_normal()
    s.auto_compact()
    rows = s.entities
    p1 = pager_preview(rows)
    rep.check("首页受条数上限约束", len(p1["items"]) <= DEFAULT_LIMIT, True)
    rep.check("首页有更多页", p1["has_more"], True)
    seen, cur, pages = set(), 0, 0
    while pages < 60:
        p = pager_preview(rows, before_ts=cur)
        if not p["items"]:
            break
        seen.update(e.id for e in p["items"])
        cur = p["cursor"]
        pages += 1
        if not p["has_more"]:
            break
    rep.check("游标翻完全部记录", len(seen), len(rows))
    compacted_seen = 0
    cur = 0
    for _ in range(60):
        p = pager_preview(rows, before_ts=cur)
        if not p["items"]:
            break
        compacted_seen += sum(1 for e in p["items"]
                              if e.is_compacted and not e.is_context_summary
                              and not e.is_compaction_marker)
        cur = p["cursor"]
        if not p["has_more"]:
            break
    rep.check("已折叠原文仍可翻阅（压缩≠删除）", compacted_seen > 0, True)
    big = Session()
    for i in range(1, 21):
        big.add(ROLE_USER, "长内容 %d：" % i + "z" * 8000)
    pb = pager_preview(big.entities)
    rep.check("总字符未超上限", pb["chars"] <= MAX_TOTAL_CHARS, True)
    rep.check("字符预算触发提前结束", pb["budget_hit"], True)
    rep.check("单条超长被截断",
              pager_preview(big.entities)["items"][0].content.endswith(TRUNCATE_SUFFIX), True)
    rep.check("limit 下钳", pager_clamp_limit(0), 1)
    rep.check("limit 上钳", pager_clamp_limit(999), MAX_LIMIT)

    suite_128k(rep)
    suite_summary_window(rep)


def suite_128k(rep):
    """128k 窗口 + 默认 90% 阈值：旧实现 tail 被 20000 封顶 → head 超摘要窗口 → 静默丢历史。

    这是「设置 128k 时老是压缩失败」的回归点：压缩看起来成功（返回 OK），
    但每轮都丢掉最旧的一段历史且不进摘要。
    """
    print("── 9. 128k 窗口 + 90% 阈值（静默丢历史回归）──")
    # 数值层：直接锁定 tail 预算公式在 128k/90%/同窗口摘要模型下的输出。
    trig_128k = trigger_tokens(128_000, 90, False)
    rep.check("128k/90% 触发阈值", trig_128k, 115_200)
    old_tail = preserve_recent_tokens(trig_128k, 128_000)          # 旧公式：20000
    rep.check("旧公式 tail 被封顶在 20000", old_tail, 20_000)
    rep.check("旧公式 head 超出摘要窗口（即丢历史）",
              trig_128k - old_tail > int(128_000 * SUMMARY_WINDOW_RESERVE_RATIO), True)
    new_tail = tail_budget(trig_128k, 128_000, 128_000)
    rep.check("新公式 tail 抬到摘要下限之上", new_tail > old_tail, True)
    rep.check("新公式 head 装得进摘要窗口",
              trig_128k - new_tail <= int(128_000 * SUMMARY_WINDOW_RESERVE_RATIO), True)

    # 全组合不变式：任何窗口/阈值/摘要窗口下 head 都不超摘要预算。
    violations = []
    for ctx in (32_000, 64_000, 128_000, 200_000, 400_000, 1_000_000):
        for pct in (15, 60, 90):
            trig = trigger_tokens(ctx, pct, False)
            for sw in (128_000, 200_000, 1_000_000):
                head = trig - tail_budget(trig, ctx, sw)
                if head > int(sw * SUMMARY_WINDOW_RESERVE_RATIO):
                    violations.append((ctx, pct, sw, head))
    rep.check("所有组合下 head 均装得进摘要窗口", violations, [])

    # 端到端：构造 128k 会话（约 100 轮使估算跨过 115200 触发阈值），
    # 压缩后 dropped_tok 必须为 0（不丢历史）。
    s = Session(context_limit=128_000, base_percent=90, fixed_overhead=0,
                summary_window_tokens=128_000)
    for i in range(1, 101):
        tc = "r%d" % i
        s.add(ROLE_USER, "用户第 %d 条：" % i + "要" * 200)
        s.add(ROLE_ASSISTANT, "处理。" + "x" * 400, tool_calls=[(tc, "readFile")])
        s.add(ROLE_TOOL, "输出 %d：" % i + "y" * 3000, tool_call_id=tc)
    verdict, detail = s.auto_compact()
    rep.check("128k 会话触发压缩", verdict, "OK")
    rep.check("128k 压缩不丢任何历史（dropped_tok=0）", detail.get("dropped_tok"), 0)
    rep.check("128k head 未超摘要预算",
              detail.get("head_tok", 10 ** 9) <= detail.get("summary_budget", 0), True)


def suite_summary_window(rep):
    """摘要模型窗口远小于主窗口（如 1M 主 + 128k 摘要）时的丢弃量化与可见性。

    用 400k 主窗口 + 128k 摘要模型（4 倍差，与真实 1M vs 128k 同形态但规模更小），
    构造 head 必然超过 128k 摘要窗口输入预算的历史。
    """
    print("── 10. 摘要窗口截断的量化与可见性 ──")
    drop_probe = Session(context_limit=400_000, base_percent=90, fixed_overhead=0,
                         summary_window_tokens=128_000)
    # 每条 2000 字符 CJK ≈ 666 token，100 条 ≈ 66600；400k@90% 阈值 360000 需更多。
    for i in range(1, 601):
        drop_probe.add(ROLE_USER, "大 %d：" % i + "z" * 2000)
    v, d = drop_probe.auto_compact()
    rep.check("主>摘要窗口时仍能压缩", v, "OK")
    rep.check("丢弃量被量化（dropped_tok 字段存在）", "dropped_tok" in d, True)
    # 该形态下丢弃是固有取舍（head 装不进 128k 摘要窗口）：要么 head 装得下，要么有丢弃记录。
    rep.check("head 超预算时丢弃被记录",
              (d.get("head_tok", 0) <= d.get("summary_budget", 0)) or d.get("dropped_tok", 0) > 0,
              True)

    suite_guard_consistency(rep)


def suite_guard_consistency(rep):
    """守卫与选择器必须同口径：否则「全装得下＝无需压缩」被误判成「划分失败」→ 折叠全部历史。

    这是修复过程中**新引入**的缺陷形态（选择器用 tailBudget、守卫用更小的
    preserveRecentTokens），比原 bug 更糟：用户点 /compress 自救时反而把整段历史折走并丢弃。
    """
    print("── 11. 拆分守卫与选择器同口径（防「无需压缩」被误判成「折叠全部」）──")
    # 数值层：当 est_local 落在 (旧预算, 新预算] 区间时，选择器判「全装得下」，守卫必须同意。
    trig = 115_200
    new_budget, feasible, _ = tail_budget_plan(trig, 128_000, 128_000)
    old_budget = preserve_recent_tokens(trig, 128_000)
    rep.check("新预算 > 旧预算（区间非空，判别力成立）", new_budget > old_budget, True)
    est = (old_budget + new_budget) // 2                 # 落在危险区间
    rep.check("危险区间取值落在 (旧, 新] 内", old_budget < est <= new_budget, True)
    # 同口径守卫：est <= budget → 判「无需压缩」（不折）
    same_guard_folds = est > new_budget
    rep.check("同口径守卫不误折（est<=newBudget 时判无内容）", same_guard_folds, False)
    # 反例口径（历史 bug）：守卫用旧预算 → 误折
    buggy_guard_folds = est > old_budget
    rep.check("反例口径确实会误折（证明该检查有判别力）", buggy_guard_folds, True)

    # 端到端：构造 est 落在危险区间（旧预算 < est ≤ 新预算）、且真实 usage 已达触发条件的会话。
    # 这正是真实形态：system prompt + tools 的固定开销把 usage 顶过阈值，而消息本身还没那么大。
    est_target = (old_budget + new_budget) // 2
    overhead = 115_200 - est_target + 1_000          # 保证 usage ≥ trigger
    s = Session(context_limit=128_000, base_percent=90, fixed_overhead=overhead,
                summary_window_tokens=128_000)
    # 每条 user 消息约 20 token（60 字符 → 20）；条数按 est_target 反推。
    n = max(1, est_target // 20)
    for i in range(1, n + 1):
        s.add(ROLE_USER, "条%d" % i + "x" * 57)
    v, d = s.auto_compact()
    if v == "BELOW":
        rep.check("构造有效：会话达到触发条件", False, True)
    else:
        rep.check("est 在危险区间时判「无内容可压」而非折叠", v, "SKIP_NO_CONTENT")
        rep.check("未发生降级折叠", d.get("degraded", True), False)

    print("── 12. 滚动摘要清理（previous-summary 不进块提示）──")
    stamp = (SUMMARY_TIMESTAMP_PREFIX + " 本摘要生成于 2026-10-12 00:00:00，仅总结此前被折叠的对话历史；"
             "其后压缩保留的最近消息可能有更新进展，与本摘要矛盾时以保留消息原文为准。\n\n")
    body = "正文：完成了 A 与 B。\n"
    dropped = "[更早历史部分超出摘要窗口未纳入：约 1234 token]\n"
    hint = ("\n\n---\n> 本压缩块含被折叠的早期消息原文，"
            "如摘要缺关键细节：先用 restoreCompactedRange 的 preview 模式检索定位，"
            "确认需要完整原文再以 action=restore 恢复整块；不要为复核上下文而无谓恢复。")
    full = stamp + body + dropped + hint
    cleaned = clean_summary(full)
    rep.check("previous-summary 不含块提示", "本压缩块" not in cleaned, True)
    rep.check("previous-summary 保留丢弃注记", "1234 token" in cleaned, True)
    rep.check("previous-summary 保留正文", "完成了 A 与 B" in cleaned, True)
    rep.check("头部时点标注已剥", cleaned.startswith(SUMMARY_TIMESTAMP_PREFIX), False)
    # 正文若逐字复读含锚点（模型复读旧尾注是真实行为）：substringBeforeLast 取最后一次出现，
    # 只剥真正的尾部提示，正文里的复读文本保留（无法也不应区分）。
    full2 = stamp + body + hint + dropped + hint
    cleaned2 = clean_summary(full2)
    rep.check("复读场景真正文保留", "完成了 A 与 B" in cleaned2, True)
    rep.check("复读场景丢弃注记保留", "1234 token" in cleaned2, True)

    print("── 13. 拆分不劈开 marker/summary 对 ──")
    msgs = [
        Entity("m1", ROLE_USER, "早期消息", 1000),
        Entity("m2", ROLE_ASSISTANT, "带工具调用", 2000, tool_calls=[("tc0", "readFile")]),
        Entity("m3", ROLE_TOOL, "工具结果", 3000, tool_call_id="tc0"),
        Entity("m4", ROLE_USER, MARKER_TEXT, 4000),
        Entity("m5", ROLE_ASSISTANT, "摘要内容", 5000),
        Entity("m6", ROLE_ASSISTANT, "最近消息", 6000, tool_calls=[("tc1", "bash")]),
        Entity("m7", ROLE_TOOL, "最近工具结果", 7000, tool_call_id="tc1"),
    ]
    # 原始拆分落在 marker(m4) 与 summary(m5) 之间 → 调整后 summary 也进 head（split 前移一位）。
    rep.check("劈开位被修正为不劈开", adjust_split_index(msgs, 4), 5)
    # 边界：pair 落在可见流最末尾 (size-2, size-1)、split 指向 size-1 时，前移会清空 tail
    # （代价更大），宁留劈开也不折走最近消息。
    msgs_edge = msgs + [
        Entity("m8", ROLE_USER, MARKER_TEXT, 8000),
        Entity("m9", ROLE_ASSISTANT, "末尾摘要", 9000),
    ]
    rep.check("末尾对不强行前移（宁留劈开不清空）", adjust_split_index(msgs_edge, 8), 8)

    print("── 14. 锚点源级对账（防落库文案与剥除锚点漂移）──")
    # 注意：本组正则锚在局部 val blockHintAnchor 形态上；若源码改为 const val 提常量，
    # 正则将不再匹配 → 本组必红（方向安全），届时同步改正则，勿误读为「对账发现漂移」。
    kt_path = os.path.join(ROOT, "app", "src", "main", "java", "com", "aicode",
                           "feature", "agent", "domain", "workflow", "ContextCompactor.kt")
    am_path = os.path.join(ROOT, "app", "src", "main", "java", "com", "aicode",
                           "feature", "agent", "domain", "model", "AgentMessage.kt")
    try:
        with open(kt_path, encoding="utf-8") as f:
            kt = f.read()
        with open(am_path, encoding="utf-8") as f:
            am = f.read()
        m_anchor = re.search(r'blockHintAnchor = "([^"]*)"', kt)
        m_store = re.search(r'content = stampedSummary \+ droppedNotice \+ "([^"]*)"', kt)
        rep.check("锚点与落库文案同源（锚点是落库字面量前缀）",
                  bool(m_anchor) and bool(m_store) and m_store.group(1).startswith(m_anchor.group(1)),
                  True)
        # 脚本建模常量与源码锚点必须一致：源码 raw 字面量需经转义还原（\n 两字符 → 换行）。
        rep.check("脚本建模锚点与源码一致",
                  bool(m_anchor) and m_anchor.group(1).replace('\\n', "\n") == HINT_ANCHOR,
                  True)
        # 其余三个手抄常量（AgentMessage.kt:61/62/65）同样纳入源级对账，防漂移不红。
        m_marker = re.search(r'const val CONTEXT_COMPACTION_MARKER = "([^"]*)"', am)
        m_legacy = re.search(r'const val CONTEXT_SUMMARY_LEGACY_PREFIX = "([^"]*)"', am)
        m_ts = re.search(r'const val CONTEXT_SUMMARY_TIMESTAMP_PREFIX = "([^"]*)"', am)
        rep.check("MARKER_TEXT 与源码一致",
                  bool(m_marker) and m_marker.group(1) == MARKER_TEXT, True)
        rep.check("SUMMARY_LEGACY_PREFIX 与源码一致",
                  bool(m_legacy) and m_legacy.group(1) == SUMMARY_LEGACY_PREFIX, True)
        rep.check("SUMMARY_TIMESTAMP_PREFIX 与源码一致",
                  bool(m_ts) and m_ts.group(1) == SUMMARY_TIMESTAMP_PREFIX, True)
        rep.check("cleanSummary 使用取末次剥除", "substringBeforeLast(blockHintAnchor)" in kt, True)
    except OSError:
        rep.check("源码可读（锚点对账前置）", False, True)


# ══════════════════════════════════════════════════════════════════════
# 十五、C1 固定开销 Δ 源级对账（防「预算不扣 Δ、触发用真实 usage」口径再次分裂）
# ══════════════════════════════════════════════════════════════════════
def check_overhead_in_c1(rep):
    print("── 15. C1 固定开销 Δ 对账 ──")
    pol_path = os.path.join(ROOT, "app", "src", "main", "java", "com", "aicode",
                            "feature", "settings", "domain", "model", "ModelContextPolicy.kt")
    comp_path = os.path.join(ROOT, "app", "src", "main", "java", "com", "aicode",
                             "feature", "agent", "domain", "workflow", "ContextCompactor.kt")
    try:
        with open(pol_path, encoding="utf-8") as f:
            pol = f.read()
        with open(comp_path, encoding="utf-8") as f:
            comp = f.read()
    except OSError:
        rep.check("源文件可读（Δ 对账前置）", False, True)
        return

    # 源码 C1 上界必须含 overhead 项
    m_upper = re.search(r"val upper = \(triggerThreshold - SUMMARY_TOKENS_ESTIMATE - overhead - 1\)", pol)
    rep.check("源码 C1 上界扣除 overhead", bool(m_upper), True)
    rep.check("tailBudgetPlan 暴露 overheadTokens 参数", "overheadTokens: Int = 0" in pol, True)
    # 调用点必须把真实 usage 与本地估算之差作为 Δ 传入
    rep.check("调用点计算 Δ（真实 usage − 本地估算）",
              "lastInputTokens - estimatedTokens" in comp and "usageOverhead" in comp, True)
    rep.check("调用点把 Δ 传入 tailBudgetPlan",
              re.search(r"tailBudgetPlan\(\s*triggerThreshold,\s*contextLimit,\s*summaryWindowTokensForSplit,\s*usageOverhead\s*\)", comp) is not None,
              True)

    # 行为对账：源码公式（镜像）在 Δ>0 时必须压低 tail，且真实不变量成立
    trigger = 115_200
    base = tail_budget(trigger, 128_000, 128_000, 0)
    with_overhead = tail_budget(trigger, 128_000, 128_000, 40_000)
    rep.check("Δ=40k 压低 tail（C1 收紧）", with_overhead <= base, True)
    # 关键：C2 主导 tail 的配置下，不扣 Δ 会「压完立即再触发」
    sw_small = 64_000
    tail_no = tail_budget(trigger, 128_000, sw_small, 0)
    tail_yes = tail_budget(trigger, 128_000, sw_small, 40_000)
    rep.check("摘要窗口偏小时：不扣 Δ → 压缩后 ≥ 阈值（复现缺陷）",
              tail_no + SUMMARY_TOKENS_ESTIMATE + 40_000 >= trigger, True)
    rep.check("摘要窗口偏小时：扣 Δ → 压缩后 < 阈值（修复生效）",
              tail_yes + SUMMARY_TOKENS_ESTIMATE + 40_000 < trigger, True)
    # 向后兼容：Δ=0 与旧公式（不扣 Δ）一致
    rep.check("Δ=0 时退化为旧行为（向后兼容）",
              tail_budget(trigger, 128_000, 128_000, 0) == tail_budget_plan(trigger, 128_000, 128_000, 0)[0], True)


# ══════════════════════════════════════════════════════════════════════
# 六、失败注入（变异测试）——验证正向场景真有判别力
# ══════════════════════════════════════════════════════════════════════
def mut_split_segment_only_cap(msgs, budget, restored_ids):
    """事故 C：上界只验段自身（v1 修复的洞）——段后积累免预算膨胀。"""
    total, split = 0, len(msgs)
    anchor = None
    for i in range(len(msgs)):
        if i > 0 and is_restored(msgs[i], restored_ids) and not is_restored(msgs[i - 1], restored_ids):
            anchor = i
            break
    protected = anchor
    if anchor is not None:
        seg_end = len(msgs)
        for i in range(anchor, len(msgs)):
            if not is_restored(msgs[i], restored_ids):
                seg_end = i
                break
        if sum(m.tok() for m in msgs[anchor:seg_end]) > budget:
            protected = None
    for idx in range(len(msgs) - 1, -1, -1):
        in_r = protected is not None and idx >= protected
        nxt = msgs[idx].tok()
        if (not in_r) and total + nxt > budget and split < len(msgs):
            break
        total += nxt
        split = idx
        if protected is not None and idx == protected:
            break
    return split


def mut_split_no_index_guard(msgs, budget, restored_ids):
    """事故 A：恢复段无条件保护（不看段首是否 0）。"""
    total, split = 0, len(msgs)
    anchor = next((i for i in range(len(msgs)) if is_restored(msgs[i], restored_ids)), None)
    protected = anchor
    for idx in range(len(msgs) - 1, -1, -1):
        in_r = protected is not None and idx >= protected
        nxt = msgs[idx].tok()
        if (not in_r) and total + nxt > budget and split < len(msgs):
            break
        total += nxt
        split = idx
        if protected is not None and idx == protected:
            break
    return split


def mut_split_no_budget_cap(msgs, budget, restored_ids):
    """事故 B：恢复段超预算仍保护（去掉预算上界）。"""
    total, split = 0, len(msgs)
    anchor = None
    for i in range(len(msgs)):
        if i > 0 and is_restored(msgs[i], restored_ids) and not is_restored(msgs[i - 1], restored_ids):
            anchor = i
            break
    protected = anchor
    for idx in range(len(msgs) - 1, -1, -1):
        in_r = protected is not None and idx >= protected
        nxt = msgs[idx].tok()
        if (not in_r) and total + nxt > budget and split < len(msgs):
            break
        total += nxt
        split = idx
        if protected is not None and idx == protected:
            break
    return split


def mut_threshold_up(base_percent, fast_growth):
    """阈值自适应方向写反（下调变上调）。"""
    if not fast_growth:
        return base_percent
    return min(100, max(base_percent, ADAPTIVE_FLOOR) + ADAPTIVE_STEP)


def mut_guard_old_budget(self, trigger, est, msgs, budget):
    """守卫口径分裂（修复过程中新引入的缺陷形态）：守卫用旧预算 preserveRecentTokens，
    选择器用新预算 tailBudget → 「全装得下＝无需压缩」被误判成「划分失败」→ 强制折叠全部历史。"""
    old_budget = preserve_recent_tokens(trigger, self.context_limit)
    if est > old_budget and len(msgs) > 1:
        return len(msgs) - 1, True
    return None, False


def mut_budget_fixed(usable_tokens, context_limit=DEFAULT_CONTEXT_TOKENS):
    """tail 预算写死，不随窗口缩放。"""
    return max(MIN_PRESERVE_RECENT_TOKENS,
               min(usable_tokens // 4, MAX_PRESERVE_RECENT_TOKENS))


def mut_budget_ignore_summary_window(trigger, context_limit=DEFAULT_CONTEXT_TOKENS,
                                     summary_window_tokens=DEFAULT_CONTEXT_TOKENS):
    """旧实现：tail 预算只看窗口比例，无视摘要窗口下限。

    这正是 128k/90% 静默丢历史的旧行为——head 超出摘要窗口仍照压不误。
    """
    return preserve_recent_tokens(trigger, context_limit)


def mut_restore_reversed(self, block_id):
    """解压方向写反（原文不回场）。"""
    for e in self.entities:
        if e.block_id != block_id:
            continue
        e.is_compacted = not (e.is_context_summary or e.is_compaction_marker)


def mut_split_adjust_breaks_pair(msgs, initial_split):
    """旧实现：marker/summary 对可被劈开（只有 tool 回溯，无前移约束）。"""
    split = max(0, min(initial_split, len(msgs)))
    while split > 0 and msgs[split].role == ROLE_TOOL:
        split -= 1
    return split


def mut_clean_summary_keep_hint(text):
    """旧实现：只剥头部标注，尾部块提示原样进 previous-summary。"""
    if text.startswith(SUMMARY_LEGACY_PREFIX):
        text = text[len(SUMMARY_LEGACY_PREFIX):]
    if text.startswith(SUMMARY_TIMESTAMP_PREFIX):
        nl = text.find("\n")
        text = text[nl + 1:] if nl >= 0 else ""
    return text.lstrip()


def run_with_patch(patch):
    """在 monkeypatch 下跑全部正向场景，返回失败条数（静默）。
    patch 的键可以是模块级函数名，也可以是 "Session.restore_block" 这样的方法名。"""
    mod = sys.modules[__name__]
    module_names = [k for k in patch if "." not in k]
    method_names = [k for k in patch if "." in k]
    originals = {k: getattr(mod, k) for k in module_names}
    method_originals = {}
    for k in method_names:
        cls_name, meth = k.split(".", 1)
        cls = getattr(mod, cls_name)
        method_originals[k] = getattr(cls, meth)
        setattr(cls, meth, patch[k])
    for k in module_names:
        setattr(mod, k, patch[k])
    buf = io.StringIO()
    try:
        rep = Report()
        with contextlib.redirect_stdout(buf):
            suite_forward(rep)
        return len(rep.failures)
    finally:
        for k, v in originals.items():
            setattr(mod, k, v)
        for k, v in method_originals.items():
            cls_name, meth = k.split(".", 1)
            setattr(getattr(mod, cls_name), meth, v)


def suite_mutation(rep):
    print("── 失败注入：正向场景是否真有判别力 ──")
    injections = [
        ("事故A（恢复段无条件保护）", {"compute_tail_split": mut_split_no_index_guard}),
        ("事故B（超预算仍保护）", {"compute_tail_split": mut_split_no_budget_cap}),
        ("事故C（上界只验段自身）", {"compute_tail_split": mut_split_segment_only_cap}),
        ("阈值自适应方向写反", {"effective_percent": mut_threshold_up}),
        ("tail 预算写死", {"preserve_recent_tokens": mut_budget_fixed}),
        ("tail 预算无视摘要窗口（128k 丢历史旧行为）",
         {"tail_budget": mut_budget_ignore_summary_window}),
        ("拆分守卫口径分裂（把『无需压缩』误判成『折叠全部』）",
         {"Session._guard_budget": mut_guard_old_budget}),
        ("解压方向写反", {"Session.restore_block": mut_restore_reversed}),
        ("marker/summary 对可被劈开（缺前移约束）",
         {"adjust_split_index": mut_split_adjust_breaks_pair}),
        ("摘要清理未剥尾部块提示",
         {"clean_summary": mut_clean_summary_keep_hint}),
    ]
    for name, patch in injections:
        caught = run_with_patch(dict(patch))
        ok = caught > 0
        print("  %s 注入「%s」→ 检出 %d 处失败" % ("OK  " if ok else "FAIL", name, caught))
        if not ok:
            rep.failures.append("失败注入「%s」未被检出——该场景无判别力（假绿灯）" % name)
    caught_base = run_with_patch({})
    rep.check("基线（不注入）应全绿", caught_base, 0)


# ══════════════════════════════════════════════════════════════════════
def main():
    print("=" * 74)
    print("上下文压缩全流程回归")
    print("=" * 74)
    rep = Report()
    suite_forward(rep)
    # C1 固定开销 Δ 的源级对账只在基线跑（变异运行会把 tail_budget 换成 3 参突变体，签名不符）。
    check_overhead_in_c1(rep)
    print()
    suite_mutation(rep)
    print()
    print("=" * 74)
    if rep.failures:
        print("[FAIL] 上下文压缩全流程回归未通过：")
        for f in rep.failures:
            print("  - %s" % f)
        return 1
    print("[OK] 上下文压缩全流程回归通过")
    print("  - 正向场景 15 组 + 失败注入 10 项均已核对")
    print("  - 建模对账：CompactionTailSelector / CompactionThreshold / ModelContextPolicy /")
    print("    AgentMessageDao 压缩块 SQL / buildHistory 筛选与配对 / SessionHistoryPager /")
    print("    CompactionSplitAdjuster / cleanSummary")
    return 0


if __name__ == "__main__":
    sys.exit(main())
