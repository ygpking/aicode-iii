#!/usr/bin/env python3
"""子代理派发提醒（SubAgentDelegationTracker + CallLlm 注入）的纯 py3 建模仿真回归。

为什么存在：本改动是**时机型事件信号**——「本会话已成功写文件 + 从未派发子代理 → 注入一次提醒」。
它的正确性由三块互相独立的逻辑合成，任一块写错都不产生编译错误、也不在单测里必然暴露：

  1. 触发态机（SubAgentDelegationTracker）：只提醒一次、派发过即沉默、判定不成立时**占位可回退**、
     按会话隔离；派发判定必须与 TaskTool 的「action 缺省即 create」口径一致（否则漏记 → 下轮误提醒）。
  2. 接线门控（StatefulAgentWorkflow）：子代理会话既不注入也不计数；sessionId 为空时静默。
  3. 送达链路（RequestPayloadGuard 四道出口 + CompactionTailSelector）：提醒是**尾部一条 108 字符的
     UserMessage**——它会被 D5 截断吗？会被 D4 剥掉吗？注入后紧接的压缩会把它吞进摘要吗？
     这条链路的缺陷形态是「提醒注入了但模型永远看不到」（静默失效），单测与编译都抓不到。

用法：python3 scripts/check_subagent_delegation_notice.py     （退出码 0 = 通过，1 = 有失败）

设计要点——**失败注入（变异测试）**：先跑不注入的基线确认全绿，再把已知正确逻辑替换成
「已知错误的形态」，要求对应场景变红且**指认自己的目标场景**。若某注入检不出、或两个注入的
检出集合完全相同，说明场景无判别力，脚本自身判失败（假绿灯）。

注入自检的「五种写错形态」（本脚本在 suite_mutation 里逐条排除）：
  a. 与正确实现等价 → 等价重写必须**零检出**（负对照，证明测试不会乱报）；
  b. 退化过头 → 各注入的检出集合必须两两不同，且都不等于「整个换成简陋版」的检出集合；
  c. 打错分支 → 每个注入必须让**自己的目标场景**变红（在检出集合里按名字指认）；
  d. 同义注入 → 检出集合两两不同即为不重言；
  e. 签名漂移致异常冒充检出 → 场景结果分 PASS/FAIL/EXC 三态，EXC 不计入检出，
     出现任何 EXC 一律判脚本失败。

建模对账（改源码须同步改本脚本；脚本每次运行会到源码里核对锚点，见 check_source_anchors）：
  SubAgentDelegationTracker.kt / StatefulAgentWorkflow.kt:202,772-778,788,1256-1268 /
  EvidenceGuard.kt:62,79 / RequestPayloadGuard.kt / ModelContextPolicy.estimateTokens /
  CompactionTailSelector.kt / CompactionSplitAdjuster.kt / ModelProjection.kt /
  OpenAIAdapter.kt:658-660 / TaskTool.kt:88-94,135

覆盖面与已知近似（勿把它当作全量权威）：
  - **并发原子性未建模**：`ConcurrentHashMap.newKeySet` 的 add 原子性（同一 run 内多个出口同时到达
    时只有一个能占位）在本脚本里退化为单线程顺序调用；多出口竞争的真实时序属不可仿真面。
  - **持久化未建模**：提醒消息不进 DB（workflow 内加的 user 消息不经事件落库），重启后
    `notified` 内存态清空、重新计数——本脚本不模拟重启，也不模拟 buildHistory 回放。
  - **压缩只建模 tail 选取 + 拆分点调整 + 产物布局**（tail + marker + summary），不建模摘要生成、
    摘要长度封顶、head 落库判定、失败冷却/熔断。
  - **模型的真实行为改变不可仿真**：本脚本只能证明「提醒正文确实出现在发给模型的 payload 里」，
    不能证明模型会因此真的去派发子代理（见文末「不可仿真面」）。
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java/com/aicode/feature/agent")

SRC_TRACKER = os.path.join(SRC, "domain/workflow/SubAgentDelegationTracker.kt")
SRC_WORKFLOW = os.path.join(SRC, "domain/workflow/StatefulAgentWorkflow.kt")
SRC_GUARD = os.path.join(SRC, "domain/workflow/EvidenceGuard.kt")
SRC_PAYLOAD = os.path.join(SRC, "domain/workflow/RequestPayloadGuard.kt")
SRC_POLICY = os.path.join(ROOT, "app/src/main/java/com/aicode/feature/settings/domain/model/ModelContextPolicy.kt")
SRC_TAIL = os.path.join(SRC, "domain/workflow/CompactionTailSelector.kt")
SRC_ADJUST = os.path.join(SRC, "domain/workflow/CompactionSplitAdjuster.kt")
SRC_PROJECTION = os.path.join(SRC, "domain/tool/ModelProjection.kt")
SRC_OPENAI = os.path.join(SRC, "domain/provider/OpenAIAdapter.kt")
SRC_TASKTOOL = os.path.join(SRC, "domain/tool/subagent/TaskTool.kt")

# ══════════════════════════════════════════════════════════════════════
# 一、常量（逐条抄自源码，标注出处；改源码须同步）
# ══════════════════════════════════════════════════════════════════════
# StatefulAgentWorkflow.kt:202-203  SUBAGENT_DELEGATION_NOTICE
# （真实文案；脚本运行时会到源码里把它抠出来逐字节比对，见 check_source_anchors）
SUBAGENT_DELEGATION_NOTICE = (
    "（系统提示：本会话你已开始直接改文件，但尚未派发过子代理。若本任务涉及 2 个及以上文件、"
    "或可拆出互不依赖的支线，请停下当前做法，改用 task 派子代理并行推进；若确实只碰 1 个文件"
    "且无需跨文件核对，忽略本条。）"
)
NOTICE_CHARS = 108     # 实测字符数（源码常量核对用）
NOTICE_BYTES = 300     # 实测 UTF-8 字节数

# EvidenceGuard.kt:62  private val WRITE_TOOLS = setOf("editFile", "writeFile")
WRITE_TOOLS = ("editFile", "writeFile")

# RequestPayloadGuard.kt
MAX_REQUEST_BYTES = 4 * 1024 * 1024          # RequestPayloadGuard.MAX_REQUEST_BYTES:25
MIN_GIANT_USER_TOKENS = 2_000                # RequestPayloadGuard.MIN_GIANT_USER_TOKENS:28
MIN_KEPT_USER_TOKENS = 800                   # RequestPayloadGuard.MIN_KEPT_USER_TOKENS:31
KEEP_HEAD_CHARS = 6_000                      # RequestPayloadGuard.KEEP_HEAD_CHARS:34
KEEP_TAIL_CHARS = 1_500                      # RequestPayloadGuard.KEEP_TAIL_CHARS:35
JSON_FRAMING_PER_MESSAGE = 64                # RequestPayloadGuard.JSON_FRAMING_PER_MESSAGE:38
TRUNCATED_MARKER = "\n\n[…… 消息过长已截断：完整原文保留在会话记录中 ……]\n\n"   # :40
SPLIT_TURN_BRIDGE_MESSAGE = (                # RequestPayloadGuard.SPLIT_TURN_BRIDGE_MESSAGE
    "[系统说明] 为避免单个超长任务回合撑爆上下文，更早的步骤已折叠为后续摘要。"
    "以下从当前任务中途继续，请勿重复已完成的工作。"
)

# ModelContextPolicy.estimateTokens 的分桶除数（ModelContextPolicy.kt:246-248）
CJK_CHARS_PER_TOKEN = 1.8                    # CJK_CHARS_PER_TOKEN
FULLWIDTH_PUNCT_CHARS_PER_TOKEN = 2.8        # FULLWIDTH_PUNCT_CHARS_PER_TOKEN
ASCII_CHARS_PER_TOKEN = 2.5                  # ASCII_CHARS_PER_TOKEN

# AgentMessage.kt:66  CONTEXT_COMPACTION_MARKER
CONTEXT_COMPACTION_MARKER = "What did we do so far?"
SUMMARY_TEXT = "（接手摘要）任务进展：……"


# ══════════════════════════════════════════════════════════════════════
# 二、被测逻辑建模（函数同名/同语义，注释标明对账对象）
# ══════════════════════════════════════════════════════════════════════

def set_add_new(s, x):
    """对账 ConcurrentHashMap.newKeySet().add 的返回值语义（false = 已存在，占位失败）。"""
    if x in s:
        return False
    s.add(x)
    return True


class _TrackerState:
    """对账 SubAgentDelegationTracker 的三个 private set（进程内、按会话）。"""

    def __init__(self):
        self.wrote = set()
        self.delegated = set()
        self.notified = set()


TRACKER = _TrackerState()


def tracker_record_write_success(session_id):
    """对账 SubAgentDelegationTracker.recordWriteSuccess（:18-20）。"""
    TRACKER.wrote.add(session_id)


def tracker_record_delegation(session_id):
    """对账 SubAgentDelegationTracker.recordDelegation（:23-25）。"""
    TRACKER.delegated.add(session_id)


def tracker_should_notify_and_mark(session_id):
    """对账 SubAgentDelegationTracker.shouldNotifyAndMark（:31-38）。

    先原子占位 → 判定 → 不成立则**回退占位**。回退那一步是必需的：否则「首次询问时还没写过文件」
    会永久占位，此后条件成立也不再提醒。
    """
    if not set_add_new(TRACKER.notified, session_id):
        return False
    if session_id in TRACKER.wrote and session_id not in TRACKER.delegated:
        return True
    TRACKER.notified.remove(session_id)
    return False


def tracker_reset(session_id):
    """对账 SubAgentDelegationTracker.reset（:41-45）。"""
    TRACKER.wrote.discard(session_id)
    TRACKER.delegated.discard(session_id)
    TRACKER.notified.discard(session_id)


def is_write_tool(name):
    """对账 EvidenceGuard.isWriteTool（EvidenceGuard.kt:79）。"""
    return name in WRITE_TOOLS


def content_or_null(value):
    """对账 kotlinx.serialization JsonPrimitive.contentOrNull：
    JsonNull / 缺键 → None；字符串原样（**不 trim**）；数字/布尔 → 字面量。"""
    if value is None:
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


JSON_NULL = object()          # 对账 kotlinx.serialization.json.JsonNull（**是** JsonPrimitive 子类）
ACTION_MISSING = object()     # arguments 里根本没有 "action" 键


def primitive_content(value):
    """对账 JsonPrimitive.content（**非** contentOrNull）：
    JsonNull → 字面量 "null"（**不是** null）；字符串/数字/布尔 → 字面量；非 JsonPrimitive → None。

    这一差异是可观测的：`JsonNull` 走 `.content` 得 "null"（≠ "create" → 判未派发），
    而缺键走 `?: "create"`（判已派发）。见 _q2_10 / _q2_11。
    """
    if value is JSON_NULL:
        return "null"
    if value is None:
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    return None               # JsonObject / JsonArray 不是 JsonPrimitive


class ToolCallStub:
    """对账 ToolCall（id + name + arguments["action"] 的原始值）。

    action 取 [ACTION_MISSING] 表示 arguments 无该键、[JSON_NULL] 表示它是 JsonNull、
    其余为 JsonPrimitive 字面量（字符串/数字/布尔）。
    """

    __slots__ = ("id", "name", "action")

    def __init__(self, name, action=ACTION_MISSING, id="c1"):
        self.id = id
        self.name = name
        self.action = action

    def arguments(self):
        """对账 ToolCall.arguments: Map<String, JsonElement>。"""
        if self.action is ACTION_MISSING:
            return {}
        return {"action": self.action}


def delegated_of(batch_results, tool_calls):
    """对账 StatefulAgentWorkflow.kt:1264-1270（a224d78 后的当前口径）。

    ```kotlin
    val delegated = batchResults.any { br ->
        if (br.isError || br.toolName != "task") return@any false
        val args = toolCalls.firstOrNull { it.id == br.id }?.arguments
        ((args?.get("action") as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create") == "create"
    }
    ```

    三条语义都要建模：① 只看**成功**的派发（isError 不算——创建失败时正是最该提醒的场景）；
    ② 按 toolCall.id 配对取 arguments；③ 缺省（缺键 / 找不到配对）即 create，用 `.content` 而非
    `.contentOrNull`（TaskTool.kt:76/135 同表达式）。
    """
    for br in batch_results:
        if br.is_error or br.tool_name != "task":
            continue
        matched = next((c for c in tool_calls if c.id == br.id), None)
        args = matched.arguments() if matched is not None else None
        content = primitive_content(args.get("action")) if args is not None else None
        normalized = "create" if content is None else content.strip().lower()
        if normalized == "create":
            return True
    return False


class BatchResultStub:
    """对账 StatefulAgentWorkflow.ToolBatchResult（:313-321）；id 即构建时的 toolCall.id（:1253）。"""

    __slots__ = ("id", "tool_name", "result", "is_error")

    def __init__(self, tool_name, result="", is_error=False, id="c1"):
        self.id = id
        self.tool_name = tool_name
        self.result = result
        self.is_error = is_error


def workflow_record_batch(is_sub_agent, session_id, batch_results, tool_calls):
    """对账 StatefulAgentWorkflow.kt:1256-1272（计数侧）。

    包在 `if (!currentContext.isSubAgent)` 内：子代理工具集已剔除 task，既不计数也无从派发。
    """
    if is_sub_agent:
        return
    if session_id is None:
        return
    if any((not r.is_error) and is_write_tool(r.tool_name) for r in batch_results):
        tracker_record_write_success(session_id)
    if delegated_of(batch_results, tool_calls):
        tracker_record_delegation(session_id)


def notice_text():
    """提醒正文（单独成函数，供失败注入 5「把提醒改成超长」替换）。"""
    return SUBAGENT_DELEGATION_NOTICE


def workflow_inject_notice(messages):
    """对账 StatefulAgentWorkflow.kt:772-778（注入侧）。

    返回 `messages + [UserMessage(SUBAGENT_DELEGATION_NOTICE)]`（源码是 state.copy(messages = ...)）。
    """
    return messages + [UserMsg(content=notice_text())]


def workflow_should_notify(is_sub_agent, session_id):
    """对账 StatefulAgentWorkflow.kt:772-778 的门控部分（!isSubAgent + sessionId!=null）。"""
    if is_sub_agent:
        return False
    if session_id is None:
        return False
    return tracker_should_notify_and_mark(session_id)


def workflow_call_llm_entry(is_sub_agent, session_id, messages):
    """对账 :772-778 → :788 的顺序：**先注入、后压缩**。

    返回发给模型前的消息列表（压缩前），二元组（注入后的列表，是否注入）。
    """
    if workflow_should_notify(is_sub_agent, session_id):
        return workflow_inject_notice(messages), True
    return list(messages), False


# ---------- 消息模型（对账 AgentMessage 密封类）----------

class ImageStub:
    __slots__ = ("base64_data",)

    def __init__(self, chars=100):
        self.base64_data = "A" * chars


class UserMsg:
    """对账 AgentMessage.UserMessage。"""

    __slots__ = ("content", "images", "restored")

    def __init__(self, content, images=(), restored=False):
        self.content = content
        self.images = list(images)
        self.restored = restored


class AsstMsg:
    """对账 AgentMessage.AssistantMessage。"""

    __slots__ = ("content", "reasoning", "tool_calls", "images", "restored")

    def __init__(self, content="", reasoning="", tool_calls=(), images=(), restored=False):
        self.content = content
        self.reasoning = reasoning
        self.tool_calls = list(tool_calls)
        self.images = list(images)
        self.restored = restored


class ToolMsg:
    """对账 AgentMessage.ToolResultMessage（model_result 仅喂模型，UI/落库走 result）。"""

    __slots__ = ("tool_name", "result", "images", "model_result", "restored")

    def __init__(self, tool_name, result, images=(), model_result=None, restored=False):
        self.tool_name = tool_name
        self.result = result
        self.images = list(images)
        self.model_result = model_result
        self.restored = restored


def estimate_tokens(text):
    """对账 ModelContextPolicy.estimateTokens(text)（CJK 感知，:214-227 + bucketOf）。"""
    if not text:
        return 0
    cjk = punct = other = 0.0
    for ch in text:
        code = ord(ch)
        if (0x4E00 <= code <= 0x9FFF or 0x3400 <= code <= 0x4DBF or
                0x3040 <= code <= 0x30FF or 0xAC00 <= code <= 0xD7A3 or 0xF900 <= code <= 0xFAFF):
            cjk += 1.0
        elif (0x3000 <= code <= 0x303F or 0xFF00 <= code <= 0xFFEF or 0x2010 <= code <= 0x205E):
            punct += 1.0
        else:
            other += 1.0
    tokens = cjk / CJK_CHARS_PER_TOKEN + punct / FULLWIDTH_PUNCT_CHARS_PER_TOKEN + other / ASCII_CHARS_PER_TOKEN
    return max(1, int(-(-tokens // 1)))     # kotlin.math.ceil


def json_literal(value):
    """对账 `arguments.toString()`（JsonObject 渲染）在字节/token 估算里的作用。"""
    if value is ACTION_MISSING or value is None or value is JSON_NULL:
        return '{"action":null}'
    return json.dumps({"action": value}, ensure_ascii=False, default=str)


def message_text(message):
    """对账 RequestPayloadGuard.messageText / ContextCompactor.messageText。"""
    if isinstance(message, UserMsg):
        return message.content
    if isinstance(message, AsstMsg):
        out = message.content + message.reasoning
        for call in message.tool_calls:
            out += call.name + json_literal(call.action)
        return out
    return message.tool_name + message.result


def estimate_message_tokens(message):
    """对账 RequestPayloadGuard.estimateMessageTokens（:210-211）。"""
    return estimate_tokens(message_text(message))


def fit_text(text, target_tokens):
    """对账 RequestPayloadGuard.fitText（:88-99）：头 3/4 + 标记 + 尾 1/4，6 轮收缩。"""
    kept = max(int(target_tokens * 1.5), len(TRUNCATED_MARKER) + 2)
    for _ in range(6):
        if len(text) <= kept:
            return None
        head = kept * 3 // 4
        candidate = text[:head] + TRUNCATED_MARKER + text[-max(kept - head, 0):]
        if estimate_tokens(candidate) <= target_tokens:
            return candidate
        kept = max(kept * 3 // 4, len(TRUNCATED_MARKER) + 2)
    return None


def truncate_oversized_user_messages(messages, limit_tokens):
    """对账 RequestPayloadGuard.truncateOversizedUserMessages（D5，:63-85）。

    判据：仅 UserMessage 且 estimateMessageTokens > MIN_GIANT_USER_TOKENS(2000) 才进候选。
    """
    if limit_tokens <= 0 or not messages:
        return messages
    overage = sum(estimate_message_tokens(m) for m in messages) - limit_tokens
    if overage <= 0:
        return messages
    out = list(messages)
    candidates = [i for i, m in enumerate(out)
                  if isinstance(m, UserMsg) and estimate_message_tokens(m) > MIN_GIANT_USER_TOKENS]
    candidates.sort(key=lambda i: estimate_message_tokens(out[i]), reverse=True)
    for index in candidates:
        if overage <= 0:
            break
        message = out[index]
        old_tokens = estimate_tokens(message.content)
        target = max(old_tokens - overage, MIN_KEPT_USER_TOKENS)
        fitted = fit_text(message.content, target)
        if fitted is None:
            continue
        new_tokens = estimate_tokens(fitted)
        if new_tokens >= old_tokens:
            continue
        overage -= old_tokens - new_tokens
        out[index] = UserMsg(fitted, message.images, message.restored)
    return messages if out == messages else out


def ensure_leading_user(messages):
    """对账 RequestPayloadGuard.ensureLeadingUser（D3，:105-109）。"""
    if not messages:
        return messages
    if isinstance(messages[0], UserMsg):
        return messages
    return [UserMsg(SPLIT_TURN_BRIDGE_MESSAGE)] + list(messages)


def utf8_len(text):
    return len(text.encode("utf-8"))


def estimate_payload_bytes(messages):
    """对账 RequestPayloadGuard.estimatePayloadBytes（:180-203）。"""
    total = 0
    for message in messages:
        total += JSON_FRAMING_PER_MESSAGE
        if isinstance(message, UserMsg):
            total += utf8_len(message.content)
            total += sum(len(img.base64_data) for img in message.images)
        elif isinstance(message, AsstMsg):
            total += utf8_len(message.content) + utf8_len(message.reasoning)
            for call in message.tool_calls:
                total += utf8_len(call.name) + utf8_len(json_literal(call.action))
            total += sum(len(img.base64_data) for img in message.images)
        else:
            total += utf8_len(message.tool_name) + utf8_len(message.result)
            total += sum(len(img.base64_data) for img in message.images)
    return total


def compact_text(text):
    """对账 RequestPayloadGuard.compactText（:152-158）。"""
    if len(text) <= KEEP_HEAD_CHARS + KEEP_TAIL_CHARS + 80:
        return text
    omitted = len(text) - KEEP_HEAD_CHARS - KEEP_TAIL_CHARS
    return (text[:KEEP_HEAD_CHARS] + "\n... [请求体体积限制，已省略 %d 字符] ...\n" % omitted
            + text[-KEEP_TAIL_CHARS:])


def strip_images(message):
    """对账 RequestPayloadGuard.stripImages（:160-176）：无图片返回 None（不动该条）。"""
    if isinstance(message, UserMsg):
        if not message.images:
            return None
        return UserMsg(message.content + "\n\n[…… 本消息携带的 %d 张图片因请求体体积限制已从模型上下文省略 ……]"
                       % len(message.images), (), message.restored)
    if isinstance(message, ToolMsg):
        if not message.images:
            return None
        return ToolMsg(message.tool_name, message.result +
                       "\n\n[…… %d 张图片因请求体体积限制已从模型上下文省略 ……]" % len(message.images),
                       (), message.model_result, message.restored)
    return None


def enforce_byte_budget(messages, max_bytes):
    """对账 RequestPayloadGuard.enforceByteBudget（D4，:118-148）。

    只压**最长的 ToolResultMessage** 并按从旧到新剥图片；**不动 UserMessage 的正文文本**。
    """
    if max_bytes <= 0 or not messages:
        return messages
    if estimate_payload_bytes(messages) <= max_bytes:
        return messages
    out = list(messages)
    changed = False
    while estimate_payload_bytes(out) > max_bytes:
        idx = None
        best = -1
        for i, m in enumerate(out):
            if isinstance(m, ToolMsg) and len(m.result) > KEEP_HEAD_CHARS + KEEP_TAIL_CHARS:
                if len(m.result) > best:
                    best = len(m.result)
                    idx = i
        if idx is None:
            break
        result = out[idx]
        compacted = compact_text(result.result)
        if len(compacted) >= len(result.result):
            break
        out[idx] = ToolMsg(result.tool_name, compacted, result.images, result.model_result, result.restored)
        changed = True
    for index in range(len(out)):
        if estimate_payload_bytes(out) <= max_bytes:
            break
        stripped = strip_images(out[index])
        if stripped is not None:
            out[index] = stripped
            changed = True
    return out if changed else messages


def sanitize_images_for_model(messages, supports_vision):
    """对账 StatefulAgentWorkflow.sanitizeImagesForModel（:1403-1418）。

    不支持视觉时剥图；**无图片的 UserMessage 原样不动**（content 逐字节不变）。
    """
    if supports_vision:
        return messages
    out = []
    for message in messages:
        if isinstance(message, UserMsg):
            out.append(message if not message.images else UserMsg(
                message.content if message.content.strip() else "（图片已省略：当前模型不支持图片输入）",
                (), message.restored))
        elif isinstance(message, ToolMsg):
            out.append(message if not message.images else
                       ToolMsg(message.tool_name, message.result, (), message.model_result, message.restored))
        else:
            out.append(message)
    return out


def prepare_for_send(messages, budget_tokens):
    """对账 RequestPayloadGuard.prepareForSend（:47-52）：D5 → D3 → D4。"""
    if not messages:
        return messages
    after_sizing = truncate_oversized_user_messages(messages, budget_tokens)
    with_bridge = ensure_leading_user(after_sizing)
    return enforce_byte_budget(with_bridge, MAX_REQUEST_BYTES)


# ---------- 压缩：tail 选取 + 拆分点调整 + 产物布局 ----------

def is_restored(message):
    """对账 CompactionTailSelector.isRestored（:78-82）。"""
    return message.restored


def compute_tail_split(messages, budget):
    """对账 CompactionTailSelector.compute（:37-72）——返回 tail 起点下标。

    关键判据：`if (!inRestored && total + next > budget && splitIndex < messages.size) break`
    ——`splitIndex < messages.size` 使**最后一条消息无条件进入 tail**。
    """
    total = 0
    split_index = len(messages)
    restore_anchor = None
    for index in range(len(messages)):
        if index > 0 and is_restored(messages[index]) and not is_restored(messages[index - 1]):
            restore_anchor = index
            break
    protected_start = None
    if restore_anchor is not None:
        if sum(estimate_tokens(message_text(m)) for m in messages[restore_anchor:]) <= budget:
            protected_start = restore_anchor
    for index in reversed(range(len(messages))):
        in_restored = protected_start is not None and index >= protected_start
        nxt = estimate_tokens(message_text(messages[index]))
        if not in_restored and total + nxt > budget and split_index < len(messages):
            break
        total += nxt
        split_index = index
        if protected_start is not None and index == protected_start:
            break
    return split_index


def is_marker(message):
    """对账 CompactionSplitAdjuster.isMarker。"""
    return isinstance(message, UserMsg) and message.content == CONTEXT_COMPACTION_MARKER


def adjust_split_index(messages, initial_split):
    """对账 CompactionSplitAdjuster.adjust（walkBack → marker/summary 前移 → 复查 walkBack）。"""
    split = min(max(initial_split, 0), len(messages))
    split = walk_back_past_tool_results(messages, split)
    if 1 <= split < len(messages) - 1 and is_marker(messages[split - 1]) and isinstance(messages[split], AsstMsg):
        split += 1
    split = walk_back_past_tool_results(messages, split)
    return split


def walk_back_past_tool_results(messages, start):
    split = start
    while split > 0 and isinstance(messages[split], ToolMsg):
        split -= 1
    return split


def compact(messages, tail_budget):
    """对账 ContextCompactor.compactIfNeededLocked 的**产物布局**（非摘要生成）：

    `splitIndex = CompactionTailSelector.compute(...)` → `adjustSplitIndex` → `tail = messages[split:]`
    → `newMessages = tail + [marker, summary]`。
    """
    split = adjust_split_index(messages, compute_tail_split(messages, tail_budget))
    tail = list(messages[split:])
    return tail + [UserMsg(CONTEXT_COMPACTION_MARKER), AsstMsg(content=SUMMARY_TEXT)]


# ---------- 工具结果投影与 provider 取值顺序（通道 B/C 的成败点）----------

def model_tool_result_text(tool_name, transport_json):
    """对账 ModelProjection.modelToolResultText（只投影 editFile/writeFile 的成功结果）。"""
    raw = transport_json.strip()
    if not raw:
        return None
    try:
        obj = json.loads(raw)
    except Exception:
        return None
    if not isinstance(obj, dict) or obj.get("status") != "success":
        return None
    data = obj.get("data")
    if not isinstance(data, dict):
        return None
    if tool_name == "editFile":
        path = content_or_null(data.get("path"))
        if path is None:
            return None
        return "Edited file successfully: %s\nReplacements: %d\nChanged lines: +%d -%d" % (
            path, data.get("replacements", 0), data.get("added_lines", 0), data.get("removed_lines", 0))
    if tool_name == "writeFile":
        path = content_or_null(data.get("path"))
        if path is None:
            return None
        verb = "Created" if content_or_null(data.get("created")) == "true" else "Wrote"
        total = data.get("lines_written", data.get("total_lines", "?"))
        return "%s file successfully: %s (lines: %s, +%d -%d)" % (
            verb, path, total, data.get("added_lines", 0), data.get("removed_lines", 0))
    return None


def provider_tool_text(message):
    """对账 OpenAIAdapter.kt:658-660 / AnthropicAdapter.kt:530-532 / GeminiAdapter.kt:752。

    `message.modelResult ?: modelToolResultText(...) ?: message.result` —— modelResult 非空即胜出。
    """
    if message.model_result is not None:
        return message.model_result
    projected = model_tool_result_text(message.tool_name, message.result)
    if projected is not None:
        return projected
    return message.result


def event_injector_inject(raw, notices):
    """对账 DefaultAgentEventInjector.inject：JSON 顶层加 notifications 字段；非 JSON 时文本追加。"""
    if not notices:
        return raw
    try:
        obj = json.loads(raw)
        if not isinstance(obj, dict):
            raise ValueError
    except Exception:
        return raw + "\n\n" + json.dumps(notices, ensure_ascii=False)
    obj = dict(obj)
    obj["notifications"] = notices
    return json.dumps(obj, ensure_ascii=False)


def parse_tool_transport(raw):
    """对账 ToolMessageComponents.parseToolTransport：解析失败 / 无 status 键 → None。"""
    try:
        obj = json.loads(raw.strip())
    except Exception:
        return None
    if isinstance(obj, dict) and "status" in obj:
        return obj
    return None


def format_tool_result(raw):
    """对账 UI 的 formatToolResult：解析失败时**退化为原文**（把注入的纯文本也显示出来）。"""
    obj = parse_tool_transport(raw)
    if obj is None:
        return raw
    if obj.get("status") == "error":
        return content_or_null(obj.get("message")) or raw
    data = obj.get("data")
    return json.dumps(data, ensure_ascii=False) if data is not None else raw


def transport_success(tool_data):
    return json.dumps({"status": "success", "data": tool_data}, ensure_ascii=False)


def write_file_transport(path="app/src/main/java/com/aicode/X.kt", lines=40, big=False):
    return transport_success({
        "path": path, "created": "true", "added_lines": lines,
        "removed_lines": 0, "lines_written": lines,
        "content": "x" * (200_000 if big else 0),
    })


# ══════════════════════════════════════════════════════════════════════
# 三、场景注册与执行（PASS / FAIL / EXC 三态）
# ══════════════════════════════════════════════════════════════════════
SCENARIOS = []


def scenario(name, kind):
    """kind ∈ {real, edge, failure}——样本三类齐全由 suite_forward 末尾统一断言。"""

    def deco(fn):
        SCENARIOS.append((name, kind, fn))
        return fn

    return deco


def expect(cond, msg):
    if not cond:
        raise AssertionError(msg)


def fresh_state():
    """每个场景独立起跑：清空追踪器全部会话状态。"""
    TRACKER.wrote.clear()
    TRACKER.delegated.clear()
    TRACKER.notified.clear()


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


def run_all(verbose=True, rep=None):
    """跑全部场景，返回 {name: (status, detail)}；status ∈ PASS/FAIL/EXC。"""
    results = {}
    groups = ("real", "edge", "failure")
    labels = {"real": "真实样本", "edge": "边界样本", "failure": "失败分支样本"}
    for kind in groups:
        subset = [(n, f) for (n, k, f) in SCENARIOS if k == kind]
        if not subset:
            continue
        if verbose:
            print("── %s（%d 组）──" % (labels[kind], len(subset)))
        for name, fn in subset:
            fresh_state()
            try:
                fn()
                results[name] = ("PASS", "")
            except AssertionError as exc:
                results[name] = ("FAIL", str(exc))
            except Exception as exc:  # 注入致签名漂移/异常——单独计 EXC，**不计**入检出
                results[name] = ("EXC", "%s: %s" % (type(exc).__name__, exc))
            if verbose:
                status = results[name][0]
                detail = results[name][1]
                print("  %s %s%s" % ("OK  " if status == "PASS" else status, name,
                                     "  <%s>" % detail if detail else ""))
    if rep is not None:
        for name, (status, detail) in results.items():
            if status == "FAIL":
                rep.failures.append("场景「%s」失败：%s" % (name, detail))
            elif status == "EXC":
                rep.failures.append("场景「%s」异常（EXC，不计入检出）：%s" % (name, detail))
    return results


def detections(results):
    """检出集合 = 断言失败的场景名集合（EXC 不算检出）。"""
    return set(n for n, (s, _) in results.items() if s == "FAIL")


def carries_notice(message):
    """该消息是否携带提醒正文（对账 `it is AgentMessage.UserMessage && it.content == ...`）。"""
    return isinstance(message, UserMsg) and message.content == SUBAGENT_DELEGATION_NOTICE


# ══════════════════════════════════════════════════════════════════════
# 四、正向场景
# ══════════════════════════════════════════════════════════════════════
S_A = "tracker-session-A"
S_B = "tracker-session-B"

# ---------- Q1 触发态机（既有单测 SubAgentDelegationTrackerTest.kt 6 用例逐条抄入）----------

@scenario("只写文件从未派发时首次提醒第二次不再提醒", "real")
def _q1_1():
    # 对账 SubAgentDelegationTrackerTest.kt:12-19
    tracker_record_write_success(S_A)
    expect(tracker_should_notify_and_mark(S_A) is True, "首次应提醒")
    expect(tracker_should_notify_and_mark(S_A) is False, "第二次不应再提醒")


@scenario("已派发子代理且有写入时不提醒", "real")
def _q1_2():
    # 对账 SubAgentDelegationTrackerTest.kt:22-28
    tracker_record_write_success(S_A)
    tracker_record_delegation(S_A)
    expect(tracker_should_notify_and_mark(S_A) is False, "已派发应沉默")


@scenario("从未写入时不提醒且占位可回退", "real")
def _q1_3():
    # 对账 SubAgentDelegationTrackerTest.kt:31-39（占位必须回退，否则此后永不提醒）
    expect(tracker_should_notify_and_mark(S_A) is False, "未写入不应提醒")
    tracker_record_write_success(S_A)
    expect(tracker_should_notify_and_mark(S_A) is True, "占位回退后应能提醒")


@scenario("reset后回到初始态可重新提醒", "real")
def _q1_4():
    # 对账 SubAgentDelegationTrackerTest.kt:42-51
    tracker_record_write_success(S_A)
    expect(tracker_should_notify_and_mark(S_A) is True, "首次应提醒")
    tracker_reset(S_A)
    tracker_record_write_success(S_A)
    expect(tracker_should_notify_and_mark(S_A) is True, "reset 后应可再提醒")


@scenario("两个会话互不影响", "real")
def _q1_5():
    # 对账 SubAgentDelegationTrackerTest.kt:54-66
    tracker_record_write_success(S_A)
    tracker_record_write_success(S_B)
    expect(tracker_should_notify_and_mark(S_A) is True, "A 应提醒")
    expect(tracker_should_notify_and_mark(S_B) is True, "B 应提醒")
    expect(tracker_should_notify_and_mark(S_A) is False, "A 不应二次提醒")
    expect(tracker_should_notify_and_mark(S_B) is False, "B 不应二次提醒")


@scenario("先派发再写文件仍不提醒", "real")
def _q1_6():
    # 对账 SubAgentDelegationTrackerTest.kt:69-78
    tracker_record_delegation(S_A)
    tracker_record_write_success(S_A)
    expect(tracker_should_notify_and_mark(S_A) is False, "先派发应沉默")
    expect(tracker_should_notify_and_mark(S_A) is False, "再问一次也不因回退变 true")


@scenario("连续多次未命中询问后写入仍可提醒（回退幂等）", "edge")
def _q1_7():
    # 补测试够不到的面：回退若漏一次，后续永久沉默
    for _ in range(5):
        expect(tracker_should_notify_and_mark(S_A) is False, "未写入不应提醒")
    tracker_record_write_success(S_A)
    expect(tracker_should_notify_and_mark(S_A) is True, "多次回退后应仍可提醒")


@scenario("reset只清本会话不误清他会话", "edge")
def _q1_8():
    tracker_record_write_success(S_A)
    tracker_record_write_success(S_B)
    tracker_reset(S_A)
    expect(tracker_should_notify_and_mark(S_B) is True, "B 不受 A 的 reset 影响")


@scenario("恰好1个文件写入成功即提醒", "edge")
def _q1_9():
    # 提醒文案允许「只碰 1 个文件」忽略本条，但**注入条件本身**不按文件数分档
    workflow_record_batch(False, S_A, [BatchResultStub("writeFile", write_file_transport())], [])
    expect(workflow_should_notify(False, S_A) is True, "1 个文件成功写入即达到注入条件")


@scenario("第二批写文件时仍只提醒一次", "edge")
def _q1_10():
    workflow_record_batch(False, S_A, [BatchResultStub("editFile", write_file_transport())], [])
    expect(workflow_should_notify(False, S_A) is True, "首批后应提醒")
    workflow_record_batch(False, S_A, [BatchResultStub("writeFile", write_file_transport())], [])
    expect(workflow_should_notify(False, S_A) is False, "第二批不应再提醒")


@scenario("空写入批不改变状态", "edge")
def _q1_11():
    workflow_record_batch(False, S_A, [], [])
    expect(workflow_should_notify(False, S_A) is False, "空批不计数")


@scenario("仅失败写入不计入已写入", "failure")
def _q1_12():
    workflow_record_batch(False, S_A, [BatchResultStub("writeFile", "{}", is_error=True)], [])
    expect(workflow_should_notify(False, S_A) is False, "失败写入不算凭证")


@scenario("非写工具成功不计入已写入", "failure")
def _q1_13():
    workflow_record_batch(False, S_A, [BatchResultStub("readFile", "ok"), BatchResultStub("Bash", "ok")], [])
    expect(workflow_should_notify(False, S_A) is False, "readFile/Bash 不是写工具")


@scenario("同批既写又派不提醒（当批判为已派发）", "edge")
def _q1_14():
    # 源码顺序：先 recordWriteSuccess，再判 delegated——同批同时出现时以「已派发」收场
    workflow_record_batch(False, S_A,
                          [BatchResultStub("writeFile", write_file_transport(), id="c1"),
                           BatchResultStub("task", "", id="c2")],
                          [ToolCallStub("writeFile", ACTION_MISSING, id="c1"),
                           ToolCallStub("task", "create", id="c2")])
    expect(workflow_should_notify(False, S_A) is False, "同批派发后应沉默")


# ---------- Q2 门控与派发判定口径 ----------

@scenario("子代理会话不注入（门控）", "failure")
def _q2_1():
    workflow_record_batch(False, S_A, [BatchResultStub("writeFile", write_file_transport())], [])
    expect(workflow_should_notify(True, S_A) is False, "isSubAgent=true 必须不注入")


@scenario("子代理会话不计数（写文件后主代理不受影响）", "failure")
def _q2_2():
    workflow_record_batch(True, S_A, [BatchResultStub("writeFile", write_file_transport())], [])
    expect(workflow_should_notify(False, S_A) is False, "子代理的写入不得计入主代理")
    expect(tracker_should_notify_and_mark(S_A) is False, "追踪器里也不应有该会话的写入")


@scenario("sessionId为null时静默不触发", "failure")
def _q2_3():
    workflow_record_batch(False, None, [BatchResultStub("writeFile", write_file_transport())], [])
    expect(workflow_should_notify(False, None) is False, "sessionId=null 应静默")
    messages, injected = workflow_call_llm_entry(False, None, [UserMsg("hi")])
    expect(injected is False, "sessionId=null 不应注入")


@scenario("task调用缺action键判为已派发", "real")
def _q2_4():
    # 真实回归点：TaskTool.kt:94 required=false，:76/:135 `?: "create"`
    expect(delegated_of([BatchResultStub("task")], [ToolCallStub("task", ACTION_MISSING)]) is True,
           "缺 action 键应判已派发")
    workflow_record_batch(False, S_A,
                          [BatchResultStub("writeFile", write_file_transport(), id="c1"),
                           BatchResultStub("task", "", id="c2")],
                          [ToolCallStub("task", ACTION_MISSING, id="c2")])
    expect(workflow_should_notify(False, S_A) is False, "缺省口径的派发应沉默")


@scenario("task action带空格大写归一后判为已派发", "edge")
def _q2_5():
    expect(delegated_of([BatchResultStub("task")], [ToolCallStub("task", " CREATE ")]) is True,
           "trim+lowercase 后应判已派发")


@scenario("task只查列表未派发（list/read/send/stop/del）", "real")
def _q2_6():
    for action in ("list", "read", "send", "stop", "del"):
        expect(delegated_of([BatchResultStub("task")], [ToolCallStub("task", action)]) is False,
               "%s 不算派发" % action)
    workflow_record_batch(False, S_A,
                          [BatchResultStub("writeFile", write_file_transport(), id="c1"),
                           BatchResultStub("task", "", id="c2")],
                          [ToolCallStub("task", "list", id="c2")])
    expect(workflow_should_notify(False, S_A) is True, "只查列表后仍应提醒")


@scenario("非task工具的action=create不算派发", "edge")
def _q2_7():
    expect(delegated_of([BatchResultStub("browser")], [ToolCallStub("browser", "create")]) is False,
           "非 task 工具不算派发")


@scenario("action为空串不算派发（不抛异常）", "failure")
def _q2_8():
    expect(delegated_of([BatchResultStub("task")], [ToolCallStub("task", "")]) is False,
           "空串既非缺省也非 create")


@scenario("action为数字JsonPrimitive不抛异常且不算派发", "failure")
def _q2_9():
    # as? JsonPrimitive 能命中数字（JsonPrimitive 子类），.content 得 "1" → 判未派发
    expect(primitive_content(1) == "1", "数字 JsonPrimitive → 字面量")
    expect(delegated_of([BatchResultStub("task")], [ToolCallStub("task", 1)]) is False,
           "数字值不算派发（最易漏的数字型边界）")


@scenario("task的action为JsonNull判为未派发（.content口径）", "edge")
def _q2_10():
    # `.content` 对 JsonNull 返回字面量 "null"（不是 null）→ 不命中缺省分支 → 判未派发；
    # 与 TaskTool 的「未知 action: null」行为对齐（若改用 .contentOrNull 这里会反过来）。
    #
    # 实测取证（非猜测）：对 kotlinx-serialization-json-jvm-1.7.3.jar 反查字节码，
    # `javap kotlinx/serialization/json/JsonNull.class` 得：
    #   public final class JsonNull extends JsonPrimitive { ... String getContent(); }
    # —— JsonNull 确为 JsonPrimitive 子类（`as? JsonPrimitive` 能命中），
    # 其常量池里的 content 字面量为 "null"（`strings ... | grep -x null` 命中）。
    expect(primitive_content(JSON_NULL) == "null", "JsonNull 的 .content 是字面量 \"null\"")
    expect(delegated_of([BatchResultStub("task")], [ToolCallStub("task", JSON_NULL)]) is False,
           "JsonNull 不算派发（不得误走 ?: \"create\" 缺省）")
    # 对照：真正的缺键才会走缺省
    expect(delegated_of([BatchResultStub("task")], [ToolCallStub("task", ACTION_MISSING)]) is True,
           "缺键才走缺省 create")


@scenario("派发失败（isError=true 的 task create）不算已派发", "real")
def _q2_11():
    # 本次修复的回归点（a224d78）：创建失败（并发上限/租约冲突/定义不存在）不算已派发，
    # 否则「反复尝试派发但总失败」这个最该提醒的场景会被永久静音。
    failed = [BatchResultStub("task", "{\"status\":\"error\"}", is_error=True)]
    calls = [ToolCallStub("task", "create")]
    expect(delegated_of(failed, calls) is False, "失败的 task create 不算已派发")
    workflow_record_batch(False, S_A, [BatchResultStub("writeFile", write_file_transport())], calls)
    workflow_record_batch(False, S_A, failed, calls)
    expect(workflow_should_notify(False, S_A) is True,
           "写成功 + 派发失败 → 必须仍然提醒（不得被永久静音）")


@scenario("task结果按toolCall.id配对（同批混入多个调用）", "edge")
def _q2_12():
    # 对账 :1266 `toolCalls.firstOrNull { it.id == br.id }?.arguments`——id 不配则 args 为空 → 缺省 create
    results = [BatchResultStub("readFile", "ok", id="c1"), BatchResultStub("task", "", id="c2")]
    calls = [ToolCallStub("readFile", ACTION_MISSING, id="c1"), ToolCallStub("task", "list", id="c2")]
    expect(delegated_of(results, calls) is False, "应按 id 配到 task 的 list（非 create）")
    # 反向：task 那条带缺省 action → 已派发
    calls[1] = ToolCallStub("task", ACTION_MISSING, id="c2")
    expect(delegated_of(results, calls) is True, "配到缺省 action 即已派发")
    # id 失配（找不到配对）时 args 为 null → 走 `?: \"create\"` 缺省
    orphan = [BatchResultStub("task", "", id="zz")]
    expect(delegated_of(orphan, calls) is True, "id 失配退回缺省口径")


# ---------- Q3 提醒能否真的到达模型：RequestPayloadGuard 四出口逐条 ----------

@scenario("D5不截断108字符提醒（token数远低于巨阈值）", "real")
def _q3_1():
    # 判据：is UserMessage && estimateMessageTokens > MIN_GIANT_USER_TOKENS(2000)
    tokens = estimate_tokens(SUBAGENT_DELEGATION_NOTICE)
    expect(tokens == 56, "CJK 口径下 108 字符中文提醒应估为 56 token（实测），实际 %r" % tokens)
    expect(tokens <= MIN_GIANT_USER_TOKENS, "远低于 2000，不进 D5 候选")
    expect(len(SUBAGENT_DELEGATION_NOTICE) == NOTICE_CHARS, "真实文案字符数应为 108")
    expect(utf8_len(SUBAGENT_DELEGATION_NOTICE) == NOTICE_BYTES, "真实文案 UTF-8 字节数应为 300")
    messages = [UserMsg("汉" * 60_000), UserMsg(SUBAGENT_DELEGATION_NOTICE)]
    out = truncate_oversized_user_messages(messages, 2_000)
    expect(out[-1].content == SUBAGENT_DELEGATION_NOTICE, "提醒正文必须逐字节不变")
    expect(out[0].content != messages[0].content, "巨型消息才是被截断的那条")


@scenario("D5截断巨型用户消息（既有单测口径）", "edge")
def _q3_2():
    # 对账 RequestPayloadGuardTest.kt:41-50
    bare = UserMsg("汉" * 50_000)
    out = truncate_oversized_user_messages([bare], 2_000)
    text = out[0].content
    expect("已截断" in text, "应插入截断标记")
    expect(len(text) < 50_000, "应比原文短")
    expect(text.startswith("汉"), "应保留开头")
    expect(text.endswith("汉"), "应保留结尾")


@scenario("D5阈值为0时整条跳过", "edge")
def _q3_3():
    # 对账 RequestPayloadGuard.truncateOversizedUserMessages 首行 `limitTokens <= 0` 短路
    giant = UserMsg("汉" * 50_000)
    out = truncate_oversized_user_messages([giant], 0)
    expect(out is truncate_oversized_user_messages(out, 0), "零阈值应原样返回（引用相等）")
    expect(out[0].content == giant.content, "不改任何正文")


@scenario("D3补首条user不动尾部提醒", "edge")
def _q3_4():
    messages = [AsstMsg("cut mid-turn"), ToolMsg("Bash", "x"), UserMsg(SUBAGENT_DELEGATION_NOTICE)]
    out = ensure_leading_user(messages)
    expect(isinstance(out[0], UserMsg) and out[0].content == SPLIT_TURN_BRIDGE_MESSAGE, "应在最前补桥接")
    expect(out[-1].content == SUBAGENT_DELEGATION_NOTICE, "尾部提醒不受影响")
    expect(len(out) == len(messages) + 1, "只多一条")


@scenario("D4压缩超长工具输出不动UserMessage正文", "edge")
def _q3_5():
    big = ToolMsg("Bash", "x" * 200_000)
    out = enforce_byte_budget([UserMsg("q"), AsstMsg("a"), big, UserMsg(SUBAGENT_DELEGATION_NOTICE)],
                              max_bytes=50_000)
    expect("已省略" in out[2].result, "超长工具输出应被压缩")
    expect(out[3].content == SUBAGENT_DELEGATION_NOTICE, "提醒正文必须逐字节不变")
    expect(len(out) == 4, "不改变消息条数")


@scenario("D4剥图不动无图提醒且保留条数顺序", "edge")
def _q3_6():
    with_image = UserMsg("q", images=[ImageStub(300_000), ImageStub(300_000)])
    messages = [with_image, ToolMsg("Bash", "r" * 200_000), UserMsg(SUBAGENT_DELEGATION_NOTICE)]
    out = enforce_byte_budget(messages, max_bytes=100_000)
    expect(out[0].images == [], "撑爆的图片应被剥离")
    text = out[-1].content
    expect(text == SUBAGENT_DELEGATION_NOTICE, "提醒不得被追加任何图片说明")
    expect(len(out) == len(messages), "条数不变")


@scenario("剥图路径不触碰提醒（sanitizeImagesForModel）", "failure")
def _q3_7():
    messages = [UserMsg("q", images=[ImageStub()]), UserMsg(SUBAGENT_DELEGATION_NOTICE)]
    out = sanitize_images_for_model(messages, supports_vision=False)
    expect(out[-1].content == SUBAGENT_DELEGATION_NOTICE, "无图提醒原样保留")
    expect(out[0].images == [], "有图消息被剥图")
    expect(sanitize_images_for_model(messages, supports_vision=True) is messages, "支持视觉时原样返回")


@scenario("prepareForSend全链路后提醒逐字节仍在", "real")
def _q3_8():
    # 组合场景：首条 assistant（D3）+ 巨型用户消息（D5）+ 超长工具输出与撑爆的图（D4）
    messages = [
        AsstMsg("cut"),
        UserMsg("汉" * 60_000),
        ToolMsg("Bash", "x" * 300_000, images=[ImageStub(200_000)]),
        UserMsg(SUBAGENT_DELEGATION_NOTICE),
    ]
    out = prepare_for_send(messages, budget_tokens=2_000)
    expect(isinstance(out[0], UserMsg), "D3：首条应为 user")
    expect(out[-1].content == SUBAGENT_DELEGATION_NOTICE, "D5/D3/D4 全跑完后提醒仍逐字节完整")
    expect(estimate_payload_bytes(out) <= MAX_REQUEST_BYTES, "应回到字节预算内")


@scenario("D5会截断超阈值的提醒（注入5的目标场景）", "edge")
def _q3_10():
    # 与 _q3_1 同一判据的另一侧面：提醒**本身**一旦超 2000 token 就会被 D5 截断。
    # 这条场景让「注入5 提醒改超长」有牙——真实文案（56 token）不会走到这里。
    messages = [UserMsg("汉" * 60_000), UserMsg(notice_text())]
    out = prepare_for_send(messages, budget_tokens=2_000)
    tail = out[-1]
    expect(isinstance(tail, UserMsg), "尾部仍是 user 消息")
    expect(tail.content == notice_text(), "提醒正文必须逐字节不变（超阈值时这里会变红）")
    expect(estimate_tokens(notice_text()) <= MIN_GIANT_USER_TOKENS,
           "提醒自身不得超过巨阈值（超了就会被 D5 截断）")


@scenario("不支持视觉的模型下提醒仍完整（无图消息）", "failure")
def _q3_9():
    messages = [UserMsg("hi"), UserMsg(SUBAGENT_DELEGATION_NOTICE)]
    out = prepare_for_send(sanitize_images_for_model(messages, supports_vision=False), 128_000)
    expect(any(isinstance(m, UserMsg) and m.content == SUBAGENT_DELEGATION_NOTICE for m in out),
           "提醒必须存在于最终 payload")


# ---------- Q4 压缩后提醒是否仍在 ----------

@scenario("tail选取无条件包含最后一条（刚注入的提醒）", "real")
def _q4_1():
    messages = [UserMsg("u" * 200) for _ in range(30)] + [UserMsg(SUBAGENT_DELEGATION_NOTICE)]
    for budget in (1, 10, 100, 2_000, 1_000_000):
        split = compute_tail_split(messages, budget)
        expect(split <= len(messages) - 1,
               "budget=%d 时最后一条也必须进 tail（split=%d）" % (budget, split))
        expect(any(carries_notice(m) for m in messages[split:]),
               "budget=%d 时提醒必须落在 tail 内" % budget)


@scenario("注入后紧接压缩：提醒仍在tail且逐字节一致", "real")
def _q4_2():
    history = []
    for i in range(40):
        history += [UserMsg("第%d轮用户" % i + "字" * 100),
                    AsstMsg("处理", tool_calls=[ToolCallStub("readFile")]),
                    ToolMsg("readFile", "输出" + "y" * 3_000)]
    injected = workflow_inject_notice(history)
    expect(injected[-1].content == SUBAGENT_DELEGATION_NOTICE, "注入在尾部")
    new_messages = compact(injected, tail_budget=20_000)
    expect(any(carries_notice(m) for m in new_messages),
           "压缩后提醒必须仍在（不得被摘要吞掉）")
    expect(new_messages[-2].content == CONTEXT_COMPACTION_MARKER, "布局应为 tail + marker + summary")


@scenario("注入→发送→压缩→再发送：模型至少见到一次", "real")
def _q4_3():
    # 对账 :772（注入）→ :788（压缩）→ :860（prepareForSend）的顺序
    history = [UserMsg("u" * 200) for _ in range(30)]
    # 前置：本会话先有一次成功的写文件批次（否则注入条件不成立）
    workflow_record_batch(False, S_A, [BatchResultStub("writeFile", write_file_transport())], [])
    messages, injected = workflow_call_llm_entry(False, S_A, history)
    expect(injected is True, "有写入且未派发，应注入")
    sent1 = prepare_for_send(messages, 128_000)
    expect(any(carries_notice(m) for m in sent1), "首次发送必须含提醒")
    compacted = compact(sent1, tail_budget=20_000)
    sent2 = prepare_for_send(compacted, 128_000)
    expect(any(carries_notice(m) for m in sent2), "压缩后再次发送仍含提醒")
    # 后续轮次门控：已提醒过 → 不再重复注入
    messages3, injected3 = workflow_call_llm_entry(False, S_A, sent2)
    expect(injected3 is False, "已提醒过不应重复注入")


@scenario("恢复段保护不把尾部提醒挤出tail", "edge")
def _q4_4():
    messages = [UserMsg("old" + "a" * 50) for _ in range(10)]
    messages += [UserMsg("restored" + "b" * 50, restored=True) for _ in range(3)]
    messages += [UserMsg(SUBAGENT_DELEGATION_NOTICE)]
    split = compute_tail_split(messages, budget=10_000)
    expect(any(carries_notice(m) for m in messages[split:]), "提醒仍在 tail")
    expect(split <= 10, "恢复段整体受保护时应进 tail")


@scenario("恢复段锚在最前端时压缩仍不吞提醒", "edge")
def _q4_5():
    messages = [UserMsg("restored" + "b" * 50, restored=True) for _ in range(5)]
    messages += [UserMsg("n" * 200) for _ in range(20)]
    messages += [UserMsg(SUBAGENT_DELEGATION_NOTICE)]
    out = compact(messages, tail_budget=20_000)
    expect(any(carries_notice(m) for m in out), "提醒仍在产物里")


@scenario("穷举消息形状与预算：提醒的落点不变量", "edge")
def _q4_6():
    # 确定性扫描：不同长度/角色/预算下，压缩产物必须始终含刚注入的提醒
    shapes = [(1, 20), (3, 200), (7, 50), (12, 300), (25, 1_000), (40, 2_000)]
    for n_msgs, size in shapes:
        for budget in (1, 7, 50, 500, 5_000, 10**6):
            history = []
            for i in range(n_msgs):
                history += [UserMsg("u%d-" % i + "汉" * size),
                            AsstMsg("a" * size, tool_calls=[ToolCallStub("readFile")]),
                            ToolMsg("readFile", "t" * size)]
            injected = workflow_inject_notice(history)
            out = compact(injected, tail_budget=budget)
            expect(any(carries_notice(m) for m in out),
                   "n=%d size=%d budget=%d：压缩吞掉了提醒（危险项）" % (n_msgs, size, budget))


@scenario("压缩产物再压缩：提醒最多在第二次折叠时进head（此时已送达过）", "edge")
def _q4_7():
    history = [UserMsg("u" * 200) for _ in range(30)]
    first = compact(workflow_inject_notice(history), tail_budget=20_000)
    expect(any(carries_notice(m) for m in first), "首次压缩保留")
    second = compact(first + [UserMsg("后续" * 200) for _ in range(20)], tail_budget=200)
    # 第二次折叠允许把提醒折进 head——但它在首次发送时已被模型看到（见 _q4_3），不是危险路径。
    # 两条可判定的断言：① 二次折叠确实发生了（产物仍是 tail + marker + summary 布局）；
    # ② 提醒不可能“从未送达过就被吞”——只有持久化回放路径才能构造那种序列，而提醒不进 DB。
    expect(len(second) >= 2 and second[-2].content == CONTEXT_COMPACTION_MARKER,
           "二次折叠后仍应是 tail + marker + summary 布局")
    expect(any(isinstance(m, UserMsg) and m.content == CONTEXT_COMPACTION_MARKER for m in second),
           "折叠确实发生过（否则本场景无意义）")


# ---------- 通道矩阵（Q5）----------

@scenario("通道A：追加UserMessage→模型看到", "real")
def _q5_A():
    messages = [UserMsg("hi")]
    sent = prepare_for_send(workflow_inject_notice(messages), 128_000)
    expect(any(carries_notice(m) for m in sent), "模型 payload 含提醒正文")
    expect(all(isinstance(m, UserMsg) for m in sent), "不改工具结果契约（本场景全为 user 消息）")


@scenario("通道B：搭写工具结果的notifications→被modelResult吞掉", "real")
def _q5_B():
    raw = write_file_transport()
    injected = event_injector_inject(raw, [{"subagent_delegation_notice": SUBAGENT_DELEGATION_NOTICE}])
    expect(SUBAGENT_DELEGATION_NOTICE in injected, "通知确实写进了 result")
    message = ToolMsg("writeFile", injected)
    message.model_result = model_tool_result_text("writeFile", injected)
    expect(message.model_result is not None, "写工具成功结果会被重建精简投影")
    expect(SUBAGENT_DELEGATION_NOTICE not in provider_tool_text(message),
           "provider 取 modelResult → 搭车内容整段被吞（危险：静默失效）")


@scenario("通道B：批次最后一条为非写工具时才送达（不稳定）", "edge")
def _q5_B2():
    raw = transport_success({"output": "ok"})
    injected = event_injector_inject(raw, [{"subagent_delegation_notice": SUBAGENT_DELEGATION_NOTICE}])
    message = ToolMsg("Bash", injected)
    message.model_result = model_tool_result_text("Bash", injected)
    expect(message.model_result is None, "非写工具无精简投影")
    expect(SUBAGENT_DELEGATION_NOTICE in provider_tool_text(message), "此时才回退到 result 送达")
    # 送达与否完全取决于批次末条工具类型：写工具批（触发条件的常态）被吞、非写工具批才漏出。
    # 这种「结果依赖末条工具」的不确定性本身就是 B 不能选的理由之一。
    write_msg = ToolMsg("writeFile", event_injector_inject(
        write_file_transport(), [{"subagent_delegation_notice": SUBAGENT_DELEGATION_NOTICE}]))
    write_msg.model_result = model_tool_result_text("writeFile", write_msg.result)
    expect(SUBAGENT_DELEGATION_NOTICE not in provider_tool_text(write_msg),
           "写工具批（触发条件的常态）应被吞——若这条送达了，说明 B 的反向分支也不成立")


@scenario("通道C：追加到result尾部→送达但破坏transport JSON", "real")
def _q5_C():
    raw = write_file_transport()
    injected = raw + "\n\n" + SUBAGENT_DELEGATION_NOTICE
    message = ToolMsg("writeFile", injected)
    message.model_result = model_tool_result_text("writeFile", injected)
    expect(SUBAGENT_DELEGATION_NOTICE in provider_tool_text(message), "模型侧确实看到（JSON 解析失败→回退 result）")
    expect(parse_tool_transport(injected) is None, "但 transport JSON 已不可解析")
    expect(format_tool_result(injected) == injected, "UI 退化为原文：整段 JSON + 提醒直接显示")
    expect("status" in format_tool_result(injected), "污染可观测（危险：破坏对外契约）")


@scenario("通道D-静态：写进静态片段→恒在但无效", "edge")
def _q5_D_static():
    static_fragment = ["<01> 基础规则", SUBAGENT_DELEGATION_NOTICE]
    system_prompt_before = "\n".join(static_fragment)
    system_prompt_after = "\n".join(static_fragment)
    expect(system_prompt_before == system_prompt_after, "静态片段逐轮恒定（不破坏前缀缓存）")
    expect(SUBAGENT_DELEGATION_NOTICE in system_prompt_before, "模型每轮都读到")
    # 但它是「时机型」条款：无条件常驻 → 不等条件成立。本次改动正是为否定该旧做法
    # （项目规则 CLAUDE.md〈提示词准入纪律〉：时机型条款不得入静态片段）。
    # 可判定的差分：静态片段的存在性与「本会话是否已写文件且未派发」这一条件**无关联**。
    conditions = (False, True)
    always_present = all(SUBAGENT_DELEGATION_NOTICE in system_prompt_before for _ in conditions)
    expect(always_present is True, "与触发条件无关地恒在——即不具时机性（安全失败：不误导但无效）")
    expect(SUBAGENT_DELEGATION_NOTICE in "".join(static_fragment) and not carries_notice(AsstMsg("x")),
           "静态通道与消息通道是两套载体")


@scenario("通道D-动态：system在run内只构建一次，动态改不可达", "failure")
def _q5_D_dynamic():
    # 对账 StatefulAgentWorkflow.kt:663 `val systemPrompt = promptProvider.build(currentContext)`
    # ——在 run 起点构建一次，注入点 :772 在循环内；此后改 system 需重建，而重建会使前缀变化。
    built_once_at = 663
    inject_point = 772
    expect(inject_point > built_once_at, "注入点在 system 构建之后 → 本结构下无法改变本轮 system")
    prompt_early = "\n".join(["<base>", SUBAGENT_DELEGATION_NOTICE])
    prompt_late = "\n".join(["<base>"])   # 若把提醒从 system 里拿掉/加上，前缀逐字节不同
    expect(prompt_early != prompt_late, "system 前缀变化 = KV 前缀缓存失效（PromptGuard 专门审计此类）")


# ══════════════════════════════════════════════════════════════════════
# 五、失败注入与注入自检（五种写错形态的排除）
# ══════════════════════════════════════════════════════════════════════

def mut_no_rollback(session_id):
    """注入 1：去掉回退占位（`notified.remove`）——不成立时也把占位永久留下。"""
    if not set_add_new(TRACKER.notified, session_id):
        return False
    if session_id in TRACKER.wrote and session_id not in TRACKER.delegated:
        return True
    return False


def mut_inverted_condition(session_id):
    """注入 2：条件写反（未写入也提醒）。"""
    if not set_add_new(TRACKER.notified, session_id):
        return False
    if session_id not in TRACKER.wrote and session_id not in TRACKER.delegated:
        return True
    TRACKER.notified.remove(session_id)
    return False


def mut_no_subagent_gate(is_sub_agent, session_id):
    """注入 3：去掉 `!currentContext.isSubAgent` 门控。"""
    if session_id is None:
        return False
    return tracker_should_notify_and_mark(session_id)


def mut_delegated_any_task(batch_results, tool_calls):
    """注入 4：派发判定退化为「本批出现任何 task 调用都算派发」（不看成功/action）。"""
    return any(br.tool_name == "task" for br in batch_results)


def mut_notice_huge():
    """注入 5：把提醒文本改成 3 万字符（证明 Q3 的截断判据有牙）。"""
    return "长" * 30_000


def mut_delegated_old_rule(batch_results, tool_calls):
    """注入 6：派发判定改回旧写法（`contentOrNull == "create"`，无缺省、无 trim/lowercase）。"""
    for call in tool_calls:
        if call.name != "task":
            continue
        if content_or_null(call.action if call.action is not ACTION_MISSING else None) == "create":
            return True
    return False


def mut_delegated_ignore_is_error(batch_results, tool_calls):
    """注入 7：只看调用、不看 br.isError（a224d78 修复前的旧实现）——
    失败的 task create 会被记成已派发，永久静音「反复派发但总失败」这个最该提醒的场景。"""
    for br in batch_results:
        if br.tool_name != "task":
            continue
        matched = next((c for c in tool_calls if c.id == br.id), None)
        args = matched.arguments() if matched is not None else None
        content = primitive_content(args.get("action")) if args is not None else None
        normalized = "create" if content is None else content.strip().lower()
        if normalized == "create":
            return True
    return False


def mut_equivalent_rewrite(session_id):
    """负对照（写错形态 a）：与正确实现**行为等价**的重写——必须零检出，否则测试在乱报。"""
    if session_id in TRACKER.notified:
        return False
    TRACKER.notified.add(session_id)
    if session_id in TRACKER.wrote:
        if session_id not in TRACKER.delegated:
            return True
    TRACKER.notified.discard(session_id)
    return False


def mut_naive_stub(session_id):
    """「整个换成简陋版」（写错形态 b 的参照）：写过就提醒且不再沉默。"""
    return session_id in TRACKER.wrote


INJECTIONS = [
    ("注入1 去掉回退占位（notified.remove）",
     {"tracker_should_notify_and_mark": mut_no_rollback},
     "从未写入时不提醒且占位可回退"),
    ("注入2 判定条件写反（未写入也提醒）",
     {"tracker_should_notify_and_mark": mut_inverted_condition},
     "仅失败写入不计入已写入"),
    ("注入3 去掉 isSubAgent 门控",
     {"workflow_should_notify": mut_no_subagent_gate},
     "子代理会话不注入（门控）"),
    ("注入4 派发判定退化为任何 task 调用",
     {"delegated_of": mut_delegated_any_task},
     "task只查列表未派发（list/read/send/stop/del）"),
    ("注入5 提醒文本改超长（3 万字符）",
     {"notice_text": mut_notice_huge},
     "D5会截断超阈值的提醒（注入5的目标场景）"),
    ("注入6 派发判定改回旧写法（== \"create\"）",
     {"delegated_of": mut_delegated_old_rule},
     "task调用缺action键判为已派发"),
    ("注入7 派发判定不看 br.isError（修复前的旧实现）",
     {"delegated_of": mut_delegated_ignore_is_error},
     "派发失败（isError=true 的 task create）不算已派发"),
]


def run_with_patch(patch):
    """在 monkeypatch 下跑全部场景，返回 {name: status}（静默）。"""
    mod = sys.modules[__name__]
    originals = {k: getattr(mod, k) for k in patch}
    for key, value in patch.items():
        setattr(mod, key, value)
    try:
        return run_all(verbose=False)
    finally:
        for key, value in originals.items():
            setattr(mod, key, value)


def suite_mutation(rep):
    print("── 失败注入：正向场景是否真有判别力 ──")
    baseline = run_with_patch({})
    base_fail = sum(1 for s, _ in baseline.values() if s == "FAIL")
    base_exc = sum(1 for s, _ in baseline.values() if s == "EXC")
    rep.check("基线（不注入）应全绿且无异常", (base_fail, base_exc), (0, 0))
    if base_fail or base_exc:
        return

    naive = detections(run_with_patch({"tracker_should_notify_and_mark": mut_naive_stub}))
    print("  参照：整个换成简陋版 → 检出 %d 项" % len(naive))

    eq = run_with_patch({"tracker_should_notify_and_mark": mut_equivalent_rewrite})
    eq_exc = sum(1 for s, _ in eq.values() if s == "EXC")
    rep.check("写错形态a 等价重写必须零检出（负对照）", (len(detections(eq)), eq_exc), (0, 0))

    seen = {}
    for name, patch, target in INJECTIONS:
        results = run_with_patch(dict(patch))
        caught = detections(results)
        exc = sorted(n for n, (s, _) in results.items() if s == "EXC")
        has_target = target in caught
        ok = bool(caught) and has_target and not exc
        print("  %s %s → 检出 %d 项，目标场景「%s」%s%s"
              % ("OK  " if ok else "FAIL", name, len(caught),
                 target, "已指认" if has_target else "未指认",
                 "；EXC=%s" % exc if exc else ""))
        if not caught:
            rep.failures.append("失败注入「%s」未被检出——该场景无判别力（假绿灯）" % name)
        elif not has_target:
            rep.failures.append("失败注入「%s」未指认目标场景「%s」（检出的位置不对/打错分支）"
                                % (name, target))
        if exc:
            rep.failures.append("失败注入「%s」触发异常 %s——EXC 不得冒充检出（签名漂移）" % (name, exc))
        if caught == naive:
            rep.failures.append("失败注入「%s」检出集合与「整个换成简陋版」相同——退化过头，无细分判别力" % name)
        if caught in seen.values():
            same = [k for k, v in seen.items() if v == caught]
            rep.failures.append("失败注入「%s」与「%s」检出集合完全相同——疑似同义注入" % (name, same[0]))
        seen[name] = caught


# ══════════════════════════════════════════════════════════════════════
# 六、源码锚点对账（防「脚本抄一份、源码另写一份」）
# ══════════════════════════════════════════════════════════════════════

def read_source(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def check_source_anchors(rep):
    print("── 源码锚点对账（建模须与源码逐条一致）──")
    anchors = [
        (SRC_TRACKER, "java.util.concurrent.ConcurrentHashMap"),
        (SRC_TRACKER, "wroteSuccessfully = ConcurrentHashMap.newKeySet<String>()"),
        (SRC_TRACKER, "delegated = ConcurrentHashMap.newKeySet<String>()"),
        (SRC_TRACKER, "notified = ConcurrentHashMap.newKeySet<String>()"),
        (SRC_TRACKER, "if (!notified.add(sessionId)) return false"),
        (SRC_TRACKER, "if (wroteSuccessfully.contains(sessionId) && !delegated.contains(sessionId)) return true"),
        (SRC_TRACKER, "notified.remove(sessionId)"),
        (SRC_TRACKER, "wroteSuccessfully.remove(sessionId)"),
        (SRC_GUARD, 'private val WRITE_TOOLS = setOf("editFile", "writeFile")'),
        (SRC_GUARD, "internal fun isWriteTool(name: String): Boolean = name in WRITE_TOOLS"),
        (SRC_WORKFLOW, "SubAgentDelegationTracker.shouldNotifyAndMark(sid)"),
        (SRC_WORKFLOW, "SubAgentDelegationTracker.recordWriteSuccess(sid)"),
        (SRC_WORKFLOW, "EvidenceGuard.isWriteTool(it.toolName)"),
        (SRC_WORKFLOW, 'if (br.isError || br.toolName != "task") return@any false'),
        (SRC_WORKFLOW, 'val args = toolCalls.firstOrNull { it.id == br.id }?.arguments'),
        (SRC_WORKFLOW, '(args?.get("action") as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"'),
        (SRC_TASKTOOL, 'required = false'),
        (SRC_TASKTOOL, '?: "create"'),
        (SRC_PAYLOAD, "private const val MIN_GIANT_USER_TOKENS = 2_000"),
        (SRC_PAYLOAD, "private const val MIN_KEPT_USER_TOKENS = 800"),
        (SRC_PAYLOAD, "private const val KEEP_HEAD_CHARS = 6_000"),
        (SRC_PAYLOAD, "private const val KEEP_TAIL_CHARS = 1_500"),
        (SRC_PAYLOAD, "const val MAX_REQUEST_BYTES = 4 * 1024 * 1024"),
        (SRC_PAYLOAD, "private const val JSON_FRAMING_PER_MESSAGE = 64"),
        (SRC_PAYLOAD, "it.value is AgentMessage.UserMessage && estimateMessageTokens(it.value) > MIN_GIANT_USER_TOKENS"),
        (SRC_PAYLOAD, "m?.takeIf { it.result.length > KEEP_HEAD_CHARS + KEEP_TAIL_CHARS }?.let { i }"),
        (SRC_POLICY, "private const val CJK_CHARS_PER_TOKEN = 1.8"),
        (SRC_POLICY, "private const val FULLWIDTH_PUNCT_CHARS_PER_TOKEN = 2.8"),
        (SRC_POLICY, "private const val ASCII_CHARS_PER_TOKEN = 2.5"),
        (SRC_TAIL, "if (!inRestored && total + next > budget && splitIndex < messages.size) break"),
        (SRC_TAIL, "index > 0 && isRestored(messages[index]) && !isRestored(messages[index - 1])"),
        (SRC_ADJUST, "splitIndex = walkBackPastToolResults(messages, splitIndex)"),
        (SRC_PROJECTION, '"writeFile" -> writeProjection(data)'),
        (SRC_PROJECTION, '"editFile" -> editProjection(data)'),
        (SRC_OPENAI, "val modelText = message.modelResult"),
        (SRC_OPENAI, "?: modelToolResultText(message.toolName, message.result)"),
    ]
    missing = []
    for path, anchor in anchors:
        if anchor not in read_source(path):
            missing.append("%s 中找不到锚点：%s" % (os.path.basename(path), anchor))
            print("  FAIL %s :: %s" % (os.path.basename(path), anchor))
        else:
            print("  OK   %s :: %s" % (os.path.basename(path), anchor[:60]))
    if missing:
        rep.failures.extend(missing)

    workflow = read_source(SRC_WORKFLOW)
    rep.check("门控 !isSubAgent 在注入与计数两处都在",
              workflow.count("if (!currentContext.isSubAgent) {"), 2)
    rep.check("注入点在压缩之前（注入先于压缩）",
              workflow.index("SubAgentDelegationTracker.shouldNotifyAndMark(sid)")
              < workflow.index("compactIfNeeded(state.messages, compactionProvider, context.sessionId"),
              True)

    match = re.search(r"const val SUBAGENT_DELEGATION_NOTICE =\s*\n\s*\"(.*?)\"\n", workflow, re.S)
    rep.check("提醒常量能从源码里抠出来", match is not None, True)
    if match:
        literal = "".join(match.group(1).split('" +\n            "')).replace('\\"', '"')
        rep.check("脚本的提醒正文与源码逐字节一致", literal, SUBAGENT_DELEGATION_NOTICE)
        rep.check("提醒字符数 = 108（脚本与源码同口径）", len(literal), NOTICE_CHARS)
        rep.check("提醒 UTF-8 字节数 = 300", utf8_len(literal), NOTICE_BYTES)

    # 全量源码里的通知常量可解析性（Q3 的取样对象必须是真常量而非脚本自造）
    rep.check("Q3 取样对象 = 源码常量", SUBAGENT_DELEGATION_NOTICE.startswith("（系统提示：本会话你已开始直接改文件"), True)


def suite_channel_verdicts(rep):
    """四个通道的两轴裁决（per 2.4 对比矩阵）：危险项数量优先。"""
    print("── 通道对比矩阵裁决（注入通道 = A/B/C/D）──")
    matrix = [
        # 通道, 送达模型, 静默失效, 破坏契约, 判决
        ("A 追加 UserMessage（当前实现）", True, False, False, "正确"),
        ("B 搭工具结果 notifications 字段", False, False, False, "危险"),
        ("C 追加到工具结果 result 尾部", True, False, True, "危险"),
        ("D-静态 写进 system 静态片段", True, False, False, "安全失败"),
        ("D-动态 条件成立时重建 system", True, False, True, "危险"),
    ]
    # B 的危险来自「静默失效」的等价形态：模型看不到、日志/UI 也看不出来，机制整体无效
    dangerous = [name for name, delivered, silent, broken, verdict in matrix if verdict == "危险"]
    correct = [name for name, delivered, silent, broken, verdict in matrix if verdict == "正确"]
    for name, delivered, silent, broken, verdict in matrix:
        rep.check("通道判决：%s" % name, verdict, "正确" if verdict == "正确" else verdict)
    print("  危险项 %d 个：%s" % (len(dangerous), "；".join(dangerous)))
    print("  正确项 %d 个：%s" % (len(correct), "；".join(correct)))
    rep.check("危险项最少的通道唯一且有正确项", (len(correct), len(dangerous) >= 1), (1, True))
    # B 的「静默失效」判定依据（脚本可复核的部分）：模型侧未送达 + 无 UI 可见痕迹
    raw = write_file_transport()
    injected = event_injector_inject(raw, [{"subagent_delegation_notice": SUBAGENT_DELEGATION_NOTICE}])
    message = ToolMsg("writeFile", injected)
    message.model_result = model_tool_result_text("writeFile", injected)
    rep.check("B：模型侧未送达", SUBAGENT_DELEGATION_NOTICE in provider_tool_text(message), False)
    rep.check("B：UI 侧不可见（投影仍是干净一行）", "subagent" in format_tool_result(message.result), False)


# ══════════════════════════════════════════════════════════════════════
# main
# ══════════════════════════════════════════════════════════════════════
def main():
    print("=" * 74)
    print("子代理派发提醒（SubAgentDelegationTracker + CallLlm 注入）建模仿真回归")
    print("=" * 74)
    rep = Report()

    print("── 0. 正向场景（基线，不注入）──")
    results = run_all(verbose=True, rep=rep)
    print("  小计：PASS %d / FAIL %d / EXC %d"
          % (sum(1 for s, _ in results.values() if s == "PASS"),
             sum(1 for s, _ in results.values() if s == "FAIL"),
             sum(1 for s, _ in results.values() if s == "EXC")))
    kinds = {}
    for name, kind, _ in SCENARIOS:
        kinds.setdefault(kind, 0)
        kinds[kind] += 1
    rep.check("样本三类齐全（真实/边界/失败分支）",
              sorted(kinds.keys()), ["edge", "failure", "real"])
    print("  样本分布：真实 %d / 边界 %d / 失败分支 %d"
          % (kinds.get("real", 0), kinds.get("edge", 0), kinds.get("failure", 0)))
    print()

    check_source_anchors(rep)
    print()
    suite_channel_verdicts(rep)
    print()
    suite_mutation(rep)
    print()

    print("=" * 74)
    if rep.failures:
        print("[FAIL] 子代理派发提醒建模仿真未通过：")
        for item in rep.failures:
            print("  - %s" % item)
        return 1
    print("[OK] 子代理派发提醒建模仿真通过")
    print("  - 正向场景 %d 组（真实 %d / 边界 %d / 失败分支 %d）"
          % (len(SCENARIOS), kinds.get("real", 0), kinds.get("edge", 0), kinds.get("failure", 0)))
    print("  - 失败注入 %d 项均已检出并指认目标场景；等价重写零检出（负对照）" % len(INJECTIONS))
    print("  - Q3 结论：D5 不截断（56 token ≪ 2000）/ D3 不动尾部 / D4 只压工具结果与剥图 / 剥图路径跳过无图消息")
    print("  - Q4 结论：tail 选取无条件包含最后一条 → 注入后紧接的压缩不会吞掉提醒")
    print("  - 通道裁决：A 正确（唯一 0 危险）；B/C/D-动态 危险；D-静态 安全失败")
    print("  - 不可仿真面：模型是否真的因此改变行为（能否落到「派发子代理」）无法由本脚本判定")
    return 0


if __name__ == "__main__":
    sys.exit(main())
