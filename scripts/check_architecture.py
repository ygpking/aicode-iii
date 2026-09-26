#!/usr/bin/env python3
"""架构守护：把项目里的硬约定从「靠自觉」变成「构建期硬拦」。

挂在 app:preBuild 上（见 app/build.gradle.kts），违规直接 fail，避免约定随迭代腐化。
纯文本扫描、零依赖，耗时不到 1 秒。

设计原则：**只拦增量，不追溯存量**。项目历史代码里已有大量不符合约定的写法，
若一刀切会让构建立即失败；故存量文件在白名单里显式豁免，新文件一旦触规即报错。
白名单只应减少，不该增加——新增豁免等同于放宽约定，须在代码评审里说明理由。

当前规则（对应 CLAUDE.md「优先使用项目自定义组件」等约定）：
  R1 原生 M3 Switch      只允许出现在 core/ui/AppSwitch.kt
  R2 原生 M3 ModalBottomSheet 只允许出现在 core/ui/AdaptiveModalBottomSheet.kt
  R3 feature/*/domain/** 不得新增直连 Room DAO（应经 Repository 端口）
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


def check_native_component(violations, rel, text, pattern, allowed_rel, tool_name, replacement):
    """检查原生 Material3 组件是否只出现在被允许的统一封装文件里。"""
    if pattern.search(text) and rel != allowed_rel:
        violations.append(
            f"{rel}: 直接使用原生 {tool_name}（请改用 {replacement}，见 CLAUDE.md「优先使用项目自定义组件」）"
        )


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

    if violations:
        print(f"[FAIL] 架构约定检查未通过，共 {len(violations)} 处：")
        for item in violations:
            print(f"  - {item}")
        print()
        print("规则说明见 scripts/check_architecture.py 头部注释与 CLAUDE.md。")
        return 1

    print("[OK] 架构约定检查通过")
    print(f"  - 扫描 {len(kotlin_files)} 个 Kotlin 文件")
    print(f"  - 规则：原生 Switch / ModalBottomSheet 禁用，domain 层不得新增直连 DAO")
    print(f"  - domain 层存量豁免 {len(DAO_ALLOWLIST)} 个文件（只减不增）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
