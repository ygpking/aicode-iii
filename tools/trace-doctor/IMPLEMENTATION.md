# 日志/轨迹功能补强与创新 — 实现说明

对应提案：5 个补强点（A1–A5）+ 3 个创新点（B1–B3）。全部已实现并验证。

## 改动清单

### Kotlin 侧（4 文件，+152/−16）

| 文件 | 改动 | 对应项 |
| --- | --- | --- |
| `core/util/EventTrace.kt` | 版本标识写入、启动回读清点未收尾回合、`currentTurnOf`、`recordUiState`、`installBackgroundMarker`、措辞去断言 | A1–A5, B1 |
| `AIEditorApp.kt` | `onCreate` 注册 `ON_STOP` 标记 | A3 |
| `core/util/AILogger.kt` | REQUEST 头追加 `turn=tN` | B1 |
| `feature/agent/presentation/component/AIChatPanel.kt` | UI 打点改走去重入口 | A5 |

### 脚本侧（新增 `tools/trace-doctor/`，只读、不联网、不调模型）

| 脚本 | 对应项 |
| --- | --- |
| `trace_metrics.py` | A1 |
| `ab_compare.py` | B2 |
| `failure_signatures.py` | B3 |
| `fixtures/` | 三脚本的合成夹具（覆盖正常/未收尾/失败/生命周期） |

## 关键设计决策

1. **A2 不能用内存清点**：进程重启后 `activeTurns` 必然为空。改为回读磁盘上**最后一个
   `PROCESS START` 之后**的轨迹段，统计有开始无结束的回合。限定范围的用意是避免重复
   报告更早进程的残局。
2. **A5 在唯一出口去重而非 `LaunchedEffect` 的 key**：打点所在 item 在 `LazyColumn` 内，
   滑出视口即被 dispose、滑回重建，effect 会重跑而 key 根本没变。实测同一状态组合占单日 UI
   记录的 37%（763/2039），故在 `recordUiState` 按「同会话 + 同内容」去重。
3. **B1 用 `currentTurnOf` 反查，不改 adapter 签名**：`logRequest` 有 4 个 adapter、
   12 个调用点，改签名会牵动 provider 层接口。反查是零侵入的等价做法。
4. **版本读 `packageManager` 而非 `BuildConfig`**：`buildFeatures.buildConfig` 当前未开，
   开了会改动构建脚本。`getPackageInfo` 取到的 versionName/versionCode 完全够用。

## 使用

```bash
# 取轨迹
adb pull /sdcard/Android/data/com.aicode.iii/files/traces ./traces

# 回合指标（A1）
python3 tools/trace-doctor/trace_metrics.py ./traces --out-dir report-base

# 失败指纹与补强建议（B3）
python3 tools/trace-doctor/failure_signatures.py ./traces --min-count 3

# 改动前后对比（B2）
python3 tools/trace-doctor/ab_compare.py report-base/turns.csv report-cand/turns.csv
```

## 验证方法

| 项 | 验证手段 | 期望 |
| --- | --- | --- |
| A4 | 重启 App，看新轨迹是否有 `APP VERSION vX.Y.Z(code)` | 出现且与 `dumpsys` 一致 |
| A3 | 切后台再回前台，看是否有 `PROCESS STOP` | 出现（此前恒为 0） |
| A2 | 强杀进程后重启，看是否有「上轮有 N 个回合未收尾」 | 出现，且 N 与 A1 算出的未收尾数吻合 |
| A5 | 同一会话滑出/滑回聊天区，比较 UI 行数 | 不新增重复行 |
| A1 | 用现有 3 天轨迹跑 `trace_metrics.py` | 回合数、token 与手工锚定统计吻合 |
| B1 | 触发一轮对话后看 ai-logs 的 `REQUEST #n` 行 | 带 `turn=tN`，且能在轨迹里找到同号回合 |
| B2 | 对同一批输入改动前后各跑一次，比较 | 指标差异可重复出现（非一次巧合） |
| B3 | 随机抽 3 条建议回溯原始行 | 确认同类，才算「值得动手」 |

## 已知限制（诚实说明）

- **真机数据未在本机验证**：容器读不到 `/sdcard`，Shizuku 为 adb 权限读不到
  `/data/data`。脚本正确性由合成夹具验证；**真机跑通需你侧执行**，若格式有偏差，
  `unparsed` 计数会立刻暴露（报告会显式警告）。
- **无单测覆盖**：`EventTrace` 是依赖 `Context` 的 object，新逻辑（尤其 `reportStaleTurns`）
  难以走 JVM 单测。已通过编译 + 逻辑 review + 夹具验证。
- **B2 不含自动重放**：重放要真调模型、产生费用，脚本刻意不碰，只对比已有轨迹。
- **compaction 三态不闭合**（64 起 / 60 成 / 30 败）：此现象已记录但**未修**，
  需先确认是否重试导致再决定是否补 `attempt` 字段。
- **A5 的 `lastUiState` 是内存态**：进程重启后清空，会在重启后放行一条重复行——可接受。
