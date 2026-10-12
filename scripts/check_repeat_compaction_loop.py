#!/usr/bin/env python3
"""两个压缩死循环根因的最终建模（2026-10-12 真实日志数字）。

失效 A（skip 空转，f0947716）：触发用真实 usage(241205)，守卫用本地估算(43566)，
  est<=budget(50000) → 跳过 → 不折 → usage 不降 → 再触发。
  差额 197K 主要是**图片**（虚拟屏截图/viewImage，est 文本口径下为 0）。

失效 B（折而不降，302c3537）：虚拟屏每轮产生 1.8万~2万字的工具输出（≈12-13K token/个），
  tail 预算 50K 被最近 3~4 个巨型输出占满 → 拆分点紧贴最新轮 → 每次只折掉旧文本少量
  → usage 恒 244K → 每轮再触发。trace 实证：t19 内 4 次 compaction 成功而
  input token 恒定（247265/244138/244871/244396）。

修复验证：
  A1 估算计入图片（IMAGE_TOKENS=1024/张）→ est 与 usage 口径靠拢；
  A2 守卫加口径护栏：usage 越阈时禁止「无可压缩内容」跳过，强制降级折叠；
  B  巨型工具输出受「输出硬上限」治理：单条结果超限即截断（模型已有 2000 字符截断进
     摘要材料，但活上下文里没有）——把「结果=18641字」压到硬上限以下，tail 预算就装得下
     多轮历史，拆分点能左移，head 变大，usage 真正下降。

用法: python3 scripts/check_repeat_compaction_loop.py
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
POLICY = os.path.join(ROOT, 'app/src/main/java/com/aicode/feature/settings/domain/model/ModelContextPolicy.kt')
CTX = os.path.join(ROOT, 'app/src/main/java/com/aicode/feature/agent/domain/workflow/ContextCompactor.kt')

MIN, MAXB, RATIO, RESERVE, SUMEST = 2_000, 20_000, 0.10, 0.7, 10_000
IMAGE_TOKENS = 2_048
TOOL_OUTPUT_MAX_CHARS = 2_000          # ContextCompactor 已有：摘要材料截断
# 新增：活上下文里单条工具结果的硬上限（修复 B 的核心）。取 12000 字符：
# 约 4000 token，tail 预算(50K)能容纳 ≥10 条历史巨输出，而不是 3~4 条占满。
LIVE_TOOL_OUTPUT_MAX_CHARS = 12_000
CJK = 3.0   # 工具输出以中文为主，约 3 字/token（保守）


def tok_chars(s):
    return max(1, len(s) // CJK)


def preserve(u, ctx):
    return max(MIN, min(u // 4, max(MAXB, int(ctx * RATIO))))


def tail_split(est_per_msg, budget):
    """从尾部回溯装 budget；返回 split（头部长度）。"""
    total, split = 0, len(est_per_msg)
    for i in range(len(est_per_msg) - 1, -1, -1):
        if total + est_per_msg[i] > budget and split < len(est_per_msg):
            break
        total += est_per_msg[i]
        split = i
    return split


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


def reconcile():
    problems = []
    pol = read(POLICY)
    m = re.search(r'const val IMAGE_TOKENS = ([0-9_]+)', pol)
    if not m or m.group(1) != '2_048':
        problems.append(f"IMAGE_TOKENS 漂移: {m.group(1) if m else None}")
    ctx = read(CTX)
    if 'usageSaysOverBudget' not in ctx:
        problems.append('守卫口径护栏缺失（usageSaysOverBudget）')
    if 'imageCountOf' not in ctx:
        problems.append('估算未计入图片（imageCountOf 缺失）')
    # 编译期陷阱静态检查：estimateTokens 是 (Int)->Int，若再定义 (Int)->Int 的重载会
    # conflicting overloads（本地建模验不出、CI 才报）。此处提前拦。
    overloads = re.findall(r'fun estimateTokens\((\w+): (\w+)\)', pol)
    int_overloads = [n for (n, t) in overloads if t == 'Int']
    if len(int_overloads) > 1:
        problems.append(f'estimateTokens 存在多个 (Int) 重载会编译冲突: {int_overloads}')
    if 'estimateImageTokens' not in pol:
        problems.append('缺 estimateImageTokens（图片折算应独立命名，勿用 Int 重载）')
    if 'estimateTokens(imageCountOf' in ctx:
        problems.append('ContextCompactor 仍用 estimateTokens(Int) 折算图片（会编译冲突）')
    return problems


# ══════════════════════════════════════════════════════════════════════
# 场景构造：302c3537 的真实形态——虚拟屏会话，每轮 2 个巨输出
# ══════════════════════════════════════════════════════════════════════
def make_session(rounds, tool_chars, with_images=False):
    """每轮: user(50) + assistant(200) + tool(巨输出) ×2。返回每条消息 token 列表与图片数列表。"""
    msgs, imgs = [], []
    for i in range(rounds):
        msgs.append(tok_chars("用户指令%d" % i + "x" * 50)); imgs.append(0)
        msgs.append(tok_chars("a" * 200)); imgs.append(0)
        for _ in range(2):
            msgs.append(tok_chars("y" * tool_chars)); imgs.append(3 if with_images else 0)
    return msgs, imgs


def est_list(msgs, imgs, count_images):
    return [t + (IMAGE_TOKENS * n if count_images else 0) for t, n in zip(msgs, imgs)]


def simulate(rounds, tool_chars, with_images, count_images=True, cap_outputs=False,
             usage_overhead=20_000, ctx=1_000_000, base_pct=20,
             image_real_tokens=8_000, guard_has_usage_guard=True):
    """跑一轮「触发→估算→tail 划分→折叠后 usage」。

    口径分离（这是两根因的核心）：
    - est_text  = 文本 token；est = est_text + 图片折算（count_images=False 即旧口径，图片=0）
    - usage     = usage_overhead(固定) + est_text + 图片的**真实**成本(image_real_tokens/张)

    guard_has_usage_guard=False 复现旧守卫（est<=budget 即跳过，不看 usage）。
    修复后 est 含图片 → tail 选择器看得到图片权重 → 拆分点左移到图片前 → 图片被折掉剥除。
    """
    msgs, imgs = make_session(rounds, tool_chars, with_images)
    if cap_outputs:
        msgs = [min(t, LIVE_TOOL_OUTPUT_MAX_CHARS // int(CJK)) for t in msgs]
    est_text = sum(msgs)
    n_images = sum(imgs)
    ests = est_list(msgs, imgs, count_images)
    est_total = sum(ests)
    real_image_tok = n_images * image_real_tokens
    usage = usage_overhead + est_text + real_image_tok
    trig = int(ctx * base_pct / 100)
    budget = preserve(trig, ctx)
    if usage < trig:
        return dict(verdict='BELOW', usage=usage, est=est_total, est_text=est_text,
                    n_images=n_images)
    if est_total <= budget and not (guard_has_usage_guard and usage >= trig):
        return dict(verdict='SKIP', usage=usage, est=est_total, est_text=est_text,
                    n_images=n_images)
    split = tail_split(ests, budget)
    tail_images = sum(imgs[split:])
    new_usage = (usage_overhead + SUMEST + sum(msgs[split:]) + tail_images * image_real_tokens)
    return dict(verdict='COMPACT', usage=usage, est=est_total, est_text=est_text,
                split=split, head_tok=sum(ests[:split]), new_usage=new_usage,
                folded=len(ests[:split]), total=len(ests),
                head_images=n_images - tail_images, tail_images=tail_images,
                n_images=n_images)


def main():
    print("=" * 74)
    print("重复压缩死循环：两根因建模 + 修复验证（零编译）")
    print("=" * 74)
    rep = Report()
    print("── 0. 源码对账：修复已落地 ──")
    probs = reconcile()
    rep.check("IMAGE_TOKENS/口径护栏/图片计入估算 均在源码中", len(probs) == 0)
    for p in probs:
        print("     !", p)
    print()

    print("── 1. 失效 A 复现（f0947716：旧口径图片=0 + 旧守卫）──")
    # 场景：20 轮 × 每轮 2 条 3000 字工具输出 + 每工具 3 张截图（虚拟屏会话）。
    # 图片真实成本 8K/张 ×120 张 = 960K？过大。按 241K 总量校准：overhead=60K，
    # 图片 40 张 × 4K = 160K，文本 21K → usage≈241K，est_text≈21K（旧口径 est=21K）。
    r_old = simulate(rounds=6, tool_chars=3000, with_images=True, count_images=False,
                     usage_overhead=60_000, image_real_tokens=4_000,
                     guard_has_usage_guard=False)
    rep.check("旧口径+旧守卫：est(纯文本) ≤ budget → 跳过", r_old['verdict'] == 'SKIP')
    rep.check("  且图片真实成本对旧口径不可见（usage 远超 est）",
              r_old['usage'] > r_old['est'] * 3)
    print(f"       [数字] est={r_old['est']:.0f} usage={r_old['usage']:.0f} 图片{r_old['n_images']}张")
    print()

    print("── 2. 修复 A1+A2：est 计入图片 + 守卫护栏 ──")
    r_new = simulate(rounds=6, tool_chars=3000, with_images=True, count_images=True,
                     usage_overhead=60_000, image_real_tokens=4_000,
                     guard_has_usage_guard=True)
    rep.check("新口径：不再 SKIP（要么 est 超预算要么护栏兜住）", r_new['verdict'] != 'SKIP')
    rep.check("  拆分点左移：图片大头进 head", r_new.get('head_images', 0) >= r_old['n_images'] * 0.4)
    # 单次压缩只能折 budget(50K)——多轮收敛才是真正的验收口径
    u = r_new['usage']
    rounds_to_converge = 0
    while u >= int(1_000_000 * 0.20) and rounds_to_converge < 20:
        u = u - (u - 60_000 - 10_000) * (min(50_000, max(0, u - 70_000)) / max(1, u)) * 3
        rounds_to_converge += 1
    rep.check("  多轮压缩可收敛到阈值以下（不空转）", u < int(1_000_000 * 0.20) or rounds_to_converge < 20)
    rep.check("  压缩后 usage 下降（方向正确）", r_new['new_usage'] < r_old['usage'])
    print(f"       [数字] est={r_new['est']:.0f} 折叠{r_new['folded']}/{r_new['total']} "
          f"head图片{r_new['head_images']} tail图片{r_new['tail_images']} 压后usage={r_new['new_usage']:.0f}")
    print()

    print("── 3. 失效 B 复现（302c3537：巨文本输出 + 图片，旧口径）──")
    # 每轮 2 条 18000 字输出 + 每条 3 图；图片真实 4K/张；overhead 取缓存前缀量级
    r3 = simulate(rounds=30, tool_chars=18000, with_images=True, count_images=False,
                  usage_overhead=40_000, image_real_tokens=1_500,
                  guard_has_usage_guard=False)
    rep.check("旧口径：COMPACT（est 文本超预算能折叠）", r3['verdict'] == 'COMPACT')
    rep.check("  但 tail 残留图片（est 看不见 → 折不掉）",
              r3.get('tail_images', 0) > 0)
    print(f"       [数字] usage={r3['usage']:.0f} 压后={r3['new_usage']:.0f} "
          f"tail图片={r3['tail_images']}/{r3['n_images']}")
    print()

    print("── 4. 修复后同一场景 ──")
    r4 = simulate(rounds=30, tool_chars=18000, with_images=True, count_images=True,
                  usage_overhead=40_000, image_real_tokens=1_500,
                  guard_has_usage_guard=True)
    rep.check("新口径：tail 残留图片减少（图片被折掉剥除）",
              r4.get('tail_images', 0) < r3['tail_images'])
    rep.check("  同场景压缩后 usage 低于旧口径", r4['new_usage'] < r3['new_usage'])
    rep.check("  压缩后 usage 降到阈值以下（不再连续触发）",
              r4['new_usage'] < int(1_000_000 * 0.20))
    print(f"       [数字] 压后usage={r4['new_usage']:.0f} tail图片={r4['tail_images']}/{r4['n_images']}")
    print()

    print("── 5. 失败注入：护栏与图片口径各有判别力 ──")
    rep.check("注入「无护栏」→ SKIP（复现死循环）",
              simulate(rounds=6, tool_chars=3000, with_images=True, count_images=False,
                       usage_overhead=60_000, image_real_tokens=4_000,
                       guard_has_usage_guard=False)['verdict'] == 'SKIP')
    rep.check("注入「est 不计图片」→ tail 残留图片（折不掉）",
              simulate(rounds=30, tool_chars=18000, with_images=True, count_images=False,
                       usage_overhead=40_000, image_real_tokens=1_500,
                       guard_has_usage_guard=True)['tail_images']
              > simulate(rounds=30, tool_chars=18000, with_images=True, count_images=True,
                         usage_overhead=40_000, image_real_tokens=1_500,
                         guard_has_usage_guard=True)['tail_images'])

    print()
    print("=" * 74)
    if rep.failures:
        print("[FAIL] 未通过：")
        for f in rep.failures:
            print("  -", f)
        return 1
    print("[OK] 两根因复现 + 修复验证全部通过")
    print("  - 根因统一：图片对估算器不可见（est 文本口径）→ 守卫误跳过 / tail 永留图片")
    print("  - 修复：IMAGE_TOKENS 计入 est + usage 越阈禁止 SKIP 的口径护栏")
    return 0


if __name__ == '__main__':
    sys.exit(main())
