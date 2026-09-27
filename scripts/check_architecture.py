#!/usr/bin/env python3
"""架构守护：把项目里的硬约定从「靠自觉」变成「构建期硬拦」。

挂在 app:preBuild 上（见 app/build.gradle.kts），违规直接 fail，避免约定随迭代腐化。
纯文本扫描、零依赖，耗时不到 1 秒。

设计原则：**只拦增量，不追溯存量**。项目历史代码里已有大量不符合约定的写法，
若一刀切会让构建立即失败；故存量文件在白名单里显式豁免，新文件一旦触规即报错。
白名单只应减少，不该增加——新增豁免等同于放宽约定，须在代码评审里说明理由。

当前规则（对应 CLAUDE.md「优先使用项目自定义组件」「资产同步」等约定）：
  R1 原生 M3 Switch      只允许出现在 core/ui/AppSwitch.kt
  R2 原生 M3 ModalBottomSheet 只允许出现在 core/ui/AdaptiveModalBottomSheet.kt
  R3 feature/*/domain/** 不得新增直连 Room DAO（应经 Repository 端口）
  R4 提示词片段 assets/prompts/**：占位符须为已知变量、不得含逐轮变化内容、
     顶层片段须已在 BASE_FRAGMENTS 登记、agent/ 片段须被代码引用
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA_ROOT = ROOT / "app" / "src" / "main" / "java"

# ── R1 / R2：唯一允许出现原生组件的位置（相对 JAVA_ROOT）──────────────────
SWITCH_ONLY = "com/aicode/core/ui/AppSwitch.kt"
SHEET_ONLY = "com/aicode/core/ui/AdaptiveModalBottomSheet.kt"

# ── R3：存量豁免名单（相对 JAVA_ROOT）────────────────────────────────────
# 这些文件在引入本守护前就已直连 Room DAO。新文件不得加入本名单。
DAO_ALLOWLIST = {
    "com/aicode/feature/agent/domain/checkpoint/CheckpointManager.kt",
    "com/aicode/feature/agent/domain/session/MessagePersistenceUseCase.kt",
    "com/aicode/feature/agent/domain/session/SessionUseCase.kt",
    "com/aicode/feature/agent/domain/tool/file/ImageTools.kt",
    "com/aicode/feature/agent/domain/tool/mode/PlanApprovalManager.kt",
    "com/aicode/feature/agent/domain/tool/mode/PlanModeTool.kt",
    "com/aicode/feature/agent/domain/tool/subagent/TaskTool.kt",
    "com/aicode/feature/agent/domain/tool/todo/TodoTool.kt",
    "com/aicode/feature/agent/domain/workflow/ContextCompactor.kt",
    "com/aicode/feature/agent/domain/workflow/DurableTaskRepository.kt",
    "com/aicode/feature/agent/domain/workflow/StatefulAgentWorkflow.kt",
    "com/aicode/feature/settings/domain/service/StorageUsageScanner.kt",
    "com/aicode/feature/workspace/domain/repository/RemoteRepository.kt",
}

RE_NATIVE_SWITCH = re.compile(r"^import\s+androidx\.compose\.material3\.Switch\s*$", re.M)
RE_NATIVE_SHEET = re.compile(r"^import\s+androidx\.compose\.material3\.ModalBottomSheet\s*$", re.M)
RE_DAO_IMPORT = re.compile(r"^import\s+com\.aicode\.feature\.\w+\.data\.local\.dao\.", re.M)

# ── R4：提示词片段契约（assets/prompts/**）───────────────────────────────
# 片段写错时运行期全是静默的：拼错的占位符会被原样注入模型、破坏稳定性的内容会无声打掉
# KV 前缀缓存（模型侧不会报错、没人会去看日志）、片段压根没被加载则完全无感。
# 故全部提到构建期拦。
PROMPTS_ROOT = ROOT / "app" / "src" / "main" / "assets" / "prompts"
SYSTEM_PROMPT_KT = "com/aicode/feature/agent/domain/prompt/SystemPromptProvider.kt"

# 与 PromptGuard.kt 的 PromptPlaceholderChecker / PromptStabilityAuditor 同源：
# Kotlin 侧是语义事实源，此处在构建期前置同类校验；改规则时两处必须同步。
PROMPT_KNOWN_VARS = {
    "AICODE_SKILLS", "AICODE_MEMORY", "AICODE_SUBAGENTS",
    "AICODE_PROJECT_RULES", "AICODE_WORKSPACE", "AICODE_DATE",
    "INSTRUCTION",
}

PROMPT_UNSTABLE_RULES = [
    ("iso-timestamp", re.compile(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}")),
    ("uuid", re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")),
    ("time-marker", re.compile(r"(?i)\bcurrent[\s_-]*(time|date)\b|\bnow\b|当前时间|当前日期|今天的日期")),
    ("random", re.compile(r"(?i)\brandom\b|随机数|随机值|randomUUID|Math\.random|SecureRandom")),
    ("epoch-millis", re.compile(r"\b1\d{12}\b")),
    ("turn-counter", re.compile(r"(?i)turn[\s_-]?count|iteration[\s_-]?count|第\s*\d+\s*轮|轮次")),
]

RE_PLACEHOLDER = re.compile(r"\{\{([A-Za-z0-9_]+)\}\}")
RE_TOP_FRAGMENT = re.compile(r"^(\d{2})-(.+)\.md$")
RE_REGISTERED_FRAGMENT = re.compile(r'\d+\s+to\s+"([^"]+\.md)"')


def check_native_component(violations, rel, text, pattern, allowed_rel, tool_name, replacement):
    """检查原生 Material3 组件是否只出现在被允许的统一封装文件里。"""
    if pattern.search(text) and rel != allowed_rel:
        violations.append(
            f"{rel}: 直接使用原生 {tool_name}（请改用 {replacement}，见 CLAUDE.md「优先使用项目自定义组件」）"
        )


def check_prompts(violations):
    """R4：提示词片段的静态契约（占位符 / 前缀缓存稳定性 / 是否真会被加载）。"""
    if not PROMPTS_ROOT.is_dir():
        violations.append(f"提示词目录不存在: {PROMPTS_ROOT}")
        return

    provider = JAVA_ROOT / SYSTEM_PROMPT_KT
    if not provider.is_file():
        violations.append(f"找不到 {SYSTEM_PROMPT_KT}，无法核对片段登记")
        return
    registered = set(RE_REGISTERED_FRAGMENT.findall(provider.read_text(encoding="utf-8", errors="replace")))

    fragments = sorted(PROMPTS_ROOT.rglob("*.md"))
    for path in fragments:
        rel = path.relative_to(PROMPTS_ROOT).as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")

        for name in sorted({n for n in RE_PLACEHOLDER.findall(text) if n not in PROMPT_KNOWN_VARS}):
            violations.append(
                f"prompts/{rel}: 未知占位符 {{{{{name}}}}}——拼错的名字会被原样注入模型，"
                f"只允许已知 AICODE_* 变量与保留变量（如 INSTRUCTION）"
            )

        for rule, pattern in PROMPT_UNSTABLE_RULES:
            found = pattern.search(text)
            if found:
                violations.append(
                    f"prompts/{rel}: 含逐轮变化的动态内容（{rule}: {found.group(0)}）——"
                    f"system prompt 须逐轮字节一致，否则 KV 前缀缓存失效"
                )

        if "/" not in rel:
            if not RE_TOP_FRAGMENT.match(rel):
                violations.append(
                    f"prompts/{rel}: 顶层片段须命名为 <两位数字>-<名称>.md，否则不会被加载"
                )
            elif rel not in registered:
                violations.append(
                    f"prompts/{rel}: 未在 SystemPromptProvider.BASE_FRAGMENTS 登记，文件不会被加载"
                )

    kotlin_corpus = "\n".join(
        p.read_text(encoding="utf-8", errors="replace") for p in sorted(JAVA_ROOT.rglob("*.kt"))
    )
    for path in fragments:
        rel = path.relative_to(PROMPTS_ROOT).as_posix()
        if rel.startswith("agent/") and rel not in kotlin_corpus:
            violations.append(f"prompts/{rel}: 未被任何代码引用，按需片段不会被加载")


def main() -> int:
    if not JAVA_ROOT.is_dir():
        print(f"[FAIL] 源码目录不存在: {JAVA_ROOT}")
        return 1

    violations = []
    kotlin_files = sorted(JAVA_ROOT.rglob("*.kt"))

    for path in kotlin_files:
        rel = path.relative_to(JAVA_ROOT).as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")

        # R1 / R2
        check_native_component(
            violations, rel, text, RE_NATIVE_SWITCH, SWITCH_ONLY, "Switch", "core/ui/AppSwitch.kt 的 AppSwitch"
        )
        check_native_component(
            violations, rel, text, RE_NATIVE_SHEET, SHEET_ONLY, "ModalBottomSheet",
            "core/ui/AdaptiveModalBottomSheet.kt",
        )

        # R3：只看 feature/*/domain/**，且豁免存量名单
        if "/domain/" in f"/{rel}" and rel.startswith("com/aicode/feature/"):
            if rel not in DAO_ALLOWLIST and RE_DAO_IMPORT.search(text):
                violations.append(
                    f"{rel}: 直连 Room DAO（应经 Repository 端口；若确需新增豁免，须在评审中说明理由）"
                )

    check_prompts(violations)

    if violations:
        print(f"[FAIL] 架构约定检查未通过，共 {len(violations)} 处：")
        for item in violations:
            print(f"  - {item}")
        print()
        print("规则说明见 scripts/check_architecture.py 头部注释与 CLAUDE.md。")
        return 1

    print("[OK] 架构约定检查通过")
    print(f"  - 扫描 {len(kotlin_files)} 个 Kotlin 文件")
    print("  - 规则：原生 Switch / ModalBottomSheet 禁用，domain 层不得新增直连 DAO，提示词片段契约")
    print(f"  - domain 层存量豁免 {len(DAO_ALLOWLIST)} 个文件（只减不增）")
    prompt_count = len(sorted(PROMPTS_ROOT.rglob("*.md"))) if PROMPTS_ROOT.is_dir() else 0
    print(f"  - 提示词片段契约：扫描 {prompt_count} 个片段")
    return 0


if __name__ == "__main__":
    sys.exit(main())
