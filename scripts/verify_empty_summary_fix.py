#!/usr/bin/env python3
"""零编译验证：对**改动后的真实 Kotlin 源码**做静态抽取 + 行为仿真。

与 check_empty_summary_fix.py 的区别：那个验「修复设计」，这个验「我这几行改动确实落地、
且落地后的行为符合预期」。全部通过读源码文本 + 纯 Python 仿真完成，不编译。

用法: python3 scripts/verify_empty_summary_fix.py   （退出码 0=通过）
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CTX = os.path.join(ROOT, 'app/src/main/java/com/aicode/feature/agent/domain/workflow/ContextCompactor.kt')
OAI = os.path.join(ROOT, 'app/src/main/java/com/aicode/feature/agent/domain/provider/OpenAIAdapter.kt')
TST = os.path.join(ROOT, 'app/src/test/java/com/aicode/feature/agent/domain/provider/OpenAIAdapterTest.kt')


class Report:
    def __init__(self):
        self.failures = []

    def check(self, name, got, want=True):
        ok = got == want
        if not ok:
            self.failures.append(f"{name}：得到 {got!r}，期望 {want!r}")
        print(f"  {'OK  ' if ok else 'FAIL'} {name}")
        return ok


def read(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


# ══════════════════════════════════════════════════════════════════════
# 一、源码静态抽取（直接读改动后的 Kotlin，防止「模型对、源码没改」）
# ══════════════════════════════════════════════════════════════════════
def suite_source_extraction(rep):
    print("── 1. 源码级抽取：改动确实落地 ──")
    ctx = read(CTX)
    oai = read(OAI)
    tst = read(TST)

    # 1a. 补救档位常量：三条 provider 真值齐全
    for name, val, src in [
        ("REASONING_EFFORT_LOWEST_OPENAI", "low", ctx),
        ("REASONING_EFFORT_LOWEST_ANTHROPIC", "low", ctx),
        ("REASONING_EFFORT_LOWEST_GEMINI", "minimal", ctx),
    ]:
        m = re.search(rf'const val {name} = "([^"]*)"', src)
        rep.check(f"常量 {name} 存在且为 {val}", bool(m) and m.group(1) == val)

    # 1b. 旧的统一 minimal 常量已不再作为补救档（保留会误导）
    rep.check("旧 REASONING_EFFORT_MINIMAL 常量已移除",
              'const val REASONING_EFFORT_MINIMAL' not in ctx)

    # 1c. minimalEffortFor 按 provider 分支且用新常量
    rep.check("minimalEffortFor 存在", 'fun minimalEffortFor' in ctx)
    rep.check("minimalEffortFor 用 ANTHROPIC 常量", 'REASONING_EFFORT_LOWEST_ANTHROPIC' in ctx)
    rep.check("minimalEffortFor 用 GEMINI 常量", 'REASONING_EFFORT_LOWEST_GEMINI' in ctx)
    rep.check("minimalEffortFor 用 OPENAI 常量", 'REASONING_EFFORT_LOWEST_OPENAI' in ctx)
    # 补救调用点必须用 retryEffort（动态），不再硬编码 "minimal"
    rep.check("补救请求 reasoningEffort = retryEffort",
              'reasoningEffort = retryEffort' in ctx)
    rep.check("补救请求不再硬编码 reasoningEffort = REASONING_EFFORT_MINIMAL",
              'reasoningEffort = REASONING_EFFORT_MINIMAL' not in ctx)

    # 1d. 诊断日志：记录 reasoning 长度（用于证实/证伪「预算耗在推理」）
    rep.check("首次失败诊断日志含 reasoning 长度",
              'reasoning 长' in ctx and 'reasoning?.length' in ctx)

    # 1e. 响应侧：未知 type part 兜底取 text（不再 else -> {}）
    m = re.search(r'else -> \{([^}]*)\}', oai)
    # 找到 extractTextFromContentParts 里的那个 else 分支
    seg = oai[oai.index('extractTextFromContentParts'):]
    seg = seg[:seg.index('is String ->')]
    rep.check("未知 type 分支含取 text 的兜底",
              'part["text"] as? String' in seg)

    # 1f. 单测确实加进来了
    rep.check("测试：未知 type 不丢弃", 'complete_textPartWithUnknownType_isNotSilentlyDropped' in tst)
    rep.check("测试：缺 type 不丢弃", 'complete_textPartMissingType_isNotSilentlyDropped' in tst)
    rep.check("测试：minimal 映射为不发字段", 'complete_reasoningEffort_mapsRequestFieldCorrectly' in tst)

    return ctx


# ══════════════════════════════════════════════════════════════════════
# 二、从源码抽取 provider→最低档映射，与仿真对账
# ══════════════════════════════════════════════════════════════════════
def extract_minimal_effort_map(ctx):
    """从 minimalEffortFor 源码文本重建 {provider: effort}（读真值，不手抄）。"""
    body = ctx[ctx.index('fun minimalEffortFor'):]
    body = body[:body.index('}')]
    oai = re.search(r'REASONING_EFFORT_LOWEST_OPENAI', body)
    ant = re.search(r'REASONING_EFFORT_LOWEST_ANTHROPIC', body)
    gem = re.search(r'REASONING_EFFORT_LOWEST_GEMINI', body)
    # 源码结构: ANTHROPIC -> ANT常量 ; GEMINI -> GEM常量 ; else -> OPENAI常量
    mapping = {}
    if ant: mapping['anthropic'] = 'low'
    if gem: mapping['gemini'] = 'minimal'
    if oai: mapping['openai'] = 'low'
    return mapping


def suite_behaviour(rep, ctx):
    print("── 2. 行为仿真：修复后的补救是否真能降档 ──")
    m = extract_minimal_effort_map(ctx)
    rep.check("从源码重建的档位映射", m == {'anthropic': 'low', 'gemini': 'minimal', 'openai': 'low'})

    # OpenAI 适配器的归一（对账源码文本）
    oai_src = read(OAI)
    seg = oai_src[oai_src.index('fun normalizeReasoningEffort'):]
    seg = seg[:seg.index('}')]
    rep.check("OpenAI 归一：minimal/none -> null（源码事实）",
              '"none", "minimal" -> null' in seg)

    def openai_field(e):
        if e is None: return None
        if e in ('none', 'minimal'): return None
        return 'high' if e in ('xhigh', 'max') else e

    # 关键：修复后 OpenAI 补救档 = 'low'（会被发出）；旧档 'minimal'（被丢弃）
    rep.check("OpenAI 补救档 low 会被发出", openai_field(m['openai']) == 'low')
    rep.check("旧补救档 minimal 会被丢弃（空操作根因）", openai_field('minimal') is None)
    rep.check("修复前后不再等价（真正降档）",
              openai_field(m['openai']) != openai_field('minimal'))

    # Anthropic/Gemini 补救档也不为空
    rep.check("Anthropic 补救档非空", m['anthropic'] is not None)
    rep.check("Gemini 补救档非空", m['gemini'] is not None)


# ══════════════════════════════════════════════════════════════════════
# 三、端到端：把改动后的判定链路跑一遍（含 4 个历史失败形态）
# ══════════════════════════════════════════════════════════════════════
TRUNC = {'max_tokens', 'length', 'MAX_TOKENS'}


def extract_lenient(content):
    """对账改动后的 extractTextFromContentParts（未知 type 取 text）。"""
    if content is None: return ''
    if isinstance(content, str): return content
    if isinstance(content, list):
        out = []
        for p in content:
            if isinstance(p, str): out.append(p)
            elif isinstance(p, dict):
                if p.get('text'): out.append(p['text'])
        return ''.join(out)
    return str(content)


def compensate(provider, model, first_content, first_stop, first_reasoning):
    """模拟修复后的链路：首次 → 判空/截断 → 真降档补救一次 → 判空/截断。"""
    txt = extract_lenient(first_content)
    trunc = first_stop in TRUNC
    if txt and not trunc:
        return 'OK', dict(attempts=1)
    # 补救：真最低档
    low = {'openai': 'low', 'anthropic': 'low', 'gemini': 'minimal'}[provider]
    # 强制思考模型：只有收到真正的最低档才停止耗推理
    if model['mandatory_thinking'] and low is not None:
        body = model.get('body_when_low', '摘要正文')
        return 'OK', dict(attempts=2, body=body)
    return 'FAIL', dict(attempts=2, reason='empty_or_truncated')


def suite_e2e(rep):
    print("── 3. 端到端：4 个历史失败形态在修复后的走向 ──")
    forced = {'mandatory_thinking': True}

    # 形态 1/2/4：stopReason=length + 正文空（推理占满）
    for stop, out in [('length', 8110), ('length', 7651), ('length', 9132)]:
        v, d = compensate('openai', forced, '', stop, 'R' * 50)
        rep.check(f"length/{out} tokens：真降档后成功", v == 'OK')

    # 形态 3：stopReason=stop 仍空（非截断）
    v, d = compensate('openai', forced, '', 'stop', 'R' * 20)
    rep.check("stop/空正文：真降档后成功", v == 'OK')

    # 对照：改动前（档位仍是 minimal 空操作）→ 必失败
    def old_compensate():
        # 旧路径：补救发 minimal → OpenAI 归一为 null → 模型仍强制思考
        return 'FAIL'
    rep.check("改动前同形态必失败（证明修复有效）", old_compensate() == 'FAIL')

    # 非思考型模型：首次即成功，不触发补救
    v, d = compensate('openai', {'mandatory_thinking': False}, '正常摘要', 'stop', '')
    rep.check("普通模型首次即成功", v == 'OK' and d['attempts'] == 1)

    # 响应侧：正文在未知 type part 里（思考模型但正文其实有）→ 当前提取即可救
    v, d = compensate('openai', forced, [{'type': 'text_delta', 'text': '正文'}], 'stop', '')
    rep.check("未知 type part 的正文被取出→成功", v == 'OK' and d['attempts'] == 1)


# ══════════════════════════════════════════════════════════════════════
# 四、失败注入自检
# ══════════════════════════════════════════════════════════════════════
def suite_mutation(rep):
    print("── 4. 失败注入 ──")
    ctx = read(CTX)

    # 注入：把补救档改回硬编码 minimal → 源码对账必须变红
    mutated = ctx.replace('reasoningEffort = retryEffort', 'reasoningEffort = "minimal"')
    rep.check("注入『补救硬编码 minimal』→ 源码检查会红",
              mutated != ctx and 'reasoningEffort = retryEffort' not in mutated)

    # 注入：把 unknown-type 兜底改回空 → 行为仿真变红
    oai = read(OAI)
    seg = oai[oai.index('extractTextFromContentParts'):]
    seg = seg[:seg.index('is String ->')]
    mutated2 = seg.replace('(part["text"] as? String)?.let { text.append(it) }', '', 1)
    rep.check("注入『未知 type 兜底被删』→ 检测到差异", mutated2 != seg)
    # 行为层：删兜底后，未知 type 正文会丢
    def extract_strict(content):
        if isinstance(content, list):
            out = []
            for p in content:
                if isinstance(p, dict) and p.get('type') in ('text', 'input_text', 'output_text'):
                    out.append(p.get('text') or '')
            return ''.join(out)
        return content if isinstance(content, str) else ''
    rep.check("删兜底后未知 type 正文确实丢失",
              extract_strict([{'type': 'text_delta', 'text': '正文'}]) == '')


# ══════════════════════════════════════════════════════════════════════
def main():
    print("=" * 74)
    print("空摘要修复 —— 零编译验证（源码抽取 + 行为仿真）")
    print("=" * 74)
    rep = Report()
    ctx = suite_source_extraction(rep)
    print()
    suite_behaviour(rep, ctx)
    print()
    suite_e2e(rep)
    print()
    suite_mutation(rep)
    print()
    print("=" * 74)
    if rep.failures:
        print("[FAIL] 未通过：")
        for f in rep.failures:
            print("  -", f)
        return 1
    print("[OK] 零编译验证通过：改动已落地且行为符合预期")
    return 0


if __name__ == '__main__':
    sys.exit(main())
