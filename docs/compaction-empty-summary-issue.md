# 压缩失败问题记录：empty_or_truncated_summary（2026-10-12）

> 本文件是问题的**证据与现状记录**，不是修复方案。修法由另行调查的 AI 基于此记录给出。
> 涉及会话：`136fc2e6-bfc5-417f-a7e3-27ba0f87e174`（当日主代理会话）。

## 一、现象（一手日志证据）

当日该会话自动压缩 **4 次失败**，失败码全部为 `empty_or_truncated_summary`（ContextCompactor 判「摘要正文为空或输出被截断」后 failCompaction）：

| 序号 | 时间 | stopReason | output tokens | 日志原文 |
|---|---|---|---|---|
| 1 | 11:10:58 | `length` | 8110 | `压缩失败：摘要正文为空或输出被截断(stopReason=length)（output 8110 tokens）` |
| 2 | 11:26:50 | `length` | 7651 | 同上 |
| 3 | 11:38:29 | `stop` | 2525 | 同上（stopReason=stop，正常结束仍空正文） |
| 4 | 11:48:17 | `length` | 9132 | 同上；随后 `压缩连续失败 4 次，已停止自动重试` |

失败轨迹（trace-2026-10-12.log）：
- `t13 #2 compaction_started 估算=248501token` → `#3 compaction_failed empty_or_truncated_summary`（11:23:13 → 11:26:50）
- `t14 #2 → #3`（11:36:16 → 11:38:29，251036 token）
- `t15 #2 → #4`（11:44:39 → 11:48:17，259423 token）

**熔断停摆**：第 4 次失败后进入长期冷却（`COMPACTION_FAIL_STOP_MS`），后续自动压缩全部被 `压缩失败冷却中（剩余 2387s）` 跳过（12:08 起每轮请求各打一条 DEBUG），上下文只涨不降。

**同刻对照**：11:26:37 另一会话 `22ddb225` 的压缩（估算 200374 token）**成功**（11:27:35 finished）；该会话当日压缩 17 次全成功。→ 链路本身可用，问题特定于本会话的压缩模型路径。

## 二、已确认：提示词与代码防护齐全（排除这两类缺陷）

- **提示词双重禁止推理**：
  - `ContextCompactor.kt:310` 压缩请求系统提示：「不要输出任何思考、推理或分析过程（不要 reasoning_content），直接从摘要正文开始」
  - `assets/prompts/agent/compact-summary.md:8` 同款条款
- **代码防护**（`ContextCompactor.kt`）：
  - 截断判定复用 `AIResponse.isTruncated`（`TRUNCATION_STOP_REASONS` 含 OpenAI `length` / Anthropic `max_tokens` / Gemini `MAX_TOKENS`，:348-349）
  - 空/截断 → **最低推理档重试一次**（`REASONING_EFFORT_MINIMAL`，:355-367）：4 次失败均未出现「最低推理档重试成功」日志 → 重试也产出空正文
  - 失败熔断冷却 + 线性退避（:509-520）
- 上述防护是 v1.19.0 事故二（思考型模型把 48000 output 全花 reasoning_content、正文空仍入库）的修复成果，本问题**不是防护缺失**。

## 三、根因判断（中-高置信度）

**压缩模型行为**：该模型无视「不要输出 reasoning_content」指令，在最低推理档（`REASONING_EFFORT_MINIMAL`）下仍强制思考，把全部输出预算花在 `reasoning_content` 上，正文恒为空。

关键证据：
1. output 7651~9132 token 但正文空 = 预算全耗在推理；非「截断丢正文」（截断是结果不是原因）
2. 第 3 次 `stopReason=stop`（**未截断、正常结束**）正文仍空 → 模型根本不生成正文，不是预算不够
3. 最低推理档重试同样失败 4 次 → 推理档位控制对该模型无效（与既有结论「部分模型强制思考关不掉」一致，见项目记忆 `reasoning-level-not-switch-decision`）

## 四、待调查者确认的未决点（取证路径给出）

1. **本会话实际用的压缩模型是哪个**：
   - `app/databases/` 的 `llm_call_records` 表 `kind='compaction'` 最近记录（宿主 `/data/user/0/com.aicode.iii/databases/`，Shizuku 只读可查；注意该库属本应用自身，非他人应用）
   - 或 ai-logs（`~/.aicode/ai-logs-view/session-136fc2e6-*.log`）中压缩请求体的 `model` 字段（**不要数出现次数**，历史重发污染；只看请求原文）
2. **压缩模型配置来源**：`CompactionModelSettingsRepository`（设置页「压缩模型」）——全局设置还是跟随会话 provider？`ContextCompactor.kt:168` `summaryMetadata = modelMetadataService.resolve(aiProvider.providerId, ...)`，`aiProvider` 由调用方（StatefulAgentWorkflow）传入。
3. **请求参数实际值**：`ContextCompactor.kt:300-330` 压缩请求构造（`reasoningEffort = compactionEffort`、max output 是否显式设置、`COMPACTION_SYSTEM_PROMPT` 全文）。
4. **成功对照**：`22ddb225` 当日的压缩模型与配置（若全局同模型却成功，则需查 head 内容/长度差异的影响）。
5. **冷却时长**：`COMPACTION_FAIL_STOP_MS` 现值（熔断后 40 分钟停摆是否过长）。

## 五、已评估的建议方向（供参考，未实施）

- **A. 换模型引导（推荐）**：压缩失败卡片/设置页提示「当前压缩模型频繁产出空摘要，建议更换非思考型模型」——成本低、直指根因
- **B. 熔断冷却时长**：40 分钟停摆是否缩短——治标，防停摆太久
- **C. 代码强制**：对已知强制思考模型特殊处理（如跳过 reasoning 字段解析/降低预算）——跨厂商字段不统一、投入大，不推荐

## 六、相关代码位置

- `app/src/main/java/com/aicode/feature/agent/domain/workflow/ContextCompactor.kt`（压缩主流程：请求构造 :300-330、空/截断判定 :345-403、熔断 :509-520）
- `app/src/main/assets/prompts/agent/compact-summary.md`（压缩提示词）
- `app/src/main/java/com/aicode/feature/settings/data/repository/GeneralSettingsRepository.kt`（压缩阈值）
- 压缩模型设置：`CompactionModelSettingsRepository`（`compaction_model` 相关）

## 七、相关记忆（调查者应读）

- 项目记忆 `subagent-flow-owner-mismatch-v1190`（事故二：思考型模型空摘要形态与修法）
- 项目记忆 `reasoning-level-not-switch-decision`（强制思考关不掉的设计决策）
