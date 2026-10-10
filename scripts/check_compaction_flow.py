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
"""

import copy
import io
import os
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
ADAPTIVE_STEP = 15                    # CompactionThreshold.ADAPTIVE_STEP
ADAPTIVE_FLOOR = 60                   # CompactionThreshold.ADAPTIVE_FLOOR
GROWTH_RATIO = 0.15                   # CompactionThreshold.GROWTH_RATIO

MAX_CHARS_PER_MESSAGE = 1_200         # SessionHistoryPager.MAX_CHARS_PER_MESSAGE
MAX_TOTAL_CHARS = 24_000              # SessionHistoryPager.MAX_TOTAL_CHARS
MAX_LIMIT = 50                        # SessionHistoryPager.MAX_LIMIT
DEFAULT_LIMIT = 20                    # SessionHistoryPager.DEFAULT_LIMIT

ROLE_USER, ROLE_ASSISTANT, ROLE_TOOL = "user", "assistant", "tool"
TRUNCATE_SUFFIX = "…（本条已截断，全文更长）"   # SessionHistoryPager.truncate


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
        seg_end = len(msgs)
        for i in range(anchor, len(msgs)):
            if not is_restored(msgs[i], restored_ids):
                seg_end = i
                break
        if sum(m.tok() for m in msgs[anchor:seg_end]) > budget:
            protected = None                   # 修复 B：恢复段自身超预算则不保护
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


class Session:
    """对账 AgentMessageDao 的压缩块 SQL 语义 + 压缩流程。"""

    def __init__(self, context_limit=1_000_000, base_percent=15, fixed_overhead=87_000):
        self.entities = []
        self.seq = 0
        self.block_seq = 1
        self.context_limit = context_limit
        self.base_percent = base_percent
        self.fixed_overhead = fixed_overhead
        self.last_compacted_size = 0

    def add(self, role, content, tool_call_id=None, tool_calls=None):
        self.seq += 1
        e = Entity("m%d" % self.seq, role, content, self.seq * 1000,
                   tool_call_id=tool_call_id, tool_calls=tool_calls)
        self.entities.append(e)
        return e

    def auto_compact(self):
        msgs, restored = build_history(self.entities)
        est = sum(m.tok() for m in msgs)
        usage = est + self.fixed_overhead
        fast = (self.last_compacted_size > 0 and
                est - self.last_compacted_size > self.context_limit * GROWTH_RATIO)
        trig = trigger_tokens(self.context_limit, self.base_percent, fast)
        if usage < trig:
            return "BELOW", {"usage": usage, "trigger": trig, "est": est}

        budget = preserve_recent_tokens(trig, self.context_limit)
        split = compute_tail_split(msgs, budget, restored)
        if split <= 0:
            return "ABANDON", {"usage": usage, "trigger": trig, "budget": budget,
                               "est": est, "n": len(msgs)}

        head, tail = msgs[:split], msgs[split:]
        kept_tok = sum(m.tok() for m in tail)
        verdict = "OK" if kept_tok <= budget else "OVER_BUDGET"

        block_id = "B%d" % self.block_seq
        self.block_seq += 1
        for m in head:
            m.is_compacted = True
            m.block_id = block_id
        self._add_marker_summary(block_id, 10_000)      # 实测摘要约 10k 字符
        self.last_compacted_size = kept_tok + 10_000 // 3
        return verdict, {"usage": usage, "trigger": trig, "budget": budget,
                         "split": split, "folded": len(head), "kept": len(tail),
                         "kept_tok": kept_tok}

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
    rep.check("解压后再压缩不 ABANDON", r3 != "ABANDON", True)

    print("── 3. 恢复段锚在最前端（事故 A 形态）──")
    s = session_front_anchored()
    msgs, rs = build_history(s.entities)
    anchor = next((i for i in range(len(msgs)) if is_restored(msgs[i], rs)), None)
    rep.check("构造有效：恢复段起始下标为 0", anchor, 0)
    budget = preserve_recent_tokens(trigger_tokens(1_000_000, 15, False), 1_000_000)
    rep.check("段首为 0 时拆分点不为 0", compute_tail_split(msgs, budget, rs) > 0, True)
    r4, d4 = s.auto_compact()
    rep.check("段首为 0 时压缩不 ABANDON", r4 != "ABANDON", True)

    print("── 4. 恢复段自身超预算（事故 B 形态）──")
    s = session_oversized_restored()
    r5, d5 = s.auto_compact()
    rep.check("超预算恢复段不阻止压缩", r5, "OK")
    rep.check("tail 落在预算内", d5["kept_tok"] <= d5["budget"], True)

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


# ══════════════════════════════════════════════════════════════════════
# 六、失败注入（变异测试）——验证正向场景真有判别力
# ══════════════════════════════════════════════════════════════════════
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


def mut_budget_fixed(usable_tokens, context_limit=DEFAULT_CONTEXT_TOKENS):
    """tail 预算写死，不随窗口缩放。"""
    return max(MIN_PRESERVE_RECENT_TOKENS,
               min(usable_tokens // 4, MAX_PRESERVE_RECENT_TOKENS))


def mut_restore_reversed(self, block_id):
    """解压方向写反（原文不回场）。"""
    for e in self.entities:
        if e.block_id != block_id:
            continue
        e.is_compacted = not (e.is_context_summary or e.is_compaction_marker)


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
        ("阈值自适应方向写反", {"effective_percent": mut_threshold_up}),
        ("tail 预算写死", {"preserve_recent_tokens": mut_budget_fixed}),
        ("解压方向写反", {"Session.restore_block": mut_restore_reversed}),
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
    print("  - 正向场景 8 组 + 失败注入 5 项均已核对")
    print("  - 建模对账：CompactionTailSelector / CompactionThreshold / ModelContextPolicy /")
    print("    AgentMessageDao 压缩块 SQL / buildHistory 筛选与配对 / SessionHistoryPager")
    return 0


if __name__ == "__main__":
    sys.exit(main())
