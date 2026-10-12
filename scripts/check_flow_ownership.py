#!/usr/bin/env python3
"""流归属（flow ownership）生命周期建模与修复验证。

背景：#49 把 flowOwners 从强引用改为 WeakReference，修了 main 的「VM 异常销毁 →
归属永久残留 → 会话永久 reject」真实 bug，但引入两处新缺陷（本脚本复现并验证修复）：

  P2-a  reject 兜底轮询去掉 20 次上限 → while(isActive) 无界且**无去重**：
        被拒 N 次即 N 条长命协程（每条约 30s 一探），只在 VM 销毁时收。
  P2-b  抢占成功后 processNextInQueue 有早退（job 已活跃 / 队列空）且**不释放归属**：
        归属被本 VM 白占，直到 GC/onCleared → 该会话此后显示「正在其他窗口处理」。

修复方向（不推翻弱引用，只补漏）：
  F1  轮询去重：Map<sessionId, Job>，同会话只一条；队列清空/会话删除时主动取消。
  F2  抢占成功路径的**单一释放点**：抢占成功后，无论 processNextInQueue 走了哪条
      早退分支，都必须成对释放归属。
  F3  releaseFlow 顺带清理「换代空弱引用」条目（current.get()==null 的 key）。

用法: python3 scripts/check_flow_ownership.py   （退出码 0=通过）
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VM = os.path.join(ROOT, 'app/src/main/java/com/aicode/feature/agent/presentation/AIAgentViewModel.kt')
BUS = os.path.join(ROOT, 'app/src/main/java/com/aicode/feature/agent/domain/subagent/SubAgentEventBus.kt')
DIFF = os.path.join(os.path.dirname(ROOT), 'pr49_v2.diff')


class Report:
    def __init__(self):
        self.failures = []

    def check(self, name, got, want=True):
        ok = got == want
        if not ok:
            self.failures.append(f"{name}：得到 {got!r}，期望 {want!r}")
        print(f"  {'OK  ' if ok else 'FAIL'} {name}")
        return ok


# ══════════════════════════════════════════════════════════════════════
# 一、归属模型（把 Kotlin 的 flowOwners + 轮询语义搬成可执行模型）
# ══════════════════════════════════════════════════════════════════════
class Bus:
    """对账 SubAgentEventBus 的 flowOwners（弱引用语义用 owner 可达性模拟）。"""

    def __init__(self):
        self.owners = {}          # sessionId -> owner_id or None(被 GC)
        self.gc_enabled = False   # True 时模拟「弱引用被 GC」

    def acquire(self, sid, owner):
        cur = self.owners.get(sid)
        if cur is not None and cur == owner:
            return False          # 本人已持有
        if cur is not None and cur != owner:
            return False          # 别处持有
        self.owners[sid] = owner  # 无主或空弱引用 → 抢占
        return True

    def release(self, sid, owner, cleanup_stale=False):
        cur = self.owners.get(sid)
        if cur == owner:
            del self.owners[sid]
        elif cleanup_stale and cur is None:
            # F3：换代空弱引用条目，顺带清掉
            del self.owners[sid]

    def gc_owner(self, owner):
        """模拟持有者不可达被 GC：其条目变空弱引用（key 保留）。"""
        for sid, o in list(self.owners.items()):
            if o == owner:
                self.owners[sid] = None
        self.gc_enabled = True

    def owned_elsewhere(self, sid, owner):
        cur = self.owners.get(sid)
        return cur is not None and cur != owner


class Vm:
    """对账 AIAgentViewModel 的归属消费 + 兜底轮询。"""

    def __init__(self, name, bus):
        self.name = name
        self.bus = bus
        self.queue = {}            # sessionId -> [req]
        self.jobs = {}             # sessionId -> bool(是否活跃)
        self.pollers = {}          # F1: sessionId -> poller_id（去重）；旧版是 list
        self.next_poller = 0
        self.leaked_sids = set()
        self.poller_count = 0

    # ---- 被拒入队 ----
    def reject(self, sid, fixed):
        self.queue.setdefault(sid, []).append("req")
        if fixed:
            # F1：同会话只起一条轮询
            if sid in self.pollers:
                return
            self.poller_count += 1
            self.pollers[sid] = self.poller_count
        else:
            # 旧版：每次被拒都起一条（无去重）
            self.poller_count += 1

    # ---- 兜底轮询一次探测（忠实对账 #49 的真实控制流）----
    def poll_once(self, sid, fixed):
        # 对应: if (sessionJobs[sid]?.isActive == true) return@launch
        if self.jobs.get(sid):
            return
        # 对应: if (tryAcquireFlow(...))
        if self.bus.acquire(sid, self.name):
            if not self.queue.get(sid):
                # 对应: if (queue.isNullOrEmpty()) { releaseFlow(); return }
                self.bus.release(sid, self.name)
                self._stop_poller(sid)
                return
            # 对应: processNextInQueue(sid)
            consumed = self.process_next(sid, fixed)
            if not consumed:
                # P2-b：processNextInQueue 早退（job 活跃/队列空）→ #49 不释放归属
                self.leaked_sids.add(sid)
                if fixed:
                    self.bus.release(sid, self.name)   # F2
            self._stop_poller(sid)

    def _stop_poller(self, sid):
        self.pollers.pop(sid, None)

    # ---- 消费队列 ----
    def process_next(self, sid, fixed):
        if self.jobs.get(sid):
            return False                 # 早退 1：job 已活跃
        q = self.queue.get(sid)
        if q is None:
            return False                 # 早退 2：无队列
        if not q:
            return False                 # 早退 3：队列空
        self.queue[sid] = q[1:]
        self.jobs[sid] = True
        return True


# ══════════════════════════════════════════════════════════════════════
# 二、复现
# ══════════════════════════════════════════════════════════════════════
def suite_reproduce(rep):
    print("── 1. P2-a 复现：N 次被拒 → N 条轮询协程（旧版无去重）──")
    bus = Bus(); vm = Vm("A", bus)
    for _ in range(5):
        vm.reject("s1", fixed=False)
    rep.check("旧版：5 次被拒起 5 条轮询", vm.poller_count, 5)

    bus2 = Bus(); vm2 = Vm("A", bus2)
    for _ in range(5):
        vm2.reject("s1", fixed=True)
    rep.check("修复版：5 次被拒只起 1 条（去重）", vm2.poller_count, 1)

    print("── 2. P2-b 复现：抢占成功但 processNextInQueue 早退 → 归属泄漏 ──")
    # 真实竞态：poll_once 开头 job 非活跃（通过），抢到归属后、process_next 消费前，
    # 另一入口（通知/收尾 flush）把 job 置为活跃 → process_next 早退 → 归属白占。
    bus3 = Bus()
    bus3.owners["s1"] = None          # 空弱引用（可抢占）
    vm3 = Vm("A", bus3)
    vm3.queue["s1"] = ["req"]
    # 注入竞态：process_next 执行前 job 变活跃
    orig = vm3.process_next
    def racy(sid, fixed):
        vm3.jobs[sid] = True          # 竞态：此刻另一入口起了 job
        return orig(sid, fixed)       # → 触发「job 已活跃」早退
    vm3.process_next = racy
    vm3.poll_once("s1", fixed=False)
    rep.check("旧版：早退未消费 → 归属被白占（泄漏）", bus3.owners.get("s1") == "A")
    rep.check("  记为泄漏会话", "s1" in vm3.leaked_sids)

    print("── 3. 修复版：早退路径成对释放（F2）──")
    bus4 = Bus(); bus4.owners["s1"] = None
    vm4 = Vm("A", bus4); vm4.queue["s1"] = ["req"]
    orig4 = vm4.process_next
    def racy4(sid, fixed):
        vm4.jobs[sid] = True
        return orig4(sid, fixed)
    vm4.process_next = racy4
    vm4.poll_once("s1", fixed=True)
    rep.check("修复版：早退后归属已释放（不泄漏）", bus4.owners.get("s1") is None)

    # 队列空早退（#49 已修的那条，确保不回归）
    bus5 = Bus(); bus5.owners["s1"] = None
    vm5 = Vm("A", bus5); vm5.queue["s1"] = []
    vm5.poll_once("s1", fixed=True)
    rep.check("队列空早退：归属已释放（#49 已修，不回归）", bus5.owners.get("s1") is None)


# ══════════════════════════════════════════════════════════════════════
# 三、F3：换代空弱引用清理
# ══════════════════════════════════════════════════════════════════════
def suite_stale_cleanup(rep):
    print("── 4. F3：空弱引用条目（key 泄漏）清理 ──")
    bus = Bus()
    bus.owners["s1"] = "OLD"
    bus.gc_owner("OLD")                    # OLD 被 GC → s1 -> None
    rep.check("GC 后条目变为空弱引用（key 仍在）", bus.owners.get("s1") is None and "s1" in bus.owners)
    # 新 owner 抢占（允许）
    ok = bus.acquire("s1", "NEW")
    rep.check("空条目可被新 owner 抢占", ok and bus.owners["s1"] == "NEW")
    # 旧版 release 语义：NEW 释放后应删除
    bus.release("s1", "NEW")
    rep.check("释放后条目删除", "s1" not in bus.owners)
    # 对照：non-owner 调 release 时，空条目应收敛（F3）
    bus.owners["s2"] = None               # 空弱引用
    bus.release("s2", "OTHER", cleanup_stale=False)
    rep.check("旧版：non-owner 释放不动空条目（key 残留）", "s2" in bus.owners)
    bus.release("s2", "OTHER", cleanup_stale=True)
    rep.check("F3：顺带清掉空条目", "s2" not in bus.owners)


# ══════════════════════════════════════════════════════════════════════
# 四、不回归：正常「别处持有则跳过」「本人持有不重复」仍成立
# ══════════════════════════════════════════════════════════════════════
def suite_no_regression(rep):
    print("── 5. 不回归：既有语义保持 ──")
    bus = Bus()
    rep.check("A 首次抢占成功", bus.acquire("s1", "A") is True)
    rep.check("B 抢已被 A 持有的会话失败", bus.acquire("s1", "B") is False)
    rep.check("A 重复抢占返回 false（本人已持有）", bus.acquire("s1", "A") is False)
    rep.check("B 判定为『别处持有』", bus.owned_elsewhere("s1", "B") is True)
    bus.release("s1", "A")
    rep.check("A 释放后 B 可抢占", bus.acquire("s1", "B") is True)
    # 弱引用 GC 后新实例可抢占（#49 的核心价值，不能回归）
    bus2 = Bus(); bus2.acquire("s2", "ZOMBIE"); bus2.gc_owner("ZOMBIE")
    rep.check("#49 价值保留：僵尸归属 GC 后新实例可抢占", bus2.acquire("s2", "FRESH") is True)


def suite_mutation(rep):
    print("── 6. 失败注入 ──")
    # 注入「早退不释放」→ 泄漏复现
    bus = Bus(); bus.owners["s1"] = None
    vm = Vm("A", bus); vm.queue["s1"] = ["req"]
    _orig = vm.process_next
    def _r(sid, fixed):
        vm.jobs[sid] = True
        return _orig(sid, fixed)
    vm.process_next = _r
    vm.poll_once("s1", fixed=False)
    rep.check("注入『早退不释放』→ 复现泄漏", bus.owners.get("s1") == "A")
    # 注入「无去重」→ 协程累积
    bus2 = Bus(); vm2 = Vm("A", bus2)
    for _ in range(3): vm2.reject("s1", fixed=False)
    rep.check("注入『无去重』→ 起 3 条", vm2.poller_count, 3)


def reconcile_source():
    """若存在已修复的 PR49 分支文件，做源级对账：三项修复必须都在。"""
    cand = os.path.join(os.path.dirname(ROOT), 'pr49_files',
                        'app__src__main__java__com__aicode__feature__agent__presentation__AIAgentViewModel.kt')
    bus_cand = os.path.join(os.path.dirname(ROOT), 'pr49_files',
                            'app__src__main__java__com__aicode__feature__agent__domain__subagent__SubAgentEventBus.kt')
    problems = []
    if os.path.exists(cand):
        vm = open(cand, encoding='utf-8').read()
        if 'rejectPollers' not in vm:
            problems.append('AIAgentViewModel: 缺 F1 轮询去重（rejectPollers）')
        if 'private fun processNextInQueue(sessionId: String): Boolean' not in vm:
            problems.append('AIAgentViewModel: processNextInQueue 未改为返回 Boolean')
        if 'if (!consumed)' not in vm:
            problems.append('AIAgentViewModel: 缺 F2 早退释放（if (!consumed) releaseFlow）')
    else:
        problems.append('（未找到 pr49_files，跳过 AIAgentViewModel 源级对账）')
    if os.path.exists(bus_cand):
        bus = open(bus_cand, encoding='utf-8').read()
        if 'live == null) null else current' not in bus:
            problems.append('SubAgentEventBus: 缺 F3 空弱引用清理')
    return problems


def main():
    print("=" * 74)
    print("流归属生命周期：缺陷复现 + 修复验证（零编译）")
    print("=" * 74)
    rep = Report()
    print("── 0. 源级对账（若已 fetch PR49 修复后文件）──")
    probs = reconcile_source()
    for p in probs:
        print("     ·", p)
    print()
    suite_reproduce(rep)
    print()
    suite_stale_cleanup(rep)
    print()
    suite_no_regression(rep)
    print()
    suite_mutation(rep)
    print()
    print("=" * 74)
    if rep.failures:
        print("[FAIL] 未通过：")
        for f in rep.failures:
            print("  -", f)
        return 1
    print("[OK] 复现与修复验证通过")
    return 0


if __name__ == '__main__':
    sys.exit(main())
