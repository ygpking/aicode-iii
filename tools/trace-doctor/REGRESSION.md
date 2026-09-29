# 回归清单（A1–A5 / B1）

用于验证日志/轨迹补强的真机行为。装 `com.aicode.iii.beta`（与正式版共存）。

## 准备

- Beta 需**单独授权 Shizuku**（授权按包名，正式版的不共享）。
- 轨迹路径：`/sdcard/Android/data/com.aicode.iii.beta/files/traces/`
- 拉取：
  ```bash
  adb pull /sdcard/Android/data/com.aicode.iii.beta/files/traces ./traces-beta
  adb pull /sdcard/Android/data/com.aicode.iii.beta/files/ai-logs ./ailogs-beta
  ```
  没有 adb 时用 Shizuku 读同一路径。

## 用例

### A4 版本标识
1. 打开 App，看轨迹首行。
- **期望**：`LIFECYCLE APP VERSION vX.Y.Z(code)`，且与
  `pm dump com.aicode.iii.beta | grep versionName` 一致。
- 已在 22:26 那次验证通过（`v1.13.1-dev.6+d447cc0(58)`）。

### A3 前后台标记
2. 按 Home 切后台 → 轨迹应出现 `LIFECYCLE PROCESS STOP`。
- **期望**：每次离开前台都有一条（修复前**恒为 0**）。

### A3 误报去伪 ← 上一轮**没测到**的一条
3. 切后台（写 STOP）→ 从最近任务**划掉** App → 重新打开。
- **期望**：启动轨迹里**没有**「上次未记录到正常退出标记」。
- **若出现** → 说明 `ON_STOP` 没兜住「划掉」这条路径，需再改。

### A2 未收尾清点 ← 核心
4. 发一条会让 AI 跑长任务的消息 → **任务进行中**直接从最近任务划掉 App → 重新打开。
- **期望**：启动轨迹出现
  `上轮有 N 个回合未收尾（s=xxxxxxxx/tN）——进程在回合中途消失…`
- 若没出现：先确认划掉时确实还在跑（看最后一行是 `tool_started` 而非 `轮次结束`）。

### A2 跨天（可选，刚修）
5. 23:5x 发起长任务 → 划掉 → 凌晨后再开。
- **期望**：能报出跨天残局（修复前因只读最新文件而**漏报**）。

### A5 UI 去重（回合内）
6. 发一句话，等回合结束。
- **期望**：UI 层行数明显减少；同一状态组合不重复出现。
- 已在 22:28 那轮验证（`reasoning=2` 仅一次）。

### A5 UI 去重（滚动）← 上一轮**没测到**的一条
7. 打开一个**长会话** → 把聊天区滑到**顶部**（让尾巴 item 滑出视口）→ 再滑回**底部** → 反复 3–5 次。
- **期望**：UI 层**不新增重复行**。
- **关键**：必须在**回合已结束、无流式**时测，否则真实状态抖动会混进来，无法判断。
- 这是 `recordUiState` 去重的真正考点（`LaunchedEffect` 的 key 管不到「重建」）。

### B1 回合外键
8. 发一句话。
- **校验**：
  ```bash
  grep "REQUEST #" ailogs-beta/*.log | grep turn=
  ```
- **期望**：带 `turn=tN`，且该 `tN` 能在轨迹里找到同号回合。
- 已在 22:27 验证（4/4 带 `turn=`）。

### A1 指标聚合
9. 拉轨迹到电脑跑：
  ```bash
  python3 tools/trace-doctor/trace_metrics.py ./traces-beta --out-dir report
  ```
- **期望**：`无法解析 0`（非 0 说明轨迹格式与正则不符，把样例发我）；
  回合数、token、未收尾数与手工统计吻合。

## 判定「有没有变好」的用法（B2）

同一批输入跑改动前后两版，各自出 `turns.csv`，再：

```bash
python3 tools/trace-doctor/ab_compare.py 改前/turns.csv 改后/turns.csv
```

看**完成率↑ 且 工具调用↓ 且 token 不涨** = 真变好；样本 < 30 回合时脚本会明确警告不要下结论。
